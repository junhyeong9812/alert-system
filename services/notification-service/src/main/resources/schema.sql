create table if not exists inbox (
    msg_id              uuid primary key,
    service             text        not null,
    payload             jsonb       not null,
    callback_url        text        not null,
    status              text        not null,
    fail_count          int         not null,
    sent_count          int         not null,
    received_count      int         not null,
    retry_command_count int         not null,
    created_at          timestamptz not null,
    updated_at          timestamptz not null
);

create index if not exists inbox_status_created on inbox (status, created_at);
