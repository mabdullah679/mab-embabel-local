package com.mab.tools.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

@Configuration
public class SchemaInitializer {

    @Bean
    ApplicationRunner ensureSchema(JdbcTemplate jdbcTemplate) {
        return args -> {
            jdbcTemplate.execute("create extension if not exists vector");
            jdbcTemplate.execute("create table if not exists contacts (" +
                    "id uuid primary key, " +
                    "name text not null, " +
                    "email text not null unique, " +
                    "created_at timestamptz not null default now())");
            jdbcTemplate.execute("create table if not exists email_drafts (" +
                    "id uuid primary key, " +
                    "recipient text not null, " +
                    "sender_name text, " +
                    "subject text not null, " +
                    "body text not null, " +
                    "tone text not null, " +
                    "status text not null default 'DRAFT', " +
                    "scheduled_for timestamptz, " +
                    "sent_at timestamptz, " +
                    "created_at timestamptz not null default now(), " +
                    "updated_at timestamptz not null default now())");
            jdbcTemplate.execute("alter table email_drafts add column if not exists sender_name text");
            jdbcTemplate.execute("create table if not exists app_state (" +
                    "id uuid primary key, " +
                    "created_at timestamptz not null default now())");
            jdbcTemplate.execute("create table if not exists rag_source_documents (" +
                    "id uuid primary key, " +
                    "title text not null, " +
                    "content text not null, " +
                    "created_at timestamptz not null default now())");
            jdbcTemplate.execute("create table if not exists rag_document_chunks (" +
                    "id uuid primary key, " +
                    "source_document_id uuid not null references rag_source_documents(id) on delete cascade, " +
                    "chunk_index integer not null, " +
                    "content text not null, " +
                    "content_tsv tsvector generated always as (to_tsvector('english', content)) stored, " +
                    "embedding vector(768) not null, " +
                    "created_at timestamptz not null default now(), " +
                    "unique (source_document_id, chunk_index))");
            jdbcTemplate.execute("create index if not exists rag_document_chunks_content_tsv_idx on rag_document_chunks using gin (content_tsv)");
            jdbcTemplate.execute("create index if not exists rag_document_chunks_source_idx on rag_document_chunks (source_document_id, chunk_index)");
            jdbcTemplate.execute("alter table calendar_events add column if not exists item_type text not null default 'MEETING'");
            jdbcTemplate.execute("alter table calendar_events add column if not exists notes text");
            Integer stateCount = jdbcTemplate.queryForObject("select count(*) from app_state", Integer.class);
            if (stateCount == null || stateCount == 0) {
                jdbcTemplate.update("insert into app_state (id) values (?::uuid)", UUID.randomUUID().toString());
            }
            jdbcTemplate.execute("insert into contacts (id, name, email) values " +
                    "('33333333-3333-3333-3333-333333333333'::uuid, 'Sam', 'sam@example.local'), " +
                    "('44444444-4444-4444-4444-444444444444'::uuid, 'Joe', 'joe@example.local'), " +
                    "('55555555-5555-5555-5555-555555555555'::uuid, 'Alex', 'alex@example.local'), " +
                    "('66666666-6666-6666-6666-666666666666'::uuid, 'Samuel Owusu', 'samuel.owusu@example.local') " +
                    "on conflict (email) do nothing");
        };
    }
}
