package com.realtimetradeprocessing.simulator.api;

import java.util.List;

import org.springframework.data.domain.Page;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Page response for operational search endpoints.")
public record PageResponse<T>(
    List<T> items,
    @Schema(example = "0")
    int page,
    @Schema(example = "20")
    int size,
    @Schema(example = "123")
    long totalElements,
    @Schema(example = "7")
    int totalPages
) {

    public static <T> PageResponse<T> fromPage(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
