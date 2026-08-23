package com.laimory.server.user;

import com.laimory.server.user.service.SubjectMappingService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * raw userId → subject lookup key(HMAC-SHA-256 32바이트) 파생기(#282, 계획 §2.3).
 *
 * <p>message는 고정 ASCII context {@value #CONTEXT}와 양수 {@code long userId}의 canonical 8-byte
 * big-endian 표현을 이 순서로 결합한다 — 두 조각 모두 고정 길이라 길이 모호성이 없다. 결과 32바이트를
 * 그대로 {@code user_subject_links.user_lookup_key BINARY(32)} PK로 쓴다(hex/base64 문자열화 금지).
 *
 * <p>이 클래스는 {@link SubjectMappingService}만 의존한다(arch test로 강제) — lookup key가 service 밖으로
 * 새지 않게 하는 경계다.
 */
@Component
public class SubjectLookupKeyDeriver {

    /** HMAC message의 도메인 분리 context. 변경은 곧 전체 mapping rotation이다 — 값이 계약이다. */
    static final String CONTEXT = "content-subject-lookup:v1";

    private static final byte[] CONTEXT_BYTES = CONTEXT.getBytes(StandardCharsets.US_ASCII);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SubjectHmacKeySnapshot snapshot;

    public SubjectLookupKeyDeriver(SubjectHmacKeySnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** current key로 lookup key를 파생한다 — 일반 조회·신규 mapping insert 경로. */
    public byte[] deriveCurrent(long userId) {
        return derive(snapshot.currentKey(), userId);
    }

    /**
     * rotation 기간의 previous key로 lookup key를 파생한다. snapshot에 previous key가 없으면
     * empty — 호출자는 previous 조회 자체를 건너뛴다.
     */
    public Optional<byte[]> derivePrevious(long userId) {
        return snapshot.previousKey().map(key -> derive(key, userId));
    }

    /** 신규 mapping insert·rotation 교체에 기록할 current key version. */
    public short currentVersion() {
        return snapshot.currentVersion();
    }

    private byte[] derive(byte[] key, long userId) {
        if (userId <= 0) {
            // IDENTITY 채번 userId는 항상 양수 — 위반은 내부 불변식 위반이다(값은 메시지에 담지 않는다).
            throw new IllegalStateException("subject lookup key derivation requires a positive userId");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            mac.update(CONTEXT_BYTES);
            mac.update(ByteBuffer.allocate(Long.BYTES).putLong(userId).array()); // big-endian
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256은 모든 JCA 구현 필수 알고리즘, key는 snapshot이 32바이트를 보증 — 도달 불가 방어.
            throw new IllegalStateException("subject lookup key derivation failed", e);
        }
    }
}
