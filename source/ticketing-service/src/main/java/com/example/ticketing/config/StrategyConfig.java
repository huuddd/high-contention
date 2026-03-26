package com.example.ticketing.config;

import com.example.ticketing.common.RetryWithBackoff;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.SeatRepository;
import com.example.ticketing.ticket.TicketRepository;
import com.example.ticketing.ticket.strategy.SerializableTicketingStrategy;
import com.example.ticketing.ticket.strategy.TicketingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * StrategyConfig — Spring configuration that wires the active TicketingStrategy.
 *
 * <p><b>Problem:</b> Multiple strategy implementations exist on different branches.
 * The active strategy must be selected at startup via configuration, not code changes.
 *
 * <p><b>Mechanism:</b> {@code @ConditionalOnProperty} inspects
 * {@code ticketing.strategy} in application.yml. Only the matching bean is created.
 *
 * <p><b>Design pattern:</b> Factory + Conditional Bean — Spring-native approach
 * to Strategy Pattern wiring.
 *
 * <p><b>Scalability:</b> Adding a new strategy = adding one more @Bean method.
 * Zero impact on existing strategies.
 */
@Configuration
public class StrategyConfig {

    private static final Logger log = LoggerFactory.getLogger(StrategyConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ticketing.strategy", havingValue = "serializable", matchIfMissing = true)
    public TicketingStrategy serializableTicketingStrategy(EventRepository eventRepository,
                                                            SeatRepository seatRepository,
                                                            TicketRepository ticketRepository,
                                                            ConflictMetrics metrics,
                                                            RetryWithBackoff retryWithBackoff) {
        log.info(">>> Active strategy: SERIALIZABLE (PostgreSQL SSI + Retry Backoff)");
        return new SerializableTicketingStrategy(eventRepository, seatRepository, ticketRepository,
                metrics, retryWithBackoff);
    }
}
