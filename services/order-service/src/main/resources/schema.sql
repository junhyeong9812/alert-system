create table if not exists orders (
    id             bigserial primary key,
    product_id     bigint      not null,
    quantity       int         not null,
    address        text        not null,
    status         text        not null,
    failure_reason text,
    created_at     timestamptz not null,
    updated_at     timestamptz not null
);

create table if not exists alert_status (
    msg_id      uuid primary key,
    payload     jsonb       not null,
    status      text        not null,
    retry_count int         not null,
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);
