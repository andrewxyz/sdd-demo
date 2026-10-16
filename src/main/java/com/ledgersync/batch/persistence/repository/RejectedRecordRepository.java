package com.ledgersync.batch.persistence.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ledgersync.batch.persistence.entity.RejectedRecordEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RejectedRecordRepository extends JpaRepository<RejectedRecordEntity, Long> {

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    List<RejectedRecordEntity> findByExecutionIdOrderBySourceRowNumberAsc(UUID executionId);

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    long countByExecutionId(UUID executionId);

    /**
     * FR-016: rejected records must be retrievable by business date as well as by execution id.
     * Business date lives on {@code settlement_run}, so this is served by the join; the run table
     * is indexed on {@code business_date} (changeset 001).
     */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    @Query("""
            select e from RejectedRecordEntity e, SettlementRun r
             where e.executionId = r.executionId
               and r.businessDate = :businessDate
             order by e.sourceRowNumber asc
            """)
    List<RejectedRecordEntity> findByBusinessDate(@Param("businessDate") LocalDate businessDate);
}
