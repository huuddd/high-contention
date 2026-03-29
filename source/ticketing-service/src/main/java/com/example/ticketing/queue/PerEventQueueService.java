package com.example.ticketing.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Optional;

/**
 * PerEventQueueService — Redis-backed per-event queue for serializing ticket purchases.
 *
 * <p><b>Problem:</b> All previous strategies handle contention at the DB level, causing
 * lock contention, retry storms, or connection pool exhaustion under extreme load.
 *
 * <p><b>Mechanism:</b> Each event has its own Redis List (queue). Producers LPUSH requests;
 * a single worker BRPOP-s and processes them one at a time. This eliminates DB-level
 * contention entirely — the database receives exactly one write per ticket, zero conflicts.
 *
 * <p><b>Design pattern:</b> Producer-Consumer with per-key partitioning. Redis List provides
 * atomic LPUSH/BRPOP, FIFO ordering, and O(1) enqueue/dequeue.
 *
 * <p><b>Scalability:</b> Throughput per event = 1/T (one worker). Throughput across events
 * scales linearly — N events = N parallel workers = N/T total TPS. Redis handles
 * millions of LPUSH/s, so the bottleneck is DB transaction time, not the queue.
 */
@Service
public class PerEventQueueService {

    private static final Logger log = LoggerFactory.getLogger(PerEventQueueService.class);
    private static final String QUEUE_PREFIX = "queue:event:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long maxQueueSize;

    public PerEventQueueService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${ticketing.queue.max-size:10000}") long maxQueueSize) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxQueueSize = maxQueueSize;
    }

    @PostConstruct
    public void init() {
        try {
            redisTemplate.opsForValue().set("health:check", "ok", Duration.ofSeconds(10));
            log.info("Redis connection OK - Queue service ready");
        } catch (Exception e) {
            log.error("Redis connection FAILED: {}", e.getMessage(), e);
        }
    }

    /**
     * Enqueue a ticket purchase request. O(1) via Redis LPUSH.
     *
     * @return true if enqueued, false if queue full (back-pressure)
     */
    public boolean enqueue(QueueTicketRequest request) {
        String key = queueKey(request.eventId());

        try {
            // Back-pressure check
            Long currentSize = redisTemplate.opsForList().size(key);
            if (currentSize != null && currentSize >= maxQueueSize) {
                log.warn("Queue full for event={}, size={}, max={}", request.eventId(), currentSize, maxQueueSize);
                return false;
            }

            String json = objectMapper.writeValueAsString(request);
            redisTemplate.opsForList().leftPush(key, json);
            log.debug("Enqueued request {} for event={}, seat={}",
                    request.requestId(), request.eventId(), request.seatLabel());
            return true;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize queue request: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Redis error during enqueue for event={}: {}", request.eventId(), e.getMessage());
            return false;
        }
    }

    /**
     * Dequeue next request. Blocks up to timeout if queue is empty.
     * Uses Redis BRPOP for efficient blocking wait.
     *
     * @return the next request, or empty if timeout/queue empty
     */
    public Optional<QueueTicketRequest> dequeue(Long eventId, Duration timeout) {
        String key = queueKey(eventId);

        try {
            String json = redisTemplate.opsForList().rightPop(key, timeout);
            if (json == null) {
                return Optional.empty();
            }
            QueueTicketRequest request = objectMapper.readValue(json, QueueTicketRequest.class);
            log.debug("Dequeued request {} for event={}", request.requestId(), eventId);
            return Optional.of(request);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize queue request: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get current queue size for an event — used for stats/monitoring.
     */
    public long getQueueSize(Long eventId) {
        Long size = redisTemplate.opsForList().size(queueKey(eventId));
        return size != null ? size : 0;
    }

    private String queueKey(Long eventId) {
        return QUEUE_PREFIX + eventId;
    }
}
