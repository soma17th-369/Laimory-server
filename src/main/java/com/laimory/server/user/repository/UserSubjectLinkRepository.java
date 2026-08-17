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
}
