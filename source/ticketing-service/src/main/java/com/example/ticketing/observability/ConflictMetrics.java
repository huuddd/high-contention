package com.example.ticketing.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * ConflictMetrics — centralized Micrometer counters for concurrency conflict tracking.
 *
 * <p><b>Problem:</b> Under high contention, we need quantitative data to compare
 * strategies: how many conflicts, retries, deadlocks, and timeouts each strategy
 * produces under the same load. Without metrics, benchmark analysis is guesswork.
 *
 * <p><b>Mechanism:</b> Micrometer counters with strategy tag — each increment is
 * O(1) and lock-free (atomic CAS). Counters are scraped by Prometheus via
 * /actuator/prometheus endpoint.
 *
 * <p><b>Design pattern:</b> Centralized metrics facade — all strategies call the
 * same ConflictMetrics instance, ensuring consistent metric names and tags.
 *
 * <p><b>Scalability:</b> Micrometer counters use atomic operations, adding
 * negligible overhead (&lt;1μs per increment) even under high concurrency.
 */
@Component
public class ConflictMetrics {

    private static final String TAG_STRATEGY = "strategy";

    private final MeterRegistry registry;

    public ConflictMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Ghi nhận 1 conflict (OCC version mismatch, serialization failure). */
    public void recordConflict(String strategy) {
        Counter.builder("ticketing.conflicts")
                .description("Number of concurrency conflicts detected")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }

    /** Ghi nhận 1 retry attempt. */
    public void recordRetry(String strategy) {
        Counter.builder("ticketing.retries")
                .description("Number of retry attempts")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }

    /** Ghi nhận 1 deadlock (PSQLException state 40P01). */
    public void recordDeadlock(String strategy) {
        Counter.builder("ticketing.deadlocks")
                .description("Number of deadlocks detected")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }

    /** Ghi nhận 1 timeout (lock wait hoặc connection timeout). */
    public void recordTimeout(String strategy) {
        Counter.builder("ticketing.timeouts")
                .description("Number of timeouts (lock wait or connection)")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }

    /** Ghi nhận 1 oversell detected (should NEVER happen if strategy is correct). */
    public void recordOversell(String strategy) {
        Counter.builder("ticketing.oversells")
                .description("Number of oversell incidents detected (should be 0)")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }

    /** Ghi nhận 1 successful ticket purchase. */
    public void recordSuccess(String strategy) {
        Counter.builder("ticketing.successes")
                .description("Number of successful ticket purchases")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }

    /** Ghi nhận 1 failed request (sau max retries hoặc business rule failure). */
    public void recordFailure(String strategy) {
        Counter.builder("ticketing.failures")
                .description("Number of failed ticket purchase attempts")
                .tag(TAG_STRATEGY, strategy)
                .register(registry)
                .increment();
    }
}
