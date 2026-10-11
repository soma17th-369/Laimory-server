package com.laimory.server.credit.repository;

import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.entity.CreditCost;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** credit_costs 레포(#558). 행은 Flyway migration만 쓰므로 조회만 한다. */
public interface CreditCostRepository extends JpaRepository<CreditCost, CreditCostType> {

    /** PK 단건 조회 — 상속 {@code findById}와 달리 인터페이스 선언이라 transaction 없이 실행된다(#499). */
    Optional<CreditCost> findByType(CreditCostType type);
}
