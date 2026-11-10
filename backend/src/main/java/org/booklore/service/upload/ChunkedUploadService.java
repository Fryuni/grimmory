package org.booklore.service.upload;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private final AppSettingService appSettingService;
    private final AuthenticationService authenticationService;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    Path storageDir = Path.of(System.getProperty("java.io.tmpdir"), "grimmory-chunked-uploads");

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

    @PostConstruct
    void clearLeftovers() {
        // Sessions live in memory only, so anything on disk is from a previous run.
        FileSystemUtils.deleteRecursively(storageDir.toFile());
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
                Path path = Files.createFile(storageDir.resolve(uploadId));
                sessions.put(uploadId, new Session(userId, baseName, fileSize, path));
            } catch (IOException e) {
                throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
            }
            return new StartResponse(uploadId);
        }
    }

    /**
     * Writes the request body at {@code offset}. An offset below the bytes already received
     * overwrites from there, so a client can safely retry a chunk whose request failed midway.
     */
    public ChunkResponse appendChunk(String uploadId, long offset, InputStream body) {
        Session session = ownedSession(uploadId);
        synchronized (session) {
            ensureOpen(session);
            if (offset < 0 || offset > session.received) {
                throw ApiError.CONFLICT.createException("Expected offset " + session.received);
            }
            long position = offset;
            try (FileChannel channel = FileChannel.open(session.path, StandardOpenOption.WRITE)) {
                channel.truncate(offset);
                OutputStream out = Channels.newOutputStream(channel.position(offset));
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    if (position + read > session.fileSize) {
                        channel.truncate(offset);
                        position = offset;
                        throw ApiError.GENERIC_BAD_REQUEST.createException("Chunk exceeds declared file size");
                    }
                    out.write(buffer, 0, read);
                    position += read;
                }
            } catch (IOException e) {
                log.warn("Chunk write failed for upload {}: {}", uploadId, e.getMessage());
                throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
            } finally {
                session.received = position;
                session.lastActivity = Instant.now();
            }
            return new ChunkResponse(session.received);
        }
    }

    public void abort(String uploadId) {
        Session session = ownedSession(uploadId);
        synchronized (session) {
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
                sessions.remove(uploadId);
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
