package com.domain.shared.model;

import java.util.Map;

public record MetadataLookupResponse(String uuid, Map<String, Object> metadata) {
}
