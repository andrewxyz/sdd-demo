package com.ledgersync.batch.persistence.repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.ledgersync.batch.persistence.entity.StagedTransaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface StagedTransactionRepository extends JpaRepository<StagedTransaction, Long> {

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    long countByExecutionId(UUID executionId);

    /**
     * FR-018: the observed net total is the signed sum of staged rows.
     *
     * <p>Returned as {@link Optional} because an empty file legitimately sums to nothing — a
     * zero-record file is valid and reconciles (spec Edge Cases).
     */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    @Query("select sum(s.signedAmount) from StagedTransaction s where s.executionId = :executionId")
    Optional<BigDecimal> sumSignedAmount(@Param("executionId") UUID executionId);

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    @Query("select count(distinct s.accountNumber) from StagedTransaction s where s.executionId = :executionId")
    long countDistinctAccounts(@Param("executionId") UUID executionId);

    /**
     * FR-028 / research R6: a resumed run clears its own staged rows before re-staging, so a
     * restart cannot double-count.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRED)
    @Query("delete from StagedTransaction s where s.executionId = :executionId")
    int deleteByExecutionId(@Param("executionId") UUID executionId);
}
