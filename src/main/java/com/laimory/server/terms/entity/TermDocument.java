package com.laimory.server.terms.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.terms.TermType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigInteger;

/**
 * 약관 문서 한 버전 — 불변(immutable) 행이다. 개정은 기존 행 UPDATE가 아니라 새 행 INSERT이며,
 * 게시된 행의 본문·버전을 수정·삭제하는 API는 없다(관리자 등록은 persist 전용 INSERT).
 * 같은 종류에서 canonical {@code major.minor} 버전이 가장 큰 행이 즉시 current다.
 *
 * <p>이 행은 원문을 담지 않는다 — 약관 원문은 게시된 버전별 page가 소유하고 이 행은 그 주소
 * ({@code contentUrl})만 들고 있다(#320). URL은 게시 시점에 확정된 사실이라 코드에서 역산하지 않는다:
 * 게시 host·경로 규칙이 바뀌어도 과거 버전 행이 조용히 다른 주소를 가리키지 않는다. 공개 조회 순서는
 * 요청의 {@code termTypes}가 정하므로 DB에 복제하지 않는다.
 *
 * <p>{@code contentUrl}을 {@code URI}가 아닌 {@code String}으로 매핑하는 이유: 이 값은 운영 seed가 넣는
 * 문자열이고, 타입 변환을 걸면 오타 seed 행 하나가 공개 조회 hydration 자체를 500으로 깨뜨린다. 형식
 * 위반은 {@code TermCatalogReadiness}가 경보하고 조회는 계속된다(term_type binary collation과 같은 이유).
 */
@Entity
@Table(name = "term_documents")
public class TermDocument extends BaseEntity {

    @EmbeddedId
    private TermDocumentId id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 게시된 이 버전 원문 page의 절대 https URL — 서버는 조회·검증만 하고 HTTP로 열지 않는다. */
    @Column(name = "content_url", nullable = false, length = 512)
    private String contentUrl;

    protected TermDocument() {
    }

    private TermDocument(TermDocumentId id, String title, String contentUrl) {
        this.id = id;
        this.title = title;
        this.contentUrl = contentUrl;
    }

    /** 새 버전 행을 만든다. 등록 저장 시 save(merge) 대신 INSERT 전용 repository를 사용한다. */
    public static TermDocument of(TermType termType, String version, String title, String contentUrl) {
        return new TermDocument(new TermDocumentId(termType, version), title, contentUrl);
    }

    /**
     * 같은 종류의 후보 중 더 높은 버전인지 숫자로 비교한다. canonical 형식은 키 생성과 DB CHECK가
     * 보장하므로 조회 중 정규식 검증을 반복하지 않는다.
     */
    public boolean isNewerThan(TermDocument other) {
        String version = getVersion();
        String otherVersion = other.getVersion();
        int separator = version.indexOf('.');
        int otherSeparator = otherVersion.indexOf('.');
        int majorComparison = new BigInteger(version.substring(0, separator))
                .compareTo(new BigInteger(otherVersion.substring(0, otherSeparator)));
        if (majorComparison != 0) {
            return majorComparison > 0;
        }
        return new BigInteger(version.substring(separator + 1))
                .compareTo(new BigInteger(otherVersion.substring(otherSeparator + 1))) > 0;
    }

    public TermDocumentId getId() {
        return id;
    }

    public TermType getTermType() {
        return id.getTermType();
    }

    public String getVersion() {
        return id.getVersion();
    }

    public String getTitle() {
        return title;
    }

    public String getContentUrl() {
        return contentUrl;
    }
}
