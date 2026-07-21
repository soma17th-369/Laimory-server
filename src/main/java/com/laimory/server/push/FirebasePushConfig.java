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

    /** 컨텍스트 종료 시 app을 정리해({@code delete}) 재기동 시 [DEFAULT] app 중복 초기화를 막는다. */
    @Bean(destroyMethod = "delete")
    public FirebaseApp firebaseApp() {
        try {
            return FirebaseApp.initializeApp(FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build());
        } catch (IOException e) {
            throw new IllegalStateException("Application Default Credentials are required when "
                    + "app.push.mode=firebase (set GOOGLE_APPLICATION_CREDENTIALS to the "
                    + "service account JSON file path)", e);
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }
}
