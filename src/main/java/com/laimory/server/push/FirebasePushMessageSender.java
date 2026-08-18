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
import java.time.Clock;
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
 * <p>광고성 메시지에는 {@code (광고)} 제목 표기, 전송자 정보, 무료 수신거부 안내를 여기서 공통으로
 * 합성한다 — 개별 호출자가 빠뜨릴 수 없게 하기 위해서다. 야간 판정도 예정 시각이나 claim 시각이 아니라
 * <b>각 SDK 호출 직전</b>의 실제 KST 시각으로 다시 한다. 그래서 동의 조회와 전송 사이, 또는 여러 chunk를
 * 처리하는 도중 21:00 경계를 넘어도 야간 미동의 target에는 전송되지 않는다.
 *
 * <p>chunk 단위 호출 실패(전이 오류)는 SDK 내부 재시도 후에도 실패한 것이므로 실패 개수로만 흡수하고 다음
 * chunk를 계속한다 — durable retry는 두지 않는다(polling·다음 occurrence가 안전망). 로그에는 알림 종류·
 * 개수·오류 분류만 남기고 FID·Firebase 응답 원문·credential은 남기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.push.mode", havingValue = "firebase")
class FirebasePushMessageSender implements PushMessageSender {

    static final int MAX_TARGETS_PER_MULTICAST = 500;
    /** Android TTL 1시간 — 넘긴 알림은 폐기한다(늦은 알림은 앱 polling·재진입 동기화가 대체). */
    static final Duration ANDROID_TTL = Duration.ofHours(1);
    /** 광고성 정보 표기 — 제목 맨 앞에 붙인다. */
    static final String ADVERTISING_TITLE_PREFIX = "(광고) ";
    /** 무료 수신거부 안내 — 본문 마지막 줄에 붙인다. */
    static final String OPT_OUT_NOTICE = "수신거부: 설정 > 알림 (무료)";
    static final String SENDER_NAME_KEY = "senderName";
    static final String SENDER_CONTACT_KEY = "senderContact";

    private final FirebaseMessaging firebaseMessaging;
    private final PushSenderProperties pushSenderProperties;
    private final Clock clock;

    FirebasePushMessageSender(FirebaseMessaging firebaseMessaging, PushSenderProperties pushSenderProperties,
                              Clock clock) {
        this.firebaseMessaging = firebaseMessaging;
        this.pushSenderProperties = pushSenderProperties;
        this.clock = clock;
    }

    @Override
    public PushSendResult send(PushMessage message, List<PushTarget> targets) {
        if (targets.isEmpty()) {
            return PushSendResult.empty();
        }
        int successCount = 0;
        int failureCount = 0;
        int skippedCount = 0;
        List<String> invalidFids = new ArrayList<>();
        Map<String, Integer> failureByCode = new LinkedHashMap<>();
        for (int from = 0; from < targets.size(); from += MAX_TARGETS_PER_MULTICAST) {
            List<PushTarget> chunk = targets.subList(from,
                    Math.min(from + MAX_TARGETS_PER_MULTICAST, targets.size()));
            // 야간 판정은 chunk마다 다시 한다 — 앞 chunk를 보내는 동안 경계를 넘을 수 있다.
            List<PushTarget> deliverable = filterNightRestricted(message, chunk);
            skippedCount += chunk.size() - deliverable.size();
            if (deliverable.isEmpty()) {
                continue;
            }
            List<String> fids = deliverable.stream().map(PushTarget::firebaseInstallationId).toList();
            try {
                BatchResponse batch = firebaseMessaging.sendEachForMulticast(buildMessage(message, fids));
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
                        invalidFids.add(fids.get(index));
                    }
                }
            } catch (FirebaseMessagingException e) {
                // 호출 수준(전체 chunk) 실패 — target 무효 근거가 아니므로 등록은 지우지 않고 다음 chunk 계속.
                failureCount += fids.size();
                failureByCode.merge(classifyError("CALL", e), fids.size(), Integer::sum);
            }
        }
        if (failureCount > 0) {
            log.warn("fcm send failures: type={} failure={} byCode={}",
                    message.type(), failureCount, failureByCode);
        }
        return new PushSendResult(targets.size(), successCount, failureCount, skippedCount, List.copyOf(invalidFids));
    }

    /**
     * 광고성 메시지이고 지금이 야간이면 야간 동의가 있는 target만 남긴다. 정보성 메시지와 주간 전송에는
     * 아무 제한이 없다 — 예정 시각이 야간이었더라도 실제 전송이 주간이면 일반 광고 동의만으로 보낸다.
     */
    private List<PushTarget> filterNightRestricted(PushMessage message, List<PushTarget> chunk) {
        if (!message.type().isAdvertising() || !PushTimes.isNight(PushTimes.kstWallClock(clock.instant()))) {
            return chunk;
        }
        return chunk.stream().filter(PushTarget::nightAdvertisingConsented).toList();
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

    private MulticastMessage buildMessage(PushMessage message, List<String> fids) {
        boolean advertising = message.type().isAdvertising();
        MulticastMessage.Builder builder = MulticastMessage.builder()
                .setNotification(Notification.builder()
                        .setTitle(advertising ? ADVERTISING_TITLE_PREFIX + message.type().title()
                                : message.type().title())
                        .setBody(advertising ? message.type().body() + "\n" + OPT_OUT_NOTICE
                                : message.type().body())
                        .build())
                .setAndroidConfig(AndroidConfig.builder().setTtl(ANDROID_TTL.toMillis()).build())
                .addAllFids(fids);
        message.data().forEach(builder::putData);
        if (advertising) {
            // 전송자 표기는 Android의 광고 표시 영역이 렌더링한다 — 값 자체는 법무 확정본이다.
            builder.putData(SENDER_NAME_KEY, pushSenderProperties.requireSenderName());
            builder.putData(SENDER_CONTACT_KEY, pushSenderProperties.senderContact());
        }
        return builder.build();
    }
}
