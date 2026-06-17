package com.laimory.server;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 전체 컨텍스트 로드는 MySQL/Redis 연결을 요구하므로 통합 테스트로 분류한다(기본 test 태스크에서 제외).
@Tag("integration")
@SpringBootTest
class ServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
