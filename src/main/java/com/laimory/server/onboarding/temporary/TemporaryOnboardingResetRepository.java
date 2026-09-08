package com.laimory.server.onboarding.temporary;

import com.laimory.server.push.entity.SubjectPreference;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 임시 writer를 일반 subject 설정 repository에서 분리해 패키지 삭제만으로 제거한다. */
public interface TemporaryOnboardingResetRepository extends Repository<SubjectPreference, UUID> {

    @Modifying
    @Transactional
    @Query("update SubjectPreference p set p.onboardingCompleted = false where p.subjectId = :subjectId")
    int resetOnboarding(@Param("subjectId") UUID subjectId);
}
