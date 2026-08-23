package com.laimory.server.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 번들 드리프트 가드: 모든 ExceptionType의 distinct numeric code는 기본/ko/en 메시지를 가져야 한다.
 * (코드만 추가하고 번역을 빠뜨리면 빌드에서 실패 — use-code-as-default-message 폴백에 조용히 기대지 않게.)
 */
class ExceptionTypeMessagesTest {

    @ParameterizedTest
    @ValueSource(strings = {"messages.properties", "messages_ko.properties", "messages_en.properties"})
    void everyDistinctCodeHasMessageEntry(String bundleFile) throws IOException {
        Properties bundle = load(bundleFile);

        Arrays.stream(ExceptionType.values())
                .map(ExceptionType::messageKey)
                .distinct()
                .forEach(key -> assertThat(bundle.getProperty(key))
                        .as("%s에 %s 메시지 누락", bundleFile, key)
                        .isNotBlank());
    }

    private Properties load(String name) throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("번들 파일 없음: %s", name).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
