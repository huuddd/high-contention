package com.example.ticketing.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TicketingStatsService — aggregates Micrometer counter values for observability.
 *
 * <p><b>Problem:</b> Micrometer counters are scattered across the registry.
 * The stats endpoint needs a unified view of all conflict-related metrics
 * filtered by the active strategy.
 *
 * <p><b>Mechanism:</b> Queries MeterRegistry by counter name + strategy tag.
 * Returns a Map of metric name → current value. Counter.count() is O(1).
 *
 * <p><b>Design pattern:</b> Service — encapsulates metric aggregation logic,
 * keeping the controller thin.
 *
 * <p><b>Scalability:</b> Read-only access to atomic counters — no contention.
 */
@Service
public class TicketingStatsService {

    private final MeterRegistry registry;
    private final String activeStrategy;

    public TicketingStatsService(
            MeterRegistry registry,
            @Value("${ticketing.strategy:naive}") String activeStrategy) {
        this.registry = registry;
        this.activeStrategy = activeStrategy;
    }

    /**
     * Get the active strategy name.
     */
    public String getActiveStrategy() {
        return activeStrategy;
    }

    /**
     * Aggregate all conflict metrics for the active strategy.
     *
     * @return map of metric name → current counter value
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("conflicts", getCounterValue("ticketing.conflicts"));
        metrics.put("retries", getCounterValue("ticketing.retries"));
        metrics.put("deadlocks", getCounterValue("ticketing.deadlocks"));
        metrics.put("timeouts", getCounterValue("ticketing.timeouts"));
        metrics.put("oversells", getCounterValue("ticketing.oversells"));
        metrics.put("successes", getCounterValue("ticketing.successes"));
        metrics.put("failures", getCounterValue("ticketing.failures"));
        return metrics;
    }

    /**
     * Read a single counter value filtered by active strategy tag.
     * Returns 0 if counter has not been registered yet.
     */
    private long getCounterValue(String counterName) {
        Counter counter = registry.find(counterName)
                .tag("strategy", activeStrategy)
                .counter();
        return counter != null ? (long) counter.count() : 0L;
    }
}
