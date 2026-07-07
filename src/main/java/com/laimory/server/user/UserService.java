package com.laimory.server.user;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** users leaf 서비스. 자신과 1:1인 UserRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * (provider, providerUserId)로 사용자를 찾고 없으면 생성한다.
     *
     * <p>의도적으로 <b>무트랜잭션</b>이다: 동시 최초 로그인 레이스에서 한쪽 insert가 UNIQUE 위반으로 지는데,
     * 합류 트랜잭션 안에서 catch하면 트랜잭션이 rollback-only로 오염돼 같은 트랜잭션의 재조회가 무의미해진다
     * ({@code DailyRecordService.findOrCreateDraft} 주석 참고 — 같은 함정으로 옛 upsert를 폐기한 이력).
     * 여기선 {@code saveAndFlush}가 repository 프록시의 자체 트랜잭션에서 실행·롤백되고 catch는 그 밖이라
     * 재조회가 유효하다. <b>호출자는 이 메서드를 둘러싼 트랜잭션 안에서 부르지 않는다.</b>
     */
    public User findOrCreate(Provider provider, String providerUserId, String email, String nickname) {
        return userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .orElseGet(() -> {
                    try {
                        return userRepository.saveAndFlush(User.of(provider, providerUserId, email, nickname));
                    } catch (DataIntegrityViolationException e) {
                        // 동시 최초 로그인: 상대가 먼저 insert — 그 행으로 수렴한다.
                        return userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                                .orElseThrow(() -> e);
                    }
                });
    }
}
