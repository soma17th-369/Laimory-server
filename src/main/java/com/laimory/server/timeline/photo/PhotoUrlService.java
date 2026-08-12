package com.laimory.server.timeline.photo;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 사진 서빙용 CloudFront URL(비서명, 안정 URL) 구성기.
 *
 * <p>서빙은 서명 없이 {@code https://{cdnDomain}/{fullKey}} 형태의 안정 URL을 그대로 사용한다(만료/서명
 * 쿼리스트링 없음). 전체 key는 {@link PhotoObjectKeys}의 legacy(userId)·subject 규칙으로 파생하고,
 * CDN 도메인({@code photo.cdn.domain})만 설정에서 주입한다.
 *
 * <p>PHOTO payload 저장 전 주입용이다 — draft enrich와 Event PATCH writer가 이 URL을 payload의
 * {@code photoUrl}로 넣어 DB에 저장하고, 응답은 저장본을 그대로 통과시킨다(읽기 시점 재구성 없음).
 * live 경로는 {@link #buildSubjectUrl}을 사용하고 legacy {@link #buildUrl}은 migration 검증용으로만 남는다.
 */
@Service
public class PhotoUrlService {

    private final String cdnDomain;

    public PhotoUrlService(@Value("${photo.cdn.domain}") String cdnDomain) {
        this.cdnDomain = cdnDomain;
    }

    /**
     * <b>legacy</b> — 파일명과 raw 사용자 id로부터 비서명 CloudFront 서빙 URL을 만든다.
     * migration 검증용으로만 남는다.
     *
     * @param filename 파일명(예: {@code uuidv7.jpg})
     * @param userId   사용자 id(전체 key 파생에 사용)
     * @return {@code https://{cdnDomain}/{sha256hex(userId)}/photos/{filename}}
     */
    public String buildUrl(String filename, long userId) {
        return "https://" + cdnDomain + "/" + PhotoObjectKeys.fullKey(filename, userId);
    }

    /**
     * subject 기반 비서명 CloudFront 서빙 URL. live 경로의 정본이며
     * migration 도구의 URL rewrite 기대값 검증에도 쓴다.
     *
     * @param filename  파일명(예: {@code uuidv7.jpg})
     * @param subjectId 콘텐츠 subject(전체 key 파생에 사용)
     * @return {@code https://{cdnDomain}/{subjectNamespace(subjectId)}/photos/{filename}}
     */
    public String buildSubjectUrl(String filename, UUID subjectId) {
        return "https://" + cdnDomain + "/" + PhotoObjectKeys.subjectFullKey(filename, subjectId);
    }
}
