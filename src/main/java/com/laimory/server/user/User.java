package com.laimory.server.user;

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

/**
 * 소셜 로그인 사용자. 유일성은 (provider, provider_user_id)로만 판별한다 —
 * email 기반 계정 병합 금지(Kakao는 email null 허용, email 병합은 계정 탈취 통로).
 * 같은 사람이 두 provider로 로그인하면 행이 2개 생기는 것이 의도된 동작이다(계정 연동은 후속).
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

    /** OIDC id_token의 sub — provider 내에서 사용자를 유일하게 식별한다. */
    @Column(nullable = false)
    private String providerUserId;

    @Column
    private String email;

    @Column(length = 100)
    private String nickname;

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
}
