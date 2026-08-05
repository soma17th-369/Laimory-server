package com.laimory.server.user;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** users leaf 서비스. 자신과 1:1인 UserRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * (provider, providerUserId)로 사용자를 찾고 없으면 생성한다. Kakao 기존 사용자는 이번 로그인의
     * 닉네임으로 갱신한다 — 단 누락 claim(null)은 동의 철회인지 provider 응답 누락인지 구분할 수 없어
     * 기존 값을 지우지 않는다. Google 기존 사용자는 갱신 없이 반환한다(기존 동작 유지).
     *
     * <p>의도적으로 <b>무트랜잭션</b>이다: 동시 최초 로그인 레이스에서 한쪽 insert가 UNIQUE 위반으로 지는데,
     * 합류 트랜잭션 안에서 catch하면 트랜잭션이 rollback-only로 오염돼 같은 트랜잭션의 재조회가 무의미해진다
     * ({@code DailyRecordService.findOrCreateDraft} 주석 참고 — 같은 함정으로 옛 upsert를 폐기한 이력).
     * 여기선 {@code saveAndFlush}가 repository 프록시의 자체 트랜잭션에서 실행·롤백되고 catch는 그 밖이라
     * 재조회가 유효하다. <b>호출자는 이 메서드를 둘러싼 트랜잭션 안에서 부르지 않는다.</b>
     */
    public User findOrCreate(Provider provider, String providerUserId, String email, String nickname) {
        return userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(existing -> refreshKakaoNickname(existing, provider, nickname))
                .orElseGet(() -> {
                    try {
                        return userRepository.saveAndFlush(User.of(provider, providerUserId, email, nickname));
                    } catch (DataIntegrityViolationException e) {
                        // 동시 최초 로그인: 상대가 먼저 insert — 그 행으로 수렴하고 이번 닉네임을 적용한다.
                        return userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                                .map(winner -> refreshKakaoNickname(winner, provider, nickname))
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * 사용자의 User Memory 문서를 읽는다. 사용자가 없을 때와 메모리가 아직 없을 때를 구분하지 않고
     * 모두 빈 {@link Optional}이다 — 현재 호출자에게 두 경우의 처리가 같기 때문이며, 구분이 필요해지면
     * 그때 시그니처를 나눈다.
     */
    public Optional<JsonNode> findUserMemory(long userId) {
        return userRepository.findById(userId).map(User::getUserMemory);
    }

    /**
     * User Memory 문서를 통째로 교체한다({@code null}은 제거). 서버는 문서 내부를 해석·정규화하지 않고
     * 받은 JSON을 그대로 보존한다. 사용자가 없으면 {@link IllegalArgumentException}이다.
     *
     * <p>{@code @DynamicUpdate} 엔티티라 실제로 바뀐 컬럼만 UPDATE에 실린다 — 로그인의 nickname 갱신과
     * 서로를 되돌리지 않는다({@link User} 주석 참고).
     */
    public void replaceUserMemory(long userId, JsonNode userMemory) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: userId=" + userId));
        user.replaceUserMemory(userMemory);
        userRepository.saveAndFlush(user);
    }

    private User refreshKakaoNickname(User user, Provider provider, String nickname) {
        if (provider != Provider.KAKAO || nickname == null) {
            return user;
        }
        user.updateNickname(nickname);
        return userRepository.saveAndFlush(user);
    }
}
