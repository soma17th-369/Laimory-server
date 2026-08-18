package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * firebase sender 단위 검증 — server-built payload(문구·data·FID target·TTL)를 고정하고, 광고성 표기 합성,
 * 전송 직전 야간 재판정, 500 chunk 분할, response index 매핑, 오류 분류별 삭제 정책, FID 비로그를 검증한다.
 * 인프라 0.
 *
 * <p>{@link MulticastMessage}는 public 접근자가 없어 pinned SDK(9.10.0)의 내부 필드를 reflection으로
 * 읽는다 — 이 payload 고정이 있어야 response의 {@code INVALID_ARGUMENT}를 target(FID) 무효로 간주하는
 * 전제가 성립한다. SDK 버전을 올리면 이 테스트가 필드 구조 변화를 먼저 잡는다.
 */
@ExtendWith(MockitoExtension.class)
class FirebasePushMessageSenderTest {

    private static final String TASK_ID = "t-1";
    private static final PushMessage COMPLETION = PushMessage.timelineCompletion(TASK_ID, "SUCCESS");
    private static final PushMessage REMINDER = PushMessage.dailyReminder();
    /** KST 14:00 — 주간. */
    private static final Clock DAY_CLOCK = Clock.fixed(Instant.parse("2026-07-21T05:00:00Z"), ZoneOffset.UTC);
    /** KST 21:30 — 야간(21:00 이상). */
    private static final Clock NIGHT_CLOCK = Clock.fixed(Instant.parse("2026-07-21T12:30:00Z"), ZoneOffset.UTC);
    private static final PushSenderProperties SENDER =
            new PushSenderProperties("라이모리 주식회사", "help@laimory.app");

    @Mock
    private FirebaseMessaging firebaseMessaging;

    @Captor
    private ArgumentCaptor<MulticastMessage> messageCaptor;

    private FirebasePushMessageSender sender;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger senderLogger;

    @BeforeEach
    void setUp() {
        sender = new FirebasePushMessageSender(firebaseMessaging, SENDER, DAY_CLOCK);
        senderLogger = (Logger) LoggerFactory.getLogger(FirebasePushMessageSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        senderLogger.detachAppender(logAppender);
    }

    private FirebasePushMessageSender senderAt(Clock clock) {
        return new FirebasePushMessageSender(firebaseMessaging, SENDER, clock);
    }

    /** 야간 동의 없는 정보성/일반 target. */
    private static List<PushTarget> targets(String... fids) {
        return Arrays.stream(fids).map(PushTarget::informational).toList();
    }

    // --- reflection helpers (pinned firebase-admin 9.10.0 내부 구조) ---

    @SuppressWarnings("unchecked")
    private static List<String> fidsOf(MulticastMessage message) {
        return (List<String>) ReflectionTestUtils.getField(message, "fids");
    }

    @SuppressWarnings("unchecked")
    private static List<String> tokensOf(MulticastMessage message) {
        return (List<String>) ReflectionTestUtils.getField(message, "tokens");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> dataOf(MulticastMessage message) {
        return (Map<String, String>) ReflectionTestUtils.getField(message, "data");
    }

    private static String notificationTitleOf(MulticastMessage message) {
        Notification notification = (Notification) ReflectionTestUtils.getField(message, "notification");
        return (String) ReflectionTestUtils.getField(notification, "title");
    }

    private static String notificationBodyOf(MulticastMessage message) {
        Notification notification = (Notification) ReflectionTestUtils.getField(message, "notification");
        return (String) ReflectionTestUtils.getField(notification, "body");
    }

    private static AndroidConfig androidConfigOf(MulticastMessage message) {
        return (AndroidConfig) ReflectionTestUtils.getField(message, "androidConfig");
    }

    // --- response stubs ---

    private static SendResponse successResponse() {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(true);
        return response;
    }

    private static SendResponse failureResponse(MessagingErrorCode code) {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(code);
        when(response.getException()).thenReturn(exception);
        return response;
    }

    private static BatchResponse batchOf(SendResponse... responses) {
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(Arrays.asList(responses));
        return batch;
    }

    /** batch mock은 반드시 바깥 {@code when(...)} 시작 전에 완성한다 — 중첩 스터빙 금지. */
    private void givenAllSuccess(int count) throws FirebaseMessagingException {
        SendResponse[] responses = IntStream.range(0, count)
                .mapToObj(i -> successResponse())
                .toArray(SendResponse[]::new);
        BatchResponse batch = batchOf(responses);
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);
    }

    // --- 정보성 payload 고정 ---

    @Test
    void informational_buildsPlainNotificationWithoutAdvertisingDecoration() throws Exception {
        givenAllSuccess(2);

        PushSendResult result = sender.send(COMPLETION, targets("fid-1", "fid-2"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        MulticastMessage message = messageCaptor.getValue();
        assertThat(notificationTitleOf(message)).isEqualTo("타임라인 생성 완료");
        assertThat(notificationBodyOf(message)).isEqualTo("타임라인이 준비됐어요.");
        // 정보성에는 (광고) 표기·수신거부 안내·전송자 정보를 붙이지 않는다.
        assertThat(dataOf(message)).containsExactlyInAnyOrderEntriesOf(
                Map.of("taskId", TASK_ID, "status", "SUCCESS"));
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
    }

    @Test
    void targetsFids_notDeprecatedTokens() throws Exception {
        givenAllSuccess(2);

        sender.send(COMPLETION, targets("fid-1", "fid-2"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        MulticastMessage message = messageCaptor.getValue();
        // 9.10.0 계약: target은 FID다 — deprecated registration token 목록은 비어 있어야 한다.
        assertThat(fidsOf(message)).containsExactly("fid-1", "fid-2");
        assertThat(tokensOf(message)).isEmpty();
    }

    @Test
    void androidConfig_hasOneHourTtl_andDefaultPriority() throws Exception {
        givenAllSuccess(1);

        sender.send(COMPLETION, targets("fid-1"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        AndroidConfig androidConfig = androidConfigOf(messageCaptor.getValue());
        // TTL 1시간(3600s) — 넘긴 알림은 폐기(늦은 알림은 polling·재진입 동기화가 대체).
        assertThat(ReflectionTestUtils.getField(androidConfig, "ttl")).isEqualTo("3600s");
        // 우선순위는 명시하지 않는다(기본 유지 — 긴급 메시지가 아니고 Doze 지연은 polling으로 수용).
        assertThat(ReflectionTestUtils.getField(androidConfig, "priority")).isNull();
    }

    // --- 광고성 표기 합성 ---

    @Test
    void advertising_prefixesTitleAndAppendsFreeOptOutNoticeWithSenderInfo() throws Exception {
        givenAllSuccess(1);

        sender.send(REMINDER, targets("fid-1"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        MulticastMessage message = messageCaptor.getValue();
        // 광고 표기·무료 수신거부 안내는 sender가 공통으로 붙인다 — 호출자가 빠뜨릴 수 없다.
        assertThat(notificationTitleOf(message)).isEqualTo("(광고) 타임라인을 완성해보세요!");
        assertThat(notificationBodyOf(message)).isEqualTo("하루를 기록해보세요!\n수신거부: 설정 > 알림 (무료)");
        assertThat(dataOf(message)).containsAllEntriesOf(Map.of(
                "senderName", "라이모리 주식회사",
                "senderContact", "help@laimory.app",
                "optOutRoute", PushMessage.OPT_OUT_ROUTE));
        // 수신거부 credential은 payload에 싣지 않는다 — 단말이 보관한 값으로 호출한다.
        assertThat(dataOf(message)).doesNotContainKeys("optOutToken", "firebaseInstallationId");
    }

    // --- 전송 직전 야간 재판정 ---

    @Test
    void advertisingAtNight_dropsTargetsWithoutNightConsent() throws Exception {
        BatchResponse batch = batchOf(successResponse());
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        PushSendResult result = senderAt(NIGHT_CLOCK).send(REMINDER, List.of(
                new PushTarget("fid-night-ok", true),
                new PushTarget("fid-no-night", false)));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        assertThat(fidsOf(messageCaptor.getValue())).containsExactly("fid-night-ok");
        assertThat(result.targetCount()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    @Test
    void advertisingAtNight_allTargetsWithoutNightConsent_skipsFcmCallEntirely() throws Exception {
        PushSendResult result = senderAt(NIGHT_CLOCK).send(REMINDER, targets("fid-1", "fid-2"));

        verify(firebaseMessaging, never()).sendEachForMulticast(any());
        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.successCount()).isZero();
        assertThat(result.failureCount()).isZero();
    }

    @Test
    void advertisingDuringDay_sendsWithoutNightConsent() throws Exception {
        givenAllSuccess(1);

        // 예정 시각이 야간이었더라도 실제 전송이 주간이면 일반 광고 동의만으로 보낸다.
        PushSendResult result = sender.send(REMINDER, targets("fid-1"));

        verify(firebaseMessaging).sendEachForMulticast(any());
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
    }

    @Test
    void informationalAtNight_isNotRestricted() throws Exception {
        givenAllSuccess(1);

        PushSendResult result = senderAt(NIGHT_CLOCK).send(COMPLETION, targets("fid-1"));

        verify(firebaseMessaging).sendEachForMulticast(any());
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
    }

    // --- chunk 분할 ---

    @Test
    void emptyTargets_skipFcmEntirely() throws Exception {
        PushSendResult result = sender.send(COMPLETION, List.of());

        verify(firebaseMessaging, never()).sendEachForMulticast(any());
        assertThat(result).isEqualTo(PushSendResult.empty());
    }

    @Test
    void exactly500Targets_singleCall() throws Exception {
        List<PushTarget> fids = IntStream.range(0, 500)
                .mapToObj(i -> PushTarget.informational("fid-" + i)).toList();
        givenAllSuccess(500);

        PushSendResult result = sender.send(COMPLETION, fids);

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues()).hasSize(1);
        assertThat(fidsOf(messageCaptor.getValue())).hasSize(500);
        assertThat(result.successCount()).isEqualTo(500);
    }

    @Test
    void fiveHundredOneTargets_splitInto500Plus1_preservingOrder() throws Exception {
        List<PushTarget> fids = IntStream.range(0, 501)
                .mapToObj(i -> PushTarget.informational("fid-" + i)).toList();
        SendResponse[] first = IntStream.range(0, 500).mapToObj(i -> successResponse())
                .toArray(SendResponse[]::new);
        BatchResponse firstBatch = batchOf(first);
        BatchResponse secondBatch = batchOf(successResponse());
        when(firebaseMessaging.sendEachForMulticast(any()))
                .thenReturn(firstBatch)
                .thenReturn(secondBatch);

        PushSendResult result = sender.send(COMPLETION, fids);

        verify(firebaseMessaging, org.mockito.Mockito.times(2)).sendEachForMulticast(messageCaptor.capture());
        List<MulticastMessage> messages = messageCaptor.getAllValues();
        assertThat(fidsOf(messages.get(0))).hasSize(500).startsWith("fid-0").endsWith("fid-499");
        assertThat(fidsOf(messages.get(1))).containsExactly("fid-500");
        assertThat(result.targetCount()).isEqualTo(501);
        assertThat(result.successCount()).isEqualTo(501);
    }

    // --- response 매핑과 삭제 정책 ---

    @Test
    void mapsFailuresByResponseIndex_collectingOnlyInvalidCodes() throws Exception {
        // index 매핑: [성공, UNREGISTERED, INVALID_ARGUMENT, UNAVAILABLE] → 무효는 2·3번째 FID만.
        BatchResponse batch = batchOf(
                successResponse(),
                failureResponse(MessagingErrorCode.UNREGISTERED),
                failureResponse(MessagingErrorCode.INVALID_ARGUMENT),
                failureResponse(MessagingErrorCode.UNAVAILABLE));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        PushSendResult result = sender.send(COMPLETION,
                targets("fid-ok", "fid-gone", "fid-bad", "fid-flaky"));

        assertThat(result.targetCount()).isEqualTo(4);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(3);
        assertThat(result.invalidFirebaseInstallationIds()).containsExactly("fid-gone", "fid-bad");
    }

    @Test
    void nightSkippedTargets_areNotMisalignedWithResponseIndexes() throws Exception {
        // 야간 제외 뒤 남은 target 순서와 response index가 어긋나면 엉뚱한 FID를 무효로 지운다.
        BatchResponse batch = batchOf(successResponse(), failureResponse(MessagingErrorCode.UNREGISTERED));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        PushSendResult result = senderAt(NIGHT_CLOCK).send(REMINDER, List.of(
                new PushTarget("fid-dropped", false),
                new PushTarget("fid-kept-ok", true),
                new PushTarget("fid-kept-gone", true)));

        assertThat(result.invalidFirebaseInstallationIds()).containsExactly("fid-kept-gone");
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = MessagingErrorCode.class,
            names = {"THIRD_PARTY_AUTH_ERROR", "QUOTA_EXCEEDED", "SENDER_ID_MISMATCH", "UNAVAILABLE", "INTERNAL"})
    void transientOrConfigErrors_doNotInvalidateRegistrations(MessagingErrorCode code) throws Exception {
        // 인증·project mismatch·quota·internal 오류는 target 무효 근거가 아니다 — 등록을 지우면 안 된다.
        BatchResponse batch = batchOf(failureResponse(code));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        PushSendResult result = sender.send(COMPLETION, targets("fid-1"));

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
    }

    @Test
    void responseWithoutException_countsAsFailureWithoutInvalidation() throws Exception {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getException()).thenReturn(null);
        BatchResponse batch = batchOf(response);
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        PushSendResult result = sender.send(COMPLETION, targets("fid-1"));

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
    }

    @Test
    void missingMessagingCode_fallsBackToTargetPlatformCode() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getErrorCode()).thenReturn(ErrorCode.PERMISSION_DENIED);
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getException()).thenReturn(exception);
        BatchResponse batch = batchOf(response);
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        sender.send(COMPLETION, targets("fid-1"));

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("fcm send failures: type=TIMELINE_COMPLETION failure=1 "
                        + "byCode={TARGET_PLATFORM_PERMISSION_DENIED=1}");
    }

    @Test
    void callLevelException_countsChunkAsFailed_withoutInvalidation_andContinuesOtherChunks() throws Exception {
        // 호출 수준(chunk 전체) 실패: payload/일시 장애일 수 있으므로 삭제 근거가 아니고 다음 chunk는 계속한다.
        List<PushTarget> fids = IntStream.range(0, 501)
                .mapToObj(i -> PushTarget.informational("fid-" + i)).toList();
        FirebaseMessagingException callFailure = mock(FirebaseMessagingException.class);
        when(callFailure.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INTERNAL);
        BatchResponse secondBatch = batchOf(successResponse());
        when(firebaseMessaging.sendEachForMulticast(any()))
                .thenThrow(callFailure)
                .thenReturn(secondBatch);

        PushSendResult result = sender.send(COMPLETION, fids);

        verify(firebaseMessaging, org.mockito.Mockito.times(2)).sendEachForMulticast(any());
        assertThat(result.failureCount()).isEqualTo(500);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("fcm send failures: type=TIMELINE_COMPLETION failure=500 "
                        + "byCode={CALL_FCM_INTERNAL=500}");
    }

    @Test
    void missingMessagingCode_fallsBackToCallPlatformCode() throws Exception {
        FirebaseMessagingException callFailure = mock(FirebaseMessagingException.class);
        when(callFailure.getErrorCode()).thenReturn(ErrorCode.UNAVAILABLE);
        when(firebaseMessaging.sendEachForMulticast(any())).thenThrow(callFailure);

        sender.send(COMPLETION, targets("fid-1"));

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("fcm send failures: type=TIMELINE_COMPLETION failure=1 "
                        + "byCode={CALL_PLATFORM_UNAVAILABLE=1}");
    }

    // --- 비밀 비로그 ---

    @Test
    void logsNeverContainFids() throws Exception {
        BatchResponse batch = batchOf(
                failureResponse(MessagingErrorCode.UNREGISTERED),
                failureResponse(MessagingErrorCode.QUOTA_EXCEEDED));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        sender.send(COMPLETION, targets("fid-secret-1", "fid-secret-2"));

        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain("fid-secret-1")
                    .doesNotContain("fid-secret-2");
        }
    }
}
