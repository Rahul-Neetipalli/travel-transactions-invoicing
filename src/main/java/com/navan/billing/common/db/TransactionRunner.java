package com.navan.billing.common.db;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit transaction boundaries (no proxies, so no self-invocation trap). Writes run READ COMMITTED
 * with explicit row locks; multi-statement reads use a snapshot so a response can never be torn.
 */
@Component
public class TransactionRunner {

    private final TransactionTemplate write;
    private final TransactionTemplate snapshotRead;

    public TransactionRunner(PlatformTransactionManager manager) {
        this.write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshotRead = new TransactionTemplate(manager);
        snapshotRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotRead.setReadOnly(true);
    }

    public <T> T inTransaction(Supplier<T> work) {
        return write.execute(status -> work.get());
    }

    public void inTransaction(Runnable work) {
        write.executeWithoutResult(status -> work.run());
    }

    public <T> T inSnapshot(Supplier<T> work) {
        return snapshotRead.execute(status -> work.get());
    }
}
