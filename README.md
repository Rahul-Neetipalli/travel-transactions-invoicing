# Travel transactions and invoices

A small HTTP service that stores travel transactions (flight, hotel, rail and Navan fees) and builds invoices from them.
An invoice is a frozen copy: changing a transaction later never changes an invoice that already includes it.

Java 21, Spring Boot, plain SQL on a file-backed H2 database. Design notes: [ARCHITECTURE.md](ARCHITECTURE.md).

<br>

## Run

Needs JDK 21 or newer. Nothing else to install.

```bash
./mvnw spring-boot:run
```

The API runs on `http://localhost:8080`. Data is kept in `./data/` (delete the folder to start fresh).

<br>

## Test

```bash
./mvnw test
```

128 tests. They start the real service and call it over HTTP, against a file-backed H2 database.

| # | Required case | Test |
|---|---|---|
| 1 | Create every type; GET returns coupons, hotel address, rail legs, `fee_kind` | `TransactionApiTest` |
| 2 | A duplicate `external_id` does not create a second row | `TransactionApiTest` |
| 3 | Trip invoice totals match `expected-invoice.json` (685.70 EUR) | `InvoiceApiTest` |
| 4 | Changing the stored flight leaves the invoice at 220.00 | `InvoiceSnapshotTest` |
| 5 | An invoice mixing EUR and USD is a 409 | `InvoiceApiTest` |
| 6 | Concurrent creates with the same `external_id` leave one row | `ConcurrencyTest` |
| 7 | PATCH of an invoiced transaction is a 409; the invoice is unchanged | `TransactionMutationTest` |
| 8 | DELETE of an invoiced transaction is a 409; the row remains | `TransactionMutationTest` |
| 9 | DELETE of an invoice keeps its transactions | `InvoiceApiTest` |
| 10 | Default page size is 50; `limit=1000` is a 400 | `PaginationTest` |

Test 4 edits the flight directly in the database, because the API refuses to change an invoiced transaction.

<br>

## API

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/transactions` | 201 | 400 invalid body, 409 duplicate `external_id` |
| GET | `/transactions/{id}` | 200 | 404 |
| GET | `/transactions` | 200 | 400 bad paging |
| PATCH | `/transactions/{id}` | 200 | 404, 409 if invoiced |
| DELETE | `/transactions/{id}` | 204 | 404, 409 if invoiced |
| POST | `/invoices` | 201 | 400, 404 unknown transaction, 409 mixed currency |
| GET | `/invoices/{id}` | 200 | 404 |
| GET | `/invoices` | 200 | 400 bad paging |
| PATCH | `/invoices/{id}` | 200 | 404, 409 mixed currency |
| DELETE | `/invoices/{id}` | 204 | 404 |
| GET | `/health` | 200 | |

An invoice body is `{"transaction_id": "..."}` or `{"transaction_ids": ["...", "..."]}`.

<br>

## Key behaviour

| Topic | Behaviour |
|---|---|
| Duplicate `external_id` | **409**, with the existing transaction's `id`. Nothing is saved. |
| Invoices | Built from a copy of each transaction. Lists **taxes and fees separately**, totals by type, and a grand total. |
| Frozen transactions | A transaction on any invoice cannot be changed or deleted (**409**). Deleting the invoice frees it. |
| Repeated id in one invoice request | **409**, not silently merged. |
| Money | Exact decimals, never floats. Shown at the currency's precision (`220.00`). Extra decimals (`10.005` EUR) are a **400**. |
| `total` | The gross amount. Tax and fee lines break it down; they are not added on top. |
| Paging | `limit` defaults to 50, max 100. Use `cursor` (from `next_cursor`) or `offset`. |
| Errors | JSON (`application/problem+json`) with a `code` and a readable `detail`. |

<br>

## Example requests

Start the service, then run these in order from the project root. They need `curl` and `jq`.

```bash
BASE=http://localhost:8080
SEEDS=fixtures/task-b/seed-payloads.json

# create a transaction from a fixture and print its id
create() { jq "$1" $SEEDS | curl -s -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | jq -r .id; }

# send a request and print the body, then the HTTP status
call() { curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' "$@"; }
```

**Create one of each type**

```bash
FLIGHT=$(create .flight)
HOTEL=$(create .hotel)
RAIL=$(create .rail)
TRIP_FEE=$(create .trip_fee)
AGENT_FEE=$(create .agent_call_fee)
```

**Duplicate create: the same `external_id` twice leaves one row**

```bash
jq .duplicate_flight $SEEDS | call -X POST $BASE/transactions -d @-                   # HTTP 409, with the existing id
curl -s "$BASE/transactions?external_id=flight-TXL-LHR-2026-03-11" | jq '.items | length'   # 1
```

**Get and list transactions**

```bash
curl -s $BASE/transactions/$FLIGHT | jq .
curl -s "$BASE/transactions?limit=2" | jq .
call "$BASE/transactions?limit=1000"                                                   # HTTP 400
```

**Create, get and list invoices**

```bash
INVOICE=$(curl -s -X POST $BASE/invoices -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\": [\"$FLIGHT\", \"$HOTEL\", \"$RAIL\", \"$TRIP_FEE\", \"$AGENT_FEE\"]}" | jq -r .id)

curl -s $BASE/invoices/$INVOICE | jq .                                                 # grand_total 685.70
curl -s $BASE/invoices | jq .
```

**Patch and delete a transaction**

```bash
call -X PATCH $BASE/transactions/$FLIGHT -d '{"total": 999}'                           # HTTP 409, it is invoiced
call -X DELETE $BASE/transactions/$FLIGHT                                              # HTTP 409, it is invoiced

SPARE=$(create '.flight | .external_id = "flight-spare"')
call -X PATCH $BASE/transactions/$SPARE -d '{"total": 230}'                            # HTTP 200
call -X DELETE $BASE/transactions/$SPARE                                               # HTTP 204
```

**Patch and delete an invoice**

```bash
call -X PATCH $BASE/invoices/$INVOICE -d "{\"transaction_ids\": [\"$FLIGHT\", \"$HOTEL\"]}"   # HTTP 200, rebuilt
call -X DELETE $BASE/invoices/$INVOICE                                                 # HTTP 204
call $BASE/transactions/$FLIGHT                                                        # HTTP 200, still there
```

<br>

## Project layout

```
src/main/java/com/navan/billing/
  transaction/    transactions: API, rules, storage, validation
  invoice/        invoices: API, rules, snapshot, totals
  common/         ids, money, paging, errors, database helpers
src/main/resources/db/migration/V1__init.sql     database schema
src/test/java/com/navan/billing/                 tests
fixtures/task-b/                                 example payloads and the expected invoice
```
