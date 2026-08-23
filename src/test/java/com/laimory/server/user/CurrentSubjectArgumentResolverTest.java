package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.user.service.SubjectMappingService;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentSubjectArgumentResolverTest {

    private static final long USER_ID = 77L;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsOnlyCurrentSubjectSubjectIdParameter() throws Exception {
        CurrentSubjectArgumentResolver resolver = resolver(null);

        assertThat(resolver.supportsParameter(parameter("subject", UUID.class))).isTrue();
        assertThat(resolver.supportsParameter(parameter("unannotated", UUID.class))).isFalse();
        assertThat(resolver.supportsParameter(parameter("wrongType", Long.class))).isFalse();
    }

    @Test
    void resolveArgument_mapsAuthenticatedLongPrincipal() throws Exception {
        UUID expected = UUID.randomUUID();
        SubjectMappingService mappingService = mock(SubjectMappingService.class);
        when(mappingService.getRequired(USER_ID)).thenReturn(expected);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(USER_ID, null, java.util.List.of()));

        UUID actual = resolver(mappingService).resolveArgument(
                parameter("subject", UUID.class), null, null, null);

        assertThat(actual).isEqualTo(expected);
        verify(mappingService).getRequired(USER_ID);
    }

    @Test
    void resolveArgument_rejectsMissingOrWrongPrincipal() throws Exception {
        CurrentSubjectArgumentResolver resolver = resolver(mock(SubjectMappingService.class));

        assertThatThrownBy(() -> resolver.resolveArgument(
                parameter("subject", UUID.class), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Long principal");

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("77", null, java.util.List.of()));
        assertThatThrownBy(() -> resolver.resolveArgument(
                parameter("subject", UUID.class), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Long principal");
    }

    @Test
    void resolveArgument_failsClosedWhenMappingServiceIsUnavailable() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(USER_ID, null, java.util.List.of()));

        assertThatThrownBy(() -> resolver(null).resolveArgument(
                parameter("subject", UUID.class), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("subject mapping service is unavailable");
    }

    private static CurrentSubjectArgumentResolver resolver(SubjectMappingService mappingService) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SubjectMappingService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mappingService);
        return new CurrentSubjectArgumentResolver(provider);
    }

    private static MethodParameter parameter(String name, Class<?> type) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(name, type);
        return new MethodParameter(method, 0);
    }

    private static final class Fixture {
        void subject(@CurrentSubject UUID subjectId) {
        }

        void unannotated(UUID subjectId) {
        }

        void wrongType(@CurrentSubject Long userId) {
        }
    }
}
