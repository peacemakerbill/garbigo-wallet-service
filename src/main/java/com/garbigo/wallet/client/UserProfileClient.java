package com.premisave.wallet.client;

import com.premisave.wallet.client.config.UserProfileFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Proxy to the auth-service's user-facing endpoints.
 * Auth is handled by forwarding the caller's Bearer JWT (via UserProfileFeignConfig).
 *
 * No API key — these are the same endpoints a browser would call,
 * just tunnelled through the wallet service so wallet users never need
 * to talk to the auth service directly.
 */
@FeignClient(
        name = "auth-service-profile",
        url = "${auth.service.url}",
        configuration = UserProfileFeignConfig.class
)
public interface UserProfileClient {

    // ── Profile ──────────────────────────────────────────────────────────────

    @GetMapping("/profile/user/{userId}")
    Map<String, Object> getPublicProfile(@PathVariable String userId);

    @GetMapping("/profile/search")
    List<Map<String, Object>> searchUsers(@RequestParam String query);

    @GetMapping("/profile/all")
    List<Map<String, Object>> getAllUsers();

    // ── Social: Like ─────────────────────────────────────────────────────────

    @PostMapping("/social/like")
    Map<String, Object> likeUser(@RequestBody Map<String, String> request);

    @DeleteMapping("/social/unlike/{targetId}")
    Map<String, Object> unlikeUser(@PathVariable String targetId);

    @GetMapping("/social/my-likes")
    List<Map<String, Object>> getMyLikes();

    // ── Social: Follow ───────────────────────────────────────────────────────

    @PostMapping("/social/follow")
    Map<String, Object> followUser(@RequestBody Map<String, String> request);

    @DeleteMapping("/social/unfollow/{targetId}")
    Map<String, Object> unfollowUser(@PathVariable String targetId);

    @GetMapping("/social/my-following")
    List<Map<String, Object>> getMyFollowing();

    // ── Social: Review ───────────────────────────────────────────────────────

    @PostMapping("/social/review")
    Map<String, Object> reviewUser(@RequestBody Map<String, Object> request);

    @PutMapping("/social/review")
    Map<String, Object> editReview(@RequestBody Map<String, Object> request);

    @DeleteMapping("/social/review/{reviewId}")
    Map<String, Object> deleteReview(@PathVariable String reviewId);

    @GetMapping("/social/reviews/{targetId}")
    List<Map<String, Object>> getUserReviews(@PathVariable String targetId);

    @GetMapping("/social/stats/{userId}")
    Map<String, Object> getUserStats(@PathVariable String userId);

    // ── Profile Views ────────────────────────────────────────────────────────

    @PostMapping("/profile/views/{targetId}")
    Map<String, Object> recordProfileView(@PathVariable String targetId);

    @GetMapping("/profile/views/who-viewed-me")
    List<Map<String, Object>> getWhoViewedMe();

    @GetMapping("/profile/views/who-i-viewed")
    List<Map<String, Object>> getWhoIViewed();

    @GetMapping("/profile/views/stats")
    Map<String, Object> getMyViewStats();

    @GetMapping("/profile/views/stats/{userId}")
    Map<String, Object> getUserViewStats(@PathVariable String userId);

    // ── Inbound: who liked / follows / reviewed me ────────────────────────────

    @GetMapping("/social/my-likers")
    List<Map<String, Object>> getMyLikers();

    @GetMapping("/social/my-followers")
    List<Map<String, Object>> getMyFollowers();

    @GetMapping("/social/my-reviews")
    List<Map<String, Object>> getMyReviews();

    @GetMapping("/social/my-written-reviews")
    List<Map<String, Object>> getMyWrittenReviews();

    // ── Relationship status checks ────────────────────────────────────────────

    @GetMapping("/social/like/status/{targetId}")
    Map<String, Object> getLikeStatus(@PathVariable String targetId);

    @GetMapping("/social/follow/status/{targetId}")
    Map<String, Object> getFollowStatus(@PathVariable String targetId);

    @GetMapping("/social/review/status/{targetId}")
    Map<String, Object> getReviewStatus(@PathVariable String targetId);

    @GetMapping("/social/follow/mutual/{targetId}")
    Map<String, Object> getMutualFollowStatus(@PathVariable String targetId);
}