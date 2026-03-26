package com.example.ticketing.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Duration;

/**
 * IdempotencyFilter — servlet filter that prevents duplicate request processing.
 *
 * <p><b>Problem:</b> Network retries, client-side retry logic, and load balancer
 * failovers can cause the same request to be sent multiple times. Without
 * idempotency, each duplicate creates an additional ticket — violating invariants.
 *
 * <p><b>Mechanism:</b> Uses Redis SET NX (set-if-not-exists) with TTL.
 * First request with a given Idempotency-Key wins; subsequent requests with the
 * same key receive the cached response without re-executing the operation.
 *
 * <p><b>Design pattern:</b> Servlet Filter (Chain of Responsibility) — runs
 * before any controller, transparent to business logic. Uses
 * {@code OncePerRequestFilter} to guarantee single execution per request.
 *
 * <p><b>Scalability:</b> Redis GET/SET is O(1), ~0.1ms per operation.
 * TTL ensures keys don't accumulate indefinitely.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String REDIS_PREFIX = "idempotency:";
    private static final String PROCESSING_MARKER = "__PROCESSING__";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public IdempotencyFilter(
            StringRedisTemplate redisTemplate,
            @Value("${ticketing.idempotency.ttl-seconds:3600}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        // Chỉ áp dụng cho POST requests (mutating operations)
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY_KEY);

        // Không có header → xử lý bình thường (backward compatible)
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String redisKey = REDIS_PREFIX + idempotencyKey;

        // Thử claim key bằng SET NX (atomic) — chỉ 1 request thắng
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, PROCESSING_MARKER, Duration.ofSeconds(ttlSeconds));

        if (Boolean.FALSE.equals(claimed)) {
            // Key đã tồn tại → đây là duplicate request
            String cachedResponse = redisTemplate.opsForValue().get(redisKey);

            if (PROCESSING_MARKER.equals(cachedResponse)) {
                // Request gốc vẫn đang xử lý → trả 409 Conflict
                log.debug("Idempotency key {} is still processing, returning 409", idempotencyKey);
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Request with this idempotency key is still processing\"}");
                return;
            }

            // Trả cached response từ request gốc
            log.debug("Duplicate request detected for idempotency key {}, returning cached response",
                    idempotencyKey);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write(cachedResponse);
            return;
        }

        // Đây là request đầu tiên với key này → xử lý và cache response
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);

            // Cache response body vào Redis
            String responseBody = new String(responseWrapper.getContentAsByteArray());
            if (!responseBody.isEmpty()) {
                redisTemplate.opsForValue().set(redisKey, responseBody, Duration.ofSeconds(ttlSeconds));
            }

            responseWrapper.copyBodyToResponse();
        } catch (Exception e) {
            // Xử lý thất bại → xóa key để cho phép retry
            redisTemplate.delete(redisKey);
            throw e;
        }
    }
}
