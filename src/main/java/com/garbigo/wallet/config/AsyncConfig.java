package com.premisave.wallet.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Provides a dedicated, bounded ThreadPoolTaskExecutor for @Async methods
 * — used specifically by EmailService so that sending a transactional
 * email (deposit/disbursement/transfer/payment notification) never adds
 * SMTP round-trip latency to the HTTP response for the action that
 * triggered it (clicking Transfer, Disburse, etc.).
 *
 * Does NOT declare @EnableAsync here — PremisaveWalletApplication
 * already has it at the application level, confirmed directly from that
 * file. Declaring it again here would be redundant (two @EnableAsync
 * annotations active across two different classes) rather than
 * incorrect, but it's still worth avoiding.
 *
 * Defines a real, bounded ThreadPoolTaskExecutor rather than relying on
 * Spring's default fallback (an unbounded SimpleAsyncTaskExecutor that
 * spawns a brand-new thread per call, with no limit at all) — email
 * volume here should stay modest, but an unbounded executor is still the
 * wrong thing to leave running by default.
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("email-async-");
        executor.initialize();
        return executor;
    }

    /**
     * EmailService's five @Async methods all return void — a void async
     * method has no Future/CompletableFuture for the caller to catch an
     * exception on, so Spring routes any uncaught exception here instead.
     * EmailService.send() already catches everything internally and only
     * logs, so in normal operation this should never actually fire — kept
     * purely as a safety net in case a future change to EmailService ever
     * lets an exception through uncaught.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("Uncaught async exception in {}: {}", method.getName(), throwable.getMessage(), throwable);
    }
}