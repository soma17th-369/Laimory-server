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

/** 약관 문서의 업무 식별자 — 종류와 canonical 버전 pair가 곧 복합 PK다. */
@Embeddable
@Getter
public class TermDocumentId implements Serializable {

    @Enumerated(EnumType.STRING)
    @Column(name = "term_type", nullable = false, length = 64)
    private TermType termType;

    @Column(name = "version", nullable = false, length = TermVersion.MAX_LENGTH)
    private String version;

    protected TermDocumentId() {
    }

    public TermDocumentId(TermType termType, String version) {
        if (termType == null) {
            throw new IllegalArgumentException("termType must not be null");
        }
        TermVersion.parse(version);
        this.termType = termType;
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TermDocumentId that)) {
            return false;
        }
        return termType == that.termType && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(termType, version);
    }
}
