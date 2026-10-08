package com.laimory.server.credit.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.credit.CreditCostType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 크레딧 소비 기능별 비용(#558) — 비용의 단일 기준이다. 모든 서버가 같은 행을 읽으므로 rolling 배포 중에도
 * 앱 고지·사전 검사·차감이 서버마다 갈리지 않는다.
 *
 * <p>행은 Flyway migration만 만들고 바꾸므로(앱 쓰기 경로 없음) 이 엔티티는 조회와 {@code ddl-auto=validate}
 * 검증용 read model이다.
 */
@Entity
@Table(name = "credit_costs")
@Getter
public class CreditCost extends BaseEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private CreditCostType type;

    /** 1회 소비 비용. 0은 무료다(DB CHECK {@code cost >= 0}). */
    @Column(nullable = false)
    private int cost;

    protected CreditCost() {
    }
}
