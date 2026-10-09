package com.premisave.wallet.controller;

import com.premisave.wallet.client.UserProfileClient;
import com.premisave.wallet.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Exposes auth-service social/profile features inside the wallet service.
 *
 * Use-cases:
 *  - View a recipient's profile before sending funds
 *  - Follow, like, or review a user you've transacted with
 *  - See who viewed your profile
 *
 * All calls are pass-through: this controller forwards the caller's JWT
 * to the auth service via UserProfileClient (UserProfileFeignConfig).
 * No business logic lives here — auth service owns that.
 */
@Slf4j
@RestController
@RequestMapping("/users")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileClient userProfileClient;

    // ── Profile ──────────────────────────────────────────────────────────────

    /**
     * View a user's public profile — e.g. before sending them funds.
     * Also records a profile view on the auth side automatically.
     * GET /users/{userId}/profile
     */
    @GetMapping("/{userId}/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfile(@PathVariable String userId) {
        userProfileClient.recordProfileView(userId); // fire-and-forget; errors are swallowed below
        Map<String, Object> profile = userProfileClient.getPublicProfile(userId);
        return ResponseEntity.ok(ApiResponse.success("Profile retrieved", profile));
    }

    /**
     * Search users by name, username, email — useful when looking up a fund recipient.
     * GET /users/search?query=john
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchUsers(@RequestParam String query) {
        List<Map<String, Object>> results = userProfileClient.searchUsers(query);
        return ResponseEntity.ok(ApiResponse.success("Search results", results));
    }

    /**
     * Browse all active users.
     * GET /users/all
     */
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllUsers() {
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", userProfileClient.getAllUsers()));
    }

    // ── Social stats ─────────────────────────────────────────────────────────

    /**
     * Get a user's social stats (followers, following, likes, avg rating).
     * GET /users/{userId}/stats
     */
    @GetMapping("/{userId}/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserStats(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("User stats retrieved", userProfileClient.getUserStats(userId)));
    }

    /**
     * Get reviews for a user — useful for trust/reputation before a transaction.
     * GET /users/{userId}/reviews
     */
    @GetMapping("/{userId}/reviews")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReviews(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Reviews retrieved", userProfileClient.getUserReviews(userId)));
    }

    // ── Like ─────────────────────────────────────────────────────────────────

    /** POST /users/{userId}/like */
    @PostMapping("/{userId}/like")
    public ResponseEntity<ApiResponse<Map<String, Object>>> likeUser(@PathVariable String userId) {
        Map<String, Object> result = userProfileClient.likeUser(Map.of("targetId", userId));
        return ResponseEntity.ok(ApiResponse.success("Action completed", result));
    }

    /** DELETE /users/{userId}/like */
    @DeleteMapping("/{userId}/like")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlikeUser(@PathVariable String userId) {
        Map<String, Object> result = userProfileClient.unlikeUser(userId);
        return ResponseEntity.ok(ApiResponse.success("Action completed", result));
    }

    /** GET /users/me/likes */
    @GetMapping("/me/likes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyLikes() {
        return ResponseEntity.ok(ApiResponse.success("Liked users retrieved", userProfileClient.getMyLikes()));
    }

    // ── Follow ───────────────────────────────────────────────────────────────

    /** POST /users/{userId}/follow */
    @PostMapping("/{userId}/follow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> followUser(@PathVariable String userId) {
        Map<String, Object> result = userProfileClient.followUser(Map.of("targetId", userId));
        return ResponseEntity.ok(ApiResponse.success("Action completed", result));
    }

    /** DELETE /users/{userId}/follow */
    @DeleteMapping("/{userId}/follow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unfollowUser(@PathVariable String userId) {
        Map<String, Object> result = userProfileClient.unfollowUser(userId);
        return ResponseEntity.ok(ApiResponse.success("Action completed", result));
    }

    /** GET /users/me/following */
    @GetMapping("/me/following")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyFollowing() {
        return ResponseEntity.ok(ApiResponse.success("Following retrieved", userProfileClient.getMyFollowing()));
    }

    // ── Review ───────────────────────────────────────────────────────────────

    /**
     * POST /users/{userId}/review
     * Body: { "rating": 5, "comment": "Great seller!" }
     */
    @PostMapping("/{userId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewUser(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body) {
        body.put("targetId", userId);
        Map<String, Object> result = userProfileClient.reviewUser(body);
        return ResponseEntity.ok(ApiResponse.success("Review submitted", result));
    }

    /**
     * PUT /users/review
     * Body: { "reviewId": "...", "rating": 4, "comment": "Updated review" }
     */
    @PutMapping("/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editReview(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = userProfileClient.editReview(body);
        return ResponseEntity.ok(ApiResponse.success("Review updated", result));
    }

    /** DELETE /users/review/{reviewId} */
    @DeleteMapping("/review/{reviewId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteReview(@PathVariable String reviewId) {
        Map<String, Object> result = userProfileClient.deleteReview(reviewId);
        return ResponseEntity.ok(ApiResponse.success("Review deleted", result));
    }

    // ── Profile Views ────────────────────────────────────────────────────────

    /** GET /users/me/views — who viewed my profile */
    @GetMapping("/me/views")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getWhoViewedMe() {
        return ResponseEntity.ok(ApiResponse.success("Profile views retrieved", userProfileClient.getWhoViewedMe()));
    }

    /** GET /users/me/viewed — profiles I have viewed */
    @GetMapping("/me/viewed")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getWhoIViewed() {
        return ResponseEntity.ok(ApiResponse.success("Viewed profiles retrieved", userProfileClient.getWhoIViewed()));
    }

    /** GET /users/me/view-stats */
    @GetMapping("/me/view-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyViewStats() {
        return ResponseEntity.ok(ApiResponse.success("View stats retrieved", userProfileClient.getMyViewStats()));
    }

    /** GET /users/{userId}/view-stats — public stats only (totalViews) */
    @GetMapping("/{userId}/view-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserViewStats(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("View stats retrieved", userProfileClient.getUserViewStats(userId)));
    }

    // ── Inbound social ────────────────────────────────────────────────────────

    /** GET /users/me/likers — who liked me */
    @GetMapping("/me/likers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyLikers() {
        return ResponseEntity.ok(ApiResponse.success("Users who liked me", userProfileClient.getMyLikers()));
    }

    /** GET /users/me/followers — who follows me */
    @GetMapping("/me/followers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyFollowers() {
        return ResponseEntity.ok(ApiResponse.success("Followers retrieved", userProfileClient.getMyFollowers()));
    }

    /** GET /users/me/reviews — reviews written about me */
    @GetMapping("/me/reviews")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyReviews() {
        return ResponseEntity.ok(ApiResponse.success("My reviews retrieved", userProfileClient.getMyReviews()));
    }

    /** GET /users/me/written-reviews — reviews I wrote */
    @GetMapping("/me/written-reviews")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyWrittenReviews() {
        return ResponseEntity.ok(ApiResponse.success("Written reviews retrieved", userProfileClient.getMyWrittenReviews()));
    }

    // ── Relationship status checks ────────────────────────────────────────────

    /** GET /users/{targetId}/like-status */
    @GetMapping("/{targetId}/like-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLikeStatus(@PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Like status", userProfileClient.getLikeStatus(targetId)));
    }

    /** GET /users/{targetId}/follow-status */
    @GetMapping("/{targetId}/follow-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFollowStatus(@PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Follow status", userProfileClient.getFollowStatus(targetId)));
    }

    /** GET /users/{targetId}/review-status */
    @GetMapping("/{targetId}/review-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReviewStatus(@PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Review status", userProfileClient.getReviewStatus(targetId)));
    }

    /** GET /users/{targetId}/mutual-follow */
    @GetMapping("/{targetId}/mutual-follow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMutualFollowStatus(@PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Mutual follow status", userProfileClient.getMutualFollowStatus(targetId)));
    }
}