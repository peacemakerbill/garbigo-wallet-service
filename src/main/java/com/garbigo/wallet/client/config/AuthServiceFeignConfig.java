package com.garbigo.wallet.client.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;

public class AuthServiceFeignConfig implements RequestInterceptor {

    @Value("${internal.api-key}")
    private String apiKey;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Api-Key", apiKey);
    }
}