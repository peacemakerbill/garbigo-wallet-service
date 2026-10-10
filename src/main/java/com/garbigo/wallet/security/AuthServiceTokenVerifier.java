package com.garbigo.wallet.security;

import com.garbigo.wallet.client.UserProfileClient;
import com.garbigo.wallet.enums.Role;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confirms a (cryptographically valid) JWT with the auth service.
 *
 * The auth service tokens carry no role, and logout only revokes the token's jti
 * on the auth side. So after the signature check, wallet-service asks auth
 * GET /users/profile with the caller's own token. That single call:
 *  - rejects logged-out (revoked) tokens, because auth's JwtFilter returns 401,
 *  - returns the user's current role, active and verified flags.
 *
 * Results are cached per jti for auth.service.token-cache-seconds (default 60) so
 * that a request does not trigger a call to auth every time. This means a logout
 * or role change reaches the wallet within that window. Set it to 0 to disable.
 *
 * Fails closed: if auth cannot be reached, the request stays unauthenticated.
 */
@Slf4j
@Component
public class AuthServiceTokenVerifier {

    private record CacheEntry(VerifiedUser user, long expiresAtMillis) {}

    private final UserProfileClient userProfileClient;
    private final long cacheMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AuthServiceTokenVerifier(UserProfileClient userProfileClient,
                                    @Value("${auth.service.token-cache-seconds:60}") long cacheSeconds) {
        this.userProfileClient = userProfileClient;
        this.cacheMillis = Math.max(0, cacheSeconds) * 1000L;
    }

    /**
     * @param bearerToken the raw JWT (without the "Bearer " prefix)
     * @param jti         the token id, used as the cache key
     * @param tokenExpiry expiry of the token in epoch millis, so cache entries never outlive it
     */
    public Optional<VerifiedUser> verify(String bearerToken, String jti, long tokenExpiry) {
        long now = System.currentTimeMillis();

        if (cacheMillis > 0 && jti != null) {
            CacheEntry cached = cache.get(jti);
            if (cached != null && cached.expiresAtMillis() > now) {
                return Optional.of(cached.user());
            }
        }

        try {
            Map<String, Object> profile = userProfileClient.getMyProfile("Bearer " + bearerToken);
            VerifiedUser user = toVerifiedUser(profile);
            if (user == null) {
                return Optional.empty();
            }
            if (cacheMillis > 0 && jti != null) {
                if (cache.size() > 10_000) {
                    evictExpired(now);
                }
                cache.put(jti, new CacheEntry(user, Math.min(now + cacheMillis, tokenExpiry)));
            }
            return Optional.of(user);
        } catch (FeignException.Unauthorized | FeignException.Forbidden e) {
            // Revoked token, deactivated account, or similar: auth rejected it
            if (jti != null) cache.remove(jti);
            log.warn("Auth service rejected token (status {}): {}", e.status(), e.contentUTF8());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not verify token with auth service: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private VerifiedUser toVerifiedUser(Map<String, Object> profile) {
        if (profile == null || profile.get("email") == null) return null;

        Role role = null;
        Object rawRole = profile.get("role");
        if (rawRole != null) {
            try {
                role = Role.valueOf(rawRole.toString().toUpperCase());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown role '{}' returned by auth service; user gets no role", rawRole);
            }
        }

        return new VerifiedUser(
                profile.get("id") != null ? profile.get("id").toString() : null,
                profile.get("email").toString(),
                role,
                asBoolean(profile.get("active")),
                asBoolean(profile.get("verified")));
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(value.toString());
    }

    private void evictExpired(long now) {
        cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
    }
}