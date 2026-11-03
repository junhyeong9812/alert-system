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
    ack_resend_count int    not null default 0,
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);

-- 기존 볼륨에 이미 만들어진 테이블에도 컬럼을 추가한다
alter table alert_status add column if not exists ack_resend_count int not null default 0;
