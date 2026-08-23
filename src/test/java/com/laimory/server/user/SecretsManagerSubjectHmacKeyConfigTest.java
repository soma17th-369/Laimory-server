package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/** Secrets Manager secret schema와 기동 1회 조회 snapshot 계약을 네트워크 없이 검증한다. */
class SecretsManagerSubjectHmacKeyConfigTest {

    private static final byte[] CURRENT_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PREVIOUS_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);

    @Test
    void loadSnapshot_callsGetSecretValueOnce_thenLookupsUseOnlyMemory() {
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class))).thenReturn(
                GetSecretValueResponse.builder().secretString(currentOnlyJson()).build());
        SecretsManagerSubjectHmacKeyConfig config = new SecretsManagerSubjectHmacKeyConfig();

        SubjectHmacKeySnapshot snapshot = config.loadSnapshot(client, "arn:fixture");
        SubjectLookupKeyDeriver deriver = new SubjectLookupKeyDeriver(snapshot);
        deriver.deriveCurrent(1L);
        deriver.deriveCurrent(2L);

        verify(client).getSecretValue(any(GetSecretValueRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void parse_acceptsCurrentOnlyAndCurrentPreviousSchemas() {
        SubjectHmacKeySnapshot currentOnly =
                SecretsManagerSubjectHmacKeyConfig.parse(currentOnlyJson());
        SubjectHmacKeySnapshot rotating = SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":2,"currentKey":"%s","previousVersion":1,"previousKey":"%s"}
                """.formatted(base64(CURRENT_KEY), base64(PREVIOUS_KEY)));

        assertThat(currentOnly.currentVersion()).isEqualTo((short) 1);
        assertThat(currentOnly.currentKey()).isEqualTo(CURRENT_KEY);
        assertThat(currentOnly.hasPreviousKey()).isFalse();
        assertThat(rotating.currentVersion()).isEqualTo((short) 2);
        assertThat(rotating.previousVersion()).contains((short) 1);
        assertThat(rotating.previousKey().orElseThrow()).isEqualTo(PREVIOUS_KEY);
    }

    @Test
    void parse_rejectsMalformedJsonInvalidVersionAndInvalidKeysWithoutLeakingValues() {
        String sensitive = "sensitive-secret-value";

        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("{" + sensitive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(sensitive);
        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":0,"currentKey":"%s"}
                """.formatted(base64(CURRENT_KEY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentVersion");
        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":1,"currentKey":"%s"}
                """.formatted(sensitive)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64")
                .hasMessageNotContaining(sensitive);
        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":1,"currentKey":"%s"}
                """.formatted(base64(new byte[31]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void parse_rejectsHalfPreviousPairDuplicateVersionAndDuplicateKey() {
        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":2,"currentKey":"%s","previousVersion":1}
                """.formatted(base64(CURRENT_KEY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("present together");
        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":2,"currentKey":"%s","previousVersion":2,"previousKey":"%s"}
                """.formatted(base64(CURRENT_KEY), base64(PREVIOUS_KEY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previousVersion");
        assertThatThrownBy(() -> SecretsManagerSubjectHmacKeyConfig.parse("""
                {"currentVersion":2,"currentKey":"%s","previousVersion":1,"previousKey":"%s"}
                """.formatted(base64(CURRENT_KEY), base64(CURRENT_KEY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    private static String currentOnlyJson() {
        return """
                {"currentVersion":1,"currentKey":"%s"}
                """.formatted(base64(CURRENT_KEY));
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
