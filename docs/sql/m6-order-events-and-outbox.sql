-- Milestone 6: order timeline and live-update outbox (MySQL 8).
-- Hibernate creates these with ddl-auto=update today. When Milestone 5 adds
-- Flyway, use this as the migration after the baseline (or fold it into the
-- baseline if that is generated after M6 merges).

CREATE TABLE IF NOT EXISTS order_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    order_id       BIGINT       NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    from_status    VARCHAR(32)  NULL,
    to_status      VARCHAR(32)  NOT NULL,
    actor_username VARCHAR(255) NULL,
    actor_role     VARCHAR(32)  NULL,
    occurred_at    DATETIME(6)  NOT NULL,
    detail         VARCHAR(500) NULL,
    PRIMARY KEY (id),
    INDEX idx_order_events_order (order_id)
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    type         VARCHAR(64)   NOT NULL,
    payload      VARCHAR(4000) NOT NULL,
    order_id     BIGINT        NULL,
    target_users VARCHAR(500)  NULL,
    target_roles VARCHAR(100)  NULL,
    created_at   DATETIME(6)   NOT NULL,
    published_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_published (published_at),
    INDEX idx_outbox_order (order_id)
);

-- Orders placed before M6 start their timeline here (the app also does this
-- at startup in OrderEventBackfill, so running it is optional).
INSERT INTO order_events (order_id, type, from_status, to_status, occurred_at, detail)
SELECT o.id, 'IMPORTED', NULL, COALESCE(o.status, 'PLACED'), NOW(6), 'History before this point wasn''t recorded.'
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM order_events e WHERE e.order_id = o.id);
