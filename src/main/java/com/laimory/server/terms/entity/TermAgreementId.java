package com.laimory.server.terms.entity;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.TermVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;

/** 회원 약관 동의의 복합 PK — 한 회원은 같은 종류·버전에 한 번만 동의한다. */
@Embeddable
@Getter
public class TermAgreementId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "term_type", nullable = false, length = 64)
    private TermType termType;

    @Column(name = "version", nullable = false, length = TermVersion.MAX_LENGTH)
    private String version;

    protected TermAgreementId() {
    }

    public TermAgreementId(Long userId, TermType termType, String version) {
        if (userId == null || termType == null) {
            throw new IllegalArgumentException("userId and termType must not be null");
        }
        TermVersion.parse(version);
        this.userId = userId;
        this.termType = termType;
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TermAgreementId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && termType == that.termType
                && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, termType, version);
    }
}
