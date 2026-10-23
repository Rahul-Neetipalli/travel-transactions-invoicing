-- Portable H2 (PostgreSQL mode) / PostgreSQL DDL.

CREATE TABLE transactions (
    id           VARCHAR(32)  PRIMARY KEY,                 -- txn_<ULID>
    external_id  VARCHAR(128) NOT NULL,
    type         VARCHAR(16)  NOT NULL,
    fee_kind     VARCHAR(32),                              -- promoted from metadata, navan_fee only
    currency     CHAR(3)      NOT NULL,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    total        NUMERIC(19,4) NOT NULL,
    metadata     VARCHAR(65536) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_transactions_external_id UNIQUE (external_id),
    CONSTRAINT ck_transactions_type CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee')),
    CONSTRAINT ck_transactions_fee_kind CHECK (
        (type = 'navan_fee' AND fee_kind IN ('trip_fee', 'agent_call_fee'))
        OR (type <> 'navan_fee' AND fee_kind IS NULL)),
    CONSTRAINT ck_transactions_metadata_json CHECK (metadata IS JSON OBJECT)
);

CREATE TABLE transaction_tax_lines (
    transaction_id VARCHAR(32)   NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT           NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    rate           NUMERIC(9,6),
    amount         NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

CREATE TABLE transaction_fee_lines (
    transaction_id VARCHAR(32)   NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT           NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

CREATE TABLE invoices (
    id                VARCHAR(32)   PRIMARY KEY,           -- inv_<ULID>
    currency          CHAR(3)       NOT NULL,
    total             NUMERIC(19,4) NOT NULL,
    transaction_count INT           NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Frozen snapshot of each transaction. The only live link is transaction_id (traceability and the
-- "is it invoiced?" guard); content is never read back from transactions.
CREATE TABLE invoice_transactions (
    invoice_id     VARCHAR(32)   NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    transaction_id VARCHAR(32)   NOT NULL REFERENCES transactions (id) ON DELETE RESTRICT,
    line_no        INT           NOT NULL,
    type           VARCHAR(16)   NOT NULL,
    fee_kind       VARCHAR(32),
    external_id    VARCHAR(128)  NOT NULL,
    currency       CHAR(3)       NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    total          NUMERIC(19,4) NOT NULL,
    metadata       VARCHAR(65536) NOT NULL,
    PRIMARY KEY (invoice_id, transaction_id),
    CONSTRAINT uq_invoice_transactions_line UNIQUE (invoice_id, line_no)
);
CREATE INDEX ix_invoice_transactions_transaction_id ON invoice_transactions (transaction_id);

CREATE TABLE invoice_tax_lines (
    invoice_id     VARCHAR(32)   NOT NULL,
    transaction_id VARCHAR(32)   NOT NULL,
    position       INT           NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    rate           NUMERIC(9,6),
    amount         NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (invoice_id, transaction_id, position),
    FOREIGN KEY (invoice_id, transaction_id)
        REFERENCES invoice_transactions (invoice_id, transaction_id) ON DELETE CASCADE
);

CREATE TABLE invoice_fee_lines (
    invoice_id     VARCHAR(32)   NOT NULL,
    transaction_id VARCHAR(32)   NOT NULL,
    position       INT           NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (invoice_id, transaction_id, position),
    FOREIGN KEY (invoice_id, transaction_id)
        REFERENCES invoice_transactions (invoice_id, transaction_id) ON DELETE CASCADE
);
