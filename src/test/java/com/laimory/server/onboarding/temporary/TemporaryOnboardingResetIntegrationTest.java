package com.laimory.server.onboarding.temporary;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.push.repository.SubjectPreferenceRepository;
import com.laimory.server.user.Provider;
import com.laimory.server.user.service.NewUserProvisioner;
import com.laimory.server.user.service.SubjectMappingService;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 실제 인증 필터·userId 매핑·MySQL UPDATE·initializer 조회까지 검증하고 fixture는 rollback한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TemporaryOnboardingResetIntegrationTest {

    private static final String RESET = "/api/v1/onboarding/reset";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NewUserProvisioner newUserProvisioner;

    @Autowired
    private SubjectMappingService subjectMappingService;

    @Autowired
    private SubjectPreferenceRepository subjectPreferenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void anonymousReset_changesOnlySelectedUsersOnboarding_andCanBeRepeated() throws Exception {
        long userId = createUser();
        long otherUserId = createUser();
        UUID subjectId = subjectMappingService.getRequired(userId);
        UUID otherSubjectId = subjectMappingService.getRequired(otherUserId);
        subjectPreferenceRepository.markOnboardingCompleted(subjectId);
        subjectPreferenceRepository.markOnboardingCompleted(otherSubjectId);
        subjectPreferenceRepository.updatePushEnabled(subjectId, false);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(RESET).queryParam("userId", Long.toString(userId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.code").value(0))
                    .andExpect(jsonPath("$.body").doesNotExist());
        }

        assertThat(onboardingCompleted(subjectId)).isFalse();
        assertThat(onboardingCompleted(otherSubjectId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select push_enabled from subject_preferences where subject_id = ?",
                Boolean.class, subjectId.toString())).isFalse();
        mockMvc.perform(get("/a/api/v1/initializer").with(authenticatedUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.onboardingCompleted").value(false));
    }

    @Test
    void missingUserId_returns400() throws Exception {
        mockMvc.perform(post(RESET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "-1"})
    void invalidUserId_returns400(String userId) throws Exception {
        mockMvc.perform(post(RESET).queryParam("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    private long createUser() {
        return newUserProvisioner.provision(Provider.GOOGLE,
                "temporary-onboarding-reset-" + UUID.randomUUID(), "test@example.com", "test")
                .getUserId();
    }

    private boolean onboardingCompleted(UUID subjectId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select onboarding_completed from subject_preferences where subject_id = ?",
                Boolean.class, subjectId.toString()));
    }
}
