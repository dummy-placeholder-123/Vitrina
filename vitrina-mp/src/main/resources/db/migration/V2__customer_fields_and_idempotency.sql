ALTER TABLE customer
    ADD COLUMN first_name VARCHAR(255),
    ADD COLUMN last_name VARCHAR(255),
    ADD COLUMN email VARCHAR(255),
    ADD COLUMN phone_number VARCHAR(255),
    ADD COLUMN address VARCHAR(255);

CREATE INDEX idx_customer_email ON customer (email);

CREATE TABLE idempotency_key (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    resource_id UUID,
    resource_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_resource ON idempotency_key (resource_id);
