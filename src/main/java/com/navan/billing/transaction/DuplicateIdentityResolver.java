package com.navan.billing.transaction;

import com.navan.billing.common.db.DbErrors;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers "who already owns this external_id?". The indexed read is the fast path; the unique constraint
 * is the race referee. After a failed insert the owner is read again on a new connection, because the failed
 * transaction has rolled back (and a failed Postgres transaction cannot be continued).
 */
@Component
public class DuplicateIdentityResolver {

    private final TransactionRepository repository;

    public DuplicateIdentityResolver(TransactionRepository repository) {
        this.repository = repository;
    }

    /** The id of the transaction that owns this external_id, if any. */
    public Optional<String> findOwner(String externalId) {
        return repository.findIdByExternalId(externalId);
    }

    /** True when an insert failed the way a lost race fails: unique violation, lock timeout or H2 concurrent update. */
    public boolean isLostRace(RuntimeException failure) {
        return DbErrors.isUniqueViolation(failure) || DbErrors.isRetryable(failure);
    }
}
