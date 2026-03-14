package com.mab.shared.model;

public record RecordLookupRequest(
        String queryText,
        String recordType,
        String itemType,
        String recordId,
        String date,
        String referenceText,
        String sortBy,
        String sortDirection,
        Integer limit,
        boolean includeDetails
) {
}
