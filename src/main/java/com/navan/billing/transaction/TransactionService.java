package com.navan.billing.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.billing.common.error.ApiException.ConflictException;
import com.navan.billing.common.error.ApiException.NotFoundException;
import com.navan.billing.common.error.ApiException.TryAgainException;
import com.navan.billing.common.id.IdPrefix;
import com.navan.billing.common.MoneyRules;
import com.navan.billing.common.paging.PageRequestParser;
import com.navan.billing.common.paging.Paging;
import com.navan.billing.common.paging.Paging.PageRequest;
import com.navan.billing.common.paging.Paging.PageResponse;
import com.navan.billing.common.Timestamps;
import com.navan.billing.common.db.TransactionRunner;
import com.navan.billing.common.id.UlidGenerator;
import com.navan.billing.invoice.InvoiceRepository;
import com.navan.billing.transaction.TransactionRecords.TransactionRow;
import com.navan.billing.transaction.TransactionRecords.TransactionSummary;
import com.navan.billing.transaction.TransactionRecords.TransactionSummaryRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Transaction use cases: identity-first create, read, list, and PATCH/DELETE guarded against invoiced rows. */
@Service
public class TransactionService {

    private static final int INVOICE_IDS_IN_CONFLICT = 10;

    private final TransactionRepository repository;
    private final InvoiceRepository invoices;
    private final TransactionRequestValidator validator;
    private final DuplicateIdentityResolver duplicates;
    private final PageRequestParser pageParser;
    private final UlidGenerator ids;
    private final TransactionRunner tx;
    private final Clock clock;
    private final ObjectMapper mapper;

    public TransactionService(TransactionRepository repository, InvoiceRepository invoices, TransactionRequestValidator validator,
                              DuplicateIdentityResolver duplicates, PageRequestParser pageParser, UlidGenerator ids,
                              TransactionRunner tx, Clock clock, ObjectMapper mapper) {
        this.repository = repository;
        this.invoices = invoices;
        this.validator = validator;
        this.duplicates = duplicates;
        this.pageParser = pageParser;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** Identity first: a known external_id is answered by identity (409) whatever the rest of the body says. */
    public TransactionView create(JsonNode body) {
        // 1. Identity: is this external_id already ours? (one unique-index read)
        String externalId = validator.externalId(body);
        Optional<String> owner = duplicates.findOwner(externalId);
        if (owner.isPresent()) {
            throw new DuplicateExternalIdException(externalId, owner.get());
        }
        // 2. Content: full structural validation, every problem reported at once
        NewTransaction request = validator.validateNew(body, externalId);
        String id = ids.next(IdPrefix.TRANSACTION);
        String metadataJson = validator.serialize(request.metadata());
        // 3. Insert header + lines atomically; the unique constraint referees concurrent POSTs.
        try {
            tx.inTransaction(() -> repository.insert(id, request, metadataJson, now()));
        } catch (RuntimeException failure) {
            if (!duplicates.isLostRace(failure)) {
                throw failure;
            }
            // Lost to a concurrent POST: answer with the winner's id. If the winner already vanished (rolled back
            // or deleted), nothing was stored and retrying is safe, so say "retry" (503) rather than "bug" (500).
            String winner = duplicates.findOwner(externalId).orElseThrow(() -> new TryAgainException(failure));
            throw new DuplicateExternalIdException(externalId, winner);
        }
        return get(id);
    }

    public TransactionView get(String id) {
        requireWellFormed(id);
        return tx.inSnapshot(() -> repository.findById(id).map(this::load).orElseThrow(() -> NotFoundException.of("Transaction", id)));
    }

    public PageResponse<TransactionSummary> list(String limit, String cursor, String offset, String externalId) {
        PageRequest page = pageParser.parse(limit, cursor, offset, IdPrefix.TRANSACTION);
        return Paging.toPage(repository.list(page, externalId), page.limit(), TransactionSummaryRow::id, this::summary);
    }

    public TransactionView patch(String id, JsonNode body) {
        requireWellFormed(id);
        return tx.inTransaction(() -> {
            TransactionRow stored = lockIfNotInvoiced(id);
            TransactionPatch patch = validator.validatePatch(body, stored);
            // Omitted fields keep their stored value; lists are replaced whole, never merged.
            String metadataJson = patch.metadata() != null ? validator.serialize(patch.metadata()) : stored.metadata();
            String feeKind = patch.metadata() != null ? patch.feeKind() : stored.feeKind();
            repository.update(id,
                    patch.occurredAt() != null ? patch.occurredAt() : stored.occurredAt(),
                    patch.total() != null ? patch.total() : stored.total(),
                    metadataJson, feeKind, now());
            if (patch.taxLines() != null) {
                repository.replaceTaxLines(id, patch.taxLines());
            }
            if (patch.feeLines() != null) {
                repository.replaceFeeLines(id, patch.feeLines());
            }
            return load(repository.findById(id).orElseThrow());
        });
    }

    public void delete(String id) {
        requireWellFormed(id);
        tx.inTransaction(() -> {
            lockIfNotInvoiced(id);
            repository.delete(id);
        });
    }

    /** Locks the row, then (a separate statement, so it sees any committed invoice) refuses if it is on an invoice. */
    private TransactionRow lockIfNotInvoiced(String id) {
        TransactionRow row = repository.lockById(id).orElseThrow(() -> NotFoundException.of("Transaction", id));
        List<String> onInvoices = invoices.invoiceIdsContaining(id, INVOICE_IDS_IN_CONFLICT + 1);
        if (!onInvoices.isEmpty()) {
            throw new ConflictException("TRANSACTION_INVOICED", "Transaction '" + id + "' is already on an invoice and is frozen")
                    .with("invoice_ids", onInvoices.subList(0, Math.min(onInvoices.size(), INVOICE_IDS_IN_CONFLICT)))
                    .with("has_more", onInvoices.size() > INVOICE_IDS_IN_CONFLICT)
                    .with("hint", "Delete the invoice(s), or PATCH them without this transaction, then retry.");
        }
        return row;
    }

    private TransactionView load(TransactionRow row) {
        String currency = row.currency();
        List<TransactionView.TaxLineView> taxes = repository.taxLines(row.id()).stream()
                .map(tax -> new TransactionView.TaxLineView(tax.name(), MoneyRules.rateForOutput(tax.rate()), MoneyRules.forOutput(tax.amount(), currency)))
                .toList();
        List<TransactionView.FeeLineView> fees = repository.feeLines(row.id()).stream()
                .map(fee -> new TransactionView.FeeLineView(fee.name(), MoneyRules.forOutput(fee.amount(), currency)))
                .toList();
        return new TransactionView(row.id(), row.type(), row.externalId(), currency, Timestamps.format(row.occurredAt()),
                MoneyRules.forOutput(row.total(), currency), taxes, fees, parse(row.metadata()),
                Timestamps.format(row.createdAt()), Timestamps.format(row.updatedAt()));
    }

    private TransactionSummary summary(TransactionSummaryRow row) {
        return new TransactionSummary(row.id(), row.type(), row.externalId(), row.currency(), Timestamps.format(row.occurredAt()),
                MoneyRules.forOutput(row.total(), row.currency()), Timestamps.format(row.createdAt()));
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored metadata is not valid JSON", e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireWellFormed(String id) {
        if (!IdPrefix.TRANSACTION.isWellFormed(id)) {
            throw NotFoundException.of("Transaction", id);
        }
    }
}
