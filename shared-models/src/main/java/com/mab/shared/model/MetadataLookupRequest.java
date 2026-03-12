package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record MetadataLookupRequest(@NotBlank String uuid) {
}