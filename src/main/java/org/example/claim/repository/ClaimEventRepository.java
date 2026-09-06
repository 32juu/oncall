package org.example.claim.repository;

import org.example.claim.entity.ClaimEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 认领事件仓库（只追加，审计/时间线）。
 */
public interface ClaimEventRepository extends JpaRepository<ClaimEvent, Long> {

    List<ClaimEvent> findByAlertNameOrderByCreatedAtAsc(String alertName);
}
