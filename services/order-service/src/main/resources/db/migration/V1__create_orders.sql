-- Schema for Order Service (spec §13, §16, §19).
-- Owns the orders, order_items, order_status_history, and idempotency_keys tables in the 'ordering' schema.

CREATE TABLE orders (
    id             UUID        PRIMARY KEY,
    user_id        UUID        NOT NULL,
    reservation_id UUID        NOT NULL UNIQUE,
    status         TEXT        NOT NULL,
    total_amount   NUMERIC(19,4) NOT NULL,
    currency       TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT orders_status_valid CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED')),
    CONSTRAINT orders_amount_non_negative CHECK (total_amount >= 0),
    CONSTRAINT orders_timestamps_ordered CHECK (updated_at >= created_at)
);

COMMENT ON TABLE orders IS 'Orders placed against reserved stock. id is application-generated UUIDv7 (ADR-005).';
COMMENT ON CONSTRAINT orders_reservation_id_key ON orders IS 'Strictly one order per reservation (spec §13).';

CREATE TABLE order_items (
    order_id    UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id  UUID          NOT NULL,
    quantity    INT           NOT NULL,
    unit_price  NUMERIC(19,4) NOT NULL,
    currency    TEXT          NOT NULL,
    PRIMARY KEY (order_id, product_id),
    CONSTRAINT order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_items_price_non_negative CHECK (unit_price >= 0)
);

CREATE TABLE order_status_history (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    reason      TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_user_history
    ON orders (user_id, created_at DESC, id);

CREATE INDEX idx_order_status_history_order_id
    ON order_status_history (order_id, occurred_at);

CREATE TABLE idempotency_keys (
    key              TEXT        NOT NULL,
    user_id          UUID        NOT NULL,
    request_hash     TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    response_status  INT,
    response_payload TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_keys_pk PRIMARY KEY (user_id, key),
    CONSTRAINT idempotency_keys_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_keys_order_expires_at
    ON idempotency_keys (expires_at);
