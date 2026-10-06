package com.laimory.server.appconfig;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 구동 설정(정확히 1행) 조회·관리자 변경.
 *
 * <p>조회는 공유 Redis 캐시를 탄다(#491) — {@code /intro}는 앱 기동마다 호출되고 이 SELECT가 요청의 유일한
 * 쿼리라, 적중 시 DB 커넥션을 잡지 않는다. 관리자 웹도 같은 조회를 쓰므로 변경({@link #updateVersions})은
 * commit 뒤 evict해 전 인스턴스의 다음 요청부터 새 값을 보게 한다. 쓰기 경로는 캐시를 거치지 않고 DB를 직접
 * 읽는다(캐시 값을 dirty checking 대상으로 쓰지 않는다). 저장소·TTL·동시 miss 의미론은 {@code CacheConfig} 소유다.
 */
@Service
@RequiredArgsConstructor
public class AppConfigService {

    public static final String CACHE_NAME = "appconfig:current";

    private static final String CACHE_MANAGER = "redisCacheManager";
    /** 행이 하나뿐이라 키도 하나다 — {@code applicationVersion}은 분기가 없고 공개 path 변수라 키에 넣지 않는다. */
    private static final String CACHE_KEY = "'all'";

    private final AppConfigRepository appConfigRepository;

    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, key = CACHE_KEY, sync = true)
    public AppConfigResponse getAppConfig(String applicationVersion) {
        // applicationVersion: 버전별 config 분기 지점(현재 단일 버전이라 분기 없음).
        return AppConfigResponse.from(requireSingleConfig());
    }

    /** 관리자 쓰기 — 결과는 반환하지 않는다. 관리자 웹이 저장 직후 다시 조회한다(조회가 단일 원천, #528). */
    @Transactional
    @CacheEvict(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, key = CACHE_KEY)
    public void updateVersions(Long minimum, Long recommended) {
        requireSingleConfig().updateVersions(minimum, recommended);
    }

    private AppConfig requireSingleConfig() {
        List<AppConfig> rows = appConfigRepository.findTop2ByOrderByAppConfigIdAsc();
        if (rows.size() != 1) {
            throw new IllegalStateException("AppConfig must contain exactly one row");
        }
        return rows.getFirst();
    }
}
