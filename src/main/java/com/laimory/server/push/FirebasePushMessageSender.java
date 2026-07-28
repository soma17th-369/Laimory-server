package com.laimory.server.push;

import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.laimory.server.timeline.TaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * firebase 모드 {@link PushMessageSender} — Firebase Admin SDK의 FID target 발송(9.10.0에서 추가,
 * registration token target은 deprecated). deprecated {@code setToken/addToken/addAllTokens}는 쓰지 않는다.
 *
 * <p>호출당 최대 {@value #MAX_TARGETS_PER_MULTICAST} target 제약에 맞춰 입력 순서를 보존한 chunk로 나눠
 * 보내고, response index를 같은 순서의 FID에 매핑한다. 영구 무효로 정리할 대상은
 * {@code UNREGISTERED}와 target-level {@code INVALID_ARGUMENT}뿐이다 — server-built payload가 정상임은
 * unit test로 고정하므로 후자를 target(FID) 무효로 간주한다. 인증·project mismatch·quota·internal 오류는
 * 등록 삭제 근거가 아니다.
 *
 * <p>chunk 단위 호출 실패(전이 오류)는 SDK 내부 재시도 후에도 실패한 것이므로 실패 개수로만 흡수하고 다음
 * chunk를 계속한다 — durable retry는 두지 않는다(polling이 안전망). 로그에는 taskId·status·개수·오류 분류만
 * 남기고 FID·Firebase 응답 원문·credential은 남기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.push.mode", havingValue = "firebase")
class FirebasePushMessageSender implements PushMessageSender {

    static final int MAX_TARGETS_PER_MULTICAST = 500;
    /** Android TTL 1시간 — 넘긴 알림은 폐기한다(늦은 알림은 앱 polling·재진입 동기화가 대체). */
    static final Duration ANDROID_TTL = Duration.ofHours(1);
    static final String SUCCESS_TITLE = "타임라인 생성 완료";
    static final String SUCCESS_BODY = "타임라인이 준비됐어요.";
    static final String FAILED_TITLE = "타임라인 생성 실패";
    static final String FAILED_BODY = "타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.";

    private final FirebaseMessaging firebaseMessaging;

    FirebasePushMessageSender(FirebaseMessaging firebaseMessaging) {
        this.firebaseMessaging = firebaseMessaging;
    }

    @Override
    public PushSendResult send(String taskId, TaskStatus status, List<String> firebaseInstallationIds) {
        if (status != TaskStatus.SUCCESS && status != TaskStatus.FAILED) {
            throw new IllegalArgumentException("terminal status required: " + status);
        }
        if (firebaseInstallationIds.isEmpty()) {
            return PushSendResult.empty();
        }
        int successCount = 0;
        int failureCount = 0;
        List<String> invalidFids = new ArrayList<>();
        Map<String, Integer> failureByCode = new LinkedHashMap<>();
        for (int from = 0; from < firebaseInstallationIds.size(); from += MAX_TARGETS_PER_MULTICAST) {
            List<String> chunk = firebaseInstallationIds.subList(from,
                    Math.min(from + MAX_TARGETS_PER_MULTICAST, firebaseInstallationIds.size()));
            try {
                BatchResponse batch = firebaseMessaging.sendEachForMulticast(buildMessage(taskId, status, chunk));
                List<SendResponse> responses = batch.getResponses();
                for (int index = 0; index < responses.size(); index++) {
                    SendResponse response = responses.get(index);
                    if (response.isSuccessful()) {
                        successCount++;
                        continue;
                    }
                    failureCount++;
                    FirebaseMessagingException exception = response.getException();
                    MessagingErrorCode code = exception == null ? null : exception.getMessagingErrorCode();
                    failureByCode.merge(classifyError("TARGET", exception), 1, Integer::sum);
                    if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                        invalidFids.add(chunk.get(index));
                    }
                }
            } catch (FirebaseMessagingException e) {
                // 호출 수준(전체 chunk) 실패 — target 무효 근거가 아니므로 등록은 지우지 않고 다음 chunk 계속.
                failureCount += chunk.size();
                failureByCode.merge(classifyError("CALL", e), chunk.size(), Integer::sum);
            }
        }
        if (failureCount > 0) {
            log.warn("fcm send failures: taskId={} status={} failure={} byCode={}",
                    taskId, status, failureCount, failureByCode);
        }
        return new PushSendResult(firebaseInstallationIds.size(), successCount, failureCount,
                List.copyOf(invalidFids));
    }

    private static String classifyError(String scope, FirebaseMessagingException exception) {
        if (exception == null) {
            return scope + "_UNKNOWN";
        }
        MessagingErrorCode messagingCode = exception.getMessagingErrorCode();
        if (messagingCode != null) {
            return scope + "_FCM_" + messagingCode.name();
        }
        ErrorCode platformCode = exception.getErrorCode();
        return scope + "_PLATFORM_" + (platformCode == null ? "UNKNOWN" : platformCode.name());
    }

    private static MulticastMessage buildMessage(String taskId, TaskStatus status, List<String> fids) {
        boolean success = status == TaskStatus.SUCCESS;
        return MulticastMessage.builder()
                .setNotification(Notification.builder()
                        .setTitle(success ? SUCCESS_TITLE : FAILED_TITLE)
                        .setBody(success ? SUCCESS_BODY : FAILED_BODY)
                        .build())
                .putData("taskId", taskId)
                .putData("status", status.name())
                .setAndroidConfig(AndroidConfig.builder().setTtl(ANDROID_TTL.toMillis()).build())
                .addAllFids(fids)
                .build();
    }
}
