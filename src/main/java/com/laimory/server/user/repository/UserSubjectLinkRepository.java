package com.laimory.server.user.repository;

import com.laimory.server.user.entity.UserSubjectLink;
import com.laimory.server.user.service.SubjectMappingService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * user_subject_links 레포. {@link SubjectMappingService}만 의존한다(arch test로 강제) —
 * lookup key·subject 바이트가 service 밖 application 코드로 새지 않게 하는 경계다.
 */
public interface UserSubjectLinkRepository extends JpaRepository<UserSubjectLink, byte[]> {

    /**
     * rotation의 PK 원자 교체 — previous key로 찾은 행의 lookup key와 version을 current 값으로 한
     * UPDATE 문에서 바꾼다(subject 불변). JPA는 @Id 갱신을 금지하므로 native 벌크 UPDATE로 수행한다.
     *
     * @return 영향 행 수(0 = 동시 교체 경합에서 상대가 먼저 바꿈 — 호출자에게 멱등)
     */
    @Modifying
    @Query(value = "UPDATE user_subject_links "
            + "SET user_lookup_key = :newLookupKey, lookup_key_version = :version "
            + "WHERE user_lookup_key = :oldLookupKey", nativeQuery = true)
    int rekey(@Param("oldLookupKey") byte[] oldLookupKey,
              @Param("newLookupKey") byte[] newLookupKey,
              @Param("version") short version);

    /**
     * 계정 삭제 finalization의 mapping 제거(#302) — lookup key와 subject가 <b>둘 다</b> 일치할 때만
     * 지운다. 콘텐츠 owner 행이 하나라도 남아 있으면 subject FK({@code ON DELETE RESTRICT})가 이
     * 삭제를 거절해 finalization transaction 전체가 rollback된다 — 그게 "콘텐츠를 먼저 지웠다"의
     * DB 차원 증명이자, 지연 도착한 writer와 직렬화되는 지점이다.
     *
     * @return 영향 행 수(0 = mapping이 이미 없거나 기대 subject와 다름 — 호출자는 fail-closed 처리)
     */
    @Modifying
    @Query(value = "DELETE FROM user_subject_links "
            + "WHERE user_lookup_key = :lookupKey AND subject_id = :subjectId", nativeQuery = true)
    int deleteByLookupKeyAndSubjectId(@Param("lookupKey") byte[] lookupKey,
                                      @Param("subjectId") String subjectId);
}
