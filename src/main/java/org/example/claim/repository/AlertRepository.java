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

    /**
     * 设置/取消抑制窗口（US2）：仅当前负责人对「处理中」告警可操作。
     * {@code until} 为 null = 取消窗口；非 null = 覆盖式设置/替换（单活动窗口）。
     * {@code IS DISTINCT FROM} 谓词保证【值真的变了才命中】：取消无窗口 / 重复设置同值 → 0 行，
     * 使 H2 与 MySQL 受影响行数语义一致（否则 NULL→NULL 在 MySQL 计 0、H2 可能计 1，审计会分叉）。
     * 并发语义同 claimIfDiagnosed（行锁 + 谓词重估）；失败由 service 回读分类（404/409 守卫）。
     *
     * @return 受影响行数（1=值变更成功；0=无实际变更 / 非处理中 / 非本人负责 / 已结束 / 不存在）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE alerts
               SET suppressed_until = :until, updated_at = :now
             WHERE alert_name = :alertName AND status = 'IN_PROGRESS' AND claimed_by = :operator
               AND suppressed_until IS DISTINCT FROM :until
            """, nativeQuery = true)
    int setSuppression(@Param("alertName") String alertName,
                       @Param("operator") String operator,
                       @Param("until") Instant until,
                       @Param("now") Instant now);

    List<Alert> findByStatus(AlertStatus status);
}
