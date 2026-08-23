package com.cde.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a listing.
 *
 * <p>Every paginated endpoint returns this shape, always — including when the
 * page happens to hold everything. An endpoint that returned a bare array in
 * some circumstances and an envelope in others could not be described by a
 * single schema, and a client had to guess which it had received by inspecting
 * the value it just parsed.
 *
 * @param <T> what the page holds
 */
@Schema(name = "PageResponse", description = "One page of a listing, with the counts needed to "
                                           + "render a pager without a second request.")
public record PageResponse<T>(

    @Schema(description = "The items on this page, in the requested order.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    List<T> content,

    @Schema(description = "Total items across every page.", example = "137", minimum = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    long totalElements,

    @Schema(description = "Total pages at the current page size.", example = "3", minimum = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    int totalPages,

    @Schema(description = "Zero-based index of this page.", example = "0", minimum = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    int number,

    @Schema(description = "Maximum items a page of this listing holds.", example = "50",
            minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    int size,

    @Schema(description = "Whether this is the first page.", example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    boolean first,

    @Schema(description = "Whether this is the last page.", example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    boolean last
) {

    /** Wraps a Spring {@code Page}, mapping each entity to its response shape. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> toResponse) {
        return new PageResponse<>(
            page.getContent().stream().map(toResponse).toList(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber(),
            page.getSize(),
            page.isFirst(),
            page.isLast());
    }
}
