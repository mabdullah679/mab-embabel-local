package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record MetadataLookupRequest(@NotBlank String uuid) {
}
