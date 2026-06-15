package org.booklore.model.dto;

import org.booklore.model.enums.BookFileType;

public record BookConversionCompletionNotification(
        int totalCount,
        BookFileType targetFormat,
        int convertedCount,
        int skippedCount,
        int failedCount
) {}
