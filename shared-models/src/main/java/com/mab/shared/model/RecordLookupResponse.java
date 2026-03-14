package com.mab.shared.model;

import java.util.List;

public record RecordLookupResponse(
        String queryText,
        String recordType,
        String itemType,
        String recordId,
        String date,
        String sortBy,
        String sortDirection,
        boolean includeDetails,
        int totalMatches,
        RecordLookupMatch primaryMatch,
        List<RecordLookupMatch> matches
) {
}
