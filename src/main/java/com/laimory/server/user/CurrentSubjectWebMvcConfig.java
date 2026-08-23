package com.laimory.server.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 콘텐츠 API의 {@link CurrentSubject} MVC argument resolver 등록. */
@Configuration
@RequiredArgsConstructor
public class CurrentSubjectWebMvcConfig implements WebMvcConfigurer {

    private final CurrentSubjectArgumentResolver currentSubjectArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentSubjectArgumentResolver);
    }
}
