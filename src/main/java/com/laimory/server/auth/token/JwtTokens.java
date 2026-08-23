package com.laimory.server.auth.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 자체 access token(JWT, HS256) 발급/검증. 클레임은 iss/sub(userId 문자열)/iat/exp만 —
 * PII(email·nickname)는 넣지 않는다(JWT는 서명만 될 뿐 암호화가 아니라 누구나 디코드 가능).
 *
 * <p>access는 서버에 저장하지 않고 서명·만료만 검증한다(stateless). 취소가 필요한 수명 관리는
 * refresh token(DB) 몫이다. 만료 검증은 클럭 스큐 대비 60초 leeway를 둔다.
 */
@Component
public class JwtTokens {

    private static final String ISSUER = "laimory";
    private static final long LEEWAY_SECONDS = 60;

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtTokens(@Value("${app.auth.jwt.secret}") String secret,
                     @Value("${app.auth.jwt.access-ttl}") Duration accessTtl,
                     Clock clock) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            // HS256 최소 키 길이 — 짧은 시크릿으로 조용히 약해지지 않게 기동 시점에 fail-fast.
            throw new IllegalStateException("app.auth.jwt.secret(JWT_SECRET)은 32바이트 이상이어야 합니다(HS256).");
        }
        try {
            this.signer = new MACSigner(secretBytes);
            this.verifier = new MACVerifier(secretBytes);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 서명키 초기화에 실패했습니다.", e);
        }
        this.accessTtl = accessTtl;
        this.clock = clock;
    }

    /** 양수 userId를 sub에 담은 access token을 발급한다. 0·음수는 내부 invariant 위반이라 발급을 거절한다. */
    public String issueAccessToken(long userId) {
        if (userId <= 0) {
            // MySQL AUTO_INCREMENT user ID 계약(양수)과 어긋나는 발급은 버그 — 과거 fallback 0 계열 접근을 원천 차단.
            throw new IllegalStateException("access token userId는 양수여야 합니다: " + userId);
        }
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("access token 서명에 실패했습니다.", e);
        }
        return jwt.serialize();
    }

    /**
     * 서명·alg·iss·exp(leeway 60s)를 검증하고 sub(양수 userId)를 반환한다.
     * 무효/만료/변조는 사유 구분 없이 empty — 사유는 클라이언트 행동을 바꾸지 않는다(전부 재인증 경로).
     * 0·음수 subject는 유효한 서명이 있어도 empty다(과거 user 0 데이터 접근 차단).
     */
    public Optional<Long> parseUserId(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null
                    || expiration.toInstant().plusSeconds(LEEWAY_SECONDS).isBefore(clock.instant())) {
                return Optional.empty();
            }
            long userId = Long.parseLong(claims.getSubject());
            if (userId <= 0) {
                return Optional.empty();
            }
            return Optional.of(userId);
        } catch (ParseException | JOSEException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
