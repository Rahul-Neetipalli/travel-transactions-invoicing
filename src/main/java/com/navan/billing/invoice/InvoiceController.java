package com.navan.billing.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.common.paging.Paging.PageResponse;
import com.navan.billing.invoice.InvoiceRecords.InvoiceSummary;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP layer for /invoices. Parsing and status codes only; rules live in InvoiceService. */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<InvoiceView> create(@RequestBody JsonNode body) {
        InvoiceView created = service.create(body);
        return ResponseEntity.created(URI.create("/invoices/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    InvoiceView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    PageResponse<InvoiceSummary> list(@RequestParam(required = false) String limit,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) String offset) {
        return service.list(limit, cursor, offset);
    }

    @PatchMapping("/{id}")
    InvoiceView patch(@PathVariable String id, @RequestBody JsonNode body) {
        return service.patch(id, body);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
