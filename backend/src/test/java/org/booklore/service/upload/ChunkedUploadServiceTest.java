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
    void setUp() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().maxFileUploadSizeInMb(1).build());
        service = new ChunkedUploadService(appSettingService, authenticationService);
        service.storageDir = tempDir.resolve("chunks");
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
        assertThatThrownBy(() -> service.appendChunk(id, 1, bytes("x"))).isInstanceOf(APIException.class);
        assertThatThrownBy(() -> service.appendChunk(id, 0, bytes("12345"))).isInstanceOf(APIException.class);
        assertThat(service.appendChunk(id, 0, bytes("12")).received()).isEqualTo(2);
        assertThatThrownBy(() -> service.resolve(null, id)).isInstanceOf(APIException.class);
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
    }

    @Test
    void limitsConcurrentSessionsAndAbortFreesSlot() {
        String first = service.start("a.pdf", 1).uploadId();
        for (int i = 1; i < ChunkedUploadService.MAX_SESSIONS_PER_USER; i++) {
            service.start("a.pdf", 1);
        }
        assertThatThrownBy(() -> service.start("a.pdf", 1)).isInstanceOf(APIException.class);
        service.abort(first);
        assertThat(Files.exists(service.storageDir.resolve(first))).isFalse();
        service.start("a.pdf", 1);
    }

    @Test
    void passesThroughDirectFile() {
        MultipartFile direct = new MockMultipartFile("file", "a.pdf", null, new byte[]{1});
        assertThat(service.resolve(direct, null)).isSameAs(direct);
        assertThatThrownBy(() -> service.resolve(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
