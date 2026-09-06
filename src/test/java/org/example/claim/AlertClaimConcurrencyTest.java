package org.example.claim;

import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.example.claim.repository.AlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发核心验收：两名值班人同时认领同一条 DIAGNOSED 告警 → 【恰一人成功】，不出现双主。
 * 条件 UPDATE 的行锁 + 谓词重估保证；H2(MODE=MySQL) 上验证语义，真库复验见 quickstart §Step F。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 无外层事务，线程各自开独立事务
class AlertClaimConcurrencyTest {

    private static final String ALERT = "HighCPUUsage";

    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private <T> T inTx(Supplier<T> action) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        return tt.execute(status -> action.get());
    }

    private void seedDiagnosed() {
        inTx(() -> {
            Alert a = new Alert();
            a.setAlertName(ALERT);
            a.setStatus(AlertStatus.DIAGNOSED);
            a.setLastDiagnosedAt(Instant.now());
            alertRepository.save(a);
            return null;
        });
    }

    @Test
    void concurrentClaims_exactlyOneSucceeds() throws Exception {
        seedDiagnosed();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claimAlice = () -> inTx(() ->
                    alertRepository.claimIfDiagnosed(ALERT, "sre-alice", Instant.now()));
            Callable<Integer> claimBob = () -> inTx(() ->
                    alertRepository.claimIfDiagnosed(ALERT, "sre-bob", Instant.now()));

            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(pool.submit(claimAlice));
            futures.add(pool.submit(claimBob));

            long successes = 0;
            for (Future<Integer> f : futures) {
                if (f.get() == 1) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);

            Alert after = inTx(() -> alertRepository.findById(ALERT).orElseThrow());
            assertThat(after.getStatus()).isEqualTo(AlertStatus.IN_PROGRESS);
            assertThat(after.getClaimedBy()).isIn("sre-alice", "sre-bob");
        } finally {
            pool.shutdownNow();
        }
    }
}
