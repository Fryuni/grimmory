package org.booklore.controller;

import org.booklore.service.upload.ChunkedUploadService;
import org.booklore.service.upload.FileUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileUploadControllerTest {

    private final FileUploadService fileUploadService = mock(FileUploadService.class);
    private final ChunkedUploadService chunkedUploadService = mock(ChunkedUploadService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FileUploadController(fileUploadService, chunkedUploadService))
            .build();

    @Test
    void finalizesFromUploadIdFormField() throws Exception {
        MultipartFile assembled = new MockMultipartFile("file", "big.epub", null, new byte[]{1});
        when(chunkedUploadService.resolve(isNull(), eq("abc"))).thenReturn(assembled);

        mvc.perform(multipart("/api/v1/files/upload")
                        .part(new MockPart("uploadId", "abc".getBytes()))
                        .param("libraryId", "1")
                        .param("pathId", "2"))
                .andExpect(status().isNoContent());

        verify(fileUploadService).uploadFile(assembled, 1L, 2L);
        verify(chunkedUploadService).release(assembled);
    }

    @Test
    void directFileUploadStillWorks() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "small.epub", null, new byte[]{1});
        when(chunkedUploadService.resolve(file, null)).thenReturn(file);

        mvc.perform(multipart("/api/v1/files/upload/bookdrop").file(file)).andExpect(status().isOk());

        verify(fileUploadService).uploadFileBookDrop(file);
    }

    @Test
    void malformedOffsetIsBadRequest() throws Exception {
        mvc.perform(put("/api/v1/files/upload/chunked/abc")
                        .param("offset", "invalid")
                        .contentType("application/octet-stream")
                        .content(new byte[]{1}))
                .andExpect(status().isBadRequest());
    }
}
