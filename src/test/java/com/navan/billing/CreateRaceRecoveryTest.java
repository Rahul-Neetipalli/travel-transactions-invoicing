package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import com.navan.billing.transaction.TransactionRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The rare path behind a lost create race: our insert hits the unique constraint, but by the time we look the
 * winner is gone (it rolled back or was deleted). Real threads cannot time this reliably, so the insert failure
 * is injected and everything else runs for real over HTTP.
 */
class CreateRaceRecoveryTest extends ApiTestBase {

    @MockitoSpyBean
    TransactionRepository repository;

    /** Lost the race and the winner vanished: nothing is stored and the answer is a retryable 503, never a 500. */
    @Test
    void answersRetryableServiceUnavailableWhenTheWinnerHasVanished() {
        doThrow(uniqueViolation()).when(repository).insert(any(), any(), any(), any());

        Resp response = api.post("/transactions", Fixtures.seed("flight"));

        assertThat(response.status()).as(response.raw()).isEqualTo(503);
        assertThat(response.body().get("code").asText()).isEqualTo("TRY_AGAIN");
        assertThat(response.retryAfter()).isNotNull();
        verify(repository, times(1)).insert(any(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isZero();
    }

    /** Why a 503 is the honest answer: the client's retry of the same body is safe and simply succeeds. */
    @Test
    void theClientsRetryOfTheSameBodySucceeds() {
        doThrow(uniqueViolation()).doCallRealMethod().when(repository).insert(any(), any(), any(), any());

        Resp first = api.post("/transactions", Fixtures.seed("flight"));
        Resp retry = api.post("/transactions", Fixtures.seed("flight"));

        assertThat(first.status()).isEqualTo(503);
        assertThat(retry.status()).as(retry.raw()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(1);
    }

    /** A failure that is not a race (e.g. the database is unreachable) is a real error: a 500 with a trace id. */
    @Test
    void failuresThatAreNotRacesAreA500() {
        doThrow(new DataAccessResourceFailureException("database unreachable")).when(repository).insert(any(), any(), any(), any());

        Resp response = api.post("/transactions", Fixtures.seed("flight"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().get("code").asText()).isEqualTo("INTERNAL");
        assertThat(response.body().get("trace_id").asText()).isNotBlank();
    }

    /** With the real repository, a normal create works (guards against a leaking stub). */
    @Test
    void normalCreatesAreUnaffected() {
        doCallRealMethod().when(repository).insert(any(), any(), any(), any());

        assertThat(api.post("/transactions", Fixtures.seed("hotel")).status()).isEqualTo(201);
    }

    private static DuplicateKeyException uniqueViolation() {
        return new DuplicateKeyException("simulated lost race", new SQLException("duplicate key", "23505"));
    }
}
