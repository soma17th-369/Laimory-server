package com.laimory.server.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 정책 계약 테스트. 모든 fixture는 형식상 유효하되 명백히 합성인 값이다(실존 개인정보 아님).
 */
class PrivacyRedactorTest {

    private final PrivacyRedactor redactor = new PrivacyRedactor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- 유형별 정상 치환 ---

    @ParameterizedTest
    @MethodSource("redactedFixtures")
    void redactsEachTypeWithoutLeakingOriginal(String raw, RedactionType type) {
        RedactionResult result = redactor.redactText("기록: " + raw + " 끝");

        assertThat(result.text()).contains(type.token()).doesNotContain(raw);
        assertThat(result.count(type)).isEqualTo(1);
    }

    private static Stream<Arguments> redactedFixtures() {
        return Stream.of(
                Arguments.of("010-1234-5678", RedactionType.PHONE),
                Arguments.of("01012345678", RedactionType.PHONE),
                Arguments.of("02-123-4567", RedactionType.PHONE),
                Arguments.of("031 123 4567", RedactionType.PHONE),
                Arguments.of("+82-10-1234-5678", RedactionType.PHONE),
                Arguments.of("+82 2 345 6789", RedactionType.PHONE),
                Arguments.of("yun.diary+test@example.com", RedactionType.EMAIL),
                Arguments.of("940101-1234567", RedactionType.RRN),
                Arguments.of("9401011234567", RedactionType.RRN),
                // 유효 check digit(외국인등록번호 알고리즘: legacy 가중합 + 2)을 가진 합성값
                Arguments.of("900101-5123452", RedactionType.FOREIGNER_ID),
                Arguments.of("여권번호 M12345678", RedactionType.PASSPORT),
                Arguments.of("passport no. M123A4567", RedactionType.PASSPORT),
                // 값-먼저 계좌 표현 — 문맥 label이 값 뒤에 와도 같은 거리 상한 안이면 치환한다.
                Arguments.of("M12345678 여권번호", RedactionType.PASSPORT),
                Arguments.of("110-123-456789 계좌로 송금", RedactionType.ACCOUNT),
                Arguments.of("12-34-567890-12", RedactionType.DRIVER_LICENSE),
                Arguments.of("4111-1111-1111-1111", RedactionType.CARD),
                Arguments.of("4111 1111 1111 1111", RedactionType.CARD),
                Arguments.of("계좌번호 110-123-456789", RedactionType.ACCOUNT),
                Arguments.of("송금 계좌: 3333-01-1234567", RedactionType.ACCOUNT),
                Arguments.of("Bearer abcDEF123456xyz", RedactionType.SECRET),
                Arguments.of("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJlLXNhbXBsZQ", RedactionType.SECRET),
                Arguments.of("비밀번호는 hunter4242!", RedactionType.SECRET),
                Arguments.of("인증번호 483920", RedactionType.SECRET),
                Arguments.of("refresh token: 9f8e7d6c5b4a3210", RedactionType.SECRET),
                Arguments.of("@diary_yun", RedactionType.SOCIAL_ID),
                Arguments.of("instagram.com/yun.daily", RedactionType.SOCIAL_ID));
    }

    // --- 오탐 경계 ---

    @ParameterizedTest
    @ValueSource(strings = {
            // 날짜·시각·금액·좌표·압축 날짜
            "2026-08-11", "07:30", "총 1,234,567원", "37.5665, 126.9780", "20260811",
            // 주문번호(13자리, Luhn 불통과)
            "주문번호 1234567890123",
            // Luhn 불통과 카드형 숫자
            "4111111111111112",
            // 주민번호형이지만 무효 날짜(13월·32일) — Luhn도 불통과
            "941301-1234567", "940132-1234567",
            // 외국인등록번호형이지만 check digit 불일치
            "900101-5123453",
            // 여권 형식이지만 여권/passport 문맥 없음
            "재고 코드 M12345678",
            // 계좌형 숫자지만 label 문맥 없음
            "그냥 110-123-456789",
            // token label 뒤 구분자 없음
            "task token was refreshed",
            // '@' 단독은 handle이 아니다
            "meet @ 3pm",
            // handle 형식 단어지만 SNS label 문맥이 앞뒤 어디에도 없다
            "john_doe 랑 점심",
            // 평문 문맥으로 SNS 사용자명을 추론하지 않는다 — @handle/profile URL만 고신뢰도로 치환한다.
            "facebook은 sns 플랫폼이다", "SNS는 facebook", "yun_daily는 내 인스타 아이디",
            // rawId·taskId — canonical lowercase UUID는 절대 치환하지 않는다
            "0198a5f0-3c4e-7d2a-8b1c-9e8f7a6b5c4d",
            "12345678-1234-1234-1234-123456789012"})
    void keepsNonPiiTextUnchanged(String text) {
        RedactionResult result = redactor.redactText(text);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.total()).isZero();
    }

    @Test
    void redactsPost2020RrnEvenWhenLegacyChecksumFails() {
        // 040101-312345의 legacy check digit은 0 — 마지막 자리 4는 checksum 불일치지만
        // 2020-10 개편 이후 번호이므로 반드시 치환해야 한다.
        RedactionResult result = redactor.redactText("주민등록번호 040101-3123454");

        assertThat(result.text()).isEqualTo("주민등록번호 " + RedactionType.RRN.token());
        assertThat(result.count(RedactionType.RRN)).isEqualTo(1);
    }

    @Test
    void cardFollowedByExpiryStillRedactsCardBody() {
        // 카드+유효기간 표기에서 후보가 뒤의 12까지 탐욕 소비해 18자리 Luhn에 실패해도,
        // 그룹 경계 기준 prefix 재검증으로 카드 본체(16자리)는 치환돼야 한다.
        RedactionResult spaced = redactor.redactText("결제 4111 1111 1111 1111 12/28");

        assertThat(spaced.text()).isEqualTo("결제 " + RedactionType.CARD.token() + " 12/28");
        assertThat(spaced.count(RedactionType.CARD)).isEqualTo(1);
    }

    @Test
    void cardWithDashesFollowedByExpiryStillRedactsCardBody() {
        // 카드(하이픈)와 유효기간(공백 연결)이 섞여도 단일 구분자 prefix까지는 카드로 판정한다.
        RedactionResult dashed = redactor.redactText("결제 4111-1111-1111-1111 12/28");

        assertThat(dashed.text()).isEqualTo("결제 " + RedactionType.CARD.token() + " 12/28");
        assertThat(dashed.count(RedactionType.CARD)).isEqualTo(1);
    }

    @Test
    void unseparatedCardExpiryRunStaysUnchanged() {
        // 구분자 없는 숫자 연쇄는 그룹 경계가 없어 중간 절단 재검증을 하지 않는다 —
        // 주문번호·송장번호류 긴 숫자 오탐을 막는 보수 규칙의 한계를 회귀 fixture로 고정한다.
        RedactionResult result = redactor.redactText("결제 411111111111111112/28");

        assertThat(result.text()).isEqualTo("결제 411111111111111112/28");
        assertThat(result.total()).isZero();
    }

    @Test
    void dateFollowedByPhoneIsPhoneNotCard() {
        // 날짜+전화 연쇄(구분자 혼합, 합계 19자리)를 카드로 오인하면 안 된다.
        RedactionResult result = redactor.redactText("2026-08-11 010-1234-5678");

        assertThat(result.text()).isEqualTo("2026-08-11 " + RedactionType.PHONE.token());
        assertThat(result.count(RedactionType.PHONE)).isEqualTo(1);
        assertThat(result.count(RedactionType.CARD)).isZero();
    }

    @Test
    void emailIsNotAlsoSocialId() {
        RedactionResult result = redactor.redactText("연락은 yun@example.com");

        assertThat(result.count(RedactionType.EMAIL)).isEqualTo(1);
        assertThat(result.count(RedactionType.SOCIAL_ID)).isZero();
    }

    @Test
    void deviceUriTokenHasNoDetector() {
        // DEVICE_URI는 AI projection의 필드 값 전체 치환용 token 상수만 제공한다.
        assertThat(RedactionType.DEVICE_URI.token()).isEqualTo("[REDACTED_DEVICE_URI]");
        assertThat(redactor.redactText("content://media/external/images/42").total()).isZero();
    }

    // --- 멱등성·placeholder 보호·occurrence ---

    @Test
    void reapplyingIsIdempotentWithZeroOccurrences() {
        String source = "연락처 010-1234-5678, 메일 yun@example.com, 비밀번호는 hunter4242";

        RedactionResult first = redactor.redactText(source);
        RedactionResult second = redactor.redactText(first.text());

        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.total()).isZero();
    }

    @Test
    void existingPlaceholderLiteralPassesThroughUncounted() {
        String text = "이미 [REDACTED_PHONE] 처리됨, 사진은 [REDACTED_DEVICE_URI]";

        RedactionResult result = redactor.redactText(text);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.total()).isZero();
    }

    @Test
    void countsActualOccurrencesPerType() {
        RedactionResult result = redactor.redactText(
                "전화 010-1234-5678 또는 010-9876-5432, 메일 yun@example.com");

        assertThat(result.count(RedactionType.PHONE)).isEqualTo(2);
        assertThat(result.count(RedactionType.EMAIL)).isEqualTo(1);
        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    void nullTextIsNullSafe() {
        assertThat(redactor.redactText(null).text()).isNull();
        assertThat(redactor.redactText(null).occurrences()).isEmpty();
        assertThat(redactor.redactText(null, 255).text()).isNull();
        assertThat(redactor.redactText(null, 255).occurrences()).isEmpty();
    }

    // --- bounded(255/500) ---

    @Test
    void boundedRedactionKeeps255LimitWhenTokensInflateShortEmails() {
        // 7자 email 30개가 16자 token으로 팽창(210자 → 510자)해도 상한을 지킨다.
        String source = "a@b.co ".repeat(30);

        RedactionResult result = redactor.redactText(source, 255);

        assertThat(result.text()).hasSizeLessThanOrEqualTo(255);
        assertThat(noPartialToken(result.text())).isTrue();
        // occurrence는 치환 시점의 탐지 건수를 유지한다(절단과 무관).
        assertThat(result.count(RedactionType.EMAIL)).isEqualTo(30);
    }

    @Test
    void boundedRedactionKeeps500LimitWithMixedContent() {
        String source = ("메모 " + "가".repeat(40) + " yun@example.com ").repeat(12);

        RedactionResult result = redactor.redactText(source, 500);

        assertThat(result.text()).hasSizeLessThanOrEqualTo(500);
        assertThat(noPartialToken(result.text())).isTrue();
    }

    @Test
    void cutInsideTokenMovesBoundaryBeforeTokenStart() {
        // 치환 결과 267자 — 255 경계가 token 내부(251~267)에 걸리므로 token 시작 앞에서 끊는다.
        String source = "가".repeat(250) + " a@b.co";

        RedactionResult result = redactor.redactText(source, 255);

        assertThat(result.text()).isEqualTo("가".repeat(250) + " ");
        assertThat(result.text()).doesNotContain("[REDACTED");
        assertThat(result.count(RedactionType.EMAIL)).isEqualTo(1);
    }

    @Test
    void boundedRedactionKeepsShortResultUnchanged() {
        assertThat(redactor.redactText("짧은 메모", 255).text()).isEqualTo("짧은 메모");
    }

    @Test
    void boundedRedactionRejectsNonPositiveMaxLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> redactor.redactText("text", 0));
    }

    private static boolean noPartialToken(String text) {
        String stripped = text;
        for (RedactionType type : RedactionType.values()) {
            stripped = stripped.replace(type.token(), "");
        }
        return !stripped.contains("[REDACTED");
    }

    // --- JsonNode redaction ---

    @Test
    void redactsTypedPayloadLikeTextualLeavesOnly() throws Exception {
        JsonNode source = objectMapper.readTree("""
                {"appName":"Messenger","title":"인증번호 483920","text":"연락처 010-1234-5678",
                 "startAt":"2026-08-11T07:30:00+09:00","allDay":false,"distanceMeters":1250}
                """);

        JsonRedactionResult result = redactor.redactTree(source);

        assertThat(result.node().get("appName").textValue()).isEqualTo("Messenger");
        assertThat(result.node().get("title").textValue()).isEqualTo("인증번호 " + RedactionType.SECRET.token());
        assertThat(result.node().get("text").textValue()).isEqualTo("연락처 " + RedactionType.PHONE.token());
        assertThat(result.node().get("startAt").textValue()).isEqualTo("2026-08-11T07:30:00+09:00");
        assertThat(result.node().get("allDay").booleanValue()).isFalse();
        assertThat(result.node().get("distanceMeters").intValue()).isEqualTo(1250);
        assertThat(result.count(RedactionType.SECRET)).isEqualTo(1);
        assertThat(result.count(RedactionType.PHONE)).isEqualTo(1);
    }

    @Test
    void redactsNestedUserMemoryLikeSchemaPreservingStructure() throws Exception {
        JsonNode source = objectMapper.readTree("""
                {"version":3,
                 "profile":{"nickname":"윤","contacts":["010-1234-5678","yun@example.com"],
                            "active":true,"note":null,"backup@example.com":true},
                 "routine":{"wakeUp":"07:30","steps":8500}}
                """);
        String before = source.toString();

        JsonRedactionResult result = redactor.redactTree(source);
        JsonNode node = result.node();

        assertThat(node.get("version").intValue()).isEqualTo(3);
        assertThat(node.at("/profile/contacts/0").textValue()).isEqualTo(RedactionType.PHONE.token());
        assertThat(node.at("/profile/contacts/1").textValue()).isEqualTo(RedactionType.EMAIL.token());
        assertThat(node.at("/profile/active").booleanValue()).isTrue();
        assertThat(node.at("/profile/note").isNull()).isTrue();
        // field name은 email 형태여도 치환하지 않는다 — 값(textual leaf)만 대상이다.
        assertThat(node.at("/profile/backup@example.com").booleanValue()).isTrue();
        assertThat(node.at("/routine/wakeUp").textValue()).isEqualTo("07:30");
        assertThat(node.at("/routine/steps").intValue()).isEqualTo(8500);
        assertThat(result.count(RedactionType.PHONE)).isEqualTo(1);
        assertThat(result.count(RedactionType.EMAIL)).isEqualTo(1);
        assertThat(node.toString()).doesNotContain("010-1234-5678").doesNotContain("yun@example.com");
        // 입력 node는 변형되지 않는다(Hibernate 관리 엔티티 필드일 수 있음).
        assertThat(source.toString()).isEqualTo(before);
    }

    @Test
    void excludedFieldNamesPassStringValuesThroughVerbatim() throws Exception {
        String deviceUri = "content://media/external/images/010-1234-5678";
        JsonNode source = objectMapper.readTree("""
                {"items":[{"clientPhotoUri":"%s","description":"연락처 010-1234-5678"}]}
                """.formatted(deviceUri));

        JsonRedactionResult result = redactor.redactTree(source, Set.of("clientPhotoUri"));

        assertThat(result.node().at("/items/0/clientPhotoUri").textValue()).isEqualTo(deviceUri);
        assertThat(result.node().at("/items/0/description").textValue())
                .isEqualTo("연락처 " + RedactionType.PHONE.token());
        assertThat(result.count(RedactionType.PHONE)).isEqualTo(1);
    }

    @Test
    void treeRedactionIsIdempotent() throws Exception {
        JsonNode source = objectMapper.readTree(
                "{\"memo\":\"주민 940101-1234567 카드 4111-1111-1111-1111\"}");

        JsonRedactionResult first = redactor.redactTree(source);
        JsonRedactionResult second = redactor.redactTree(first.node());

        assertThat(second.node()).isEqualTo(first.node());
        assertThat(second.total()).isZero();
        assertThat(first.count(RedactionType.RRN)).isEqualTo(1);
        assertThat(first.count(RedactionType.CARD)).isEqualTo(1);
    }

    @Test
    void treeRedactionAggregatesOccurrencesAcrossLeaves() {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("a", "010-1234-5678");
        source.putArray("b").add("010-9876-5432").add("yun@example.com");

        JsonRedactionResult result = redactor.redactTree(source);

        assertThat(result.count(RedactionType.PHONE)).isEqualTo(2);
        assertThat(result.count(RedactionType.EMAIL)).isEqualTo(1);
        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    void nullNodeIsNullSafe() {
        JsonRedactionResult result = redactor.redactTree(null);

        assertThat(result.node()).isNull();
        assertThat(result.occurrences()).isEmpty();
    }
}
