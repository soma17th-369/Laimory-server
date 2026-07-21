package com.laimory.server.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * firebase 모드 전용 Firebase Admin SDK 배선. ADC(Application Default Credentials)로만 초기화한다 —
 * credential JSON 원문을 property로 받거나 직접 파싱하지 않고, {@code GOOGLE_APPLICATION_CREDENTIALS}에는
 * 컨테이너 내부 read-only service account JSON <b>파일 경로</b>만 둔다.
 *
 * <p>ADC 로드·초기화 실패는 기동 실패다 — 알림 활성화를 요청한 환경이 조용히 알림을 유실하지 않게
 * fail-fast한다(다른 mode adapter의 필수 설정 fail-fast 관례와 동일).
 */
@Configuration
@ConditionalOnProperty(name = "app.push.mode", havingValue = "firebase")
public class FirebasePushConfig {

    /**
     * Admin SDK HTTP timeout. FirebaseOptions 기본값은 0(무한)이라 FCM 연결이 응답하지 않으면 {@code @Async}
     * 작업이 영구 점유되고 기본 executor thread(8개)가 고갈될 수 있어 유한값을 강제한다.
     * read/write는 500개 multicast batch 왕복을 감안해 connect보다 길게 둔다.
     */
    static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    static final int READ_TIMEOUT_MILLIS = 15_000;
    static final int WRITE_TIMEOUT_MILLIS = 15_000;

    /** 컨텍스트 종료 시 app을 정리해({@code delete}) 재기동 시 [DEFAULT] app 중복 초기화를 막는다. */
    @Bean(destroyMethod = "delete")
    public FirebaseApp firebaseApp() {
        try {
            return FirebaseApp.initializeApp(firebaseOptions(GoogleCredentials.getApplicationDefault()));
        } catch (IOException e) {
            throw new IllegalStateException("Application Default Credentials are required when "
                    + "app.push.mode=firebase (set GOOGLE_APPLICATION_CREDENTIALS to the "
                    + "service account JSON file path)", e);
        }
    }

    /** credential 로드(ADC)와 분리한 options 조립 seam — 유한 timeout 계약을 테스트로 고정한다. */
    static FirebaseOptions firebaseOptions(GoogleCredentials credentials) {
        return FirebaseOptions.builder()
                .setCredentials(credentials)
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setReadTimeout(READ_TIMEOUT_MILLIS)
                .setWriteTimeout(WRITE_TIMEOUT_MILLIS)
                .build();
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }
}
