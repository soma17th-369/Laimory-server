package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.dto.TermResponse;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 전 종류 current 약관 catalog의 캐시 적재 지점(#491).
 *
 * <p>공개 약관 조회·initializer 재동의 판정·동의 등록 검증이 요청마다 읽던 current를 공유 Redis 캐시 엔트리
 * 하나로 둔다. 요청 종류별 키를 두지 않는 이유는 공개 조회 키를 열거할 수 없어 무효화에 일괄 삭제가 필요한데,
 * Redis 캐시 매니저의 일괄 삭제가 {@code KEYS}(전체 keyspace 블로킹)이기 때문이다. 요청 순서·부분집합 재구성은
 * {@link TermDocumentService}가 메모리에서 한다 — 같은 bean 안 호출은 캐시 프록시를 타지 않으므로 적재 메서드를
 * 별도 bean으로 뒀다.
 *
 * <p>무효화는 관리자 등록({@link TermDocumentRegistrationService#register})의 commit 뒤 evict다. 등록 전 상위 버전
 * 검사는 이 캐시가 아니라 DB를 직접 읽는다(stale 캐시로 낮은 버전이 등록되지 않게). 저장소·TTL·동시 miss
 * 의미론은 {@code CacheConfig} 소유다.
 */
@Service
@RequiredArgsConstructor
public class TermCatalogService {

    public static final String CACHE_NAME = "terms:current";
    /** 무효화 지점({@link TermDocumentRegistrationService})이 같은 키를 쓴다. */
    static final String CACHE_MANAGER = "redisCacheManager";
    static final String CACHE_KEY = "'all'";

    private final TermDocumentRepository termDocumentRepository;

    /**
     * 전 종류의 current 문서(enum 선언 순). current가 없는 종류는 빠진다. 캐시 값이라 JSON으로 왕복 가능한
     * 가변 목록으로 만든다({@code Stream.toList()}의 불변 구현은 역직렬화되지 않는다) — 호출자는 수정하지 않는다.
     */
    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, key = CACHE_KEY, sync = true)
    public List<TermResponse> findAllCurrentTerms() {
        Map<TermType, TermDocument> currentByType = TermDocument.selectCurrent(
                termDocumentRepository.findDocumentCandidates(List.of(TermType.values())));
        return Arrays.stream(TermType.values())
                .map(currentByType::get)
                .filter(Objects::nonNull)
                .map(TermResponse::from)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
