package com.premisave.wallet.exception;

/**
 * Thrown when Safaricom rejects a C2B Register URL call because URLs are
 * already on file for this shortcode (observed error code 500.003.1001,
 * "Duplicate notification info"). This is a real, well-understood
 * Safaricom state — not a system fault — and deserves its own typed
 * exception rather than falling through MpesaC2BService's generic
 * RuntimeException path into GlobalExceptionHandler's catch-all, which
 * previously surfaced this as a bare 500 "An unexpected error occurred"
 * with no indication of what actually went wrong or how to fix it.
 *
 * Fix: delete the existing C2B URLs for this shortcode first (see
 * c2b_url_deletion.java) before retrying registration — Safaricom
 * doesn't support overwriting an existing registration in place.
 */
@SuppressWarnings("serial")
public class C2BUrlsAlreadyRegisteredException extends RuntimeException {
    public C2BUrlsAlreadyRegisteredException(String message) {
        super(message);
    }
}