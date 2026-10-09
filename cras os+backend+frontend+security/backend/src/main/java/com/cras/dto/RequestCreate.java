package com.cras.dto;

import jakarta.validation.constraints.*;

public record RequestCreate(
        @NotNull Long resourceId,
        Long locationId,
        @Min(1) @Max(10000) int quantity,
        @Min(1) @Max(10) int severity,
        @Min(0) int affectedPeople,
        @Min(1) @Max(10) int scarcity
) {}
