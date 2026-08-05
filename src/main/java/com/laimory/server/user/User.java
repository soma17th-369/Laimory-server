package com.laimory.server.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 소셜 로그인 사용자. 유일성은 (provider, provider_user_id)로만 판별한다 —
 * email 기반 계정 병합 금지(Kakao는 email null 허용, email 병합은 계정 탈취 통로).
 * 같은 사람이 두 provider로 로그인하면 행이 2개 생기는 것이 의도된 동작이다(계정 연동은 후속).
 *
 * <p>{@code @DynamicUpdate}: 로그인의 nickname 갱신과 User Memory 교체는 서로 다른 필드 그룹을 쓰는
 * 별개 흐름이다. Hibernate 기본 UPDATE는 모든 updatable 컬럼을 SET에 포함하므로, 두 흐름이 같은 row를
 * 읽고 순차 커밋하면 나중 커밋이 상대 변경을 자신의 로드 시점 값으로 되돌린다(교차-필드 lost update —
 * 재로그인 한 번이 방금 저장한 User Memory를 지울 수 있다). dynamic update는 실제 변경된 컬럼만 SET해
 * 이 경로를 제거한다(같은 필드 동시 수정은 여전히 last-write-wins). 같은 이유로
 * {@code TimelineEvent}도 이 전략을 쓴다.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
@Getter
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Provider provider;

    /** OIDC id_token의 sub — provider 내에서 사용자를 유일하게 식별한다. */
    @Column(nullable = false)
    private String providerUserId;

    @Column
    private String email;

    @Column(length = 100)
    private String nickname;

    /**
     * AI가 생성·갱신하는 누적 요약. 서버는 opaque 문서로만 다룬다 — 내부 필드·버전을 해석·정규화하지
     * 않고 받은 JSON을 그대로 보존한다. {@code null}은 아직 메모리가 없는 상태다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_memory")
    private JsonNode userMemory;

    protected User() {
    }

    private User(Provider provider, String providerUserId, String email, String nickname) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.nickname = nickname;
    }

    public static User of(Provider provider, String providerUserId, String email, String nickname) {
        return new User(provider, providerUserId, email, nickname);
    }

    /** 재로그인 시 provider 최신 닉네임 반영. 누락 claim으로 기존 값을 지우지 않는 판단은 호출자 몫이다. */
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * User Memory 문서를 통째로 교체한다. 부분 병합은 하지 않으며 {@code null}은 메모리 제거다.
     * 문서 내용에 대한 검증은 "JSON으로 파싱된 값인가"({@link JsonNode} 타입) 하나뿐이다.
     */
    public void replaceUserMemory(JsonNode userMemory) {
        this.userMemory = userMemory;
    }
}
