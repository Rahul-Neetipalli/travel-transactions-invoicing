package com.navan.billing.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real server on a random port, real HTTP, real file-backed H2 in a temp directory (never jdbc:h2:mem).
 * Tables are emptied before every test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTestBase {

    private static final Path DB_DIR = tempDir();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + DB_DIR.resolve("billing") + ";MODE=PostgreSQL;LOCK_TIMEOUT=10000");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api;

    @BeforeEach
    void resetState() {
        api = new Api("http://localhost:" + port);
        for (String table : new String[]{"invoice_tax_lines", "invoice_fee_lines", "invoice_transactions", "invoices",
                "transaction_tax_lines", "transaction_fee_lines", "transactions"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    protected String create(String seedName) {
        Api.Resp created = api.post("/transactions", Fixtures.seed(seedName));
        assertThat(created.status()).as("create %s: %s", seedName, created.raw()).isEqualTo(201);
        return created.id();
    }

    protected String createInvoice(String... transactionIds) {
        Api.Resp created = api.post("/invoices", Fixtures.invoiceOf(transactionIds));
        assertThat(created.status()).as("create invoice: %s", created.raw()).isEqualTo(201);
        return created.id();
    }

    /** Creates the five trip transactions of the fixtures, returned in invoice order. */
    protected String[] createTrip() {
        return new String[]{create("flight"), create("hotel"), create("rail"), create("trip_fee"), create("agent_call_fee")};
    }

    protected static void assertMoney(JsonNode actual, String expected) {
        assertThat(actual.isNumber()).as("expected a number but was %s", actual).isTrue();
        assertThat(actual.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }

    protected static ObjectNode copy(JsonNode node) {
        return ((ObjectNode) node).deepCopy();
    }

    private static Path tempDir() {
        try {
            Path dir = Files.createTempDirectory("billing-test-db");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best effort cleanup of a temp directory
                }
            }));
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
