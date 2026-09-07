package com.laimory.server.terms.entity;

import com.laimory.server.terms.TermType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.Getter;

/** 약관 문서의 업무 식별자 — 종류와 canonical 버전 pair가 곧 복합 PK다. */
@Embeddable
@Getter
public class TermDocumentId implements Serializable {

    public static final int VERSION_MAX_LENGTH = 64;
    public static final String VERSION_PATTERN_TEXT = "^([1-9][0-9]*)[.](0|[1-9][0-9]*)$";
    private static final Pattern VERSION_PATTERN = Pattern.compile(VERSION_PATTERN_TEXT);

    @Enumerated(EnumType.STRING)
    @Column(name = "term_type", nullable = false, length = 64)
    private TermType termType;

    @Column(name = "version", nullable = false, length = VERSION_MAX_LENGTH)
    private String version;

    protected TermDocumentId() {
    }

    public TermDocumentId(TermType termType, String version) {
        if (termType == null) {
            throw new IllegalArgumentException("termType must not be null");
        }
        validateVersion(version);
        this.termType = termType;
        this.version = version;
    }

    /** 키 생성·동의 등록 입력 경계의 형식 검증. 저장 후 조회는 DB CHECK가 보장하는 값을 사용한다. */
    public static void validateVersion(String version) {
        if (version == null || version.length() > VERSION_MAX_LENGTH
                || !VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("invalid canonical term version: " + version);
        }
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
