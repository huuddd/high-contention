package com.example.ticketing.queue;

import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * QueueWorker — consumer that processes enqueued ticket purchase requests.
 *
 * <p><b>Problem:</b> Requests in the per-event Redis queue need to be processed
 * one at a time per event. The worker dequeues, performs the DB write, and completes
 * the CompletableFuture so the HTTP thread can return the result.
 *
 * <p><b>Mechanism:</b> A background thread per known event continuously BRPOP-s from
 * the Redis queue and processes each request in a separate DB transaction. Since only
 * one worker processes per event, there is ZERO DB contention — no locks, no retries,
 * no conflicts. The DB receives exactly one write per ticket.
 *
 * <p><b>Design pattern:</b> Consumer in Producer-Consumer pattern. Uses
 * ConcurrentHashMap for pending futures and ExecutorService for worker threads.
 *
 * <p><b>Scalability:</b> Throughput = 1/T per event (T = DB transaction time).
 * For N events, N workers run in parallel. Worker count is bounded by the thread pool.
 */
@Component
public class QueueWorker {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    private final PerEventQueueService queueService;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;

    /**
     * Pending futures map: requestId → CompletableFuture.
     * Producer puts, consumer removes + completes.
     */
    private final ConcurrentHashMap<String, CompletableFuture<TicketResult>> pendingRequests =
            new ConcurrentHashMap<>();

    private ExecutorService workerPool;
    private volatile boolean running = true;

    @Value("${ticketing.queue.worker-threads:4}")
    private int workerThreads;

    @Value("${ticketing.queue.poll-timeout-seconds:5}")
    private int pollTimeoutSeconds;

    @Value("${ticketing.queue.default-event-id:1}")
    private long defaultEventId;

    public QueueWorker(PerEventQueueService queueService,
                        EventRepository eventRepository,
                        SeatRepository seatRepository,
                        TicketRepository ticketRepository,
                        ConflictMetrics metrics) {
        this.queueService = queueService;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
    }

    @PostConstruct
    public void start() {
        workerPool = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "queue-worker");
            t.setDaemon(true);
            return t;
        });

        // Start worker(s) for the default event (seed data has event ID 1)
        // In production, workers would be dynamically created per event
        for (int i = 0; i < workerThreads; i++) {
            final int workerId = i;
            workerPool.submit(() -> workerLoop(defaultEventId, workerId));
        }

        log.info("QueueWorker started: {} threads for event={}", workerThreads, defaultEventId);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("QueueWorker stopped");
    }

    /**
     * Register a pending request — called by QueueTicketingStrategy before enqueue.
     * Returns the CompletableFuture that will be completed by the worker.
     */
    public CompletableFuture<TicketResult> registerPending(String requestId) {
        CompletableFuture<TicketResult> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        return future;
    }

    /**
     * Worker loop — continuously dequeues and processes requests for a specific event.
     * Only ONE worker should process per event to maintain serialization guarantee.
     * Multiple workers here share the same event queue — Redis BRPOP is atomic,
     * so each worker gets a unique item, but they process in parallel.
     * For strict serialization, use workerThreads=1.
     */
    private void workerLoop(Long eventId, int workerId) {
        log.info("Worker-{} started for event={}", workerId, eventId);

        while (running) {
            try {
                Optional<QueueTicketRequest> optRequest =
                        queueService.dequeue(eventId, Duration.ofSeconds(pollTimeoutSeconds));

                if (optRequest.isEmpty()) {
                    continue; // timeout, try again
                }

                QueueTicketRequest request = optRequest.get();
                log.debug("Worker-{} processing request {} for event={}, seat={}",
                        workerId, request.requestId(), eventId, request.seatLabel());

                // Process the ticket purchase — zero DB contention
                TicketResult result = processPurchase(request);

                // Complete the future — HTTP thread receives result
                CompletableFuture<TicketResult> future = pendingRequests.remove(request.requestId());
                if (future != null) {
                    future.complete(result);
                } else {
                    // Future already removed — timeout on HTTP side
                    log.warn("Worker-{}: no pending future for request {} — client may have timed out",
                            workerId, request.requestId());
                }

            } catch (Exception e) {
                if (running) {
                    log.error("Worker-{} error: {}", workerId, e.getMessage(), e);
                    // Sleep briefly to avoid tight error loop
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.info("Worker-{} stopped for event={}", workerId, eventId);
    }

    /**
     * Process a single ticket purchase — the actual DB work.
     * No locking needed — only one worker processes per event at a time.
     * Uses plain SELECT + UPDATE — zero contention.
     */
    @Transactional
    public TicketResult processPurchase(QueueTicketRequest request) {
        try {
            // Bước 1: Read event — no lock needed (single writer)
            Event event = eventRepository.findById(request.eventId()).orElse(null);
            if (event == null) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.eventNotFound(request.eventId());
            }

            // Bước 2: Check available seats
            if (event.getAvailableSeats() <= 0) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.seatNotAvailable(request.eventId(), request.seatLabel());
            }

            // Bước 3: Find seat
            Seat seat = seatRepository.findByEventIdAndSeatLabel(request.eventId(), request.seatLabel())
                    .orElse(null);
            if (seat == null || !seat.isAvailable()) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.seatNotAvailable(request.eventId(), request.seatLabel());
            }

            // Bước 4: Decrement counter — safe: single writer
            int updated = eventRepository.decrementAvailableSeats(request.eventId());
            if (updated == 0) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.seatNotAvailable(request.eventId(), request.seatLabel());
            }

            // Bước 5: Mark seat as SOLD
            int seatUpdated = seatRepository.markAsSold(seat.getId());
            if (seatUpdated == 0) {
                log.error("Queue: seat {} already sold — THIS SHOULD NOT HAPPEN (single writer)",
                        request.seatLabel());
                metrics.recordOversell(TicketingConstants.STRATEGY_QUEUE);
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.conflict(request.eventId(), request.seatLabel(),
                        "Seat sold unexpectedly");
            }

            // Bước 6: Create ticket
            Ticket ticket = new Ticket(event, seat, request.userId(), null);
            ticket = ticketRepository.save(ticket);

            metrics.recordSuccess(TicketingConstants.STRATEGY_QUEUE);
            log.info("Queue: ticket {} created for event={}, seat={}, user={}",
                    ticket.getId(), request.eventId(), request.seatLabel(), request.userId());
            return TicketResult.success(ticket.getId(), request.eventId(),
                    request.seatLabel(), request.userId());

        } catch (DataIntegrityViolationException e) {
            log.warn("Queue: DataIntegrityViolation for event={}, seat={}: {}",
                    request.eventId(), request.seatLabel(), e.getMostSpecificCause().getMessage());
            metrics.recordConflict(TicketingConstants.STRATEGY_QUEUE);
            metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
            return TicketResult.conflict(request.eventId(), request.seatLabel(),
                    "DB constraint: " + e.getMostSpecificCause().getMessage());
        }
    }
}
