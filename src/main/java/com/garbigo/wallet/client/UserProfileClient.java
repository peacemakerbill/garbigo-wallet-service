package com.garbigo.wallet.client;

import com.garbigo.wallet.client.config.UserProfileFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Proxy to the Garbigo auth-service's user-facing endpoints.
 * Auth is handled by forwarding the caller's Bearer JWT (via UserProfileFeignConfig).
 *
 * No API key: these are the same endpoints a browser would call, tunnelled through
 * the wallet service. Paths and shapes follow AUTH_SERVICE_API.md.
 *
 * Note: many auth /social and /profile-views endpoints answer with plain text
 * (for example "Liked successfully"), so those methods return String.
 */
@FeignClient(
        name = "auth-service-profile",
        url = "${auth.service.url}",
        configuration = UserProfileFeignConfig.class
)
public interface UserProfileClient {

    // ── Token verification / own profile ─────────────────────────────────────

    /**
     * The signed-in user's UserDto. Used by AuthServiceTokenVerifier, which passes the
     * Authorization header explicitly. Auth answers 401 for expired or logged-out tokens.
     */
    @GetMapping("/users/profile")
    Map<String, Object> getMyProfile(@RequestHeader("Authorization") String authorization);

    // ── Users ────────────────────────────────────────────────────────────────

    /** Public summary of a user (UserSummaryDto). */
    @GetMapping("/social/profile/{userId}")
    Map<String, Object> getPublicProfile(@PathVariable("userId") String userId);

    /** ADMIN only on the auth side (403 otherwise). Filters are all optional. */
    @GetMapping("/users")
    List<Map<String, Object>> searchUsers(@RequestParam(name = "search", required = false) String search,
                                          @RequestParam(name = "role", required = false) String role,
                                          @RequestParam(name = "verified", required = false) Boolean verified,
                                          @RequestParam(name = "active", required = false) Boolean active,
                                          @RequestParam(name = "archived", required = false) Boolean archived);

    /** Any signed-in user may search collectors. */
    @GetMapping("/users/collectors")
    List<Map<String, Object>> searchCollectors(@RequestParam(name = "search", required = false) String search,
                                               @RequestParam(name = "verified", required = false) Boolean verified,
                                               @RequestParam(name = "active", required = false) Boolean active,
                                               @RequestParam(name = "archived", required = false) Boolean archived);

    // ── Social: Like ─────────────────────────────────────────────────────────

    /** Body: { "targetId": "...", "targetType": "USER" }. Returns plain text. */
    @PostMapping("/social/like")
    String like(@RequestBody Map<String, Object> request);

    /** Same body as like. DELETE with a body, as the auth service expects. */
    @DeleteMapping("/social/like")
    String unlike(@RequestBody Map<String, Object> request);

    /** Response: { "liked": true } */
    @GetMapping("/social/is-liked")
    Map<String, Object> isLiked(@RequestParam("targetId") String targetId,
                                @RequestParam(name = "targetType", defaultValue = "USER") String targetType);

    /** Who liked the target (UserSummaryDto list). */
    @GetMapping("/social/likes/{targetId}")
    List<Map<String, Object>> getLikers(@PathVariable("targetId") String targetId,
                                        @RequestParam(name = "targetType", defaultValue = "USER") String targetType);

    // ── Social: Follow ───────────────────────────────────────────────────────

    @PostMapping("/social/follow/{userId}")
    String follow(@PathVariable("userId") String userId);

    @DeleteMapping("/social/follow/{userId}")
    String unfollow(@PathVariable("userId") String userId);

    /** Response: { "following": true } */
    @GetMapping("/social/is-following/{userId}")
    Map<String, Object> isFollowing(@PathVariable("userId") String userId);

    @GetMapping("/social/followers/{userId}")
    List<Map<String, Object>> getFollowers(@PathVariable("userId") String userId);

    @GetMapping("/social/following/{userId}")
    List<Map<String, Object>> getFollowing(@PathVariable("userId") String userId);

    // ── Social: Review ───────────────────────────────────────────────────────

    /** Body: { "targetId", "targetType", "rating", "comment" }. Returns plain text. */
    @PostMapping("/social/review")
    String createReview(@RequestBody Map<String, Object> request);

    /** Body: { "rating", "comment" }. Returns plain text. */
    @PutMapping("/social/review/{reviewId}")
    String editReview(@PathVariable("reviewId") String reviewId, @RequestBody Map<String, Object> request);

    @DeleteMapping("/social/review/{reviewId}")
    String deleteReview(@PathVariable("reviewId") String reviewId);

    /** ReviewResponseDto list. */
    @GetMapping("/social/reviews/{targetId}")
    List<Map<String, Object>> getReviews(@PathVariable("targetId") String targetId,
                                         @RequestParam(name = "targetType", defaultValue = "USER") String targetType);

    /** Who reviewed the target (UserSummaryDto list). */
    @GetMapping("/social/reviewers/{targetId}")
    List<Map<String, Object>> getReviewers(@PathVariable("targetId") String targetId,
                                           @RequestParam(name = "targetType", defaultValue = "USER") String targetType);

    /** SocialStatsDto: followersCount, followingCount, likesCount, averageRating. */
    @GetMapping("/social/stats/{userId}")
    Map<String, Object> getUserStats(@PathVariable("userId") String userId);

    // ── Profile views ────────────────────────────────────────────────────────

    /** Returns plain text. */
    @PostMapping("/profile-views/{viewedUserId}")
    String recordProfileView(@PathVariable("viewedUserId") String viewedUserId);

    @GetMapping("/profile-views/who-viewed-me")
    List<Map<String, Object>> getWhoViewedMe();

    @GetMapping("/profile-views/who-i-viewed")
    List<Map<String, Object>> getWhoIViewed();

    /** ProfileViewStatsDto of the caller. */
    @GetMapping("/profile-views/my-stats")
    Map<String, Object> getMyViewStats();

    @GetMapping("/profile-views/stats/{userId}")
    Map<String, Object> getUserViewStats(@PathVariable("userId") String userId);
}