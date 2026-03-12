package com.mab.tools.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.shared.model.*;
import com.mab.tools.repository.ToolRepository;
import com.mab.tools.repository.ToolRepository.RagChunkRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ToolsService {

    private static final int CHUNK_SIZE = 420;
    private static final int CHUNK_OVERLAP = 80;
    private static final int DENSE_LIMIT = 8;
    private static final int LEXICAL_LIMIT = 8;
    private static final int RERANK_LIMIT = 6;
    private static final int CONTEXT_CHUNK_LIMIT = 4;
    private static final int CONTEXT_CHAR_BUDGET = 1800;
    private static final double RRF_K = 60.0;

    private final ToolRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String ollamaBaseUrl;

    public ToolsService(ToolRepository repository,
                        RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.repository = repository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    public EmailToolResponse generateEmail(EmailToolRequest request) {
        String toLine = resolveEmailRecipients(request);
        String greeting = buildGreeting(toLine);
        String body = normalizeEmailBody(request.body());
        String closing = "casual".equalsIgnoreCase(request.tone()) ? "Best" : "Regards";
        String draft = "To: %s\nSubject: %s\n\n%s\n\n%s\n\n%s,\nAgent"
                .formatted(toLine, request.subject(), greeting, body, closing);
        EmailDraftRecord record = repository.createEmailDraft(request.recipient(), request.subject(), body, request.tone());
        return new EmailToolResponse(record.id(), draft, record.recipient(), record.subject(), record.body(), record.tone(), record.status(), record.scheduledFor(), record.sentAt());
    }

    private String normalizeEmailBody(String body) {
        if (body == null || body.isBlank()) {
            return "I wanted to follow up with you.";
        }
        String trimmed = body.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private String resolveEmailRecipients(EmailToolRequest request) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        if (request.recipient() != null && !request.recipient().isBlank()) {
            for (String value : request.recipient().split(",")) {
                String normalized = value.trim();
                if (!normalized.isBlank()) {
                    recipients.add(normalized);
                }
            }
        }
        if (request.recipientEmails() != null) {
            request.recipientEmails().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(recipients::add);
        }
        if (request.recipientNames() != null && !request.recipientNames().isEmpty()) {
            repository.findContactsByNames(request.recipientNames()).stream()
                    .map(ContactRecord::email)
                    .forEach(recipients::add);
        }
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("Unable to resolve any email recipients.");
        }
        return String.join(", ", recipients);
    }

    private String buildGreeting(String recipientField) {
        List<String> names = List.of(recipientField.split(","))
                .stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::displayName)
                .toList();

        if (names.isEmpty()) {
            return "Hi Team,";
        }
        if (names.size() == 1) {
            return "Hi %s,".formatted(names.getFirst());
        }
        return "Hi %s,".formatted(String.join(" and ", names));
    }

    private String displayName(String recipient) {
        int atIndex = recipient.indexOf('@');
        String localPart = atIndex >= 0 ? recipient.substring(0, atIndex) : recipient;
        if (localPart.isBlank()) {
            return "Team";
        }
        String cleaned = localPart.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        String[] parts = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.isEmpty() ? "Team" : builder.toString();
    }

    public CalendarToolResponse createCalendarEvent(CalendarToolRequest request) {
        List<String> participants = request.participants() == null ? List.of() : request.participants();
        String eventId = repository.createEvent(request.title(), request.date(), request.time(), participants, request.itemType(), request.notes());
        return new CalendarToolResponse(eventId, request.title(), request.date(), request.time(), participants, "CREATED", request.itemType(), request.notes());
    }

    public CalendarItemsResponse listCalendarItems() {
        return new CalendarItemsResponse(repository.listCalendarItems());
    }

    public CalendarItemRecord updateCalendarItem(String id, CalendarItemUpdateRequest request) {
        List<String> participants = request.participants() == null ? List.of() : request.participants();
        return repository.updateCalendarItem(id, request.title(), request.date(), request.time(), participants, request.itemType(), request.notes());
    }

    public void deleteCalendarItem(String id) {
        repository.deleteCalendarItem(id);
    }

    public MetadataLookupResponse lookupMetadata(MetadataLookupRequest request) {
        return new MetadataLookupResponse(request.uuid(), repository.lookupMetadata(request.uuid()));
    }

    public HardwareInventoryResponse searchHardware(HardwareInventoryRequest request) {
        return new HardwareInventoryResponse(repository.searchHardware(request.deviceName()));
    }

    public RagRetrievalResponse retrieveRag(RagRetrievalRequest request) {
        List<Double> embedding = embed(request.queryText());
        String vectorLiteral = toPgVector(embedding);

        List<RagChunkRow> denseRows = repository.denseSearch(vectorLiteral, DENSE_LIMIT);
        List<RagChunkRow> lexicalRows = repository.lexicalSearch(request.queryText(), LEXICAL_LIMIT);

        Map<String, CandidateState> fusedMap = fuseCandidates(denseRows, lexicalRows);
        List<CandidateState> fusedStates = fusedMap.values().stream()
                .sorted(Comparator.comparingDouble(CandidateState::fusedScore).reversed())
                .limit(RERANK_LIMIT)
                .toList();

        applyRerank(request.queryText(), fusedStates);

        List<CandidateState> rerankedStates = fusedStates.stream()
                .sorted(Comparator
                        .comparingDouble(CandidateState::finalScore).reversed()
                        .thenComparingInt(CandidateState::fusedRank))
                .toList();

        for (int index = 0; index < rerankedStates.size(); index++) {
            rerankedStates.get(index).setRerankRank(index + 1);
        }

        List<RagContextChunk> contextChunks = assembleContext(rerankedStates);
        RagAnswerContextSummary answerContext = summarizeContext(request.queryText(), contextChunks);
        List<RagDocument> documents = contextChunks.stream()
                .map(chunk -> new RagDocument(
                        chunk.chunkId(),
                        chunk.content(),
                        chunk.finalScore(),
                        chunk.sourceDocumentId(),
                        chunk.sourceLabel(),
                        chunk.chunkIndex()
                ))
                .toList();

        return new RagRetrievalResponse(
                request.queryText(),
                denseRowsToCandidates(denseRows, fusedMap),
                lexicalRowsToCandidates(lexicalRows, fusedMap),
                toCandidates(fusedMap.values().stream()
                        .sorted(Comparator.comparingDouble(CandidateState::fusedScore).reversed())
                        .toList()),
                toCandidates(rerankedStates),
                contextChunks,
                answerContext,
                documents
        );
    }

    public RagIngestResponse ingestRag(RagIngestRequest request) {
        String normalized = normalizeContent(request.content());
        String sourceTitle = inferSourceTitle(normalized);
        String sourceDocumentId = repository.insertRagSourceDocument(sourceTitle, normalized);
        List<String> chunks = chunkContent(normalized);
        for (int index = 0; index < chunks.size(); index++) {
            List<Double> embedding = embed(chunks.get(index));
            repository.insertRagChunk(sourceDocumentId, index, chunks.get(index), toPgVector(embedding));
        }
        return new RagIngestResponse(sourceDocumentId, "INGESTED", sourceDocumentId, chunks.size());
    }

    public ContactsResponse listContacts() {
        return new ContactsResponse(repository.listContacts());
    }

    public EmailDraftsResponse listEmailDrafts() {
        return new EmailDraftsResponse(repository.listEmailDrafts());
    }

    public EmailDraftRecord updateEmailDraft(String id, EmailDraftUpdateRequest request) {
        return repository.updateEmailDraft(id, request.recipient(), request.subject(), normalizeEmailBody(request.body()), request.tone());
    }

    public EmailDraftRecord scheduleEmailDraft(String id, EmailDraftScheduleRequest request) {
        return repository.scheduleEmailDraft(id, request.scheduledFor());
    }

    public EmailDraftRecord sendEmailDraft(String id) {
        return repository.markEmailDraftSent(id);
    }

    public void deleteEmailDraft(String id) {
        repository.deleteEmailDraft(id);
    }

    public ContactRecord upsertContact(ContactUpsertRequest request) {
        return repository.upsertContact(request.name(), request.email());
    }

    public void deleteContact(String id) {
        repository.deleteContact(id);
    }

    public ContactsResponse resolveContacts(List<String> names) {
        return new ContactsResponse(repository.findContactsByNames(names));
    }

    public SystemStateResponse systemState() {
        return new SystemStateResponse(repository.databaseId());
    }

    List<String> chunkContent(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = normalizeContent(content);
        if (normalized.length() <= CHUNK_SIZE) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int maxEnd = Math.min(normalized.length(), start + CHUNK_SIZE);
            int end = maxEnd;
            if (end < normalized.length()) {
                int whitespace = normalized.lastIndexOf(' ', end);
                if (whitespace > start + (CHUNK_SIZE / 2)) {
                    end = whitespace;
                }
            }
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }

    List<RagContextChunk> assembleContext(List<CandidateState> candidates) {
        List<RagContextChunk> selected = new ArrayList<>();
        int usedChars = 0;

        for (CandidateState candidate : candidates) {
            if (selected.size() >= CONTEXT_CHUNK_LIMIT) {
                break;
            }
            if (usedChars + candidate.content().length() > CONTEXT_CHAR_BUDGET) {
                continue;
            }
            boolean duplicate = selected.stream().anyMatch(existing ->
                    existing.sourceDocumentId().equals(candidate.sourceDocumentId())
                            && looksNearDuplicate(existing.content(), candidate.content()));
            if (duplicate) {
                continue;
            }
            usedChars += candidate.content().length();
            selected.add(new RagContextChunk(
                    candidate.chunkId(),
                    candidate.sourceDocumentId(),
                    candidate.sourceLabel(),
                    candidate.chunkIndex(),
                    candidate.content(),
                    candidate.finalScore(),
                    candidate.rerankScore() != null ? "selected after reranking" : "selected from fused retrieval"
            ));
        }
        return selected;
    }

    private RagAnswerContextSummary summarizeContext(String query, List<RagContextChunk> contextChunks) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        int charCount = 0;
        for (RagContextChunk chunk : contextChunks) {
            sources.add(chunk.sourceLabel());
            charCount += chunk.content().length();
        }
        String summary = contextChunks.isEmpty()
                ? "No context chunks were selected."
                : "Selected %d chunk(s) from %s."
                .formatted(contextChunks.size(), String.join(", ", sources));
        return new RagAnswerContextSummary(query, summary, List.copyOf(sources), contextChunks.size(), charCount);
    }

    private Map<String, CandidateState> fuseCandidates(List<RagChunkRow> denseRows, List<RagChunkRow> lexicalRows) {
        Map<String, CandidateState> fused = new LinkedHashMap<>();

        for (int index = 0; index < denseRows.size(); index++) {
            RagChunkRow row = denseRows.get(index);
            CandidateState state = fused.computeIfAbsent(row.chunkId(), ignored -> CandidateState.fromRow(row));
            state.setDenseScore(row.score());
            state.setDenseRank(index + 1);
            state.addFusedScore(1.0 / (RRF_K + index + 1));
        }

        for (int index = 0; index < lexicalRows.size(); index++) {
            RagChunkRow row = lexicalRows.get(index);
            CandidateState state = fused.computeIfAbsent(row.chunkId(), ignored -> CandidateState.fromRow(row));
            state.setLexicalScore(row.score());
            state.setLexicalRank(index + 1);
            state.addFusedScore(1.0 / (RRF_K + index + 1));
        }

        List<CandidateState> ranked = fused.values().stream()
                .sorted(Comparator.comparingDouble(CandidateState::fusedScore).reversed())
                .toList();
        for (int index = 0; index < ranked.size(); index++) {
            ranked.get(index).setFusedRank(index + 1);
        }
        return fused;
    }

    private void applyRerank(String query, List<CandidateState> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        try {
            String prompt = buildRerankPrompt(query, candidates);
            String response = generate(prompt);
            Map<String, Object> parsed = parseJsonObject(response);
            List<Map<String, Object>> ranking = objectMapper.convertValue(parsed.get("ranking"), new TypeReference<>() {
            });
            if (ranking == null || ranking.isEmpty()) {
                throw new IllegalArgumentException("missing ranking");
            }

            Map<String, Integer> positionById = new LinkedHashMap<>();
            Map<String, Double> scoreById = new LinkedHashMap<>();
            for (int index = 0; index < ranking.size(); index++) {
                Map<String, Object> item = ranking.get(index);
                String chunkId = String.valueOf(item.get("chunkId"));
                if (chunkId == null || chunkId.isBlank()) {
                    continue;
                }
                positionById.put(chunkId, index);
                Object score = item.get("score");
                if (score instanceof Number number) {
                    scoreById.put(chunkId, number.doubleValue());
                }
            }

            if (positionById.isEmpty()) {
                throw new IllegalArgumentException("invalid ranking");
            }

            for (CandidateState candidate : candidates) {
                Integer position = positionById.get(candidate.chunkId());
                if (position == null) {
                    candidate.setFinalScore(candidate.fusedScore());
                    continue;
                }
                double rerankScore = scoreById.getOrDefault(candidate.chunkId(), Math.max(0.0, 1.0 - (position * 0.1)));
                candidate.setRerankScore(rerankScore);
                candidate.setFinalScore((candidate.fusedScore() * 0.35) + (rerankScore * 0.65));
            }
        } catch (Exception ignored) {
            for (CandidateState candidate : candidates) {
                candidate.setFinalScore(candidate.fusedScore());
                candidate.setRerankScore(null);
            }
        }
    }

    private String buildRerankPrompt(String query, List<CandidateState> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are reranking retrieval candidates for a local RAG system.\n")
                .append("Return strict JSON only with the shape {\"ranking\":[{\"chunkId\":\"...\",\"score\":0.0}] }.\n")
                .append("Scores must be between 0 and 1, highest first.\n")
                .append("User query: ").append(query).append("\nCandidates:\n");
        for (CandidateState candidate : candidates) {
            builder.append("- chunkId: ").append(candidate.chunkId())
                    .append("\n  source: ").append(candidate.sourceLabel())
                    .append("\n  chunkIndex: ").append(candidate.chunkIndex())
                    .append("\n  content: ").append(candidate.content().replace("\n", " "))
                    .append("\n");
        }
        return builder.toString();
    }

    private List<RagChunkCandidate> denseRowsToCandidates(List<RagChunkRow> rows, Map<String, CandidateState> fusedMap) {
        return rows.stream()
                .map(row -> fusedMap.get(row.chunkId()))
                .filter(candidate -> candidate != null)
                .sorted(Comparator.comparingInt(candidate -> candidate.denseRank() == null ? Integer.MAX_VALUE : candidate.denseRank()))
                .map(CandidateState::toCandidate)
                .toList();
    }

    private List<RagChunkCandidate> lexicalRowsToCandidates(List<RagChunkRow> rows, Map<String, CandidateState> fusedMap) {
        return rows.stream()
                .map(row -> fusedMap.get(row.chunkId()))
                .filter(candidate -> candidate != null)
                .sorted(Comparator.comparingInt(candidate -> candidate.lexicalRank() == null ? Integer.MAX_VALUE : candidate.lexicalRank()))
                .map(CandidateState::toCandidate)
                .toList();
    }

    private List<RagChunkCandidate> toCandidates(List<CandidateState> states) {
        return states.stream().map(CandidateState::toCandidate).toList();
    }

    private boolean looksNearDuplicate(String left, String right) {
        String normalizedLeft = left.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String normalizedRight = right.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.contains(normalizedRight)
                || normalizedRight.contains(normalizedLeft);
    }

    private String inferSourceTitle(String content) {
        String firstLine = content.lines().findFirst().orElse(content);
        String trimmed = firstLine.trim();
        if (trimmed.isEmpty()) {
            return "Seeded RAG Document";
        }
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80).trim();
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    @SuppressWarnings("unchecked")
    private List<Double> embed(String text) {
        Map<String, Object> payload = Map.of(
                "model", "nomic-embed-text",
                "prompt", text
        );

        Map<String, Object> response = restTemplate.postForObject(
                ollamaBaseUrl + "/api/embeddings",
                payload,
                Map.class
        );

        if (response == null || response.get("embedding") == null) {
            throw new IllegalStateException("Ollama embedding response missing data");
        }

        return (List<Double>) response.get("embedding");
    }

    @SuppressWarnings("unchecked")
    private String generate(String prompt) {
        Map<String, Object> payload = Map.of(
                "model", "qwen2.5:7b-instruct",
                "prompt", prompt,
                "stream", false,
                "format", "json"
        );

        Map<String, Object> response = restTemplate.postForObject(
                ollamaBaseUrl + "/api/generate",
                payload,
                Map.class
        );

        if (response == null || response.get("response") == null) {
            throw new IllegalStateException("Ollama generation response missing data");
        }
        return String.valueOf(response.get("response"));
    }

    private Map<String, Object> parseJsonObject(String raw) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "").replaceFirst("\\s*```$", "");
        }
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            trimmed = trimmed.substring(objectStart, objectEnd + 1);
        }
        return objectMapper.readValue(trimmed, new TypeReference<>() {
        });
    }

    private String toPgVector(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }

    private static final class CandidateState {
        private final String chunkId;
        private final String sourceDocumentId;
        private final String sourceLabel;
        private final int chunkIndex;
        private final String content;
        private Double denseScore;
        private Double lexicalScore;
        private double fusedScore;
        private Double rerankScore;
        private double finalScore;
        private Integer denseRank;
        private Integer lexicalRank;
        private int fusedRank;
        private Integer rerankRank;

        private CandidateState(String chunkId, String sourceDocumentId, String sourceLabel, int chunkIndex, String content) {
            this.chunkId = chunkId;
            this.sourceDocumentId = sourceDocumentId;
            this.sourceLabel = sourceLabel;
            this.chunkIndex = chunkIndex;
            this.content = content;
        }

        static CandidateState fromRow(RagChunkRow row) {
            return new CandidateState(row.chunkId(), row.sourceDocumentId(), row.sourceLabel(), row.chunkIndex(), row.content());
        }

        String chunkId() {
            return chunkId;
        }

        String sourceDocumentId() {
            return sourceDocumentId;
        }

        String sourceLabel() {
            return sourceLabel;
        }

        int chunkIndex() {
            return chunkIndex;
        }

        String content() {
            return content;
        }

        Double denseScore() {
            return denseScore;
        }

        void setDenseScore(Double denseScore) {
            this.denseScore = denseScore;
        }

        Double lexicalScore() {
            return lexicalScore;
        }

        void setLexicalScore(Double lexicalScore) {
            this.lexicalScore = lexicalScore;
        }

        double fusedScore() {
            return fusedScore;
        }

        void addFusedScore(double increment) {
            this.fusedScore += increment;
            this.finalScore = this.fusedScore;
        }

        Double rerankScore() {
            return rerankScore;
        }

        void setRerankScore(Double rerankScore) {
            this.rerankScore = rerankScore;
        }

        double finalScore() {
            return finalScore;
        }

        void setFinalScore(double finalScore) {
            this.finalScore = finalScore;
        }

        Integer denseRank() {
            return denseRank;
        }

        void setDenseRank(Integer denseRank) {
            this.denseRank = denseRank;
        }

        Integer lexicalRank() {
            return lexicalRank;
        }

        void setLexicalRank(Integer lexicalRank) {
            this.lexicalRank = lexicalRank;
        }

        int fusedRank() {
            return fusedRank;
        }

        void setFusedRank(int fusedRank) {
            this.fusedRank = fusedRank;
        }

        void setRerankRank(Integer rerankRank) {
            this.rerankRank = rerankRank;
        }

        RagChunkCandidate toCandidate() {
            return new RagChunkCandidate(
                    chunkId,
                    sourceDocumentId,
                    sourceLabel,
                    chunkIndex,
                    content,
                    denseScore,
                    lexicalScore,
                    fusedScore,
                    rerankScore,
                    finalScore,
                    denseRank,
                    lexicalRank,
                    fusedRank,
                    rerankRank
            );
        }
    }
}
