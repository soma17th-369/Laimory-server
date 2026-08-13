package com.laimory.server.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** users 레포. 조회는 (provider, provider_user_id) 유일키 기준. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderUserId(Provider provider, String providerUserId);
}
