package com.laimory.server.terms;

import com.laimory.server.common.ApiUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 인증 API({@code /a/api})의 {@link TermsEnforcementInterceptor} 등록. */
@Configuration
@RequiredArgsConstructor
public class TermsEnforcementWebMvcConfig implements WebMvcConfigurer {

    private final TermsEnforcementInterceptor termsEnforcementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(termsEnforcementInterceptor)
                .addPathPatterns(ApiUrls.AUTHENTICATED_API_PREFIX,
                        ApiUrls.AUTHENTICATED_API_PREFIX + "/**");
    }
}
