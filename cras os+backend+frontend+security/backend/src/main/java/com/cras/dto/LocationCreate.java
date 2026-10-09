package com.cras.dto;

import jakarta.validation.constraints.*;

public record LocationCreate(
        @NotBlank String name,
        double latitude,
        double longitude,
        @Min(1) @Max(10) int riskLevel
) {}
