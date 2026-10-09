package com.premisave.wallet.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders and sends transactional emails (deposit confirmations,
 * disbursement success/failure, transfers, payments) from plain HTML
 * templates stored as classpath resources under templates/email/.
 *
 * Every public method here runs asynchronously (@Async, enabled via
 * AsyncConfig) — sending an email involves a real SMTP round-trip, and
 * without this, that latency would sit directly in the HTTP response
 * time for whatever action triggered it. The caller gets its own
 * response back immediately; the email is dispatched on a separate
 * thread shortly after.
 *
 * Templates use simple {{placeholder}} string substitution, not a real
 * templating engine — deliberate, see earlier reasoning. To still
 * support OPTIONAL detail rows (gateway, exchange rate, sender/recipient
 * name, account info, provider receipt) without a real conditional
 * syntax, each template has a single {{extraRows}} placeholder that this
 * class populates with zero or more pre-built <tr> HTML fragments — see
 * row()/rows() below. A row whose value is null/blank contributes
 * nothing at all, so the placeholder is simply empty when a piece of
 * data doesn't apply (e.g. no exchange rate for a USD-native gateway).
 *
 * NO MASKING — every value (phone number, email, contact info) is shown
 * in full, exactly as the caller supplies it.
 *
 * NAMES: DepositDetails/DisbursementDetails below carry senderName and
 * recipientName/senderName respectively, resolved via UserNameResolver
 * (a thin wrapper over the auth service). Deliberately ONLY the resolved
 * name is ever passed in here — never active, verified, or role from the
 * auth service's response; those fields are never even seen by this
 * class, let alone rendered in an email or saved anywhere via it.
 *
 * Every send is wrapped in try/catch and only logged on failure — a
 * failed email must NEVER fail the transaction it's notifying about.
 *
 * Depends on spring-boot-starter-mail being on the classpath — this
 * config already exists in application.yml (spring.mail.*), which
 * implies the dependency is present, but that wasn't independently
 * confirmed by viewing pom.xml/build.gradle directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    private static final String TEMPLATE_PATH = "templates/email/";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a");

    /** Matches every existing detail row's exact styling in every template — see class javadoc on {{extraRows}}. */
    private static final String ROW_HTML =
            "<tr><td style=\"padding: 10px 0; color:#6b7280; border-bottom: 1px solid #F0F1F3;\">%s</td>"
                    + "<td style=\"padding: 10px 0; color:#1a1a1a; font-weight:600; text-align:right; border-bottom: 1px solid #F0F1F3;\">%s</td></tr>";

    /**
     * Optional extra detail rows for a deposit confirmation — bundled
     * into one record rather than a long, ever-growing positional
     * parameter list. Any field left null simply doesn't render as a row.
     *
     * gateway: provider name (e.g. "M-Pesa") — null if not applicable.
     * exchangeRateInfo: pre-formatted, e.g. "1 KES = 0.0077 USD" — null
     * when no conversion applied (a USD-native gateway).
     * rawSource: phone number / email the deposit came from, shown in
     * full — null if not applicable.
     * senderName: the PAYING party's name, when the gateway's own
     * callback provides one (confirmed for M-Pesa C2B) — null for a
     * self-deposit or a gateway with no equivalent field.
     * providerReference: the gateway's OWN transaction id/receipt number
     * — distinct from the "reference" argument, which is Premisave's own
     * internal tracking id (for M-Pesa STK specifically, that's the long
     * checkoutRequestId the customer never sees; this is the short
     * receipt number their own M-Pesa SMS shows them).
     * recipientName/accountNumber/accountId: the WALLET OWNER's own
     * identity — resolved via UserNameResolver and read directly off the
     * Wallet entity, respectively.
     */
    public record DepositDetails(
            String gateway, String exchangeRateInfo, String rawSource,
            String senderName, String providerReference,
            String recipientName, String accountNumber, String accountId) {
        public static DepositDetails empty() {
            return new DepositDetails(null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Optional extra detail rows for a disbursement email.
     * senderName/accountNumber/accountId: the WALLET OWNER's own identity
     * (they're the one sending money out, for a disbursement).
     */
    public record DisbursementDetails(
            String gateway, String exchangeRateInfo,
            String senderName, String accountNumber, String accountId) {
        public static DisbursementDetails empty() {
            return new DisbursementDetails(null, null, null, null, null);
        }
    }

    /**
     * @Async here (and on every other public method in this class) — NOT
     * on the private send() helper below, since @Async only takes effect
     * on calls that go through Spring's proxy, and send() is only ever
     * called from within this same class (a self-invocation, which
     * bypasses the proxy entirely and would make @Async silently do
     * nothing). Requires AsyncConfig's @EnableAsync to actually take
     * effect at all.
     */
    @Async
    public void sendDepositConfirmation(String toEmail, String amount, String currency,
                                         String reference, String newBalance, DepositDetails details) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("reference", reference);
        vars.put("newBalance", newBalance);
        vars.put("extraRows", rows(
                "Payment Gateway", details.gateway(),
                "Exchange Rate Used", details.exchangeRateInfo(),
                "Sender Name", details.senderName(),
                "Paid From", details.rawSource(),
                "Provider Receipt", details.providerReference(),
                "Recipient Name", details.recipientName(),
                "Account Number", details.accountNumber(),
                "Account ID", details.accountId()
        ));
        send(toEmail, "Deposit Confirmation - Premisave", "deposit-confirmation-email.html", vars);
    }

    /** destination: shown exactly as given, no masking. */
    @Async
    public void sendDisbursementSuccess(String toEmail, String amount, String currency,
                                         String destination, String reference, DisbursementDetails details) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("destination", destination);
        vars.put("reference", reference);
        vars.put("extraRows", rows(
                "Payment Gateway", details.gateway(),
                "Exchange Rate Used", details.exchangeRateInfo(),
                "Sender Name", details.senderName(),
                "Account Number", details.accountNumber(),
                "Account ID", details.accountId()
        ));
        send(toEmail, "Withdrawal Successful - Premisave", "disbursement-success-email.html", vars);
    }

    /**
     * gateway/destination in DisbursementDetails let a failure email
     * still tell the customer which withdrawal attempt this was about,
     * even though nothing was actually debited. destination shown
     * exactly as given, no masking.
     */
    @Async
    public void sendDisbursementFailed(String toEmail, String amount, String currency, String reason,
                                        String destination, DisbursementDetails details) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("reason", reason);
        vars.put("extraRows", rows(
                "Payment Gateway", details.gateway(),
                "Intended Destination", destination,
                "Sender Name", details.senderName(),
                "Account Number", details.accountNumber(),
                "Account ID", details.accountId()
        ));
        send(toEmail, "Disbursement Failed - Premisave", "disbursement-failed-email.html", vars);
    }

    /**
     * Sent to BOTH parties of an internal wallet-to-wallet transfer, once
     * each, with different wording per direction — one shared template
     * (transfer-notification-email.html) rather than two near-duplicate
     * files, since the only real difference is a handful of labels, not
     * the overall layout.
     *
     * counterpartyEmail shown exactly as given, no masking.
     * counterpartyName: the other party's full name, resolved via
     * UserNameResolver by the caller (TransferService) — may still be
     * null/blank if that lookup failed or the account has no name on
     * file; the row simply doesn't render in that case.
     *
     * @param isSenderCopy true for the copy sent to whoever sent the
     *                      money, false for the copy sent to whoever
     *                      received it.
     */
    @Async
    public void sendTransferNotification(String toEmail, String amount, String currency,
                                          String counterpartyEmail, String counterpartyName,
                                          String reference, boolean isSenderCopy) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("counterpartyEmail", counterpartyEmail);
        vars.put("reference", reference);
        vars.put("extraRows", rows(
                isSenderCopy ? "Recipient Name" : "Sender Name", counterpartyName
        ));

        if (isSenderCopy) {
            vars.put("headline", "Money Sent");
            vars.put("amountLabel", "Amount Sent");
            vars.put("counterpartyLabel", "Sent To");
        } else {
            vars.put("headline", "Money Received");
            vars.put("amountLabel", "Amount Received");
            vars.put("counterpartyLabel", "Received From");
        }

        String subject = (isSenderCopy ? "Transfer Sent" : "Transfer Received") + " - Premisave";
        send(toEmail, subject, "transfer-notification-email.html", vars);
    }

    /**
     * For a Payment (wallet deducted for a service — e.g. a booking fee),
     * not a Disbursement — genuinely different wording, hence its own
     * template rather than reusing disbursement-success-email.html's
     * static "Withdrawal Successful" text. No gateway/exchange-rate
     * fields — this is an internal deduction with no external gateway
     * involved. senderName/accountNumber/accountId are the wallet
     * owner's own identity (they're the one paying).
     */
    @Async
    public void sendPaymentConfirmation(String toEmail, String amount, String currency,
                                         String service, String reference,
                                         String senderName, String accountNumber, String accountId) {
        Map<String, String> vars = new HashMap<>();
        vars.put("amount", amount);
        vars.put("currency", currency);
        vars.put("service", service);
        vars.put("reference", reference);
        vars.put("extraRows", rows(
                "Sender Name", senderName,
                "Account Number", accountNumber,
                "Account ID", accountId
        ));
        send(toEmail, "Payment Confirmation - Premisave", "payment-confirmation-email.html", vars);
    }

    private void send(String toEmail, String subject, String templateFile, Map<String, String> vars) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skipping email '{}' — no recipient email address available", subject);
            return;
        }

        // Added automatically here, not by each caller — the moment the
        // email is actually dispatched, not when the underlying
        // transaction happened (those can differ slightly if sending is
        // ever delayed/retried), and this guarantees every template gets
        // it consistently rather than relying on each call site to
        // remember to pass one.
        vars.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT));

        try {
            String html = loadTemplate(templateFile);
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                html = html.replace("{{" + entry.getKey() + "}}", value);
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Email sent: subject='{}' to={}", subject, toEmail);
        } catch (Exception e) {
            // Deliberately caught broadly and only logged — see class javadoc.
            log.error("Failed to send email: subject='{}' to={}", subject, toEmail, e);
        }
    }

    private String loadTemplate(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH + filename);
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ─── Optional-row building (see class javadoc on {{extraRows}}) ────────

    /** Builds zero or more <tr> rows from label/value pairs, in order, skipping any pair whose value is null/blank entirely — no empty row ever renders. */
    private static String rows(String... labelValuePairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labelValuePairs.length - 1; i += 2) {
            sb.append(row(labelValuePairs[i], labelValuePairs[i + 1]));
        }
        return sb.toString();
    }

    private static String row(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return String.format(ROW_HTML, label, value);
    }
}