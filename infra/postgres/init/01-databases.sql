-- 서비스별 DB (인스턴스 1개, DB 4개 — spec I6). 테이블은 각 서비스의 schema.sql이 만든다.
create database order_db;
create database product_db;
create database shipping_db;
create database notification_db;
