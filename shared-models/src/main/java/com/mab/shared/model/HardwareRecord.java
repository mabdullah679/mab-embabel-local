package com.mab.shared.model;

public record HardwareRecord(String uuid, String deviceName, String manufacturer, String cpu, String ram, String storage, String metadataJson, String createdAt) {
}