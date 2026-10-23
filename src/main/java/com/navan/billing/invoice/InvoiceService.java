package com.navan.billing.invoice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.billing.common.error.ApiException.NotFoundException;
import com.navan.billing.common.id.IdPrefix;
import com.navan.billing.common.MoneyRules;
import com.navan.billing.common.paging.PageRequestParser;
import com.navan.billing.common.paging.Paging;
import com.navan.billing.common.paging.Paging.PageRequest;
import com.navan.billing.common.paging.Paging.PageResponse;
import com.navan.billing.common.Timestamps;
import com.navan.billing.common.db.TransactionRunner;
import com.navan.billing.common.id.UlidGenerator;
import com.navan.billing.invoice.InvoiceRecords.InvoiceRow;
import com.navan.billing.invoice.InvoiceRecords.InvoiceSummary;
import com.navan.billing.invoice.InvoiceRecords.SnapshotRow;
import com.navan.billing.transaction.TransactionRecords.FeeLine;
import com.navan.billing.transaction.TransactionRecords.TaxLine;
import com.navan.billing.transaction.TransactionType;
import com.navan.billing.transaction.TransactionView;
import com.navan.billing.transaction.metadata.FeeKind;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Invoice use cases. Each write runs in one DB transaction; reads come only from the frozen snapshot. */
@Service
public class InvoiceService {

    // Output order follows the enums: flight, hotel, rail, navan_fee; trip_fee, agent_call_fee.
    private static final List<String> TYPE_ORDER = Arrays.stream(TransactionType.values()).map(TransactionType::wire).toList();
    private static final List<String> FEE_KIND_ORDER = Arrays.stream(FeeKind.values()).map(FeeKind::wire).toList();
    // Keys of "totals", as in expected-invoice.json: one per type, except navan fees which are split by kind.
    private static final List<String> TOTAL_KEYS = Stream.concat(
            TYPE_ORDER.stream().filter(type -> !type.equals(TransactionType.NAVAN_FEE.wire())), FEE_KIND_ORDER.stream()).toList();

    private final InvoiceRepository repository;
    private final InvoiceSnapshotBuilder snapshots;
    private final InvoiceRollupQuery rollups;
    private final PageRequestParser pageParser;
    private final UlidGenerator ids;
    private final TransactionRunner tx;
    private final Clock clock;
    private final ObjectMapper mapper;

    public InvoiceService(InvoiceRepository repository, InvoiceSnapshotBuilder snapshots, InvoiceRollupQuery rollups,
                          PageRequestParser pageParser, UlidGenerator ids, TransactionRunner tx, Clock clock, ObjectMapper mapper) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.rollups = rollups;
        this.pageParser = pageParser;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
        this.mapper = mapper;
    }

    public InvoiceView create(JsonNode body) {
        List<String> transactionIds = InvoiceRequest.parse(body).transactionIds();
        String invoiceId = ids.next(IdPrefix.INVOICE);
        tx.inTransaction(() -> {
            // Lock + check the sources, write the header, copy the snapshot, then derive totals from the copy.
            String currency = snapshots.lockAndValidate(transactionIds);
            repository.insertHeader(invoiceId, currency, now());
            snapshots.copy(invoiceId, transactionIds);
            repository.refreshTotals(invoiceId, currency, now());
        });
        return get(invoiceId);
    }

    /** Atomic re-snapshot from the current stored transactions. Lock order: invoice row, then transaction rows by id. */
    public InvoiceView patch(String invoiceId, JsonNode body) {
        requireWellFormed(invoiceId);
        List<String> transactionIds = InvoiceRequest.parse(body).transactionIds();
        tx.inTransaction(() -> {
            lockInvoice(invoiceId);
            String currency = snapshots.lockAndValidate(transactionIds);
            snapshots.clear(invoiceId);
            snapshots.copy(invoiceId, transactionIds);
            repository.refreshTotals(invoiceId, currency, now());
        });
        return get(invoiceId);
    }

    /** Removes the invoice and its snapshot lines; the underlying transactions are untouched. */
    public void delete(String invoiceId) {
        requireWellFormed(invoiceId);
        tx.inTransaction(() -> {
            lockInvoice(invoiceId);
            repository.delete(invoiceId);
        });
    }

    public InvoiceView get(String invoiceId) {
        requireWellFormed(invoiceId);
        return tx.inSnapshot(() -> {
            InvoiceRow header = repository.findHeader(invoiceId).orElseThrow(() -> NotFoundException.of("Invoice", invoiceId));
            return assemble(header);
        });
    }

    public PageResponse<InvoiceSummary> list(String limit, String cursor, String offset) {
        PageRequest page = pageParser.parse(limit, cursor, offset, IdPrefix.INVOICE);
        return Paging.toPage(repository.list(page), page.limit(), InvoiceRow::id, this::summary);
    }

    private InvoiceSummary summary(InvoiceRow row) {
        return new InvoiceSummary(row.id(), row.currency(), MoneyRules.forOutput(row.total(), row.currency()),
                row.transactionCount(), Timestamps.format(row.createdAt()));
    }

    private InvoiceView assemble(InvoiceRow header) {
        String currency = header.currency();
        List<SnapshotRow> lines = repository.snapshotRows(header.id());
        Map<String, List<TaxLine>> taxes = repository.snapshotTaxLines(header.id());
        Map<String, List<FeeLine>> fees = repository.snapshotFeeLines(header.id());

        List<InvoiceView.SnapshotView> snapshotViews = lines.stream().map(line -> new InvoiceView.SnapshotView(
                line.transactionId(), line.lineNo(), line.type(), line.externalId(), currency, Timestamps.format(line.occurredAt()),
                MoneyRules.forOutput(line.total(), currency),
                taxes.getOrDefault(line.transactionId(), List.of()).stream()
                        .map(tax -> new TransactionView.TaxLineView(tax.name(), MoneyRules.rateForOutput(tax.rate()), MoneyRules.forOutput(tax.amount(), currency)))
                        .toList(),
                fees.getOrDefault(line.transactionId(), List.of()).stream()
                        .map(fee -> new TransactionView.FeeLineView(fee.name(), MoneyRules.forOutput(fee.amount(), currency)))
                        .toList(),
                parse(line.metadata()))).toList();

        // Totals are keyed like expected-invoice.json: by type, except navan fees which are keyed by fee kind.
        Map<String, BigDecimal> byKey = new LinkedHashMap<>();
        List<String> types = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (InvoiceRollupQuery.TypeTotal typeTotal : rollups.totalsByTypeAndKind(header.id())) {
            String key = typeTotal.feeKind() != null ? typeTotal.feeKind() : typeTotal.type();
            byKey.put(key, MoneyRules.forOutput(typeTotal.total(), currency));
            types.add(typeTotal.type());
            if (typeTotal.feeKind() != null) {
                kinds.add(typeTotal.feeKind());
            }
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        TOTAL_KEYS.stream().filter(byKey::containsKey).forEach(key -> totals.put(key, byKey.get(key)));
        totals.put("grand_total", MoneyRules.forOutput(header.total(), currency));

        return new InvoiceView(header.id(), currency, lines.stream().map(SnapshotRow::transactionId).toList(),
                header.transactionCount(),
                TYPE_ORDER.stream().filter(types::contains).toList(),
                FEE_KIND_ORDER.stream().filter(kinds::contains).toList(),
                totals,
                rollups.taxRollup(header.id()).stream()
                        .map(tax -> new InvoiceView.TaxRollupView(tax.name(), MoneyRules.rateForOutput(tax.rate()), MoneyRules.forOutput(tax.amount(), currency)))
                        .toList(),
                rollups.feeRollup(header.id()).stream()
                        .map(fee -> new InvoiceView.FeeRollupView(fee.name(), MoneyRules.forOutput(fee.amount(), currency)))
                        .toList(),
                snapshotViews, Timestamps.format(header.createdAt()), Timestamps.format(header.updatedAt()));
    }

    private void lockInvoice(String invoiceId) {
        if (!repository.lockHeader(invoiceId)) {
            throw NotFoundException.of("Invoice", invoiceId);
        }
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
        if (!IdPrefix.INVOICE.isWellFormed(id)) {
            throw NotFoundException.of("Invoice", id);
        }
    }
}
