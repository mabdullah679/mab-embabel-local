package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record HardwareInventoryRequest(@NotBlank String deviceName) {
}
