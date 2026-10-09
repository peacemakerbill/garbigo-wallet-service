package com.premisave.wallet.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.FlutterwaveWebhookRequest;
import com.premisave.wallet.dto.MpesaResultCallbackRequest;
import com.premisave.wallet.dto.MpesaStkCallbackRequest;
import com.premisave.wallet.dto.PaypalWebhookRequest;
import com.premisave.wallet.service.FlutterwaveDepositService;
import com.premisave.wallet.service.FlutterwaveDisbursementService;
import com.premisave.wallet.service.FlutterwaveService;
import com.premisave.wallet.service.MpesaDepositService;
import com.premisave.wallet.service.MpesaDisbursementService;
import com.premisave.wallet.service.MpesaOperationsService;
import com.premisave.wallet.service.NowPaymentsDepositService;
import com.premisave.wallet.service.NowPaymentsDisbursementService;
import com.premisave.wallet.service.NowPaymentsService;
import com.premisave.wallet.service.PaypalDepositService;
import com.premisave.wallet.service.PaypalDisbursementService;
import com.premisave.wallet.service.PaypalService;
import com.premisave.wallet.service.PullTransactionService;
import com.premisave.wallet.service.StripeDepositService;
import com.premisave.wallet.service.StripeDisbursementService;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.model.SetupIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.premisave.wallet.service.StripeService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles incoming webhooks/callbacks from all payment providers: M-Pesa (STK
 * Push, B2C, B2B, B2B Express Checkout, Account Balance, Transaction Status,
 * Reversal, B2Pochi), Stripe (platform + Connect), PayPal, Flutterwave, and
 * NOWPayments. All endpoints are PUBLIC (no JWT) — secured by signature
 * verification or IP allowlist at the gateway/firewall level.
 *
 * NOTE: the STK callback path below deliberately does NOT contain the substring
 * "mpesa" — Safaricom's sandbox rejects CallBackURLs containing that word with
 * "400.002.02 Bad Request - Invalid CallBackURL", even when the URL is
 * otherwise valid and publicly reachable. Keep this path (and the corresponding
 * mpesa.daraja.callback-url env value) free of "mpesa".
 *
 * IMPORTANT — every path added here MUST also be added to: 1. SecurityConfig's
 * two permitAll() matcher lists 2. WebConfig's rate-limiter exclude list 3.
 * CallbackRequestLoggingFilter.CALLBACK_PATHS Missing any one of these means
 * the provider's callback silently 401s with zero application logs (see the
 * earlier B2C/B2Pochi ResultURL incident — a mismatched result-url env value
 * produced exactly this failure mode, invisible everywhere except the
 * provider's own retry logs).
 *
 * MpesaResultCallbackRequest.Result.resultCode is a String (see that class's
 * own javadoc for why — Safaricom sends both plain numeric success codes
 * ("0") and alphanumeric error codes ("TP40153"), and only String can hold
 * both). Every success-check below against it is therefore a string
 * comparison ("0".equals(...)), not an int comparison — a prior version of
 * this file used result.getResultCode() == 0, which stopped compiling the
 * moment that field's type was corrected.
 */
@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentCallbackController {

	private final MpesaDepositService mpesaDepositService;
	private final StripeDepositService stripeDepositService;
	private final PaypalDepositService paypalDepositService;
	private final FlutterwaveDepositService flutterwaveDepositService;
	private final NowPaymentsDepositService nowPaymentsDepositService;
	private final MpesaDisbursementService mpesaDisbursementService;
	private final StripeDisbursementService stripeDisbursementService;
	private final PaypalDisbursementService paypalDisbursementService;
	private final FlutterwaveDisbursementService flutterwaveDisbursementService;
	private final NowPaymentsDisbursementService nowPaymentsDisbursementService;
	private final MpesaOperationsService mpesaOperationsService;
	private final PullTransactionService pullTransactionService;
	private final StripeService stripeService;
	private final PaypalService paypalService;
	private final FlutterwaveService flutterwaveService;
	private final NowPaymentsService nowPaymentsService;
	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Value("${stripe.webhook-secret:}")
	private String stripeWebhookSecret;

	@Value("${stripe.connect.webhook-secret:}")
	private String stripeConnectWebhookSecret;

	// ─── M-Pesa STK Push Callback ────────────────────────────────────────────

	/**
	 * Receives M-Pesa STK Push callback from Safaricom Daraja. Secured via IP
	 * allowlist at the gateway/firewall level (no JWT).
	 *
	 * Safaricom's payload is nested under Body.stkCallback — see
	 * MpesaStkCallbackRequest for the exact shape. On success (ResultCode == 0) the
	 * paid amount, receipt number, and phone are inside CallbackMetadata.Item;
	 * there is no account number or email in this payload, so the transaction is
	 * matched back to a wallet via CheckoutRequestID (see DepositService).
	 *
	 * NOTE: this uses MpesaStkCallbackRequest, a DIFFERENT DTO from
	 * MpesaResultCallbackRequest — its own ResultCode field/type is
	 * unaffected by the resultCode type fix described in the class javadoc
	 * above, and stk.getResultCode() != 0 below is unchanged.
	 */
	@PostMapping("/stk-callback")
	public ResponseEntity<ApiResponse<Void>> handleMpesaCallback(@RequestBody MpesaStkCallbackRequest callback) {
		MpesaStkCallbackRequest.StkCallback stk = callback.getBody().getStkCallback();
		String checkoutRequestId = stk.getCheckoutRequestID();

		log.info("M-Pesa STK callback received: checkoutRequestId={} resultCode={} resultDesc={}", checkoutRequestId,
				stk.getResultCode(), stk.getResultDesc());

		try {
			if (stk.getResultCode() != 0) {
				// Not an error on our side — user cancelled, wrong PIN, timed out, etc.
				mpesaDepositService.markStkTransactionFailed(checkoutRequestId, stk.getResultDesc());
				return ResponseEntity.ok(ApiResponse.success("Callback processed (payment not completed)"));
			}

			Map<String, Object> values = new HashMap<>();
			if (stk.getCallbackMetadata() != null && stk.getCallbackMetadata().getItem() != null) {
				for (MpesaStkCallbackRequest.CallbackItem item : stk.getCallbackMetadata().getItem()) {
					values.put(item.getName(), item.getValue());
				}
			}

			BigDecimal amount = new BigDecimal(String.valueOf(values.get("Amount")));
			String mpesaReceipt = String.valueOf(values.get("MpesaReceiptNumber"));
			String phoneNumber = String.valueOf(values.get("PhoneNumber"));

			mpesaDepositService.creditWalletFromStkCallback(checkoutRequestId, amount, mpesaReceipt, phoneNumber);

			return ResponseEntity.ok(ApiResponse.success("Callback processed"));
		} catch (Exception e) {
			log.error("Failed to process M-Pesa STK callback: checkoutRequestId={}", checkoutRequestId, e);
			// Always return 200 to Safaricom — they retry on non-200
			return ResponseEntity.ok(ApiResponse.error("Callback processing failed: " + e.getMessage()));
		}
	}

	// ─── M-Pesa B2C Result (disbursement outcome) ────────────────────────────

	@PostMapping("/mpesa/b2c/result")
	public ResponseEntity<Void> mpesaB2cResult(@RequestBody MpesaResultCallbackRequest callback) {
		var result = callback.getResult();
		log.info("M-Pesa B2C result: conversationId={} resultCode={} resultDesc={}", result.getConversationID(),
				result.getResultCode(), result.getResultDesc());

		try {
			boolean success = "0".equals(result.getResultCode());
			mpesaDisbursementService.completeMpesaDisbursement(result.getConversationID(), success, result.getResultDesc(),
					result.getTransactionID());
		} catch (Exception e) {
			log.error("Failed to process M-Pesa B2C result: conversationId={}", result.getConversationID(), e);
		}

		return ResponseEntity.ok().build();
	}

	@PostMapping("/mpesa/b2c/timeout")
	public ResponseEntity<Void> mpesaB2cTimeout(@RequestBody MpesaResultCallbackRequest callback) {
		String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
		log.warn("M-Pesa B2C timeout: conversationId={}", conversationId);
		try {
			mpesaDisbursementService.markMpesaDisbursementTimedOut(conversationId);
		} catch (Exception e) {
			log.error("Failed to process M-Pesa B2C timeout: conversationId={}", conversationId, e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── M-Pesa B2B Result ───────────────────────────────────────────────────

	@PostMapping("/mpesa/b2b/result")
	public ResponseEntity<Void> mpesaB2bResult(@RequestBody MpesaResultCallbackRequest callback) {
		var result = callback.getResult();
		log.info("M-Pesa B2B result: conversationId={} resultCode={} resultDesc={}", result.getConversationID(),
				result.getResultCode(), result.getResultDesc());

		try {
			boolean success = "0".equals(result.getResultCode());
			mpesaDisbursementService.completeMpesaDisbursement(result.getConversationID(), success, result.getResultDesc(),
					result.getTransactionID());
		} catch (Exception e) {
			log.error("Failed to process M-Pesa B2B result: conversationId={}", result.getConversationID(), e);
		}

		return ResponseEntity.ok().build();
	}

	@PostMapping("/mpesa/b2b/timeout")
	public ResponseEntity<Void> mpesaB2bTimeout(@RequestBody MpesaResultCallbackRequest callback) {
		String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
		log.warn("M-Pesa B2B timeout: conversationId={}", conversationId);
		try {
			mpesaDisbursementService.markMpesaDisbursementTimedOut(conversationId);
		} catch (Exception e) {
			log.error("Failed to process M-Pesa B2B timeout: conversationId={}", conversationId, e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── M-Pesa Account Balance Result ────────────────────────────────────────

	@PostMapping("/mpesa/balance/result")
	public ResponseEntity<Void> mpesaBalanceResult(@RequestBody MpesaResultCallbackRequest callback) {
		var result = callback.getResult();
		log.info("M-Pesa Account Balance result: conversationId={} resultCode={} resultDesc={}",
				result.getConversationID(), result.getResultCode(), result.getResultDesc());

		try {
			boolean success = "0".equals(result.getResultCode());
			mpesaOperationsService.completeOperation(result.getConversationID(), success,
					result.getResultCode(), result.getResultDesc(), extractResultParameters(result));
		} catch (Exception e) {
			log.error("Failed to process Account Balance result: conversationId={}", result.getConversationID(), e);
		}

		return ResponseEntity.ok().build();
	}

	@PostMapping("/mpesa/balance/timeout")
	public ResponseEntity<Void> mpesaBalanceTimeout(@RequestBody MpesaResultCallbackRequest callback) {
		String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
		log.warn("M-Pesa Account Balance timeout: conversationId={}", conversationId);
		try {
			mpesaOperationsService.markOperationTimedOut(conversationId);
		} catch (Exception e) {
			log.error("Failed to process Account Balance timeout: conversationId={}", conversationId, e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── M-Pesa Transaction Status Result ─────────────────────────────────────

	@PostMapping("/mpesa/transactionstatus/result")
	public ResponseEntity<Void> mpesaTransactionStatusResult(@RequestBody MpesaResultCallbackRequest callback) {
		var result = callback.getResult();
		log.info("M-Pesa Transaction Status result: conversationId={} resultCode={} resultDesc={}",
				result.getConversationID(), result.getResultCode(), result.getResultDesc());

		try {
			boolean success = "0".equals(result.getResultCode());
			mpesaOperationsService.completeOperation(result.getConversationID(), success,
					result.getResultCode(), result.getResultDesc(), extractResultParameters(result));
		} catch (Exception e) {
			log.error("Failed to process Transaction Status result: conversationId={}", result.getConversationID(), e);
		}

		return ResponseEntity.ok().build();
	}

	@PostMapping("/mpesa/transactionstatus/timeout")
	public ResponseEntity<Void> mpesaTransactionStatusTimeout(@RequestBody MpesaResultCallbackRequest callback) {
		String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
		log.warn("M-Pesa Transaction Status timeout: conversationId={}", conversationId);
		try {
			mpesaOperationsService.markOperationTimedOut(conversationId);
		} catch (Exception e) {
			log.error("Failed to process Transaction Status timeout: conversationId={}", conversationId, e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── M-Pesa Reversal Result ────────────────────────────────────────────────

	@PostMapping("/mpesa/reversal/result")
	public ResponseEntity<Void> mpesaReversalResult(@RequestBody MpesaResultCallbackRequest callback) {
		var result = callback.getResult();
		log.info("M-Pesa Reversal result: conversationId={} resultCode={} resultDesc={}", result.getConversationID(),
				result.getResultCode(), result.getResultDesc());

		try {
			boolean success = "0".equals(result.getResultCode());
			mpesaOperationsService.completeOperation(result.getConversationID(), success,
					result.getResultCode(), result.getResultDesc(), extractResultParameters(result));
		} catch (Exception e) {
			log.error("Failed to process Reversal result: conversationId={}", result.getConversationID(), e);
		}

		return ResponseEntity.ok().build();
	}

	@PostMapping("/mpesa/reversal/timeout")
	public ResponseEntity<Void> mpesaReversalTimeout(@RequestBody MpesaResultCallbackRequest callback) {
		String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
		log.warn("M-Pesa Reversal timeout: conversationId={}", conversationId);
		try {
			mpesaOperationsService.markOperationTimedOut(conversationId);
		} catch (Exception e) {
			log.error("Failed to process Reversal timeout: conversationId={}", conversationId, e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── M-Pesa B2Pochi Result ──────────────────────────────────────────────────

	@PostMapping("/mpesa/b2pochi/result")
	public ResponseEntity<Void> mpesaB2PochiResult(@RequestBody MpesaResultCallbackRequest callback) {
		var result = callback.getResult();
		log.info("M-Pesa B2Pochi result: conversationId={} resultCode={} resultDesc={}", result.getConversationID(),
				result.getResultCode(), result.getResultDesc());

		try {
			boolean success = "0".equals(result.getResultCode());
			mpesaDisbursementService.completeMpesaDisbursement(result.getConversationID(), success, result.getResultDesc(),
					result.getTransactionID());
		} catch (Exception e) {
			log.error("Failed to process B2Pochi result: conversationId={}", result.getConversationID(), e);
		}

		return ResponseEntity.ok().build();
	}

	@PostMapping("/mpesa/b2pochi/timeout")
	public ResponseEntity<Void> mpesaB2PochiTimeout(@RequestBody MpesaResultCallbackRequest callback) {
		String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
		log.warn("M-Pesa B2Pochi timeout: conversationId={}", conversationId);
		try {
			mpesaDisbursementService.markMpesaDisbursementTimedOut(conversationId);
		} catch (Exception e) {
			log.error("Failed to process B2Pochi timeout: conversationId={}", conversationId, e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── M-Pesa Pull Transactions Callback ────────────────────────────────────

	@PostMapping("/mpesa/pull/callback")
	public ResponseEntity<Void> mpesaPullTransactionsCallback(@RequestBody String payload) {
		try {
			pullTransactionService.handleCallback(payload);
		} catch (Exception e) {
			log.error("Failed to process Pull Transactions callback", e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── Stripe Webhook (platform — deposits, saved cards) ────────────────────

	@PostMapping("/stripe/webhook")
	public ResponseEntity<ApiResponse<Void>> stripeWebhook(@RequestBody String payload,
			@RequestHeader("Stripe-Signature") String sigHeader)
			throws com.stripe.exception.SignatureVerificationException {

		if (stripeService.isV2Event(payload)) {
			log.info("Ignoring Stripe v2 Core Event on platform webhook — this integration only handles v1 events");
			return ResponseEntity.ok(ApiResponse.success("Webhook ignored (v2 event)"));
		}

		// Called outside the try/catch below on purpose — its generic
		// catch (Exception e) would otherwise intercept
		// SignatureVerificationException too (it's an Exception subtype)
		// before it could ever reach GlobalExceptionHandler.
		Event event = stripeService.constructWebhookEvent(payload, sigHeader, stripeWebhookSecret);
		log.info("Stripe webhook received: type={} id={}", event.getType(), event.getId());

		try {
			if ("payment_intent.succeeded".equals(event.getType())) {
				PaymentIntent pi = (PaymentIntent) event.getDataObjectDeserializer().deserializeUnsafe();

				String reference = pi.getMetadata() != null ? pi.getMetadata().get("idempotency_key") : null;
				BigDecimal amount = BigDecimal.valueOf(pi.getAmount()).divide(BigDecimal.valueOf(100));

				if (reference != null) {
					stripeDepositService.creditWalletFromStripeCallback(reference, amount, pi.getId(), pi.getCurrency(),
							pi.getCustomer(), pi.getPaymentMethod());
					log.info("Stripe deposit completed: reference={} amount={}", reference, amount);
				} else {
					log.warn("Stripe PaymentIntent {} has no idempotency_key metadata — cannot reconcile", pi.getId());
				}
			} else if ("payment_intent.payment_failed".equals(event.getType())) {
				// Counterpart to payment_intent.succeeded above — was
				// missing entirely, meaning a declined card or an abandoned
				// 3DS challenge left the transaction PENDING forever with
				// no reconciliation path (unlike M-Pesa/PayPal/Flutterwave,
				// which all have this).
				PaymentIntent pi = (PaymentIntent) event.getDataObjectDeserializer().deserializeUnsafe();

				String reference = pi.getMetadata() != null ? pi.getMetadata().get("idempotency_key") : null;
				String reason = pi.getLastPaymentError() != null && pi.getLastPaymentError().getMessage() != null
						? pi.getLastPaymentError().getMessage()
						: "Payment failed";

				if (reference != null) {
					stripeDepositService.markStripeTransactionFailed(reference, reason);
					log.info("Stripe deposit failed: reference={} reason={}", reference, reason);
				} else {
					log.warn("Stripe PaymentIntent {} failed but has no idempotency_key metadata — cannot reconcile", pi.getId());
				}
			} else if ("setup_intent.succeeded".equals(event.getType())) {
				SetupIntent si = (SetupIntent) event.getDataObjectDeserializer().deserializeUnsafe();

				if (si.getCustomer() != null && si.getPaymentMethod() != null) {
					stripeDepositService.attachSavedCardByCustomerId(si.getCustomer(), si.getPaymentMethod());
					log.info("Stripe card saved via webhook: customerId={} paymentMethodId={}", si.getCustomer(),
							si.getPaymentMethod());
				} else {
					log.warn("Stripe SetupIntent {} succeeded but is missing customer or payment_method", si.getId());
				}
			}

			return ResponseEntity.ok(ApiResponse.success("Webhook processed"));
		} catch (Exception e) {
			log.error("Stripe webhook processing failed", e);
			return ResponseEntity.badRequest().body(ApiResponse.error("Webhook error: " + e.getMessage()));
		}
	}

	/**
	 * SEPARATE endpoint from /payments/stripe/webhook above — Stripe requires
	 * a distinct webhook destination configured to "Listen to events on
	 * Connected accounts" for payout.paid / payout.failed events on
	 * connected accounts; they can't be folded into the platform webhook's
	 * own destination/secret. See StripeConfig.Connect.webhookSecret.
	 *
	 * Handles payout confirmation ONLY — this is the sole mechanism that
	 * reconciles a STRIPE disbursement's PENDING status (see
	 * StripeDisbursementService.completeStripeConnectDisbursement); without it, a
	 * Stripe withdrawal never resolves and the wallet is never debited.
	 * There's no polling alternative for this the way there is for account
	 * status below, so this endpoint IS required once you have users
	 * requesting Stripe withdrawals — it's not optional infrastructure.
	 *
	 * Does NOT handle account.updated (connected-account onboarding/
	 * verification status) — that's deliberately left to
	 * POST /wallet/stripe/connect/refresh instead, an on-demand pull rather
	 * than a webhook push, since account status has no urgency the way a
	 * payout result does and refresh covers it identically (see
	 * WalletService.updateStripeConnectAccountStatus, currently unused but
	 * kept in case you want push-based account status later — wire an
	 * "account.updated".equals(event.getType()) branch back in here and
	 * call it, same shape as the payout branches below).
	 */
	@PostMapping("/stripe/connect/webhook")
	public ResponseEntity<ApiResponse<Void>> stripeConnectWebhook(@RequestBody String payload,
			@RequestHeader("Stripe-Signature") String sigHeader)
			throws com.stripe.exception.SignatureVerificationException {

		if (stripeService.isV2Event(payload)) {
			log.info("Ignoring Stripe v2 Core Event on Connect webhook — this integration only handles v1 events");
			return ResponseEntity.ok(ApiResponse.success("Webhook ignored (v2 event)"));
		}

		// Same reasoning as stripeWebhook above — called outside the try
		// block below so SignatureVerificationException isn't swallowed by
		// its generic catch (Exception e) before reaching GlobalExceptionHandler.
		Event event = stripeService.constructWebhookEvent(payload, sigHeader, stripeConnectWebhookSecret);
		log.info("Stripe Connect webhook received: type={} id={} account={}",
				event.getType(), event.getId(), event.getAccount());

		try {
			if ("payout.paid".equals(event.getType())) {
				Payout payout = (Payout) event.getDataObjectDeserializer().deserializeUnsafe();
				stripeDisbursementService.completeStripeConnectDisbursement(payout.getId(), true, null);
			} else if ("payout.failed".equals(event.getType())) {
				Payout payout = (Payout) event.getDataObjectDeserializer().deserializeUnsafe();
				String reason = payout.getFailureMessage() != null ? payout.getFailureMessage() : payout.getFailureCode();
				stripeDisbursementService.completeStripeConnectDisbursement(payout.getId(), false, reason);
			}
			// account.updated and every other Connect event type
			// intentionally ignored here — see javadoc above.

			return ResponseEntity.ok(ApiResponse.success("Webhook processed"));
		} catch (Exception e) {
			log.error("Stripe Connect webhook processing failed", e);
			return ResponseEntity.badRequest().body(ApiResponse.error("Webhook error: " + e.getMessage()));
		}
	}

	// ─── PayPal Webhook ──────────────────────────────────────────────────────

	/**
	 * PayPal sends events here after order approval, after a vaulted payment source
	 * finishes saving, after a capture that happened synchronously at order
	 * creation time (no CHECKOUT.ORDER.APPROVED fires for that case, since there
	 * was no separate approval step; PAYMENT.CAPTURE.COMPLETED is the only event
	 * PayPal sends), and after a Payouts batch item resolves (the Payouts API is
	 * asynchronous — a successful create-payout response only means the batch was
	 * queued).
	 *
	 * Verified via PayPal's Verify Webhook Signature API (see
	 * PaypalService.verifyWebhookSignature) before any processing happens. An
	 * unverifiable or invalid signature is rejected with 400 rather than trusted,
	 * since a forged event here could otherwise trigger a deposit confirmation for
	 * an arbitrary order ID (or a false payout success).
	 *
	 * Key events: - CHECKOUT.ORDER.APPROVED → capture + credit wallet (see
	 * DepositService.confirmPaypalDeposit) - PAYMENT.CAPTURE.COMPLETED → backstop
	 * for orders captured synchronously at creation time (vault reuse) or any other
	 * capture this service didn't already reconcile — confirmPaypalDeposit is
	 * idempotent either way - VAULT.PAYMENT-TOKEN.CREATED → backstop for vaulting
	 * that was still "APPROVED" (not "VAULTED") at capture time — finalizes the
	 * saved account's vault_id/email on the wallet (see
	 * DepositService.attachPaypalVaultToken) - PAYMENT.PAYOUTS-ITEM.*
	 * (SUCCEEDED/FAILED/DENIED/BLOCKED/RETURNED/ REFUNDED/UNCLAIMED/HELD/CANCELED)
	 * → reconciles a PayPal disbursement (see
	 * PaypalDisbursementService.completePaypalDisbursement). Not modeled in
	 * PaypalWebhookRequest (its Resource type is shaped for orders/vault events),
	 * so these fields are read straight off the raw JSON body, same approach as the
	 * PAYMENT.CAPTURE.COMPLETED backstop below.
	 */
	@PostMapping("/paypal/webhook")
	public ResponseEntity<Void> paypalWebhook(@RequestBody String rawBody, @RequestHeader Map<String, String> headers) {

		// Header casing varies by client/container — normalize to upper-case
		// so verifyWebhookSignature's lookups reliably match PayPal's
		// documented header names regardless of how it arrived here.
		Map<String, String> normalizedHeaders = new HashMap<>();
		headers.forEach((k, v) -> normalizedHeaders.put(k.toUpperCase(), v));

		boolean signatureValid = paypalService.verifyWebhookSignature(normalizedHeaders, rawBody);
		if (!signatureValid) {
			log.warn("PayPal webhook rejected — signature verification failed or webhook_id not configured");
			return ResponseEntity.status(400).build();
		}

		try {
			PaypalWebhookRequest payload = objectMapper.readValue(rawBody, PaypalWebhookRequest.class);
			log.info("PayPal webhook received (verified): type={}", payload.getEventType());

			if ("CHECKOUT.ORDER.APPROVED".equals(payload.getEventType()) && payload.getResource() != null) {
				paypalDepositService.confirmPaypalDeposit(payload.getResource().getId());
			} else if ("VAULT.PAYMENT-TOKEN.CREATED".equals(payload.getEventType()) && payload.getResource() != null) {
				var resource = payload.getResource();
				String vaultId = resource.getId();
				String customerId = resource.getCustomer() != null ? resource.getCustomer().getId() : null;
				String email = resource.getPaymentSource() != null && resource.getPaymentSource().getPaypal() != null
						? resource.getPaymentSource().getPaypal().getEmailAddress()
						: null;
				paypalDepositService.attachPaypalVaultToken(vaultId, customerId, email);
			} else if ("PAYMENT.CAPTURE.COMPLETED".equals(payload.getEventType())) {
				// Backstop for vault-reuse auto-capture: when an order was
				// captured synchronously at creation time (existing vault_id,
				// no re-auth required), PayPal sends this event instead of
				// CHECKOUT.ORDER.APPROVED. The order_id lives under
				// resource.supplementary_data.related_ids.order_id, which
				// PaypalWebhookRequest doesn't model — parsed from the raw
				// body directly instead.
				JsonNode capturePayload = objectMapper.readTree(rawBody);
				String orderId = capturePayload.path("resource").path("supplementary_data").path("related_ids")
						.path("order_id").asString(null);

				if (orderId != null && !orderId.isBlank()) {
					paypalDepositService.confirmPaypalDeposit(orderId);
				} else {
					log.warn(
							"PAYMENT.CAPTURE.COMPLETED webhook missing resource.supplementary_data.related_ids.order_id — cannot reconcile");
				}
			} else if (payload.getEventType() != null && payload.getEventType().startsWith("PAYMENT.PAYOUTS-ITEM.")) {
				// Payouts item webhook — resource shape (payout_batch_id,
				// payout_item_id, transaction_id, transaction_status, errors)
				// isn't modeled in PaypalWebhookRequest, so read it straight
				// off the raw body, same as the PAYMENT.CAPTURE.COMPLETED
				// backstop above.
				JsonNode payoutPayload = objectMapper.readTree(rawBody);
				JsonNode resource = payoutPayload.path("resource");
				String payoutBatchId = resource.path("payout_batch_id").asString(null);
				String payoutItemId = resource.path("payout_item_id").asString(null);
				String paypalTransactionId = resource.path("transaction_id").asString(null);
				String transactionStatus = resource.path("transaction_status").asString(null);
				String errorMessage = resource.path("errors").path("message").asString(null);

				log.info(
						"PayPal payout item webhook: eventType={} payoutBatchId={} payoutItemId={} transactionStatus={}",
						payload.getEventType(), payoutBatchId, payoutItemId, transactionStatus);

				if (payoutBatchId != null && !payoutBatchId.isBlank()) {
					paypalDisbursementService.completePaypalDisbursement(payoutBatchId, transactionStatus,
							paypalTransactionId, errorMessage);
				} else {
					log.warn(
							"PayPal payout item webhook missing resource.payout_batch_id — cannot reconcile: eventType={}",
							payload.getEventType());
				}
			}
			// Other event types intentionally ignored — capture is already
			// triggered above or by the frontend confirm endpoint;
			// confirmPaypalDeposit/attachPaypalVaultToken/completePaypalDisbursement
			// are idempotent either way.
		} catch (Exception e) {
			// Always ACK 200 to PayPal regardless of internal outcome — same
			// pattern as MpesaC2BController.confirm.
			log.error("PayPal webhook processing error: {}", e.getMessage(), e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── Flutterwave Webhook ─────────────────────────────────────────────────

	/**
	 * v4 sends "charge.completed" (deposits) and "transfer.disburse"
	 * (disbursements) to the same endpoint — note "transfer.disburse", NOT the old
	 * v3 "transfer.completed".
	 *
	 * Signature verification changed in v4: the raw body is HMAC-SHA256'd with
	 * flutterwave.webhook-secret-hash as the key, base64-encoded, and sent in the
	 * "flutterwave-signature" header — NOT v3's plain-string "verif-hash"
	 * comparison. See FlutterwaveService.verifyWebhookSignature.
	 *
	 * Always returns 200 regardless of internal outcome — same pattern as every
	 * other provider callback here — Flutterwave retries on non-200.
	 */
	@PostMapping("/flutterwave/webhook")
	public ResponseEntity<Void> flutterwaveWebhook(@RequestBody String rawBody,
			@RequestHeader(value = "flutterwave-signature", required = false) String signature) {

		if (!flutterwaveService.verifyWebhookSignature(rawBody, signature)) {
			log.warn("Flutterwave webhook rejected — flutterwave-signature mismatch or not configured");
			return ResponseEntity.status(400).build();
		}

		try {
			FlutterwaveWebhookRequest payload = objectMapper.readValue(rawBody, FlutterwaveWebhookRequest.class);
			String event = payload.getEvent();
			JsonNode data = payload.getEventData();

			log.info("Flutterwave webhook received (verified): event={}", event);

			if ("charge.completed".equals(event) && data != null) {
				String txRef = data.path("reference").asString(null);
				String chargeId = data.path("id").asString(null);
				String status = data.path("status").asString(""); // v4: "succeeded" | "failed" | "pending"

				if (txRef == null || txRef.isBlank()) {
					log.warn("Flutterwave charge.completed webhook missing data.reference — cannot reconcile");
				} else if ("succeeded".equalsIgnoreCase(status)) {
					// Re-verify server-side before crediting — see
					// FlutterwaveService.verifyChargeById's javadoc.
					flutterwaveDepositService.creditWalletFromFlutterwaveCallback(txRef, chargeId);
				} else {
					flutterwaveDepositService.markFlutterwaveTransactionFailed(txRef, "Flutterwave reported status=" + status);
				}
			} else if ("transfer.disburse".equals(event) && data != null) {
				String transferId = data.path("id").asString(null);
				String status = data.path("status").asString(""); // SUCCESSFUL | FAILED

				// NOTE: no documented FAILED transfer.disburse payload was found in
				// Flutterwave's v4 docs (only SUCCESSFUL samples, which carry no error
				// field at all). Falling back across a few plausible field names —
				// confirm the actual field against a real failed-transfer webhook in
				// sandbox before relying on this for anything user-facing.
				String failureMessage = data.path("failure_reason").asString(null);
				if (failureMessage == null || failureMessage.isBlank()) {
					failureMessage = data.path("message").asString(null);
				}
				if (failureMessage == null || failureMessage.isBlank()) {
					failureMessage = data.path("error").path("message").asString(null);
				}

				if (transferId == null || transferId.isBlank()) {
					log.warn("Flutterwave transfer.disburse webhook missing data.id — cannot reconcile");
				} else {
					boolean success = "SUCCESSFUL".equalsIgnoreCase(status);
					flutterwaveDisbursementService.completeFlutterwaveDisbursement(transferId, success,
							failureMessage != null ? failureMessage : status);
				}
			}
			// Other event types intentionally ignored.
		} catch (Exception e) {
			log.error("Flutterwave webhook processing error: {}", e.getMessage(), e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── NOWPayments Webhook (IPN) ──────────────────────────────────────────

	/**
	 * NOWPayments sends one POST per status transition to this SINGLE
	 * endpoint for BOTH deposits and payouts — no separate platform/Connect
	 * split like Stripe, and no separate deposit/payout URLs either, since
	 * both create_payment and create_payout point their ipn_callback_url
	 * here.
	 *
	 * Verified via HMAC-SHA512 over the recursively-key-sorted JSON body,
	 * sent in the "x-nowpayments-sig" header — see
	 * NowPaymentsService.verifyIpnSignature for the full explanation and
	 * its confirmed-against-official-sources caveats. Same signing scheme
	 * for both deposit and payout callbacks — confirmed from NOWPayments'
	 * own docs, not assumed.
	 *
	 * DEPOSIT callbacks are identified by a "payment_id" field — confirmed
	 * shape, tested end to end earlier in this integration.
	 *
	 * PAYOUT callbacks are routed here too, but their exact field names
	 * were NOT independently confirmed against a real captured payload at
	 * the time this was written — NOWPayments' payout API documentation is
	 * far less complete publicly than the deposit side. The field-name
	 * fallbacks below (checking a few plausible names for the payout id
	 * and status) are a best-effort guess, same defensive pattern already
	 * used for Flutterwave's transfer.disburse failure-message field
	 * uncertainty elsewhere in this file. CONFIRM the real field names
	 * against an actual sandbox payout webhook delivery before relying on
	 * this for anything user-facing — check Dashboard → Payments History
	 * or your own captured raw body (see CallbackRequestLoggingFilter) the
	 * first time a real payout webhook arrives, and adjust the field paths
	 * below if they differ.
	 *
	 * "finished" is the only DEPOSIT status that credits the wallet — see
	 * NowPaymentsDepositService.creditWalletFromNowPaymentsCallback's
	 * javadoc for why "confirmed" is deliberately NOT treated as success.
	 * For PAYOUTS, "FINISHED" is treated as success and
	 * "FAILED"/"REJECTED"/"REJECTED_NOT_CHECKED" as failure — matching the
	 * status vocabulary from NOWPayments' own payout endpoint
	 * documentation, which differs from the deposit side's lowercase
	 * waiting/confirming/finished/etc. vocabulary.
	 *
	 * Always returns 200 regardless of internal outcome — same pattern as
	 * every other provider callback here.
	 */
	@PostMapping("/nowpayments/webhook")
	public ResponseEntity<Void> nowPaymentsWebhook(@RequestBody String rawBody,
			@RequestHeader(value = "x-nowpayments-sig", required = false) String signature) {

		if (!nowPaymentsService.verifyIpnSignature(rawBody, signature)) {
			log.warn("NOWPayments webhook rejected — x-nowpayments-sig mismatch or not configured");
			return ResponseEntity.status(400).build();
		}

		try {
			JsonNode data = objectMapper.readTree(rawBody);

			if (data.has("payment_id")) {
				// ── Deposit callback — confirmed shape ──
				String orderId = data.path("order_id").asString(null);
				String paymentId = data.path("payment_id").asString(null);
				String status = data.path("payment_status").asString("");

				log.info("NOWPayments deposit webhook received (verified): orderId={} paymentId={} status={}",
						orderId, paymentId, status);

				if (orderId == null || orderId.isBlank()) {
					log.warn("NOWPayments deposit webhook missing order_id — cannot reconcile");
				} else if ("finished".equals(status)) {
					nowPaymentsDepositService.creditWalletFromNowPaymentsCallback(orderId, paymentId);
				} else if ("failed".equals(status) || "expired".equals(status) || "refunded".equals(status)) {
					nowPaymentsDepositService.markNowPaymentsTransactionFailed(orderId, "NOWPayments reported status=" + status);
				} else if ("partially_paid".equals(status)) {
					nowPaymentsDepositService.flagNowPaymentsPartialPayment(orderId, paymentId);
				}
				// waiting / confirming / confirmed / sending — still pending, no action.

			} else {
				// ── Payout callback — field names NOT independently
				// confirmed, see javadoc above. Falls back across a few
				// plausible names for the payout id.
				String payoutId = data.path("id").asString(null);
				if (payoutId == null || payoutId.isBlank()) {
					payoutId = data.path("payout_id").asString(null);
				}
				if (payoutId == null || payoutId.isBlank()) {
					payoutId = data.path("withdrawal_id").asString(null);
				}

				String status = data.path("status").asString("");

				log.info("NOWPayments payout webhook received (verified): payoutId={} status={}", payoutId, status);

				if (payoutId == null || payoutId.isBlank()) {
					log.warn("NOWPayments payout webhook missing an identifiable payout id field — cannot reconcile. Raw body logged separately via CallbackRequestLoggingFilter — inspect it to find the real field name.");
				} else if ("FINISHED".equalsIgnoreCase(status)) {
					nowPaymentsDisbursementService.completeNowPaymentsDisbursement(payoutId, true, null);
				} else if ("FAILED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)
						|| "REJECTED_NOT_CHECKED".equalsIgnoreCase(status)) {
					nowPaymentsDisbursementService.completeNowPaymentsDisbursement(payoutId, false, "NOWPayments reported status=" + status);
				}
				// NEW / CREATING / WAITING / PROCESSING — still pending, no action.
			}

		} catch (Exception e) {
			log.error("NOWPayments webhook processing error: {}", e.getMessage(), e);
		}
		return ResponseEntity.ok().build();
	}

	// ─── Helpers ─────────────────────────────────────────────────────────────

	private Map<String, Object> extractResultParameters(MpesaResultCallbackRequest.Result result) {
		Map<String, Object> map = new HashMap<>();
		if (result.getResultParameters() != null && result.getResultParameters().getResultParameter() != null) {
			for (MpesaResultCallbackRequest.ResultParameter param : result.getResultParameters().getResultParameter()) {
				map.put(param.getKey(), param.getValue());
			}
		}
		return map;
	}
}