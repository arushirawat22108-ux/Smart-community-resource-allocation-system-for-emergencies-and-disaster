package com.cras.dto;

import jakarta.validation.constraints.*;

public record ResourceCreate(
        @NotBlank String name,
        @NotBlank String unit,
        @Min(0) int availableQuantity
) {}
