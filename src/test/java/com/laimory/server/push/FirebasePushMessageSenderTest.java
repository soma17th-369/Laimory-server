package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.laimory.server.timeline.TaskStatus;
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
 * firebase sender 단위 검증 — server-built payload(문구·data 2키·FID target·TTL)를 고정하고,
 * 500 chunk 분할, response index 매핑, 오류 분류별 삭제 정책, FID 비로그를 검증한다. 인프라 0.
 *
 * <p>{@link MulticastMessage}는 public 접근자가 없어 pinned SDK(9.10.0)의 내부 필드를 reflection으로
 * 읽는다 — 이 payload 고정이 있어야 response의 {@code INVALID_ARGUMENT}를 target(FID) 무효로 간주하는
 * 전제가 성립한다. SDK 버전을 올리면 이 테스트가 필드 구조 변화를 먼저 잡는다.
 */
@ExtendWith(MockitoExtension.class)
class FirebasePushMessageSenderTest {

    private static final String TASK_ID = "t-1";

    @Mock
    private FirebaseMessaging firebaseMessaging;

    @Captor
    private ArgumentCaptor<MulticastMessage> messageCaptor;

    private FirebasePushMessageSender sender;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger senderLogger;

    @BeforeEach
    void setUp() {
        sender = new FirebasePushMessageSender(firebaseMessaging);
        senderLogger = (Logger) LoggerFactory.getLogger(FirebasePushMessageSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        senderLogger.detachAppender(logAppender);
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

    // --- payload 고정 ---

    @Test
    void success_buildsGenericNotificationWithTaskIdStatusDataOnly() throws Exception {
        givenAllSuccess(2);

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1", "fid-2"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        MulticastMessage message = messageCaptor.getValue();
        assertThat(notificationTitleOf(message)).isEqualTo("타임라인 생성 완료");
        assertThat(notificationBodyOf(message)).isEqualTo("타임라인이 준비됐어요.");
        // data는 라우팅용 두 key뿐 — 결과·오류 원문·기록 내용은 싣지 않는다(polling이 권위).
        assertThat(dataOf(message)).containsExactlyInAnyOrderEntriesOf(
                Map.of("taskId", TASK_ID, "status", "SUCCESS"));
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
    }

    @Test
    void failed_buildsGenericFailureNotification() throws Exception {
        givenAllSuccess(1);

        sender.send(TASK_ID, TaskStatus.FAILED, List.of("fid-1"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        MulticastMessage message = messageCaptor.getValue();
        assertThat(notificationTitleOf(message)).isEqualTo("타임라인 생성 실패");
        assertThat(notificationBodyOf(message)).isEqualTo("타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.");
        assertThat(dataOf(message)).containsExactlyInAnyOrderEntriesOf(
                Map.of("taskId", TASK_ID, "status", "FAILED"));
    }

    @Test
    void targetsFids_notDeprecatedTokens() throws Exception {
        givenAllSuccess(2);

        sender.send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1", "fid-2"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        MulticastMessage message = messageCaptor.getValue();
        // 9.10.0 계약: target은 FID다 — deprecated registration token 목록은 비어 있어야 한다.
        assertThat(fidsOf(message)).containsExactly("fid-1", "fid-2");
        assertThat(tokensOf(message)).isEmpty();
    }

    @Test
    void androidConfig_hasOneHourTtl_andDefaultPriority() throws Exception {
        givenAllSuccess(1);

        sender.send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1"));

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        AndroidConfig androidConfig = androidConfigOf(messageCaptor.getValue());
        // TTL 1시간(3600s) — 넘긴 알림은 폐기(늦은 알림은 polling·재진입 동기화가 대체).
        assertThat(ReflectionTestUtils.getField(androidConfig, "ttl")).isEqualTo("3600s");
        // 우선순위는 명시하지 않는다(기본 유지 — 긴급 메시지가 아니고 Doze 지연은 polling으로 수용).
        assertThat(ReflectionTestUtils.getField(androidConfig, "priority")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = "PROCESSING")
    void nonTerminalStatus_isRejected(TaskStatus status) {
        assertThatThrownBy(() -> sender.send(TASK_ID, status, List.of("fid-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- chunk 분할 ---

    @Test
    void emptyTargets_skipFcmEntirely() throws Exception {
        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, List.of());

        verify(firebaseMessaging, never()).sendEachForMulticast(any());
        assertThat(result).isEqualTo(PushSendResult.empty());
    }

    @Test
    void exactly500Targets_singleCall() throws Exception {
        List<String> fids = IntStream.range(0, 500).mapToObj(i -> "fid-" + i).toList();
        givenAllSuccess(500);

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, fids);

        verify(firebaseMessaging).sendEachForMulticast(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues()).hasSize(1);
        assertThat(fidsOf(messageCaptor.getValue())).hasSize(500);
        assertThat(result.successCount()).isEqualTo(500);
    }

    @Test
    void fiveHundredOneTargets_splitInto500Plus1_preservingOrder() throws Exception {
        List<String> fids = IntStream.range(0, 501).mapToObj(i -> "fid-" + i).toList();
        SendResponse[] first = IntStream.range(0, 500).mapToObj(i -> successResponse())
                .toArray(SendResponse[]::new);
        BatchResponse firstBatch = batchOf(first);
        BatchResponse secondBatch = batchOf(successResponse());
        when(firebaseMessaging.sendEachForMulticast(any()))
                .thenReturn(firstBatch)
                .thenReturn(secondBatch);

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, fids);

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

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS,
                List.of("fid-ok", "fid-gone", "fid-bad", "fid-flaky"));

        assertThat(result.targetCount()).isEqualTo(4);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(3);
        assertThat(result.invalidFirebaseInstallationIds()).containsExactly("fid-gone", "fid-bad");
    }

    @ParameterizedTest
    @EnumSource(value = MessagingErrorCode.class,
            names = {"THIRD_PARTY_AUTH_ERROR", "QUOTA_EXCEEDED", "SENDER_ID_MISMATCH", "UNAVAILABLE", "INTERNAL"})
    void transientOrConfigErrors_doNotInvalidateRegistrations(MessagingErrorCode code) throws Exception {
        // 인증·project mismatch·quota·internal 오류는 target 무효 근거가 아니다 — 등록을 지우면 안 된다.
        BatchResponse batch = batchOf(failureResponse(code));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1"));

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

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1"));

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
    }

    @Test
    void callLevelException_countsChunkAsFailed_withoutInvalidation_andContinuesOtherChunks() throws Exception {
        // 호출 수준(chunk 전체) 실패: payload/일시 장애일 수 있으므로 삭제 근거가 아니고 다음 chunk는 계속한다.
        List<String> fids = IntStream.range(0, 501).mapToObj(i -> "fid-" + i).toList();
        FirebaseMessagingException callFailure = mock(FirebaseMessagingException.class);
        when(callFailure.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INTERNAL);
        BatchResponse secondBatch = batchOf(successResponse());
        when(firebaseMessaging.sendEachForMulticast(any()))
                .thenThrow(callFailure)
                .thenReturn(secondBatch);

        PushSendResult result = sender.send(TASK_ID, TaskStatus.SUCCESS, fids);

        verify(firebaseMessaging, org.mockito.Mockito.times(2)).sendEachForMulticast(any());
        assertThat(result.failureCount()).isEqualTo(500);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.invalidFirebaseInstallationIds()).isEmpty();
    }

    // --- 비밀 비로그 ---

    @Test
    void logsNeverContainFids() throws Exception {
        BatchResponse batch = batchOf(
                failureResponse(MessagingErrorCode.UNREGISTERED),
                failureResponse(MessagingErrorCode.QUOTA_EXCEEDED));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batch);

        sender.send(TASK_ID, TaskStatus.FAILED, List.of("fid-secret-1", "fid-secret-2"));

        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain("fid-secret-1")
                    .doesNotContain("fid-secret-2");
        }
    }
}
