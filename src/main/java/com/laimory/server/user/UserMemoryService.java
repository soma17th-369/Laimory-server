package com.laimory.server.user;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * user_memories leaf 서비스. 자신과 1:1인 UserMemoryRepository에만 접근한다.
 *
 * <p>서버는 문서를 opaque하게 다룬다 — 내부 필드·버전을 해석·정규화하지 않고 받은 JSON을 그대로
 * 보존한다. 갱신은 전체 교체뿐이며 부분 병합·JSON path 수정은 제공하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryRepository userMemoryRepository;
    private final Clock clock;

    /** 사용자의 User Memory 문서. 행이 없으면(=아직 메모리 없음) 빈 Optional이다. */
    public Optional<JsonNode> find(UUID subjectId) {
        return userMemoryRepository.findById(subjectId).map(UserMemory::getMemory);
    }

    /**
     * 문서를 통째로 교체한다. {@code null}과 JSON {@code null}은 "메모리 없음"으로 보고 행을 지운다 —
     * 의미 없는 행을 남기지 않기 위한 결정이며, 그 외 모든 JSON(객체·배열·스칼라)은 그대로 저장한다.
     *
     * <p>사용자 존재 여부는 확인하지 않는다. {@code subject_id}는
     * {@code user_subject_links.subject_id}를 참조하며 호출자가 인증 경계에서 해석한 subject만 넘긴다.
     */
    public void replace(UUID subjectId, JsonNode memory) {
        if (memory == null || memory.isNull()) {
            userMemoryRepository.deleteBySubjectId(subjectId.toString());
            return;
        }
        userMemoryRepository.upsert(subjectId.toString(), memory.toString(), LocalDateTime.now(clock));
    }
}
