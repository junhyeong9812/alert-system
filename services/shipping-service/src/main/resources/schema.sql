create table if not exists shipments (
    order_id   bigint primary key,
    address    text        not null,
    status     text        not null,
    reason     text,
    created_at timestamptz not null
);

create table if not exists alert_status (
    msg_id      uuid primary key,
    payload     jsonb       not null,
    status      text        not null,
    retry_count int         not null,
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);
