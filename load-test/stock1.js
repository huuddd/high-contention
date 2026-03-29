import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// =============================================================================
// Benchmark B1 — Stock=1 (Extreme Contention)
// =============================================================================
// Scenario: 200 VUs fight for 1 seat → only 1 should win
// Purpose:  Prove oversell in Naive, measure latency under extreme contention
//
// Usage:
//   k6 run load-test/stock1.js -e BASE_URL=http://localhost:8080 -e STRATEGY=naive
//   k6 run load-test/stock1.js -e BASE_URL=http://localhost:8080 -e STRATEGY=pessimistic
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'unknown';
const EVENT_ID = __ENV.EVENT_ID || '1';
const SEAT_LABEL = __ENV.SEAT_LABEL || 'A1';
const VUS = parseInt(__ENV.VUS || '200');

// Custom metrics
const successCount = new Counter('success_count');
const conflictCount = new Counter('conflict_count');
const errorCount = new Counter('error_count');
const notFoundCount = new Counter('not_found_count');
const purchaseDuration = new Trend('purchase_duration_ms');

export const options = {
    scenarios: {
        stock1: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: VUS,
            maxDuration: '60s',
        },
    },
    thresholds: {
        'http_req_duration': ['p(95)<5000'],
    },
    tags: {
        strategy: STRATEGY,
        scenario: 'stock1',
    },
};

export default function () {
    const userId = uuidv4();
    const idempotencyKey = uuidv4();

    const payload = JSON.stringify({
        eventId: parseInt(EVENT_ID),
        userId: userId,
        seatLabel: SEAT_LABEL,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
        tags: { name: 'reserve-and-buy' },
    };

    const startTime = Date.now();
    const res = http.post(`${BASE_URL}/tickets/reserve-and-buy`, payload, params);
    const duration = Date.now() - startTime;

    purchaseDuration.add(duration);

    check(res, {
        'status is 201 (created)': (r) => r.status === 201,
        'status is 409 (conflict)': (r) => r.status === 409,
        'status is not 5xx': (r) => r.status < 500,
    });

    if (res.status === 201) {
        successCount.add(1);
    } else if (res.status === 409) {
        conflictCount.add(1);
    } else if (res.status === 404) {
        notFoundCount.add(1);
    } else {
        errorCount.add(1);
    }
}

export function handleSummary(data) {
    const stats = fetchStats();

    console.log('\n' + '='.repeat(70));
    console.log(`  BENCHMARK B1 — Stock=1 | Strategy: ${STRATEGY}`);
    console.log('='.repeat(70));
    console.log(`  VUs:        ${VUS}`);
    console.log(`  Successes:  ${getCounterValue(data, 'success_count')}`);
    console.log(`  Conflicts:  ${getCounterValue(data, 'conflict_count')}`);
    console.log(`  Errors:     ${getCounterValue(data, 'error_count')}`);
    console.log(`  Not Found:  ${getCounterValue(data, 'not_found_count')}`);
    console.log('  ---');
    console.log(`  p50:  ${getTrendValue(data, 'purchase_duration_ms', 'p(50)')}ms`);
    console.log(`  p95:  ${getTrendValue(data, 'purchase_duration_ms', 'p(95)')}ms`);
    console.log(`  p99:  ${getTrendValue(data, 'purchase_duration_ms', 'p(99)')}ms`);

    if (stats) {
        console.log('  ---');
        console.log(`  DB soldCount:      ${stats.soldCount}`);
        console.log(`  DB availableSeats: ${stats.availableSeats}`);
        console.log(`  DB consistent:     ${stats.consistent}`);
        if (stats.metrics) {
            console.log(`  Metric conflicts:  ${stats.metrics.conflicts}`);
            console.log(`  Metric retries:    ${stats.metrics.retries}`);
            console.log(`  Metric deadlocks:  ${stats.metrics.deadlocks}`);
        }
    }

    console.log('='.repeat(70));

    // Check invariant: only 1 success for stock=1
    const successes = getCounterValue(data, 'success_count');
    if (successes > 1) {
        console.log(`  ⚠️  OVERSELL DETECTED: ${successes} tickets sold for 1 seat!`);
    } else if (successes === 1) {
        console.log('  ✅ CORRECT: exactly 1 ticket sold');
    } else {
        console.log('  ⚠️  NO TICKETS SOLD — check if seat exists');
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
