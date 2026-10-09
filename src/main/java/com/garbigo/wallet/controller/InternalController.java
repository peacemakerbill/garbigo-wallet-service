package com.premisave.wallet.controller;

import com.premisave.wallet.dto.AccountDetailsResponse;
import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.InternalPaymentRequest;
import com.premisave.wallet.dto.InternalTransferRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.service.PaymentService;
import com.premisave.wallet.service.TransferService;
import com.premisave.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints — authenticated via X-API-Key
 * (InternalApiKeyFilter), not a user JWT. Every route here is already
 * covered by SecurityConfig's existing "/internal/**" -> hasRole
 * ("INTERNAL_SERVICE") matcher and WebConfig's rate-limiter, both of
 * which predate this file — no config changes needed to add new routes
 * here.
 *
 * Unlike every controller under /wallet or /payments, these take an
 * explicit identity (userId/senderUserId, initiatedBy) in the request
 * body rather than resolving one from Authentication/
 * HttpServletRequest's "userId" attribute — InternalApiKeyFilter
 * authenticates the CALLING SERVICE, not an end user, so there's no JWT
 * to pull an identity claim from.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final TransferService transferService;
    private final PaymentService paymentService;
    private final WalletService walletService;

    /**
     * The wallet-owner directory another Premisave service (e.g.
     * property-service) can page through — name, phone numbers
     * (M-Pesa/Pochi), PayPal email, and payment-method linkage status
     * for every account, without each caller needing its own separate
     * lookups against auth-service for everything except the name.
     * Deliberately public to any authenticated internal service, not
     * admin-role-gated — this is directory/contact data, not a
     * financial or admin-only report (balances are intentionally left
     * out — see AccountDetailsResponse's own javadoc).
     *
     * Paginated rather than returning everything in one response —
     * standard ?page=&size=&sort= query params. Worth reading
     * WalletService.getAllAccountDetails's own javadoc on the
     * performance cost of a large page size before choosing one: name
     * resolution costs one auth-service call PER wallet in the page,
     * with no batch lookup available.
     *
     * When any name lookup in the page indicates auth-service itself is
     * unreachable (not just "this account has no name"), the response
     * message says so explicitly and every fullName in that page is
     * null — still returned as success=true, since the wallet data
     * itself (phone numbers, PayPal email, payment-method linkage) is
     * still completely valid and useful even without names. The caller
     * decides what to do with a page of null names, rather than this
     * endpoint failing outright and withholding data that's still good.
     */
    @GetMapping("/accounts")
    public ResponseEntity<ApiResponse<PagedModel<AccountDetailsResponse>>> accounts(Pageable pageable) {
        WalletService.AccountDetailsPage result = walletService.getAllAccountDetails(pageable);
        PagedModel<AccountDetailsResponse> body = new PagedModel<>(result.page());
        String message = result.authServiceReachable()
                ? "Account details retrieved"
                : "Account details retrieved, but auth-service appears unreachable — names in this page could not be resolved";
        return ResponseEntity.ok(ApiResponse.success(message, body));
    }

    /**
     * Triggers a wallet-to-wallet transfer on behalf of another Premisave
     * service. Same underlying logic as POST /wallet/transfer — see
     * TransferService.executeTransfer — just with an explicit sender
     * instead of one resolved from a JWT.
     */
    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody InternalTransferRequest request) {
        PaymentResponse response = transferService.transferInternal(request);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    /**
     * Triggers a wallet-to-platform payment on behalf of another
     * Premisave service — e.g. property-service deducting a booking fee
     * or ad-subscription charge from a user's wallet. Same underlying
     * logic as POST /payments/deduct — see PaymentService.executePayment
     * — just with an explicit payer instead of one resolved from a JWT.
     */
    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<PaymentResponse>> payment(
            @Valid @RequestBody InternalPaymentRequest request) {
        PaymentResponse response = paymentService.payInternal(request);
        return ResponseEntity.ok(ApiResponse.success("Payment successful", response));
    }
}