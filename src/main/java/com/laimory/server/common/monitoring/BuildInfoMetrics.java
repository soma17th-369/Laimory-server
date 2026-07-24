package com.laimory.server.common.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 현재 실행 중인 앱 이미지의 commit 식별자.
 *
 * <p>commit을 모든 meter의 공통 tag로 붙이면 배포마다 전체 시계열이 갈라지므로, 값 1인 info gauge
 * 하나에만 둔다. Git SHA가 아닌 임의 입력은 label 오염을 막기 위해 unknown으로 정규화한다.
 */
@Component
public class BuildInfoMetrics implements MeterBinder {

    static final String BUILD_INFO = "laimory.build.info";

    private final String commit;

    public BuildInfoMetrics(@Value("${app.build.commit:local}") String commit) {
        this.commit = normalize(commit);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(BUILD_INFO, () -> 1)
                .description("Laimory application build information")
                .tag("commit", commit)
                .register(registry);
    }

    static String normalize(String rawCommit) {
        String value = rawCommit == null ? "" : rawCommit.strip().toLowerCase(Locale.ROOT);
        if ("local".equals(value)) {
            return value;
        }
        if (!value.matches("[0-9a-f]{7,64}")) {
            return "unknown";
        }
        return value.substring(0, Math.min(value.length(), 12));
    }
}
