package com.laimory.server.user;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * user_memory_documents 레포. 교체는 native upsert 한 문장으로 원자 보장한다 — read-then-insert는 같은
 * 사용자의 동시 저장에서 PK 중복 예외가 새어나온다(push_registrations와 같은 선례).
 */
public interface UserMemoryRepository extends JpaRepository<UserMemory, byte[]> {

    /**
     * User Memory 문서 전체 교체. 없으면 insert, 있으면 덮어쓴다(부분 병합 없음). native INSERT는 JPA
     * auditing을 우회하므로 감사 컬럼을 직접 채운다(modified_by는 NULL 유지 — 요청 주체는 access log가 기록).
     *
     * <p>{@code memory}는 직렬화한 JSON 문자열이며 MySQL이 JSON 컬럼으로 캐스팅한다. 파싱 불가 문자열은
     * DB가 거절하므로 "유효한 JSON"이라는 유일한 계약을 DB도 함께 강제한다.
     */
    @Modifying
    @Transactional
    @Query(value = "insert into user_memory_documents (subject_id, memory, created_at, updated_at) "
            + "values (:subjectId, :memory, :now, :now) "
            + "on duplicate key update memory = :memory, updated_at = :now",
            nativeQuery = true)
    void upsert(@Param("subjectId") byte[] subjectId, @Param("memory") String memory,
                @Param("now") LocalDateTime now);

    /** 메모리 제거 — 행 삭제다. 미존재 삭제는 0행(멱등). */
    @Modifying
    @Transactional
    @Query(value = "delete from user_memory_documents where subject_id = :subjectId", nativeQuery = true)
    int deleteBySubjectId(@Param("subjectId") byte[] subjectId);
}
