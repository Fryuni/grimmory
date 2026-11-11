package org.booklore.service.upload;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkedUploadServiceTest {

    @TempDir
    Path tempDir;

    AuthenticationService authenticationService = mock(AuthenticationService.class);
    ChunkedUploadService service;

    @BeforeEach
    void setUp() throws IOException {
        AppSettingService appSettingService = mock(AppSettingService.class);
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().maxFileUploadSizeInMb(1).build());
        service = new ChunkedUploadService(appSettingService, authenticationService);
        service.tempRoot = tempDir;
        actAs(1L);
    }

    private void actAs(long userId) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(userId).build());
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes());
    }

    @Test
    void assemblesChunksAndMovesResult() throws IOException {
        String id = service.start("../dir/book.epub", 11).uploadId();
        assertThat(service.appendChunk(id, 0, bytes("hello ")).received()).isEqualTo(6);
        assertThat(service.appendChunk(id, 6, bytes("world")).received()).isEqualTo(11);

        MultipartFile file = service.resolve(null, id);
        assertThat(file.getOriginalFilename()).isEqualTo("book.epub");
        assertThat(file.getSize()).isEqualTo(11);

        Path dest = Files.createFile(tempDir.resolve("dest.epub"));
        file.transferTo(dest);
        assertThat(Files.readString(dest)).isEqualTo("hello world");
        service.release(file);

        // completed sessions cannot be reused
        assertThatThrownBy(() -> service.resolve(null, id)).isInstanceOf(APIException.class);
    }

    @Test
    void retryFromEarlierOffsetOverwrites() throws IOException {
        String id = service.start("a.pdf", 6).uploadId();
        service.appendChunk(id, 0, bytes("abX"));
        service.appendChunk(id, 2, bytes("cdef"));
        Path dest = tempDir.resolve("out");
        service.resolve(null, id).transferTo(dest);
        assertThat(Files.readString(dest)).isEqualTo("abcdef");
    }

    @Test
    void rejectsGapsOverflowAndIncompleteUploads() {
        String id = service.start("a.pdf", 4).uploadId();
        InputStream gap = bytes("xyz");
        assertThatThrownBy(() -> service.appendChunk(id, 1, gap)).isInstanceOf(APIException.class);
        assertThat(service.appendChunk(id, 0, bytes("12")).received()).isEqualTo(2);
        assertThatThrownBy(() -> service.resolve(null, id)).isInstanceOf(APIException.class);
        InputStream overflow = new ByteArrayInputStream(new byte[200 * 1024]);
        assertThatThrownBy(() -> service.appendChunk(id, 0, overflow)).isInstanceOf(APIException.class);
        // Rejected bodies are drained so the error response reaches the client.
        assertThat(gap).isEmpty();
        assertThat(overflow).isEmpty();
    }

    @Test
    void rejectsOversizedAndInvalidStarts() {
        assertThatThrownBy(() -> service.start("a.pdf", 2L * 1024 * 1024)).isInstanceOf(APIException.class);
        assertThatThrownBy(() -> service.start("a.pdf", 0)).isInstanceOf(APIException.class);
        assertThatThrownBy(() -> service.start("..", 1)).isInstanceOf(APIException.class);
    }

    @Test
    void otherUsersCannotTouchSession() {
        String id = service.start("a.pdf", 1).uploadId();
        actAs(2L);
        assertThatThrownBy(() -> service.appendChunk(id, 0, bytes("x"))).isInstanceOf(APIException.class);
        assertThatThrownBy(() -> service.resolve(null, id)).isInstanceOf(APIException.class);
        assertThatThrownBy(() -> service.abort(id)).isInstanceOf(APIException.class);
        InputStream body = bytes("unread");
        assertThatThrownBy(() -> service.appendChunk("unknown", 0, body)).isInstanceOf(APIException.class);
        assertThat(body).isEmpty();
    }

    @Test
    void limitsConcurrentSessionsAndAbortFreesSlot() {
        String first = service.start("a.pdf", 1).uploadId();
        for (int i = 1; i < ChunkedUploadService.MAX_SESSIONS_PER_USER; i++) {
            service.start("a.pdf", 1);
        }
        assertThatThrownBy(() -> service.start("a.pdf", 1)).isInstanceOf(APIException.class);
        service.abort(first);
        service.start("a.pdf", 1);
    }

    @Test
    void lateDuplicateChunkKeepsLaterBytes() throws IOException {
        String id = service.start("a.pdf", 6).uploadId();
        service.appendChunk(id, 0, bytes("abc"));
        service.appendChunk(id, 3, bytes("def"));
        assertThat(service.appendChunk(id, 0, bytes("abc")).received()).isEqualTo(6);
        assertThat(service.appendChunk(id, 0, bytes("")).received()).isEqualTo(6);
        Path dest = tempDir.resolve("out");
        service.resolve(null, id).transferTo(dest);
        assertThat(Files.readString(dest)).isEqualTo("abcdef");
    }

    @Test
    void interruptedChunkCanBeResumedFromReceivedOffset() throws IOException {
        String id = service.start("a.pdf", 6).uploadId();
        InputStream failing = new InputStream() {
            int sent;

            @Override
            public int read() throws IOException {
                if (sent == 2) {
                    throw new IOException("client aborted");
                }
                return "ab".charAt(sent++);
            }
        };
        assertThatThrownBy(() -> service.appendChunk(id, 0, failing)).isInstanceOf(APIException.class);
        assertThat(service.appendChunk(id, 2, bytes("cdef")).received()).isEqualTo(6);
        Path dest = tempDir.resolve("out");
        service.resolve(null, id).transferTo(dest);
        assertThat(Files.readString(dest)).isEqualTo("abcdef");
    }

    @Test
    void abortAfterCompletionFailsAndKeepsClaimedFile() throws IOException {
        String id = service.start("a.pdf", 1).uploadId();
        service.appendChunk(id, 0, bytes("x"));
        MultipartFile file = service.resolve(null, id);
        assertThatThrownBy(() -> service.abort(id)).isInstanceOf(APIException.class);
        assertThat(file.getInputStream().readAllBytes()).isEqualTo("x".getBytes());
    }

    @Test
    void releaseDeletesUnmovedFileAndStagedFilesArePrivate() throws IOException {
        String id = service.start("a.pdf", 1).uploadId();
        if (service.storageDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try (var files = Files.list(service.storageDir)) {
                Path staged = files.findFirst().orElseThrow();
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(staged))).isEqualTo("rw-------");
            }
        }
        service.appendChunk(id, 0, bytes("x"));
        service.release(service.resolve(null, id));
        try (var files = Files.list(service.storageDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void stagesInPrivatePerProcessDirRemovedOnShutdown() throws IOException {
        Files.createDirectory(tempDir.resolve("grimmory-chunked-uploads-other"));
        service.start("a.pdf", 1);
        assertThat(service.storageDir.getParent()).isEqualTo(tempDir);
        if (service.storageDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(service.storageDir))).isEqualTo("rwx------");
        }
        service.shutdown();
        assertThat(service.storageDir).doesNotExist();
        // Another process's directory is never touched.
        assertThat(tempDir.resolve("grimmory-chunked-uploads-other")).isDirectory();
    }

    @Test
    void sweepDeletesOnlyUntrackedFiles() throws IOException {
        String idle = service.start("a.pdf", 1).uploadId();
        String done = service.start("b.pdf", 1).uploadId();
        service.appendChunk(done, 0, bytes("x"));
        MultipartFile claimedFile = service.resolve(null, done);
        Path orphan = Files.createFile(service.storageDir.resolve("orphan.part"));
        // Old mtimes must not matter for tracked files.
        try (var files = Files.list(service.storageDir)) {
            for (Path f : files.toList()) {
                Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
            }
        }

        service.expireStaleSessions();

        assertThat(Files.exists(orphan)).isFalse();
        assertThat(claimedFile.getInputStream().readAllBytes()).isEqualTo("x".getBytes());
        service.appendChunk(idle, 0, bytes("x"));
    }

    @Test
    void overflowDiscardsTheUpload() {
        String id = service.start("a.pdf", 3).uploadId();
        // First read fills the declared size exactly, the second carries one byte too many.
        InputStream body = new java.io.SequenceInputStream(bytes("abc"), bytes("d"));
        assertThatThrownBy(() -> service.appendChunk(id, 0, body)).isInstanceOf(APIException.class);
        assertThatThrownBy(() -> service.resolve(null, id)).isInstanceOf(APIException.class);
        // A replay that overflowed may have overwritten accepted bytes, so the whole upload is gone.
        assertThatThrownBy(() -> service.appendChunk(id, 0, bytes("abc"))).isInstanceOf(APIException.class);
    }

    @Test
    void firstUploadReclaimsOnlyDirsOfDeadOrRestartedProcesses() throws IOException {
        long self = ProcessHandle.current().pid();
        long alive = ProcessHandle.current().parent().orElseThrow().pid();
        Path restarted = Files.createDirectory(tempDir.resolve("grimmory-chunked-uploads-" + self + "-old"));
        Files.createFile(restarted.resolve("upload-1.part"));
        Path dead = Files.createDirectory(tempDir.resolve("grimmory-chunked-uploads-999999999999-x"));
        Path live = Files.createDirectory(tempDir.resolve("grimmory-chunked-uploads-" + alive + "-x"));
        Path victim = Files.createDirectory(tempDir.resolve("victim"));
        Files.createFile(victim.resolve("keep.txt"));
        Path link = tempDir.resolve("grimmory-chunked-uploads-999999999998-link");
        boolean symlinks = true;
        try {
            Files.createSymbolicLink(link, victim);
        } catch (IOException | UnsupportedOperationException e) {
            symlinks = false;
        }

        service.start("a.pdf", 1);

        assertThat(restarted).doesNotExist();
        assertThat(dead).doesNotExist();
        assertThat(live).isDirectory();
        assertThat(victim.resolve("keep.txt")).exists();
        if (symlinks) {
            assertThat(Files.isSymbolicLink(link)).isTrue();
        }
    }

    @Test
    void passesThroughDirectFile() {
        MultipartFile direct = new MockMultipartFile("file", "a.pdf", null, new byte[]{1});
        assertThat(service.resolve(direct, null)).isSameAs(direct);
        assertThatThrownBy(() -> service.resolve(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
