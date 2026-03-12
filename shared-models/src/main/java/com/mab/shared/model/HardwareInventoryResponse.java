package com.mab.shared.model;

import java.util.List;

public record HardwareInventoryResponse(List<HardwareRecord> records) {
}