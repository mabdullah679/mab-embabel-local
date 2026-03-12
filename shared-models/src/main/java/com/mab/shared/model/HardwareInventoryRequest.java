package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record HardwareInventoryRequest(@NotBlank String deviceName) {
}