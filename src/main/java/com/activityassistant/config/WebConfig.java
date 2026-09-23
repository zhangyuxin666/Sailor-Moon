package com.activityassistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // The retained frontend uses the original FastAPI mount prefix.
        registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/");
    }
}
