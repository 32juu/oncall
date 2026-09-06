package org.example.claim.repository;

import org.example.claim.entity.Alert;
import org.example.claim.entity.AlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * 告警仓库。并发不变量「一条告警至多一个有效认领」在此实现：
 * claimIfDiagnosed 是带状态谓词的原子 UPDATE，仅当 WHERE status='DIAGNOSED' 命中的行被改到（受影响行数==1）才算认领成功。
 * MySQL/H2 对同一主键行的行锁 + 谓词重估保证并发下恰一人成功（plan D2 / data-model §4）。
 */
public interface AlertRepository extends JpaRepository<Alert, String> {

    /**
     * 认领：仅当告警当前为 DIAGNOSED 时置为 IN_PROGRESS 并记归属。
     *
     * @return 受影响行数（1=成功；0=已被认领/已结束/不存在）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE alerts
               SET status = 'IN_PROGRESS', claimed_by = :operator,
                   claimed_at = :now, updated_at = :now
             WHERE alert_name = :alertName AND status = 'DIAGNOSED'
            """, nativeQuery = true)
    int claimIfDiagnosed(@Param("alertName") String alertName,
                         @Param("operator") String operator,
                         @Param("now") Instant now);

    List<Alert> findByStatus(AlertStatus status);
}
