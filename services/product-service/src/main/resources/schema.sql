create table if not exists products (
    id    bigint primary key,
    name  text   not null,
    stock bigint not null
);

-- 정상 흐름에서 재고 부족이 나지 않도록 충분한 재고로 시드 (재고 부족은 X-Fault: product:biz로만 주입)
insert into products(id, name, stock)
select g, 'product-' || g, 1000000000 from generate_series(1, 10) g
on conflict (id) do nothing;

create table if not exists reservations (
    order_id   bigint primary key,
    product_id bigint      not null,
    quantity   int         not null,
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
