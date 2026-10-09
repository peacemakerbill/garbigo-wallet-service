package com.premisave.wallet.service;

import com.premisave.wallet.client.AuthServiceClient;
import com.premisave.wallet.dto.client.UserDetailsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared, best-effort lookup of a real user's full name via the auth
 * service — every deposit, disbursement, payment, and transfer flow
 * needs the exact same thing: resolve a wallet owner's name, given
 * their email (Wallet.accountNumber), for inclusion in a confirmation
 * email and for persisting alongside the transaction record. Originally
 * built inline in TransferService for that one flow; extracted here so
 * every other caller shares the identical, already-tested logic rather
 * than each service re-implementing its own version.
 *
 * A failed or empty lookup must NEVER break the underlying transaction —
 * same principle as email sending itself. Returns null on any failure
 * (network error, account not found, no name on file) — EmailService
 * already treats a null/blank name as "omit this row" everywhere it's
 * used, and every caller here should persist null in that same case
 * rather than a placeholder string.
 *
 * Deliberately exposes ONLY the resolved name — never active, verified,
 * or role from UserDetailsDto. Callers of this class never see the rest
 * of that DTO at all, so there's no risk of a caller accidentally
 * surfacing sensitive account-status fields in an email or a saved
 * record; this class is the one place that talks to AuthServiceClient
 * directly for this purpose, and it only ever hands back a name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNameResolver {

    private final AuthServiceClient authServiceClient;

    /**
     * @param email the account's email (Wallet.accountNumber) to look up.
     * @return the resolved full name, or null if the lookup failed, the
     *         email was null/blank, or the account has no name on file.
     */
    public String resolveNameSafely(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            return authServiceClient.getUserDetails(email)
                    .map(UserDetailsDto::fullName)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Name lookup failed for email={} — proceeding without it: {}", email, e.getMessage());
            return null;
        }
    }

    /**
     * A null fullName from resolveNameSafely above is ambiguous by
     * design — it means "network error", "account not found", or
     * "account exists but has no name on file" identically, which is
     * exactly right for every existing caller (a failed name lookup
     * must never block a deposit/disbursement/transfer/payment from
     * completing, and the reason doesn't matter to any of them).
     *
     * GET /internal/accounts is different: it calls this once per
     * wallet in a page, and if auth-service is genuinely down, EVERY
     * fullName in that page comes back null with no way for the caller
     * to tell that apart from "this happens to be a page full of users
     * with no name on file" — a real, reported problem. This method
     * exists ONLY to give that one caller enough information to tell
     * the two apart; resolveNameSafely above is intentionally left
     * unchanged for everything else.
     *
     * authServiceReachable=false means the call to auth-service itself
     * threw — connection refused, timeout, a non-2xx error response,
     * etc. — never fullName resolved in that case. authServiceReachable
     * =true means auth-service responded normally; fullName may still
     * be null, but that's the ordinary, unremarkable "no name on file"
     * case, not a service outage.
     */
    public record NameResolution(String fullName, boolean authServiceReachable) {}

    public NameResolution resolveNameWithReachability(String email) {
        if (email == null || email.isBlank()) {
            return new NameResolution(null, true);
        }
        try {
            String fullName = authServiceClient.getUserDetails(email)
                    .map(UserDetailsDto::fullName)
                    .orElse(null);
            return new NameResolution(fullName, true);
        } catch (Exception e) {
            log.warn("Name lookup failed for email={} — auth-service may be unreachable: {}", email, e.getMessage());
            return new NameResolution(null, false);
        }
    }
}