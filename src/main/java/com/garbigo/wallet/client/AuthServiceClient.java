package com.premisave.wallet.client;

import com.premisave.wallet.client.config.AuthServiceFeignConfig;
import com.premisave.wallet.dto.client.UserDetailsDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;
import java.util.Optional;

@FeignClient(
        name = "auth-service",
        url = "${auth.service.url}",
        configuration = AuthServiceFeignConfig.class
)
public interface AuthServiceClient {

    /**
     * Fixed: previously pointed at /auth/users/{email}/details, which was
     * never actually built on the auth-service side — that path also
     * sits inside AuthController's /auth/** space, which has to allow
     * unauthenticated requests (signup, signin, etc.), so anything built
     * there risks being reachable with no auth at all. The real endpoint
     * now lives under /internal/users, alongside validateEmail below,
     * protected by the same ApiKeyFilter (X-API-Key header) every other
     * internal, service-to-service call in this codebase already uses.
     */
    @GetMapping("/internal/users/{email}/details")
    Optional<UserDetailsDto> getUserDetails(@PathVariable String email);

    /**
     * Validates whether an email belongs to an active, verified, non-archived user.
     * Used during M-Pesa C2B validation before crediting a wallet.
     *
     * Returns: { "valid": true/false, "reason": "NOT_FOUND|INACTIVE|UNVERIFIED|ARCHIVED|" }
     */
    @GetMapping("/internal/users/validate-email/{email}")
    Map<String, Object> validateEmail(@PathVariable String email);
}