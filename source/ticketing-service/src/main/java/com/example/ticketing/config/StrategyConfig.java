package com.example.ticketing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StrategyConfig — Spring configuration for the Reservation + Fencing Token strategy.
 *
 * <p><b>Problem:</b> The reservation strategy (2.E) uses a different architecture than
 * strategies A-D. Instead of a single TicketingStrategy bean, it uses ReservationService
 * with separate reserve/confirm/cancel endpoints + a background expiry job.
 *
 * <p><b>Mechanism:</b> Enables scheduling for the ReservationExpiryJob.
 * ReservationService, ReservationController, and ReservationExpiryJob are component-scanned
 * automatically via @Service, @RestController, and @Component annotations.
 *
 * <p><b>Design pattern:</b> Configuration class enabling Spring features.
 *
 * <p><b>Scalability:</b> EnableScheduling uses a single thread by default.
 * For production, configure a thread pool for concurrent job execution.
 */
@Configuration
@EnableScheduling
public class StrategyConfig {

    private static final Logger log = LoggerFactory.getLogger(StrategyConfig.class);

    public StrategyConfig() {
        log.info(">>> Active strategy: RESERVATION + FENCING TOKEN (2-step: reserve → confirm)");
    }
}
