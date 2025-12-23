CREATE TABLE tenant (
    uuid UUID PRIMARY KEY,
    tenant_name VARCHAR(255)
);

CREATE TABLE customer (
    uuid UUID PRIMARY KEY,
    name VARCHAR(255),
    tenant_uuid UUID,
    CONSTRAINT fk_customer_tenant
        FOREIGN KEY (tenant_uuid)
        REFERENCES tenant (uuid)
);

CREATE INDEX idx_customer_tenant_uuid ON customer (tenant_uuid);

CREATE TABLE chat (
    uuid UUID PRIMARY KEY,
    chat_name VARCHAR(255)
);

CREATE TABLE deal (
    uuid UUID PRIMARY KEY,
    deal_name VARCHAR(255),
    created_by_uuid UUID,
    chat_uuid UUID,
    CONSTRAINT fk_deal_created_by
        FOREIGN KEY (created_by_uuid)
        REFERENCES customer (uuid),
    CONSTRAINT fk_deal_chat
        FOREIGN KEY (chat_uuid)
        REFERENCES chat (uuid),
    CONSTRAINT uq_deal_chat UNIQUE (chat_uuid)
);

CREATE INDEX idx_deal_created_by_uuid ON deal (created_by_uuid);
CREATE INDEX idx_deal_chat_uuid ON deal (chat_uuid);

CREATE TABLE message (
    ulid VARCHAR(26) PRIMARY KEY,
    created_at TIMESTAMPTZ,
    created_by_uuid UUID,
    chat_uuid UUID,
    CONSTRAINT fk_message_created_by
        FOREIGN KEY (created_by_uuid)
        REFERENCES customer (uuid),
    CONSTRAINT fk_message_chat
        FOREIGN KEY (chat_uuid)
        REFERENCES chat (uuid)
);

CREATE INDEX idx_message_created_by_uuid ON message (created_by_uuid);
CREATE INDEX idx_message_chat_uuid ON message (chat_uuid);
