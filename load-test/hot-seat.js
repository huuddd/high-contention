import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// =============================================================================
// Benchmark B2 — Hot-Seat (Realistic Contention)
// =============================================================================
// Scenario: 500 VUs compete for 100 seats — 80% target front rows (A1-A10)
// Purpose:  Simulate realistic ticket sale with hot-spot contention
//           Some seats are more popular → higher contention on those rows
//
// Usage:
//   k6 run load-test/hot-seat.js -e BASE_URL=http://localhost:8080 -e STRATEGY=occ
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'unknown';
const EVENT_ID = __ENV.EVENT_ID || '1';
const VUS = parseInt(__ENV.VUS || '500');
const ITERATIONS = parseInt(__ENV.ITERATIONS || '500');
const TOTAL_SEATS = parseInt(__ENV.TOTAL_SEATS || '100');
const HOT_SEATS = parseInt(__ENV.HOT_SEATS || '10');
const HOT_RATIO = parseFloat(__ENV.HOT_RATIO || '0.8');

// Custom metrics
const successCount = new Counter('success_count');
const conflictCount = new Counter('conflict_count');
const errorCount = new Counter('error_count');
const notFoundCount = new Counter('not_found_count');
const purchaseDuration = new Trend('purchase_duration_ms');

export const options = {
    scenarios: {
        hot_seat: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: '120s',
        },
    },
    thresholds: {
        'http_req_duration': ['p(95)<10000'],
    },
    tags: {
        strategy: STRATEGY,
        scenario: 'hot-seat',
    },
};

function pickSeatLabel() {
    if (Math.random() < HOT_RATIO) {
        // 80% of requests target front rows A1-A10 (hot seats)
        const seatNum = Math.floor(Math.random() * HOT_SEATS) + 1;
        return `A${seatNum}`;
    } else {
        // 20% target remaining seats A11-A100
        const seatNum = Math.floor(Math.random() * (TOTAL_SEATS - HOT_SEATS)) + HOT_SEATS + 1;
        return `A${seatNum}`;
    }
}

export default function () {
    const userId = uuidv4();
    const idempotencyKey = uuidv4();
    const seatLabel = pickSeatLabel();

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
    };

    const startTime = Date.now();
    const res = http.post(`${BASE_URL}/tickets/reserve-and-buy`, payload, params);
    const duration = Date.now() - startTime;

    purchaseDuration.add(duration);

    check(res, {
        'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
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
    console.log(`  BENCHMARK B2 — Hot-Seat | Strategy: ${STRATEGY}`);
    console.log('='.repeat(70));
    console.log(`  VUs:         ${VUS}`);
    console.log(`  Iterations:  ${ITERATIONS}`);
    console.log(`  Hot seats:   A1-A${HOT_SEATS} (${HOT_RATIO * 100}% of traffic)`);
    console.log(`  Total seats: ${TOTAL_SEATS}`);
    console.log(`  Successes:   ${getCounterValue(data, 'success_count')}`);
    console.log(`  Conflicts:   ${getCounterValue(data, 'conflict_count')}`);
    console.log(`  Errors:      ${getCounterValue(data, 'error_count')}`);
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

    // Invariant check
    const successes = getCounterValue(data, 'success_count');
    if (stats && stats.soldCount !== undefined) {
        if (stats.soldCount > TOTAL_SEATS) {
            console.log(`  ⚠️  OVERSELL DETECTED: ${stats.soldCount} > ${TOTAL_SEATS} seats!`);
        } else if (stats.consistent) {
            console.log(`  ✅ CORRECT: ${stats.soldCount} tickets sold, consistent=true`);
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
