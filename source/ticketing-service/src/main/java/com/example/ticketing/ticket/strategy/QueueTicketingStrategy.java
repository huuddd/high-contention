package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.queue.PerEventQueueService;
import com.example.ticketing.queue.QueueTicketRequest;
import com.example.ticketing.queue.QueueWorker;
import com.example.ticketing.ticket.TicketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * QueueTicketingStrategy — enqueues requests into a per-event Redis queue and waits
 * for a worker to process them. Implements sync-over-async via CompletableFuture.
 *
 * <p><b>Problem:</b> Strategies A-D cause DB-level contention (locks, retries, conflicts).
 * Under extreme load (flash sale), DB becomes the bottleneck.
 *
 * <p><b>Mechanism:</b> Producer-Consumer pattern:
 * <ol>
 *   <li>HTTP thread enqueues request to Redis List (LPUSH, O(1), ~0.1ms)</li>
 *   <li>HTTP thread waits on CompletableFuture (with timeout)</li>
 *   <li>Worker thread dequeues (BRPOP) and processes DB write (zero contention)</li>
 *   <li>Worker completes the future → HTTP thread returns result</li>
 * </ol>
 *
 * <p><b>Design pattern:</b> Implements {@link TicketingStrategy} — same interface as
 * strategies A-D, so TicketController works without modification. Internally delegates
 * to {@link PerEventQueueService} (Redis queue) and {@link QueueWorker} (consumer).
 *
 * <p><b>Scalability:</b> Queue absorbs burst traffic. Worker processes at steady rate.
 * Back-pressure via queue size limit. Throughput per event = 1/T (DB transaction time).
 * Total throughput scales with number of events (per-event parallelism).
 */
public class QueueTicketingStrategy implements TicketingStrategy {

    private static final Logger log = LoggerFactory.getLogger(QueueTicketingStrategy.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final PerEventQueueService queueService;
    private final QueueWorker queueWorker;
    private final ConflictMetrics metrics;

    public QueueTicketingStrategy(PerEventQueueService queueService,
                                    QueueWorker queueWorker,
                                    ConflictMetrics metrics) {
        this.queueService = queueService;
        this.queueWorker = queueWorker;
        this.metrics = metrics;
    }

    @Override
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel, String idempotencyKey) {
        // Bước 1: Tạo unique request ID + CompletableFuture
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<TicketResult> future = queueWorker.registerPending(requestId);

        // Bước 2: Tạo request DTO
        QueueTicketRequest request = new QueueTicketRequest(requestId, eventId, userId, seatLabel);

        // Bước 3: Enqueue vào Redis (O(1), ~0.1ms)
        boolean enqueued = queueService.enqueue(request);
        if (!enqueued) {
            // Queue đầy — back-pressure
            future.cancel(true);
            metrics.recordFailure(strategyName());
            log.warn("Queue: back-pressure — queue full for event={}", eventId);
            return TicketResult.error(eventId, seatLabel, "Queue full — please try again later");
        }

        // Bước 4: Chờ worker xử lý (sync-over-async)
        try {
            TicketResult result = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result;

        } catch (TimeoutException e) {
            // Worker chưa xử lý kịp — request vẫn trong queue
            future.cancel(true);
            metrics.recordTimeout(strategyName());
            metrics.recordFailure(strategyName());
            log.warn("Queue: timeout waiting for worker, event={}, seat={}, requestId={}",
                    eventId, seatLabel, requestId);
            return TicketResult.error(eventId, seatLabel,
                    "Request queued but processing timed out — check status later");

        } catch (Exception e) {
            future.cancel(true);
            metrics.recordFailure(strategyName());
            log.error("Queue: error waiting for result, event={}, seat={}: {}",
                    eventId, seatLabel, e.getMessage(), e);
            return TicketResult.error(eventId, seatLabel, e.getMessage());
        }
    }

    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_QUEUE;
    }
}
