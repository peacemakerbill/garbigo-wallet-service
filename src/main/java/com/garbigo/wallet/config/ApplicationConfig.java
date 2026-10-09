package com.premisave.wallet.config;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {

    /**
     * ModelMapper for DTO ↔ entity conversions where manual mapping is verbose.
     * PasswordEncoder is intentionally omitted — this service never handles passwords;
     * authentication is delegated entirely to the Auth Service via JWT.
     */
    @Bean
    ModelMapper modelMapper() {
        return new ModelMapper();
    }
}