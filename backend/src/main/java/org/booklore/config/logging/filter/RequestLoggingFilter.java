package org.booklore.config.logging.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import java.util.Locale;
import java.util.function.Predicate;

public class RequestLoggingFilter extends CommonsRequestLoggingFilter {
    private Predicate<String> pathPredicate = null;

    public void setPathPredicate(@Nullable Predicate<String> pathPredicate) {
        this.pathPredicate = pathPredicate;
    }

    @Override
    protected boolean shouldLog(HttpServletRequest request) {
        if (pathPredicate != null && !pathPredicate.test(request.getRequestURI())) {
            return false;
        }

        return super.shouldLog(request);
    }

    @Override
    protected @Nullable String getMessagePayload(HttpServletRequest request) {
        // Raw binary bodies (upload chunks, book content) are file bytes, not something to put in logs.
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/octet-stream")) {
            return null;
        }
        return super.getMessagePayload(request);
    }
}
