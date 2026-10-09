package com.premisave.wallet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts the role claim embedded by the auth service.
     * Auth service stores the role under "roles" (e.g. "CLIENT", "ADMIN").
     * We normalise it to "ROLE_CLIENT" / "ROLE_ADMIN" for Spring Security.
     */
    public String extractRole(String token) {
        Claims claims = extractAllClaims(token);

        // Auth service writes the key as "roles" — check that first, fall back to "role"
        String role = claims.get("roles", String.class);
        if (role == null) {
            role = claims.get("role", String.class);
        }
        if (role == null) return null;

        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }

    public String extractUserId(String token) {
        return extractClaim(token, claims -> claims.get("userId", String.class));
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Mirrors the auth service key derivation exactly:
     * truncate/pad to 32 bytes so tokens are cross-verifiable between services.
     */
    private SecretKey getSignInKey() {
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret);

            if (keyBytes.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
                keyBytes = padded;
            } else if (keyBytes.length > 32) {
                byte[] truncated = new byte[32];
                System.arraycopy(keyBytes, 0, truncated, 0, 32);
                keyBytes = truncated;
            }

            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            return Keys.hmacShaKeyFor(secret.getBytes());
        }
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
}