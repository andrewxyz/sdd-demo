package com.ledgersync.batch.persistence.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ledgersync.batch.persistence.entity.RunStatus;
import com.ledgersync.batch.persistence.entity.SettlementRun;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Constitution I — every read declares {@code readOnly = true} and an explicit propagation, so no
 * query relies on an ambient or inherited transaction context.
 */
@Repository
public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    Optional<SettlementRun> findByExecutionId(UUID executionId);

    /** FR-006: redelivery detection. A matching fingerprint means we have seen this exact file. */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    Optional<SettlementRun> findByContentFingerprint(String contentFingerprint);

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    boolean existsByContentFingerprint(String contentFingerprint);

    /** FR-006a / FR-007: is this business date closed, or open to a correction? */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    boolean existsByBusinessDateAndStatus(LocalDate businessDate, RunStatus status);

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    List<SettlementRun> findByBusinessDateOrderByStartedAtAsc(LocalDate businessDate);

    /** FR-002b: which recent business dates never reached SETTLED? */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    @Query("""
            select case when count(r) = 0 then true else false end
              from SettlementRun r
             where r.businessDate = :businessDate
               and r.status = com.ledgersync.batch.persistence.entity.RunStatus.SETTLED
            """)
    boolean noSettledRunFor(@Param("businessDate") LocalDate businessDate);

    /** T080 retention: runs whose records have aged past the retention standard. */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    List<SettlementRun> findByBusinessDateBefore(LocalDate cutoff);
}
