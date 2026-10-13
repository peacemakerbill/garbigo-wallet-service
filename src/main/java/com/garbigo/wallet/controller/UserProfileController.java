package com.garbigo.wallet.controller;

import com.garbigo.wallet.client.UserProfileClient;
import com.garbigo.wallet.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes Garbigo auth-service social/profile features inside the wallet service.
 *
 * Use-cases:
 *  - View a recipient's profile before sending funds
 *  - Follow, like, or review a user (client, collector) you have transacted with
 *  - See who viewed your profile
 *
 * All calls are pass-through: the caller's JWT is forwarded to the auth service
 * via UserProfileClient (UserProfileFeignConfig). Auth owns the business rules
 * and its validation messages are relayed by GlobalExceptionHandler.
 *
 * The auth service has no "reviews I wrote" endpoint, so that list is not offered
 * here (GET /users/me/written-reviews answers 404).
 */
@Slf4j
@RestController
@RequestMapping("/users")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserProfileController {

    private static final String DEFAULT_TARGET_TYPE = "USER";

    private final UserProfileClient userProfileClient;

    // ── Profile ──────────────────────────────────────────────────────────────

    /**
     * View a user's public summary, e.g. before sending them funds.
     * Also records a profile view on the auth side (failures there are ignored).
     * GET /users/{userId}/profile
     */
    @GetMapping("/{userId}/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfile(@PathVariable String userId) {
        try {
            userProfileClient.recordProfileView(userId);
        } catch (Exception e) {
            log.debug("Profile view not recorded for {}: {}", userId, e.getMessage());
        }
        Map<String, Object> profile = userProfileClient.getPublicProfile(userId);
        return ResponseEntity.ok(ApiResponse.success("Profile retrieved", profile));
    }

    /**
     * Search all users. ADMIN only: the auth service answers 403 for everyone else.
     * GET /users/search?query=john&role=COLLECTOR&verified=true&active=true&archived=false
     */
    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean archived) {
        return ResponseEntity.ok(ApiResponse.success("Search results",
                userProfileClient.searchUsers(query, role, verified, active, archived)));
    }

    /**
     * Browse all users. ADMIN only.
     * GET /users/all
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllUsers() {
        return ResponseEntity.ok(ApiResponse.success("Users retrieved",
                userProfileClient.searchUsers(null, null, null, null, null)));
    }

    /**
     * Search collectors, open to any signed-in user.
     * GET /users/collectors?query=john&verified=true&active=true
     */
    @GetMapping("/collectors")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchCollectors(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean archived) {
        return ResponseEntity.ok(ApiResponse.success("Collectors retrieved",
                userProfileClient.searchCollectors(query, verified, active, archived)));
    }

    // ── Social stats ─────────────────────────────────────────────────────────

    /** GET /users/{userId}/stats (followers, following, likes, average rating) */
    @GetMapping("/{userId}/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserStats(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("User stats retrieved", userProfileClient.getUserStats(userId)));
    }

    /** GET /users/{userId}/reviews */
    @GetMapping("/{userId}/reviews")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReviews(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Reviews retrieved",
                userProfileClient.getReviews(userId, DEFAULT_TARGET_TYPE)));
    }

    // ── Like ─────────────────────────────────────────────────────────────────

    /** POST /users/{userId}/like */
    @PostMapping("/{userId}/like")
    public ResponseEntity<ApiResponse<Void>> likeUser(@PathVariable String userId) {
        String result = userProfileClient.like(targetBody(userId));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** DELETE /users/{userId}/like */
    @DeleteMapping("/{userId}/like")
    public ResponseEntity<ApiResponse<Void>> unlikeUser(@PathVariable String userId) {
        String result = userProfileClient.unlike(targetBody(userId));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** GET /users/me/likes: users I have liked */
    @GetMapping("/me/likes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyLikes() {
        return ResponseEntity.ok(ApiResponse.success("Liked users retrieved",
                userProfileClient.getMyLikes()));
    }

    // ── Follow ───────────────────────────────────────────────────────────────

    /** POST /users/{userId}/follow */
    @PostMapping("/{userId}/follow")
    public ResponseEntity<ApiResponse<Void>> followUser(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success(userProfileClient.follow(userId)));
    }

    /** DELETE /users/{userId}/follow */
    @DeleteMapping("/{userId}/follow")
    public ResponseEntity<ApiResponse<Void>> unfollowUser(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success(userProfileClient.unfollow(userId)));
    }

    /** GET /users/me/following */
    @GetMapping("/me/following")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyFollowing(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Following retrieved",
                userProfileClient.getFollowing(currentUserId(request))));
    }

    // ── Review ───────────────────────────────────────────────────────────────

    /**
     * POST /users/{userId}/review
     * Body: { "rating": 5, "comment": "Great service" }
     */
    @PostMapping("/{userId}/review")
    public ResponseEntity<ApiResponse<Void>> reviewUser(@PathVariable String userId,
                                                        @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetId", userId);
        payload.put("targetType", DEFAULT_TARGET_TYPE);
        payload.put("rating", body.get("rating"));
        payload.put("comment", body.get("comment"));
        return ResponseEntity.ok(ApiResponse.success(userProfileClient.createReview(payload)));
    }

    /**
     * PUT /users/review/{reviewId}
     * Body: { "rating": 4, "comment": "Updated review" }
     */
    @PutMapping("/review/{reviewId}")
    public ResponseEntity<ApiResponse<Void>> editReview(@PathVariable String reviewId,
                                                        @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rating", body.get("rating"));
        payload.put("comment", body.get("comment"));
        return ResponseEntity.ok(ApiResponse.success(userProfileClient.editReview(reviewId, payload)));
    }

    /** DELETE /users/review/{reviewId} */
    @DeleteMapping("/review/{reviewId}")
    public ResponseEntity<ApiResponse<Void>> deleteReview(@PathVariable String reviewId) {
        return ResponseEntity.ok(ApiResponse.success(userProfileClient.deleteReview(reviewId)));
    }

    // ── Profile Views ────────────────────────────────────────────────────────

    /** GET /users/me/views: who viewed my profile */
    @GetMapping("/me/views")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getWhoViewedMe() {
        return ResponseEntity.ok(ApiResponse.success("Profile views retrieved", userProfileClient.getWhoViewedMe()));
    }

    /** GET /users/me/viewed: profiles I have viewed */
    @GetMapping("/me/viewed")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getWhoIViewed() {
        return ResponseEntity.ok(ApiResponse.success("Viewed profiles retrieved", userProfileClient.getWhoIViewed()));
    }

    /** GET /users/me/view-stats */
    @GetMapping("/me/view-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyViewStats() {
        return ResponseEntity.ok(ApiResponse.success("View stats retrieved", userProfileClient.getMyViewStats()));
    }

    /** GET /users/{userId}/view-stats */
    @GetMapping("/{userId}/view-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserViewStats(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("View stats retrieved", userProfileClient.getUserViewStats(userId)));
    }

    // ── Inbound social ────────────────────────────────────────────────────────

    /** GET /users/me/likers: who liked me */
    @GetMapping("/me/likers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyLikers(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Users who liked me",
                userProfileClient.getLikers(currentUserId(request), DEFAULT_TARGET_TYPE)));
    }

    /** GET /users/me/followers: who follows me */
    @GetMapping("/me/followers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyFollowers(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Followers retrieved",
                userProfileClient.getFollowers(currentUserId(request))));
    }

    /** GET /users/me/reviews: reviews written about me */
    @GetMapping("/me/reviews")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyReviews(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success("My reviews retrieved",
                userProfileClient.getReviews(currentUserId(request), DEFAULT_TARGET_TYPE)));
    }

    // ── Relationship status checks ────────────────────────────────────────────

    /** GET /users/{targetId}/like-status -> { "liked": true } */
    @GetMapping("/{targetId}/like-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLikeStatus(@PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Like status",
                userProfileClient.isLiked(targetId, DEFAULT_TARGET_TYPE)));
    }

    /** GET /users/{targetId}/follow-status -> { "following": true } */
    @GetMapping("/{targetId}/follow-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFollowStatus(@PathVariable String targetId) {
        return ResponseEntity.ok(ApiResponse.success("Follow status", userProfileClient.isFollowing(targetId)));
    }

    /**
     * GET /users/{targetId}/review-status -> { "reviewed": true, "reviewId": "..." }
     * Auth has no such endpoint, so this reads the target's reviews and looks for one
     * written by the caller.
     */
    @GetMapping("/{targetId}/review-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReviewStatus(
            @PathVariable String targetId, HttpServletRequest request) {
        String myId = currentUserId(request);
        Map<String, Object> mine = userProfileClient.getReviews(targetId, DEFAULT_TARGET_TYPE).stream()
                .filter(r -> myId.equals(String.valueOf(r.get("reviewerId"))))
                .findFirst()
                .orElse(null);

        Map<String, Object> status = new HashMap<>();
        status.put("reviewed", mine != null);
        status.put("reviewId", mine != null ? mine.get("id") : null);
        return ResponseEntity.ok(ApiResponse.success("Review status", status));
    }

    /**
     * GET /users/{targetId}/mutual-follow
     * Built from two auth calls: is-following (me to them) and the follower list
     * of mine (them to me).
     */
    @GetMapping("/{targetId}/mutual-follow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMutualFollowStatus(
            @PathVariable String targetId, HttpServletRequest request) {
        boolean iFollowThem = Boolean.TRUE.equals(userProfileClient.isFollowing(targetId).get("following"));
        boolean theyFollowMe = userProfileClient.getFollowers(currentUserId(request)).stream()
                .anyMatch(u -> targetId.equals(String.valueOf(u.get("id"))));

        Map<String, Object> status = new HashMap<>();
        status.put("iFollowThem", iFollowThem);
        status.put("theyFollowMe", theyFollowMe);
        status.put("mutual", iFollowThem && theyFollowMe);
        return ResponseEntity.ok(ApiResponse.success("Mutual follow status", status));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> targetBody(String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("targetId", userId);
        body.put("targetType", DEFAULT_TARGET_TYPE);
        return body;
    }

    /** The caller's user id from the JWT, set by JwtAuthenticationFilter. */
    private String currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return userId.toString();
    }
}