package com.mab.tools.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.shared.model.CalendarItemRecord;
import com.mab.shared.model.ContactRecord;
import com.mab.shared.model.EmailDraftRecord;
import com.mab.shared.model.HardwareRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

@Repository
public class ToolRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ToolRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String createEvent(String title, String date, String time, List<String> participants, String itemType, String notes) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into calendar_events (id, title, event_date, event_time, participants_json, status, item_type, notes) values (?::uuid, ?, ?::date, ?::time, ?::jsonb, ?, ?, ?)",
                eventId,
                title,
                date,
                time,
                toJson(participants),
                "CREATED",
                itemType,
                notes
        );
        return eventId;
    }

    public List<CalendarItemRecord> listCalendarItems() {
        return jdbcTemplate.query(
                "select id::text, title, event_date::text, event_time::text, participants_json::text, status, item_type, coalesce(notes, ''), created_at::text " +
                        "from calendar_events order by event_date asc, event_time asc, created_at desc",
                (rs, rowNum) -> new CalendarItemRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        fromJsonList(rs.getString(5)),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9)
                )
        );
    }

    public CalendarItemRecord updateCalendarItem(String id, String title, String date, String time, List<String> participants, String itemType, String notes) {
        int updated = jdbcTemplate.update(
                "update calendar_events set title = ?, event_date = ?::date, event_time = ?::time, participants_json = ?::jsonb, item_type = ?, notes = ? where id = ?::uuid",
                title,
                date,
                time,
                toJson(participants),
                itemType,
                notes,
                id
        );
        if (updated == 0) {
            throw new IllegalArgumentException("Calendar item not found: " + id);
        }
        return jdbcTemplate.queryForObject(
                "select id::text, title, event_date::text, event_time::text, participants_json::text, status, item_type, coalesce(notes, ''), created_at::text from calendar_events where id = ?::uuid",
                (rs, rowNum) -> new CalendarItemRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        fromJsonList(rs.getString(5)),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9)
                ),
                id
        );
    }

    public void deleteCalendarItem(String id) {
        int deleted = jdbcTemplate.update(
                "delete from calendar_events where id = ?::uuid",
                id
        );
        if (deleted == 0) {
            throw new IllegalArgumentException("Calendar item not found: " + id);
        }
    }

    public Map<String, Object> lookupMetadata(String uuid) {
        List<String> rows = jdbcTemplate.query(
                "select metadata_json::text from metadata_objects where id = ?::uuid",
                (rs, rowNum) -> rs.getString(1),
                uuid
        );

        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(rows.getFirst(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of("raw", rows.getFirst());
        }
    }

    public List<HardwareRecord> searchHardware(String deviceName) {
        return jdbcTemplate.query(
                "select id, device_name, manufacturer, cpu, ram, storage, metadata_json::text, created_at::text from hardware_records where lower(device_name) like lower(?) order by created_at desc",
                (rs, rowNum) -> new HardwareRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8)
                ),
                "%" + deviceName + "%"
        );
    }

    public String insertRagSourceDocument(String title, String content) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into rag_source_documents (id, title, content) values (?::uuid, ?, ?)",
                id,
                title,
                content
        );
        return id;
    }

    public String insertRagChunk(String sourceDocumentId, int chunkIndex, String content, String vectorLiteral) {
        String chunkId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into rag_document_chunks (id, source_document_id, chunk_index, content, embedding) values (?::uuid, ?::uuid, ?, ?, ?::vector)",
                chunkId,
                sourceDocumentId,
                chunkIndex,
                content,
                vectorLiteral
        );
        return chunkId;
    }

    public List<RagChunkRow> denseSearch(String vectorLiteral, int limit) {
        return jdbcTemplate.query(
                "select c.id::text, c.source_document_id::text, s.title, c.chunk_index, c.content, 1 - (c.embedding <=> ?::vector) as score " +
                        "from rag_document_chunks c join rag_source_documents s on s.id = c.source_document_id " +
                        "order by c.embedding <=> ?::vector limit ?",
                (rs, rowNum) -> new RagChunkRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getString(5),
                        rs.getDouble(6)
                ),
                vectorLiteral,
                vectorLiteral,
                limit
        );
    }

    public List<RagChunkRow> lexicalSearch(String query, int limit) {
        return jdbcTemplate.query(
                "select c.id::text, c.source_document_id::text, s.title, c.chunk_index, c.content, " +
                        "ts_rank_cd(c.content_tsv, websearch_to_tsquery('english', ?)) as score " +
                        "from rag_document_chunks c join rag_source_documents s on s.id = c.source_document_id " +
                        "where c.content_tsv @@ websearch_to_tsquery('english', ?) " +
                        "order by score desc, c.chunk_index asc limit ?",
                (rs, rowNum) -> new RagChunkRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getString(5),
                        rs.getDouble(6)
                ),
                query,
                query,
                limit
        );
    }

    public List<ContactRecord> listContacts() {
        return jdbcTemplate.query(
                "select id::text, name, email, created_at::text from contacts order by name asc",
                (rs, rowNum) -> new ContactRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)
                )
        );
    }

    public EmailDraftRecord createEmailDraft(String recipient, String senderName, String subject, String body, String tone) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into email_drafts (id, recipient, sender_name, subject, body, tone, status) values (?::uuid, ?, ?, ?, ?, ?, 'DRAFT')",
                id,
                recipient,
                nullableTrim(senderName),
                subject,
                body,
                tone
        );
        return findEmailDraft(id);
    }

    public List<EmailDraftRecord> listEmailDrafts() {
        return jdbcTemplate.query(
                "select id::text, recipient, sender_name, subject, body, tone, status, scheduled_for::text, sent_at::text, created_at::text, updated_at::text " +
                        "from email_drafts order by updated_at desc, created_at desc",
                (rs, rowNum) -> new EmailDraftRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9),
                        rs.getString(10),
                        rs.getString(11)
                )
        );
    }

    public EmailDraftRecord updateEmailDraft(String id, String recipient, String senderName, String subject, String body, String tone) {
        int updated = jdbcTemplate.update(
                "update email_drafts set recipient = ?, sender_name = ?, subject = ?, body = ?, tone = ?, updated_at = now() where id = ?::uuid",
                recipient,
                nullableTrim(senderName),
                subject,
                body,
                tone,
                id
        );
        if (updated == 0) {
            throw new IllegalArgumentException("Email draft not found: " + id);
        }
        return findEmailDraft(id);
    }

    public EmailDraftRecord scheduleEmailDraft(String id, String scheduledFor) {
        int updated = jdbcTemplate.update(
                "update email_drafts set status = 'SCHEDULED', scheduled_for = ?::timestamptz, sent_at = null, updated_at = now() where id = ?::uuid",
                scheduledFor,
                id
        );
        if (updated == 0) {
            throw new IllegalArgumentException("Email draft not found: " + id);
        }
        return findEmailDraft(id);
    }

    public EmailDraftRecord markEmailDraftSent(String id) {
        int updated = jdbcTemplate.update(
                "update email_drafts set status = 'SENT', sent_at = now(), updated_at = now() where id = ?::uuid",
                id
        );
        if (updated == 0) {
            throw new IllegalArgumentException("Email draft not found: " + id);
        }
        return findEmailDraft(id);
    }

    public void deleteEmailDraft(String id) {
        int deleted = jdbcTemplate.update(
                "delete from email_drafts where id = ?::uuid",
                id
        );
        if (deleted == 0) {
            throw new IllegalArgumentException("Email draft not found: " + id);
        }
    }

    public ContactRecord upsertContact(String name, String email) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into contacts (id, name, email) values (?::uuid, ?, ?) " +
                        "on conflict (email) do update set name = excluded.name",
                id,
                name,
                email
        );
        return jdbcTemplate.queryForObject(
                "select id::text, name, email, created_at::text from contacts where email = ?",
                (rs, rowNum) -> new ContactRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)
                ),
                email
        );
    }

    public void deleteContact(String id) {
        int deleted = jdbcTemplate.update(
                "delete from contacts where id = ?::uuid",
                id
        );
        if (deleted == 0) {
            throw new IllegalArgumentException("Contact not found: " + id);
        }
    }

    public List<ContactRecord> findContactsByNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> normalized = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(this::normalizeNameTerm)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("select id::text, name, email, created_at::text from contacts where ");
        Object[] params = new Object[normalized.size() * 3];
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append("(lower(name) = ? or split_part(lower(name), ' ', 1) = ? or lower(name) like ?)");
            String term = normalized.get(i);
            params[i * 3] = term;
            params[i * 3 + 1] = term;
            params[i * 3 + 2] = "%" + term + "%";
        }
        sql.append(" order by name asc");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new ContactRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)
                ),
                params
        );
    }

    private String normalizeNameTerm(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized;
    }

    public String databaseId() {
        return jdbcTemplate.queryForObject(
                "select id::text from app_state order by created_at asc limit 1",
                String.class
        );
    }

    public EmailDraftRecord findEmailDraft(String id) {
        return jdbcTemplate.queryForObject(
                "select id::text, recipient, sender_name, subject, body, tone, status, scheduled_for::text, sent_at::text, created_at::text, updated_at::text from email_drafts where id = ?::uuid",
                (rs, rowNum) -> new EmailDraftRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9),
                        rs.getString(10),
                        rs.getString(11)
                ),
                id
        );
    }

    public CalendarItemRecord findCalendarItem(String id) {
        return jdbcTemplate.queryForObject(
                "select id::text, title, event_date::text, event_time::text, participants_json::text, status, item_type, coalesce(notes, ''), created_at::text from calendar_events where id = ?::uuid",
                (rs, rowNum) -> new CalendarItemRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        fromJsonList(rs.getString(5)),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9)
                ),
                id
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }

    private List<String> fromJsonList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String nullableTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record RagChunkRow(
            String chunkId,
            String sourceDocumentId,
            String sourceLabel,
            int chunkIndex,
            String content,
            double score
    ) {
    }
}
