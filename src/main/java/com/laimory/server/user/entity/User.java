package com.laimory.server.user.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.user.Provider;
import com.laimory.server.user.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 소셜 로그인 사용자. 유일성은 (provider, provider_user_id)로만 판별한다 —
 * email 기반 계정 병합 금지(Kakao는 email null 허용, email 병합은 계정 탈취 통로).
 * 같은 사람이 두 provider로 로그인하면 행이 2개 생기는 것이 의도된 동작이다(계정 연동은 후속).
 *
 * <p>탈퇴(#305)는 조건부 bulk UPDATE({@code UserRepository#transitionToWithdrawalPending})가
 * {@code WITHDRAWAL_PENDING} 전이·탈퇴 시각·provider identity release(NULL)를 한 문장으로 수행한다 —
 * 이 엔티티에는 상태 전이 메서드를 두지 않는다(read-then-write 부활 레이스 금지).
 */
@Entity
@Table(name = "users")
@Getter
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Provider provider;

    /**
     * OIDC id_token의 sub — provider 내에서 사용자를 유일하게 식별한다.
     * {@code ACTIVE} 행은 application invariant로 non-null이며, 탈퇴 전이만 NULL로 release해
     * 같은 provider identity의 신규 가입 UNIQUE 자리를 비운다(원문은 어디에도 복제하지 않음).
     */
    @Column
    private String providerUserId;

    @Column
    private String email;

    @Column(length = 100)
    private String nickname;

    /** 회원 상태(#305) — 신규 행은 {@code ACTIVE}, 탈퇴 접수 시 조건부 UPDATE로 {@code WITHDRAWAL_PENDING}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /** 탈퇴 접수 서버 시각(#305). {@code ACTIVE} 행은 null이다. */
    @Column
    private LocalDateTime withdrawalRequestedAt;

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
}
