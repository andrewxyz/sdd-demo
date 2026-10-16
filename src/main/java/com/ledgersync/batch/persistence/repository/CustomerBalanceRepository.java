package com.ledgersync.batch.persistence.repository;

import java.util.Collection;
import java.util.Set;

import com.ledgersync.batch.persistence.entity.CustomerBalance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface CustomerBalanceRepository extends JpaRepository<CustomerBalance, String> {

    /** FR-009: a row naming an unrecognised or inactive account is rejected. */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    @Query("select c.accountNumber from CustomerBalance c where c.status = 'ACTIVE'")
    Set<String> findAllActiveAccountNumbers();

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    @Query("""
            select c.accountNumber from CustomerBalance c
             where c.accountNumber in :accountNumbers and c.status = 'ACTIVE'
            """)
    Set<String> findActiveAmong(@Param("accountNumbers") Collection<String> accountNumbers);
}
