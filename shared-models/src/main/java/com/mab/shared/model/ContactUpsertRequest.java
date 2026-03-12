package com.mab.shared.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ContactUpsertRequest(@NotBlank String name, @NotBlank @Email String email) {
}
