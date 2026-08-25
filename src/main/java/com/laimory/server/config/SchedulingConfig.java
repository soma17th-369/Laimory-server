package com.laimory.server.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled 활성화 + 스케줄러가 쓰는 시스템 Clock 제공(테스트에서 고정 Clock 주입 가능). */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock clock() {
        // systemDefaultZone()은 생성 시점의 JVM 기본 존을 캡처하므로 TimeZoneConfig의 @PostConstruct와
        // bean 생성 순서를 겨루게 된다 — 존을 명시해 순서 무관하게 만든다(#371). draft cleanup cutoff처럼
        // 이 clock의 존이 판정에 직접 쓰인다.
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
