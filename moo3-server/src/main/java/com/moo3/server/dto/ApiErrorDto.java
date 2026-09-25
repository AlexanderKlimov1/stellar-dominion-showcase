package com.moo3.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Единый формат ошибки REST API. */
public record ApiErrorDto(
        Integer status,
        String error,
        String message,
        List<String> details,
        OffsetDateTime timestamp
) {
}
