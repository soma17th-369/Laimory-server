package com.laimory.server.user;

import com.laimory.server.user.service.SubjectMappingCache;
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

/**
 * {@link CurrentSubject} 파라미터를 현재 인증 사용자의 콘텐츠 subject로 변환한다.
 * 해석은 {@link SubjectMappingCache}를 탄다(#429) — 적중 시 transaction·DB 조회 없이 끝난다.
 */
@Component
public class CurrentSubjectArgumentResolver implements HandlerMethodArgumentResolver {

    private final ObjectProvider<SubjectMappingCache> subjectMappingCacheProvider;

    public CurrentSubjectArgumentResolver(ObjectProvider<SubjectMappingCache> subjectMappingCacheProvider) {
        this.subjectMappingCacheProvider = subjectMappingCacheProvider;
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
        SubjectMappingCache subjectMappingCache = subjectMappingCacheProvider.getIfAvailable();
        if (subjectMappingCache == null) {
            throw new IllegalStateException("subject mapping cache is unavailable");
        }
        return subjectMappingCache.getRequired(userId);
    }
}
