package com.laimory.server.common.privacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * v1 유형별 text 탐지기. 계획 §1의 고정 순서(placeholder 보호 → SECRET → EMAIL →
 * 법적 식별번호 → CARD → PHONE → 문맥 기반 ACCOUNT/SOCIAL_ID)로 원문 위 구간을 선점한다.
 *
 * <p>앞선 유형이 선점한 구간과 겹치는 뒤 유형의 match는 버린다. 매치 문자열·전후 문맥을
 * 예외 메시지·로그·metric에 절대 담지 않는다.
 */
final class PiiDetectors {

    // 기존 placeholder literal — 탐지 전에 보호해 같은 입력 재적용을 멱등으로 만든다.
    private static final Pattern EXISTING_TOKENS = Pattern.compile(
            Arrays.stream(RedactionType.values())
                    .map(type -> Pattern.quote(type.token()))
                    .collect(Collectors.joining("|")));

    // --- SECRET: Bearer/JWT 형식 또는 password·OTP·token label 문맥 값 ---
    private static final Pattern BEARER_VALUE =
            Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9\\-._~+/]{8,}=*)");
    // JWT header는 JSON base64url이라 항상 eyJ로 시작한다 — 이 prefix가 오탐을 막는다.
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");
    // label 뒤 명시적 구분자([:=] 또는 조사)를 요구해 "암호화"·"password is required" 같은 일반 문장 오탐을 줄인다.
    private static final Pattern PASSWORD_VALUE = Pattern.compile(
            "(?i)(?:password|passwd|pwd|passcode|비밀번호|암호)\\s*(?:[:=]|은|는|이|가)\\s*(\\S{4,})");
    private static final Pattern OTP_VALUE = Pattern.compile(
            "(?i)(?:otp|인증\\s?번호|인증\\s?코드|verification\\s+code)\\s*(?:[:=]|은|는|이|가)?\\s*(\\d{4,8})(?!\\d)");
    private static final Pattern TOKEN_VALUE = Pattern.compile(
            "(?i)(?:access[-_ ]?token|refresh[-_ ]?token|api[-_ ]?key|secret[-_ ]?key|client[-_ ]?secret|token|토큰)"
                    + "\\s*(?:[:=]|은|는)\\s*([A-Za-z0-9\\-._~+/]{8,}=*)");

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![A-Za-z0-9-])");

    // 주민등록번호·외국인등록번호 공통 후보 — 13자리 숫자 경계(하이픈 선택). [\d-] 경계가 UUID 등
    // 더 긴 숫자-하이픈 연쇄 내부 부분 매치를 막는다.
    private static final Pattern RESIDENT_LIKE =
            Pattern.compile("(?<![\\d-])(\\d{6})-?(\\d{7})(?![\\d-])");

    // 구여권(영문 1+숫자 8)·신여권(영문 1+숫자 3+영문 1+숫자 4). 형식만으로 모호해 문맥 필수.
    private static final Pattern PASSPORT = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Z](?:\\d{8}|\\d{3}[A-Z]\\d{4})(?![A-Za-z0-9])");
    private static final Pattern PASSPORT_CONTEXT = Pattern.compile("(?i)여권|passport");
    private static final int PASSPORT_CONTEXT_WINDOW = 20;

    // 2019년 이후 전국 통합 운전면허번호 2-2-6-2. 구분자까지 일치해야 치환한다.
    private static final Pattern DRIVER_LICENSE =
            Pattern.compile("(?<![\\d-])\\d{2}-\\d{2}-\\d{6}-\\d{2}(?![\\d-])");

    // 카드 후보: 구분자 제거 시 13~19자리. Luhn 통과 시에만 치환한다.
    private static final Pattern CARD_CANDIDATE =
            Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])");

    // 국제 형식: '+' + 8~15자리(E.164 상한).
    private static final Pattern INTERNATIONAL_PHONE =
            Pattern.compile("(?<![\\d+-])\\+\\d(?:[ -]?\\d){7,14}(?![\\d-])");
    private static final Pattern MOBILE_PHONE =
            Pattern.compile("(?<![\\d-])01[016789][ -]?\\d{3,4}[ -]?\\d{4}(?![\\d-])");
    private static final Pattern AREA_PHONE =
            Pattern.compile("(?<![\\d-])0(?:2|[3-6]\\d|70)[ -]?\\d{3,4}[ -]?\\d{4}(?![\\d-])");

    // 계좌번호는 숫자만으로 지우지 않는다 — label 문맥 뒤 10~16자리만 치환한다.
    private static final Pattern ACCOUNT_VALUE = Pattern.compile(
            "(?i)(?:계좌\\s*번호|계좌|account\\s*(?:no\\.?|number|#)?|acct)[^0-9\\r\\n]{0,12}(\\d(?:[ -]?\\d){9,15})(?![\\d-])");

    // 알려진 profile URL의 handle 경로 조각. 도메인 앞 경계가 netflix.com 같은 suffix 오탐을 막는다.
    private static final Pattern PROFILE_URL_HANDLE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9.-])(?:www\\.|m\\.)?"
                    + "(?:instagram\\.com|twitter\\.com|x\\.com|facebook\\.com|tiktok\\.com|threads\\.net|t\\.me)"
                    + "/@?([A-Za-z0-9_.\\-]{2,30})");
    private static final Pattern AT_HANDLE = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])@[A-Za-z0-9_][A-Za-z0-9_.]{1,29}(?<!\\.)");
    private static final Pattern SNS_LABEL_HANDLE = Pattern.compile(
            "(?i)(?:인스타(?:그램)?|카카오톡|카카오|카톡|트위터|페이스북|틱톡|텔레그램|sns)"
                    + "\\s*(?:(?:아이디|id)\\s*(?:[:=]|은|는)?|[:=]|은|는)\\s*([A-Za-z0-9_.]{3,30})(?![A-Za-z0-9_.])");

    private PiiDetectors() {
    }

    /** 시작 위치 오름차순으로 정렬된, 서로 겹치지 않는 확정 구간을 반환한다. */
    static List<RedactionSpan> detect(String text) {
        ClaimedSpans spans = new ClaimedSpans(text.length());
        claimWhole(EXISTING_TOKENS, text, spans, null);
        detectSecrets(text, spans);
        claimWhole(EMAIL, text, spans, RedactionType.EMAIL);
        detectLegalIdNumbers(text, spans);
        detectPassports(text, spans);
        claimWhole(DRIVER_LICENSE, text, spans, RedactionType.DRIVER_LICENSE);
        detectCards(text, spans);
        claimWhole(INTERNATIONAL_PHONE, text, spans, RedactionType.PHONE);
        claimWhole(MOBILE_PHONE, text, spans, RedactionType.PHONE);
        claimWhole(AREA_PHONE, text, spans, RedactionType.PHONE);
        claimGroup(ACCOUNT_VALUE, text, spans, RedactionType.ACCOUNT);
        claimGroup(PROFILE_URL_HANDLE, text, spans, RedactionType.SOCIAL_ID);
        claimWhole(AT_HANDLE, text, spans, RedactionType.SOCIAL_ID);
        claimGroup(SNS_LABEL_HANDLE, text, spans, RedactionType.SOCIAL_ID);
        return spans.inOrder();
    }

    private static void detectSecrets(String text, ClaimedSpans spans) {
        claimGroup(BEARER_VALUE, text, spans, RedactionType.SECRET);
        claimWhole(JWT, text, spans, RedactionType.SECRET);
        claimGroup(PASSWORD_VALUE, text, spans, RedactionType.SECRET);
        claimGroup(OTP_VALUE, text, spans, RedactionType.SECRET);
        claimGroup(TOKEN_VALUE, text, spans, RedactionType.SECRET);
    }

    private static void detectLegalIdNumbers(String text, ClaimedSpans spans) {
        Matcher matcher = RESIDENT_LIKE.matcher(text);
        while (matcher.find()) {
            String birth = matcher.group(1);
            String serial = matcher.group(2);
            if (!validBirthDate(birth)) {
                continue;
            }
            int genderDigit = serial.charAt(0) - '0';
            if (genderDigit >= 1 && genderDigit <= 4) {
                // 2020-10 부여체계 개편 이후 번호는 legacy check digit이 성립하지 않으므로
                // checksum을 거부 조건으로 쓰지 않는다(신뢰도 가산 용도로만 의미가 있다).
                spans.tryClaim(matcher.start(), matcher.end(), RedactionType.RRN);
            } else if (genderDigit >= 5 && genderDigit <= 8 && validForeignerCheckDigit(birth + serial)) {
                spans.tryClaim(matcher.start(), matcher.end(), RedactionType.FOREIGNER_ID);
            }
        }
    }

    private static void detectPassports(String text, ClaimedSpans spans) {
        Matcher matcher = PASSPORT.matcher(text);
        while (matcher.find()) {
            int windowStart = Math.max(0, matcher.start() - PASSPORT_CONTEXT_WINDOW);
            if (PASSPORT_CONTEXT.matcher(text.substring(windowStart, matcher.start())).find()) {
                spans.tryClaim(matcher.start(), matcher.end(), RedactionType.PASSPORT);
            }
        }
    }

    private static void detectCards(String text, ClaimedSpans spans) {
        Matcher matcher = CARD_CANDIDATE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            // 혼합 구분자는 "날짜 전화번호"처럼 서로 다른 숫자 연쇄가 붙은 경우라 카드로 보지 않는다.
            if (candidate.indexOf(' ') >= 0 && candidate.indexOf('-') >= 0) {
                continue;
            }
            if (passesLuhn(candidate.replaceAll("[ -]", ""))) {
                spans.tryClaim(matcher.start(), matcher.end(), RedactionType.CARD);
            }
        }
    }

    private static void claimWhole(Pattern pattern, String text, ClaimedSpans spans, RedactionType type) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.tryClaim(matcher.start(), matcher.end(), type);
        }
    }

    private static void claimGroup(Pattern pattern, String text, ClaimedSpans spans, RedactionType type) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.tryClaim(matcher.start(1), matcher.end(1), type);
        }
    }

    private static boolean validBirthDate(String yymmdd) {
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day = Integer.parseInt(yymmdd.substring(4, 6));
        if (month < 1 || month > 12) {
            return false;
        }
        // 세기 미상으로 윤년을 확정할 수 없어 2월은 29일까지 허용한다.
        int[] maxDays = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return day >= 1 && day <= maxDays[month - 1];
    }

    private static boolean validForeignerCheckDigit(String digits13) {
        int[] weights = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (digits13.charAt(i) - '0') * weights[i];
        }
        int expected = ((11 - sum % 11) % 10 + 2) % 10;
        return expected == digits13.charAt(12) - '0';
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** 이미 선점된 index와 겹치는 claim을 거부하는 구간 대장. */
    private static final class ClaimedSpans {

        private final boolean[] claimed;
        private final List<RedactionSpan> spans = new ArrayList<>();

        private ClaimedSpans(int length) {
            this.claimed = new boolean[length];
        }

        private void tryClaim(int start, int end, RedactionType type) {
            for (int i = start; i < end; i++) {
                if (claimed[i]) {
                    return;
                }
            }
            Arrays.fill(claimed, start, end, true);
            spans.add(new RedactionSpan(start, end, type));
        }

        private List<RedactionSpan> inOrder() {
            spans.sort(Comparator.comparingInt(RedactionSpan::start));
            return spans;
        }
    }
}
