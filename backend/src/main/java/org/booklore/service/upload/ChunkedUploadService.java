package org.booklore.service.upload;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Assembles a file from several sequential requests so large uploads fit under per-request
 * proxy limits (e.g. Cloudflare's 100 MB). The assembled file is handed to the regular upload
 * endpoints as a {@link MultipartFile} via their {@code uploadId} parameter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkedUploadService {

    static final Duration SESSION_TTL = Duration.ofHours(1);
    static final int MAX_SESSIONS_PER_USER = 5;
    private static final long MB_TO_BYTES_MULTIPLIER = 1024L * 1024L;

    // Keeps uploads from filling the disk the rest of the app writes to.
    static final long RESERVED_FREE_BYTES = 512 * MB_TO_BYTES_MULTIPLIER;

    private final AppSettingService appSettingService;
    private final AuthenticationService authenticationService;
    private final AppProperties appProperties;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** Assembled files handed to an upload endpoint but not yet released. */
    private final Set<Path> claimed = ConcurrentHashMap.newKeySet();
    Path storageDir;

    private static final class Session {
        final long userId;
        final String fileName;
        final long fileSize;
        final Path path;
        long received;
        boolean closed;
        volatile Instant lastActivity = Instant.now();

        Session(long userId, String fileName, long fileSize, Path path) {
            this.userId = userId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.path = path;
        }
    }

    public record StartResponse(String uploadId) {
    }

    public record ChunkResponse(long received) {
    }

    /** Stages under the app's own data directory (not a shared temp dir); sessions are in memory, so leftovers are stale. */
    @PostConstruct
    void init() {
        storageDir = Path.of(appProperties.getPathConfig(), "chunked_uploads");
        try {
            FileSystemUtils.deleteRecursively(storageDir);
        } catch (IOException e) {
            // Don't block startup; the orphan sweep retries.
            log.warn("Failed to clear chunked upload directory {}", storageDir, e);
        }
    }

    public StartResponse start(String fileName, long fileSize) {
        String baseName = StringUtils.getFilename(StringUtils.cleanPath(fileName == null ? "" : fileName));
        if (!StringUtils.hasText(baseName) || baseName.equals("..")) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid filename");
        }
        if (fileSize <= 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("File size must be positive");
        }
        int maxSizeMb = appSettingService.getAppSettings().getMaxFileUploadSizeInMb();
        if (fileSize > maxSizeMb * MB_TO_BYTES_MULTIPLIER) {
            throw ApiError.FILE_TOO_LARGE.createException(maxSizeMb);
        }

        long userId = currentUserId();
        synchronized (sessions) {
            long active = sessions.values().stream().filter(s -> s.userId == userId).count();
            if (active >= MAX_SESSIONS_PER_USER) {
                throw ApiError.CONFLICT.createException("Too many uploads in progress");
            }
            String uploadId = UUID.randomUUID().toString();
            try {
                Files.createDirectories(storageDir);
                long outstanding = sessions.values().stream().mapToLong(s -> s.fileSize - s.received).sum();
                if (Files.getFileStore(storageDir).getUsableSpace() < outstanding + fileSize + RESERVED_FREE_BYTES) {
                    throw ApiError.CONFLICT.createException("Not enough temporary disk space for this upload");
                }
                // createTempFile makes the file owner-only on POSIX.
                Path path = Files.createTempFile(storageDir, "upload-", ".part");
                sessions.put(uploadId, new Session(userId, baseName, fileSize, path));
            } catch (IOException e) {
                throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
            }
            return new StartResponse(uploadId);
        }
    }

    /**
     * Writes the request body at {@code offset}. An offset below the bytes already received
     * rewrites those bytes in place without discarding later ones, so retries and late
     * duplicates of a chunk are harmless.
     */
    public ChunkResponse appendChunk(String uploadId, long offset, InputStream body) {
        Session session = ownedSession(uploadId);
        APIException rejection;
        synchronized (session) {
            ensureOpen(session);
            if (offset < 0 || offset > session.received) {
                rejection = ApiError.CONFLICT.createException("Expected offset " + session.received);
            } else {
                rejection = write(session, offset, body);
                if (rejection == null) {
                    return new ChunkResponse(session.received);
                }
                // More bytes than declared is a protocol violation and may have overwritten accepted data.
                discard(uploadId, session);
            }
        }
        // Drained outside the lock so a slow rejected body cannot hold up the session or the expiry sweep.
        drain(body);
        throw rejection;
    }

    /** Returns a rejection when the body overflows the declared size; caller holds the session lock. */
    private APIException write(Session session, long offset, InputStream body) {
        session.lastActivity = Instant.now();
        long position = offset;
        try (FileChannel channel = FileChannel.open(session.path, StandardOpenOption.WRITE)) {
            OutputStream out = Channels.newOutputStream(channel.position(offset));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (position + read > session.fileSize) {
                    return ApiError.GENERIC_BAD_REQUEST.createException("Chunk exceeds declared file size");
                }
                out.write(buffer, 0, read);
                position += read;
                session.received = Math.max(session.received, position);
                // Keeps a slow but active upload from looking idle to the expiry sweep.
                session.lastActivity = Instant.now();
            }
            return null;
        } catch (IOException e) {
            log.warn("Chunk write failed for upload {}: {}", session.path.getFileName(), e.getMessage());
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void abort(String uploadId) {
        Session session = ownedSession(uploadId);
        synchronized (session) {
            ensureOpen(session);
            discard(uploadId, session);
        }
    }

    /**
     * Returns {@code file} when sent directly, otherwise the completed chunked upload. Callers
     * must pass the result to {@link #release(MultipartFile)} once done.
     */
    public MultipartFile resolve(MultipartFile file, String uploadId) {
        if (StringUtils.hasText(uploadId)) {
            Session session = ownedSession(uploadId);
            synchronized (session) {
                ensureOpen(session);
                if (session.received != session.fileSize) {
                    throw ApiError.GENERIC_BAD_REQUEST.createException(
                            "Upload incomplete: received " + session.received + " of " + session.fileSize + " bytes");
                }
                session.closed = true;
                // Atomic with the orphan sweep, which would otherwise see the file in neither set.
                synchronized (sessions) {
                    claimed.add(session.path);
                    sessions.remove(uploadId);
                }
                return new AssembledFile(session.fileName, session.fileSize, session.path);
            }
        }
        if (file == null) {
            throw new IllegalArgumentException("Uploaded file is missing.");
        }
        return file;
    }

    public void release(MultipartFile file) {
        if (file instanceof AssembledFile assembled) {
            try {
                Files.deleteIfExists(assembled.path);
            } catch (IOException e) {
                log.warn("Failed to delete assembled upload {}", assembled.path, e);
            } finally {
                // Unclaimed leftovers are retried by the orphan sweep.
                claimed.remove(assembled.path);
            }
        }
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void expireStaleSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.forEach((id, session) -> {
            if (session.lastActivity.isBefore(cutoff)) {
                synchronized (session) {
                    if (!session.closed && session.lastActivity.isBefore(cutoff)) {
                        log.info("Expiring idle chunked upload {}", id);
                        discard(id, session);
                    }
                }
            }
        });
        deleteOrphanedFiles();
    }

    /** Retries files whose earlier delete failed; anything in our directory that no session or claim tracks. */
    private void deleteOrphanedFiles() {
        // Holding the sessions lock keeps start() and resolve() from moving a file between "tracked" states mid-scan.
        if (!Files.isDirectory(storageDir)) {
            return;
        }
        synchronized (sessions) {
            Set<Path> tracked = new HashSet<>(claimed);
            sessions.values().forEach(s -> tracked.add(s.path));
            try (Stream<Path> files = Files.list(storageDir)) {
                files.filter(file -> !tracked.contains(file)).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("Failed to delete orphaned chunked upload {}", file, e);
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to list chunked upload directory {}", storageDir, e);
            }
        }
    }

    /** Consumes an unread body so Tomcat sends the error response instead of resetting the connection. */
    private static void drain(InputStream body) {
        try {
            body.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // The client went away; nothing left to answer.
        }
    }

    private void discard(String uploadId, Session session) {
        session.closed = true;
        sessions.remove(uploadId);
        try {
            Files.deleteIfExists(session.path);
        } catch (IOException e) {
            log.warn("Failed to delete chunked upload {}", session.path, e);
        }
    }

    private Session ownedSession(String uploadId) {
        Session session = uploadId == null ? null : sessions.get(uploadId);
        if (session == null || session.userId != currentUserId()) {
            throw ApiError.GENERIC_NOT_FOUND.createException("Upload not found");
        }
        return session;
    }

    private void ensureOpen(Session session) {
        if (session.closed) {
            throw ApiError.GENERIC_NOT_FOUND.createException("Upload not found");
        }
    }

    private long currentUserId() {
        var user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }
        return user.getId();
    }

    /** Read-only view of an assembled upload; transferTo moves the file instead of copying it. */
    private static final class AssembledFile implements MultipartFile {
        private final String fileName;
        private final long size;
        private final Path path;

        AssembledFile(String fileName, long size, Path path) {
            this.fileName = fileName;
            this.size = size;
            this.path = path;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            transferTo(dest.toPath());
        }

        @Override
        public void transferTo(Path dest) throws IOException {
            Files.move(path, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
