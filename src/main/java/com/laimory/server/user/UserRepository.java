package com.laimory.server.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** users 레포. 조회는 (provider, provider_user_id) 유일키 기준. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderUserId(Provider provider, String providerUserId);

    /**
     * 전 사용자 id projection(PHOTO migration 도구 #284 전용 — users 전 행 순회).
     * 엔티티 전체(email/nickname)를 migration 메모리로 끌고 오지 않도록 id만 select한다.
     */
    @Query("select u.userId from User u order by u.userId asc")
    List<Long> findAllUserIds();
}
