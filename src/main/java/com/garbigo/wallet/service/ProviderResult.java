package com.premisave.wallet.service;

/**
 * Shared result shape returned by each provider-specific disbursement
 * service's initiation method (StripeDisbursementService.disburseStripe,
 * PaypalDisbursementService.disbursePaypal,
 * NowPaymentsDisbursementService.disburseNowPayments) back to
 * DisbursementService.processDisbursement, which owns the actual
 * Disbursement record creation and PENDING-status handling shared
 * identically across all three — see processDisbursement's javadoc for
 * why that shared logic stays centralized rather than being duplicated
 * three times over.
 *
 * Extracted to its own file specifically to avoid a circular dependency:
 * if this stayed a private nested type inside DisbursementService (as it
 * was before this split), every provider-specific service returning one
 * would need to depend on DisbursementService itself, which already
 * depends on THEM (to call their disburseX methods) — a two-way
 * dependency Spring can only resolve awkwardly (setter injection, @Lazy),
 * and one this codebase has deliberately avoided everywhere else.
 */
public record ProviderResult(boolean success, String message, String providerRef) {
	
}