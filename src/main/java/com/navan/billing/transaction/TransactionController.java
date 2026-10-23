package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.common.paging.Paging.PageResponse;
import com.navan.billing.transaction.TransactionRecords.TransactionSummary;
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

/** HTTP layer for /transactions. Parsing and status codes only; rules live in TransactionService. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<TransactionView> create(@RequestBody JsonNode body) {
        TransactionView created = service.create(body);
        return ResponseEntity.created(URI.create("/transactions/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    TransactionView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    PageResponse<TransactionSummary> list(@RequestParam(required = false) String limit,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) String offset,
                                          @RequestParam(name = "external_id", required = false) String externalId) {
        return service.list(limit, cursor, offset, externalId);
    }

    @PatchMapping("/{id}")
    TransactionView patch(@PathVariable String id, @RequestBody JsonNode body) {
        return service.patch(id, body);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
