import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// =============================================================================
// Benchmark B3 — Burst (Flash Sale Simulation)
// =============================================================================
// Scenario: Ramp 0 → 1000 VUs in 5s, sustained 30s, then ramp down
// Purpose:  Simulate flash sale spike — tests connection pool exhaustion,
//           queue back-pressure, and retry storm behavior
//
// Usage:
//   k6 run load-test/burst.js -e BASE_URL=http://localhost:8080 -e STRATEGY=queue
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'unknown';
const EVENT_ID = __ENV.EVENT_ID || '1';
const TOTAL_SEATS = parseInt(__ENV.TOTAL_SEATS || '100');
const PEAK_VUS = parseInt(__ENV.PEAK_VUS || '1000');
const RAMP_UP = __ENV.RAMP_UP || '5s';
const SUSTAINED = __ENV.SUSTAINED || '30s';
const RAMP_DOWN = __ENV.RAMP_DOWN || '5s';

// Custom metrics
const successCount = new Counter('success_count');
const conflictCount = new Counter('conflict_count');
const errorCount = new Counter('error_count');
const notFoundCount = new Counter('not_found_count');
const serviceUnavailableCount = new Counter('service_unavailable_count');
const purchaseDuration = new Trend('purchase_duration_ms');

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: RAMP_UP, target: PEAK_VUS },
                { duration: SUSTAINED, target: PEAK_VUS },
                { duration: RAMP_DOWN, target: 0 },
            ],
        },
    },
    thresholds: {
        'http_req_duration': ['p(95)<15000'],
    },
    tags: {
        strategy: STRATEGY,
        scenario: 'burst',
    },
};

function pickRandomSeat() {
    const seatNum = Math.floor(Math.random() * TOTAL_SEATS) + 1;
    return `A${seatNum}`;
}

export default function () {
    const userId = uuidv4();
    const idempotencyKey = uuidv4();
    const seatLabel = pickRandomSeat();

    const payload = JSON.stringify({
        eventId: parseInt(EVENT_ID),
        userId: userId,
        seatLabel: seatLabel,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
        tags: { name: 'reserve-and-buy' },
        timeout: '30s',
    };

    const startTime = Date.now();
    const res = http.post(`${BASE_URL}/tickets/reserve-and-buy`, payload, params);
    const duration = Date.now() - startTime;

    purchaseDuration.add(duration);

    check(res, {
        'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
        'status is not 5xx': (r) => r.status < 500,
        'response time < 10s': (r) => r.timings.duration < 10000,
    });

    if (res.status === 201) {
        successCount.add(1);
    } else if (res.status === 409) {
        conflictCount.add(1);
    } else if (res.status === 404) {
        notFoundCount.add(1);
    } else if (res.status === 503) {
        serviceUnavailableCount.add(1);
    } else {
        errorCount.add(1);
    }

    // Small random sleep to simulate realistic user behavior
    sleep(Math.random() * 0.5);
}

export function handleSummary(data) {
    const stats = fetchStats();

    console.log('\n' + '='.repeat(70));
    console.log(`  BENCHMARK B3 — Burst (Flash Sale) | Strategy: ${STRATEGY}`);
    console.log('='.repeat(70));
    console.log(`  Peak VUs:     ${PEAK_VUS}`);
    console.log(`  Ramp:         ${RAMP_UP} up → ${SUSTAINED} sustained → ${RAMP_DOWN} down`);
    console.log(`  Total seats:  ${TOTAL_SEATS}`);
    console.log(`  Successes:    ${getCounterValue(data, 'success_count')}`);
    console.log(`  Conflicts:    ${getCounterValue(data, 'conflict_count')}`);
    console.log(`  503s:         ${getCounterValue(data, 'service_unavailable_count')}`);
    console.log(`  Errors:       ${getCounterValue(data, 'error_count')}`);
    console.log(`  Total reqs:   ${data.metrics['http_reqs'] ? data.metrics['http_reqs'].values.count : 'N/A'}`);
    console.log('  ---');
    console.log(`  p50:   ${getTrendValue(data, 'purchase_duration_ms', 'p(50)')}ms`);
    console.log(`  p95:   ${getTrendValue(data, 'purchase_duration_ms', 'p(95)')}ms`);
    console.log(`  p99:   ${getTrendValue(data, 'purchase_duration_ms', 'p(99)')}ms`);
    console.log(`  max:   ${getTrendValue(data, 'purchase_duration_ms', 'max')}ms`);

    if (stats) {
        console.log('  ---');
        console.log(`  DB soldCount:      ${stats.soldCount}`);
        console.log(`  DB availableSeats: ${stats.availableSeats}`);
        console.log(`  DB consistent:     ${stats.consistent}`);
        if (stats.metrics) {
            console.log(`  Metric conflicts:  ${stats.metrics.conflicts}`);
            console.log(`  Metric retries:    ${stats.metrics.retries}`);
            console.log(`  Metric deadlocks:  ${stats.metrics.deadlocks}`);
            console.log(`  Metric timeouts:   ${stats.metrics.timeouts}`);
            console.log(`  Metric oversells:  ${stats.metrics.oversells}`);
        }
    }

    console.log('='.repeat(70));

    // Invariant checks
    if (stats) {
        if (stats.soldCount > TOTAL_SEATS) {
            console.log(`  ⚠️  OVERSELL DETECTED: ${stats.soldCount} > ${TOTAL_SEATS} seats!`);
        } else if (!stats.consistent) {
            console.log(`  ⚠️  INCONSISTENCY: available + sold ≠ total`);
        } else {
            console.log(`  ✅ CORRECT: ${stats.soldCount}/${TOTAL_SEATS} sold, consistent=true`);
        }
        if (stats.metrics && stats.metrics.oversells > 0) {
            console.log(`  🚨 OVERSELL METRIC > 0: ${stats.metrics.oversells}`);
        }
    }
    console.log('');

    return {};
}

function fetchStats() {
    try {
        const res = http.get(`${BASE_URL}/events/${EVENT_ID}/stats`);
        if (res.status === 200) {
            return JSON.parse(res.body);
        }
    } catch (e) {
        // ignore
    }
    return null;
}

function getCounterValue(data, name) {
    return data.metrics[name] ? data.metrics[name].values.count : 0;
}

function getTrendValue(data, name, percentile) {
    if (data.metrics[name] && data.metrics[name].values[percentile] !== undefined) {
        return Math.round(data.metrics[name].values[percentile] * 100) / 100;
    }
    return 'N/A';
}
