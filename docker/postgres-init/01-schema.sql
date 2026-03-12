create extension if not exists vector;

create table if not exists calendar_events (
    id uuid primary key,
    title text not null,
    event_date date not null,
    event_time time not null,
    participants_json jsonb not null,
    status text not null,
    item_type text not null default 'MEETING',
    notes text,
    created_at timestamptz not null default now()
);

create table if not exists contacts (
    id uuid primary key,
    name text not null,
    email text not null unique,
    created_at timestamptz not null default now()
);

create table if not exists email_drafts (
    id uuid primary key,
    recipient text not null,
    subject text not null,
    body text not null,
    tone text not null,
    status text not null default 'DRAFT',
    scheduled_for timestamptz,
    sent_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists app_state (
    id uuid primary key,
    created_at timestamptz not null default now()
);

create table if not exists metadata_objects (
    id uuid primary key,
    metadata_json jsonb not null,
    created_at timestamptz not null default now()
);

create table if not exists hardware_records (
    id uuid primary key,
    device_name text not null,
    manufacturer text not null,
    cpu text not null,
    ram text not null,
    storage text not null,
    metadata_json jsonb not null,
    created_at timestamptz not null default now()
);

create table if not exists rag_source_documents (
    id uuid primary key,
    title text not null,
    content text not null,
    created_at timestamptz not null default now()
);

create table if not exists rag_document_chunks (
    id uuid primary key,
    source_document_id uuid not null references rag_source_documents(id) on delete cascade,
    chunk_index integer not null,
    content text not null,
    content_tsv tsvector generated always as (to_tsvector('english', content)) stored,
    embedding vector(768) not null,
    created_at timestamptz not null default now(),
    unique (source_document_id, chunk_index)
);

create index if not exists rag_document_chunks_content_tsv_idx on rag_document_chunks using gin (content_tsv);
create index if not exists rag_document_chunks_source_idx on rag_document_chunks (source_document_id, chunk_index);
