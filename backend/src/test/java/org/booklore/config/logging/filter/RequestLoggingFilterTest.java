package org.booklore.config.logging.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    private ContentCachingRequestWrapper readRequest(String contentType) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/files/upload/chunked/%61bc");
        request.setContentType(contentType);
        request.setContent("SECRET-BOOK-BYTES".getBytes());
        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request, 1000);
        wrapper.getInputStream().readAllBytes();
        return wrapper;
    }

    @Test
    void omitsRawBinaryPayloadsRegardlessOfPath() throws IOException {
        assertThat(filter.getMessagePayload(readRequest("application/octet-stream"))).isNull();
        assertThat(filter.getMessagePayload(readRequest("Application/Octet-Stream; charset=x"))).isNull();
    }

    @Test
    void keepsOtherPayloads() throws IOException {
        assertThat(filter.getMessagePayload(readRequest("application/json"))).contains("SECRET-BOOK-BYTES");
    }
}
