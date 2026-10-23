# Architecture

The service makes three promises, and the **database** enforces each one:

1. A transaction is stored **once** per `external_id`.
2. Money is **exact**.
3. An invoice **never changes** after it is built (unless it is explicitly rebuilt).

<br>

## Code layout

Each request goes **controller → service → repository**. Controllers handle HTTP, services hold the rules and own the
database transaction, repositories run SQL. The code is split by feature: `transaction`, `invoice`, `common`.

| Concern | Where |
|---|---|
| HTTP and error responses | `TransactionController`, `InvoiceController`, `GlobalExceptionHandler` |
| Storing transactions | `TransactionRepository` |
| Duplicate `external_id` | `DuplicateIdentityResolver` |
| Building the invoice copy | `InvoiceSnapshotBuilder` |
| Invoice totals and rollups | `InvoiceRollupQuery` |
| Checking type-specific data | `MetadataValidator` and the `metadata/` models |

<br>

## Tables and models

```
transactions ──< transaction_tax_lines
             ──< transaction_fee_lines

invoices ──< invoice_transactions ──< invoice_tax_lines
                                  ──< invoice_fee_lines
```

| Data | Stored as |
|---|---|
| Tax lines (e.g. VAT, with optional rate) | `transaction_tax_lines` rows |
| Fee lines (e.g. YQ, booking fee) | `transaction_fee_lines` rows, separate from taxes |
| Flight: origin, destination, coupons with flight number | `metadata`, checked as `FlightMetadata` |
| Hotel: name and address | `metadata`, checked as `HotelMetadata` |
| Rail: legs (a change of train is two legs) | `metadata`, checked as `RailMetadata` |
| Navan fee: `trip_fee` or `agent_call_fee` | `metadata`, plus a `fee_kind` column limited to those two values |

**Taxes and fees** are real rows in two tables, so the database can add them up and group them for invoices.

**Type-specific data** is a JSON object in `metadata`. Each type is checked against its own model on the way in, so a flight
without coupons or an unknown `fee_kind` is a 400. The database also checks that `metadata` is a JSON object.
JSON fits because an invoice must keep an exact copy of it, and one shape serves both the live transaction and the copy.

**Money** is `NUMERIC(19,4)` and `BigDecimal`, never floating point, including inside `metadata`. Amounts are shown at the
currency's precision, and an amount with too many decimals is rejected, never rounded. A transaction's `total` is the gross
amount; its tax and fee lines break it down.

<br>

## `external_id` uniqueness and concurrent POSTs

`external_id` has a **unique index**. The server's own `id` (`txn_…`) is what everything else refers to.

1. **Known `external_id`?** Look it up first. If it exists, answer **409** with the existing `id` and save nothing,
   whatever the rest of the body says.
2. **Two POSTs at the same moment?** Both may pass step 1. Both try to insert; the unique index lets one succeed and
   rejects the other. The loser then reads the winner and answers **409** with the winner's `id`.
3. **Atomic writes.** A transaction and its tax and fee lines are saved in one database transaction: all or nothing.

The concurrency test sends 16 identical POSTs at once, five times over: each time one is created and fifteen get 409.

<br>

## Invoice snapshot

Creating an invoice runs in one database transaction:

1. Lock the requested transactions.
2. Check that they all exist (**404** lists every missing id) and share one currency (**409** otherwise).
3. Copy each transaction, with its tax lines, fee lines and `metadata`, into the invoice's own tables.
4. Compute the totals from that copy.

From then on the invoice reads only its copy. Editing a transaction cannot change an invoice that already holds it.

**Frozen transactions.** PATCH or DELETE of a transaction that is on any invoice is a **409**, and nothing is written. The
response lists the invoices that hold it. A foreign key also stops the database itself from deleting such a transaction.

**No race between the two.** Creating an invoice and changing a transaction lock the same transaction row, so one waits
for the other. Either the change lands first and the invoice copies the new values, or the invoice lands first and the
change is refused. Locks are always taken in the same order, so requests cannot deadlock each other.

**Rebuild and delete.** `PATCH /invoices/{id}` rebuilds the copy from the current transactions. `DELETE /invoices/{id}`
removes the invoice and leaves the transactions untouched.

<br>

## High volume

What would strain first, and what is in place:

| Pressure | What is in place |
|---|---|
| Large lists | Every list is paged: `limit` defaults to 50, max 100. Paging uses a **cursor** on the id, so deep pages stay fast and stable while rows are added. `offset` is capped at 10 000. No total count is computed. |
| Heavy list payloads | List items are short summaries; the full record comes from `GET /{id}`. |
| Duplicate POST bursts | Each duplicate costs one index lookup and gets a 409. |
| "Is this transaction on an invoice?" | Answered by an index on `invoice_transactions.transaction_id`. |
| Concurrent PATCH, DELETE and invoicing | Row-level locks, held briefly and always in the same order. An invoice holds at most 100 transactions. A lock wait over 10 s returns **503** with `Retry-After`. |
| A single database | H2 is one process. Production would use **PostgreSQL** with several app instances. |

**Moving to PostgreSQL** needs the PostgreSQL driver and Flyway module, a lock timeout (Postgres waits for locks
indefinitely by default) and `metadata` as `JSONB`. The SQL is standard, so the application code does not change.

**Indexes:** primary keys on every table, a unique index on `transactions.external_id`, and an index on
`invoice_transactions.transaction_id`.

<br>

## Known simplifications

- `external_id` is unique across all clients; production would scope it per client.
- A transaction may appear on more than one invoice.
- `total` is trusted as sent; it is not checked against its tax and fee lines.
- Two PATCHes at the same moment: the last one wins.
- Retrying `POST /invoices` creates a second invoice.
- No authentication.
