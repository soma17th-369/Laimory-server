package com.laimory.server.agentcore;

/**
 * AgentCore mode({@code app.ai.mode=agentcore})에서만 소비하는 검증된 설정 스냅샷.
 *
 * <p>값은 {@link AgentCoreClientConfig}가 기동 시 한 번 검증해 만든다 — 이 record가 존재한다는 것은
 * runtime ARN·endpoint가 형식까지 통과했다는 뜻이다(누락·blank·형식 오류는 컨텍스트 기동 실패).
 * region은 기존 {@code aws.region} 단일 권위를 그대로 쓰고 ARN의 region segment와 일치해야 한다.
 */
public record AgentCoreProperties(String runtimeArn, String endpoint, String region) {
}
