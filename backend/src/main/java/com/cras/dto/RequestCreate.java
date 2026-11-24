package com.cras.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Severity and scarcity are no longer typed by the user: the server estimates them. */
public record RequestCreate(
        @NotNull Long resourceId,
        Long locationId,
        @Min(1) int quantity,
        @Min(1) int affectedPeople,
        Boolean medicalNeed,
        Boolean vulnerablePeople,
        Boolean noCleanWater
) {}
