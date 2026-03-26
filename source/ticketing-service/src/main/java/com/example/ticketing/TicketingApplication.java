package com.example.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TicketingApplication — entry point for the High Contention Ticketing Service.
 *
 * <p><b>Problem:</b> Concurrent ticket purchases for scarce resources (limited seats)
 * lead to race conditions, overselling, and double-booking without proper concurrency control.
 *
 * <p><b>Mechanism:</b> This application implements 6 concurrency strategies (A-F),
 * each on a separate Git branch, to demonstrate different approaches to handling
 * write contention on a shared PostgreSQL database.
 *
 * <p><b>Design pattern:</b> Strategy Pattern — each concurrency approach implements
 * {@code TicketingStrategy} interface, allowing runtime switching without modifying controllers.
 *
 * <p><b>Scalability:</b> Each strategy has different throughput characteristics.
 * Benchmarked with k6 under 3 scenarios: Stock=1, Hot-seat, and Burst.
 */
@SpringBootApplication
@EnableScheduling
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
