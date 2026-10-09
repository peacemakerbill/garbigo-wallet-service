package com.premisave.wallet.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
public class SystemController {

    private final MongoTemplate mongoTemplate;

    @Value("${spring.application.name:premisave-wallet-service}")
    private String serviceName;

    @Value("${server.port:8084}")
    private String serverPort;

    // ─── Health Checks ────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("version", "0.0.1-SNAPSHOT");
        status.put("timestamp", LocalDateTime.now());
        status.put("environment", System.getProperty("spring.profiles.active", "default"));
        return ResponseEntity.ok(status);
    }

    @GetMapping("/health/details")
    public ResponseEntity<Map<String, Object>> healthDetails() {
        Map<String, Object> db = checkMongo();

        Map<String, Object> details = new HashMap<>();
        details.put("status", db.get("status").equals("UP") ? "UP" : "DEGRADED");
        details.put("service", serviceName);
        details.put("port", serverPort);
        details.put("javaVersion", System.getProperty("java.version"));
        details.put("database", db);
        details.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(details);
    }

    // ─── Token Test ───────────────────────────────────────────────────────────

    /**
     * Decodes the JWT already validated by the security filter chain.
     * Useful for confirming the token is wired correctly end-to-end.
     */
    @GetMapping("/test-token")
    public ResponseEntity<Map<String, Object>> testToken(Authentication auth) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "JWT token is valid and working");
        response.put("username", auth.getName());
        response.put("authorities", auth.getAuthorities().toString());
        response.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(response);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> checkMongo() {
        Map<String, Object> result = new HashMap<>();
        try {
            mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
            result.put("status", "UP");
            result.put("type", "MongoDB");
        } catch (Exception e) {
            log.warn("MongoDB health check failed: {}", e.getMessage());
            result.put("status", "DOWN");
            result.put("type", "MongoDB");
            result.put("error", e.getMessage());
        }
        return result;
    }
}