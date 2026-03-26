package com.example.ticketing.common;

import com.example.ticketing.observability.ConflictMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * RetryWithBackoff — generic retry with exponential backoff and optional jitter.
 *
 * <p><b>Problem:</b> Under high contention, OCC version conflicts and SERIALIZABLE
 * serialization failures are expected. Without retry, every conflict = failed request.
 * Without backoff, immediate retries cause thundering herd — all threads retry
 * simultaneously, increasing contention instead of reducing it.
 *
 * <p><b>Mechanism:</b> Exponential backoff doubles the wait time after each failure.
 * Jitter adds randomness to desynchronize concurrent retries.
 * Formula: delay = min(initialDelay * multiplier^attempt + random(0, delay/2), maxDelay)
 *
 * <p><b>Design pattern:</b> Template Method via functional interface — callers pass
 * a {@code Supplier<T>} containing the retriable operation. The retry loop is
 * encapsulated here, keeping strategy code clean.
 *
 * <p><b>Scalability:</b> With 5 retries and 2x multiplier: max total wait ~3.1s.
 * Beyond that, consider queue-based approach (2.F) instead of retry storms.
 */
@Component
public class RetryWithBackoff {

    private static final Logger log = LoggerFactory.getLogger(RetryWithBackoff.class);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double multiplier;
    private final boolean jitterEnabled;
    private final ConflictMetrics metrics;

    public RetryWithBackoff(
            @Value("${ticketing.retry.max-attempts:5}") int maxAttempts,
            @Value("${ticketing.retry.initial-delay-ms:50}") long initialDelayMs,
            @Value("${ticketing.retry.max-delay-ms:2000}") long maxDelayMs,
            @Value("${ticketing.retry.multiplier:2.0}") double multiplier,
            @Value("${ticketing.retry.jitter:true}") boolean jitterEnabled,
            ConflictMetrics metrics) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.multiplier = multiplier;
        this.jitterEnabled = jitterEnabled;
        this.metrics = metrics;
    }

    /**
     * Execute a retriable operation with exponential backoff.
     *
     * @param operation    the operation to retry
     * @param retryOn      exception class that triggers retry
     * @param strategyName strategy name for metrics tagging
     * @param <T>          return type
     * @return result of the operation
     * @throws E if max retries exceeded, rethrows the last exception
     */
    public <T, E extends Exception> T execute(
            Supplier<T> operation,
            Class<E> retryOn,
            String strategyName) throws E {

        int attempt = 0;
        E lastException = null;

        while (attempt < maxAttempts) {
            try {
                return operation.get();
            } catch (Exception e) {
                if (!retryOn.isInstance(e)) {
                    // Không phải exception cần retry → ném lại ngay
                    throw e;
                }

                @SuppressWarnings("unchecked")
                E typedException = (E) e;
                lastException = typedException;
                attempt++;

                // Ghi nhận conflict vào metrics
                metrics.recordConflict(strategyName);
                metrics.recordRetry(strategyName);

                if (attempt >= maxAttempts) {
                    // Đã hết số lần retry cho phép
                    log.warn("Max retries ({}) exceeded for strategy={}, last error: {}",
                            maxAttempts, strategyName, e.getMessage());
                    break;
                }

                // Tính delay với exponential backoff + jitter
                long delay = calculateDelay(attempt);
                log.debug("Retry {}/{} for strategy={}, waiting {}ms. Cause: {}",
                        attempt, maxAttempts, strategyName, delay, e.getMessage());

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw typedException;
                }
            }
        }

        // Ném exception cuối cùng sau khi hết retry
        throw lastException;
    }

    /**
     * Tính delay cho lần retry thứ n.
     * Formula: min(initialDelay * multiplier^(attempt-1) + jitter, maxDelay)
     */
    long calculateDelay(int attempt) {
        // Exponential: 50 → 100 → 200 → 400 → 800...
        long baseDelay = (long) (initialDelayMs * Math.pow(multiplier, attempt - 1));

        // Cap tại maxDelay để tránh chờ quá lâu
        baseDelay = Math.min(baseDelay, maxDelayMs);

        if (jitterEnabled) {
            // Full jitter (AWS recommended): random(0, baseDelay)
            // Giúp desynchronize các threads đang retry cùng lúc
            long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, baseDelay / 2));
            return baseDelay + jitter;
        }

        return baseDelay;
    }
}
