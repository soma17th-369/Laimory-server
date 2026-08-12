package com.laimory.server.user;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@link CurrentSubject} 파라미터를 현재 인증 사용자의 콘텐츠 subject로 변환한다. */
@Component
public class CurrentSubjectArgumentResolver implements HandlerMethodArgumentResolver {

    private final ObjectProvider<SubjectMappingService> subjectMappingServiceProvider;

    public CurrentSubjectArgumentResolver(ObjectProvider<SubjectMappingService> subjectMappingServiceProvider) {
        this.subjectMappingServiceProvider = subjectMappingServiceProvider;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentSubject.class)
                && parameter.getParameterType() == UUID.class;
    }

    @Override
    public UUID resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new IllegalStateException("authenticated Long principal required for subject resolution");
        }
        SubjectMappingService subjectMappingService = subjectMappingServiceProvider.getIfAvailable();
        if (subjectMappingService == null) {
            throw new IllegalStateException("subject mapping service is unavailable");
        }
        return subjectMappingService.getRequired(userId);
    }
}
