package com.laimory.server.timeline.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 사진 서빙용 CloudFront URL(비서명, 안정 URL) 구성기.
 *
 * <p>서빙은 서명 없이 {@code https://{cdnDomain}/{fullKey}} 형태의 안정 URL을 그대로 사용한다(만료/서명
 * 쿼리스트링 없음). DB에는 파일명만 저장하고 전체 key는 사용자 id로부터 {@link PhotoObjectKeys#fullKey}로
 * 파생한다. CDN 도메인({@code photo.cdn.domain})만 설정에서 주입한다.
 */
@Service
public class PhotoUrlService {

    private final String cdnDomain;

    public PhotoUrlService(@Value("${photo.cdn.domain}") String cdnDomain) {
        this.cdnDomain = cdnDomain;
    }

    /**
     * 파일명과 사용자 id로부터 비서명 CloudFront 서빙 URL을 만든다.
     *
     * @param filename 파일명(예: {@code uuidv7.jpg})
     * @param userId   사용자 id(전체 key 파생에 사용)
     * @return {@code https://{cdnDomain}/{sha256hex(userId)}/photos/{filename}}
     */
    public String buildUrl(String filename, long userId) {
        return "https://" + cdnDomain + "/" + PhotoObjectKeys.fullKey(filename, userId);
    }
}
