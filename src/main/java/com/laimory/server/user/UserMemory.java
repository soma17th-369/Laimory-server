package com.laimory.server.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자별 User Memory 행 — AI가 만드는 누적 요약 문서 하나다. 행 존재가 "메모리 있음"이고 제거는 행
 * 삭제로 표현한다(별도 상태 컬럼 없음). {@code subject_id}가 PK라 콘텐츠 주체당 최대 1행이다.
 *
 * <p><b>users의 컬럼이 아니라 별도 테이블인 이유</b>: 문서가 커질 수 있는데 JPA 엔티티 로드는 항상 전
 * 컬럼을 SELECT한다. 컬럼으로 두면 로그인의 {@code User} 조회가 매번 이 blob을 함께 읽는다. 테이블을
 * 나눠 {@code User}를 읽는 어떤 경로도 문서에 닿지 않게 한다(규율이 아니라 구조로 차단).
 *
 * <p>서버는 문서를 opaque하게 다룬다 — 내부 필드·버전·스키마를 해석·정규화하지 않고, 검증은 JSON으로
 * 파싱된 값인가({@link JsonNode})가 전부다.
 *
 * <p>쓰기는 repository의 native upsert/delete로만 하므로 이 엔티티는 조회와
 * {@code ddl-auto=validate} 검증용 read model이다. JPA ID에는 attribute converter를 적용하지 않고
 * repository/service 경계에서 {@link com.laimory.server.common.id.SubjectId}와 canonical byte 배열을 변환한다.
 */
@Entity
@Table(name = "user_memory_documents")
@Getter
public class UserMemory extends BaseEntity {

    @Id
    @Column(name = "subject_id", columnDefinition = "BINARY(16)")
    private byte[] subjectId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode memory;

    protected UserMemory() {
    }
}
