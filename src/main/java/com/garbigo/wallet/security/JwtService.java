package com.garbigo.wallet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.function.Function;

/**
 * Verifies JWTs issued by the Garbigo auth service.
 *
 * The auth service signs with Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwt.secret))
 * and puts these claims in the token: sub (email), userId, jti, iat, exp.
 * It does NOT put a role in the token, so roles are resolved separately by
 * AuthServiceTokenVerifier. JWT_SECRET must be identical on both services.
 */
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    private volatile SecretKey signInKey;

    /** Email of the user (the auth service uses it as the JWT subject). */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractUserId(String token) {
        return extractClaim(token, claims -> claims.get("userId", String.class));
    }

    /** Unique token id, used by the auth service for logout (denylist). */
    public String extractJti(String token) {
        return extractClaim(token, Claims::getId);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    /** Throws a JwtException if the signature is wrong or the token is expired. */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Mirrors the auth service's JwtUtil exactly: the decoded base64 bytes are used
     * as-is (no truncating, padding or fallback), so a token signed by the auth
     * service verifies here only when both services share the same JWT_SECRET.
     */
    private SecretKey getSignInKey() {
        SecretKey key = signInKey;
        if (key == null) {
            key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
            signInKey = key;
        }
        return key;
    }

    /** True when the signature is valid and the token has not expired. */
    public boolean isTokenValid(String token) {
        try {
            return extractExpiration(token).after(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}