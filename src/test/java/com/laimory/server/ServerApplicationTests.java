package com.laimory.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 전체 컨텍스트 로드는 MySQL/Redis 연결을 요구하므로 통합 테스트로 분류한다(기본 test 태스크에서 제외).
// 로컬 docker-compose 인프라에 접속하도록 docker 프로필 사용.
@Tag("integration")
@ActiveProfiles("docker")
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureObservability
class ServerApplicationTests {

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	void contextLoads() {
	}

	@Test
	void meterRegistryContainsHikariAndBoundedApplicationMeters() {
		assertThat(meterRegistry.find("hikaricp.connections").meters()).isNotEmpty();
		assertThat(meterRegistry.find("laimory.timeline.draft.creation").meters()).isNotEmpty();
		assertThat(meterRegistry.find("laimory.timeline.task.terminal").meters()).hasSize(2);
		assertThat(meterRegistry.find("laimory.timeline.callback.duration").meters()).isNotEmpty();
		assertThat(meterRegistry.find("laimory.timeline.task.processing.stuck").meters()).isNotEmpty();
		assertThat(meterRegistry.find("laimory.push.delivery").meters()).hasSize(2);
		assertThat(meterRegistry.find("laimory.build.info").meters()).isNotEmpty();
		assertThat(meterRegistry.get("laimory.timeline.draft.creation").counter().getId().getTags())
				.anyMatch(tag -> tag.getKey().equals("application") && tag.getValue().equals("laimory"))
				.anyMatch(tag -> tag.getKey().equals("environment") && tag.getValue().equals("local"));
	}
}
