package com.laimory.server.config;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.context.annotation.Configuration;

/**
 * JVM 기본 timezone을 {@code Asia/Seoul}로 고정한다(#371).
 *
 * <p>JDBC URL의 {@code serverTimezone=Asia/Seoul} 아래에서 {@code java.sql.Timestamp}를 거치는
 * 바인딩(Hibernate 엔티티·JPQL·native query)은 "JVM 기본 존으로 해석 → 선언 존으로 렌더링" 두 단계를
 * 타므로, JVM이 UTC면 저장 리터럴에 +9h가 붙는다. 읽기가 −9h로 대칭이라 왕복 테스트로는 드러나지
 * 않고, 운영 SQL처럼 앱 밖에서 리터럴을 읽고 쓰는 순간 깨진다(2026-08-21 리마인더 오발송). 해석 존과
 * 렌더링 존을 같게 만들어 변환을 항등으로 만든다 — 이 저장소 DATETIME 공통 계약(KST 벽시계)의 전제다.
 *
 * <p>{@code main()}의 setDefault만으로는 부족하다 — 테스트는 {@code main()}을 타지 않아 UTC 기동
 * (CI, {@code TZ=UTC})에서 시프트가 살아남는다. 시프트는 쿼리 실행(바인딩) 시점의 기본 존에 걸리므로
 * context 초기화면 충분히 앞선다 — 기동 시점 시각 writer는 {@code ApplicationReadyEvent} 이후의
 * 읽기 전용 검사({@code TermCatalogReadiness})뿐이다.
 */
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    void pinJvmDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }
}
