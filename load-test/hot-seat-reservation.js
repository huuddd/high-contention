import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// =============================================================================
// Benchmark B2 — Hot-Seat (Realistic Contention) — RESERVATION STRATEGY
// =============================================================================
// Scenario: 500 VUs, 100 seats, 80% traffic → A1-A20 (hot seats)
// Purpose:  Test reservation strategy under realistic contention
//
// Usage:
//   k6 run load-test/hot-seat-reservation.js -e BASE_URL=http://localhost:8080
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = 'reservation';
const EVENT_ID = __ENV.EVENT_ID || '1';
const VUS = parseInt(__ENV.VUS || '500');

// 80% traffic to hot seats (A1-A20), 20% to cold seats (A21-A100)
function selectSeat() {
    const rand = Math.random();
    if (rand < 0.8) {
        // Hot seats: A1-A20
        const seatNum = Math.floor(Math.random() * 20) + 1;
        return `A${seatNum}`;
    } else {
        // Cold seats: A21-A100
        const seatNum = Math.floor(Math.random() * 80) + 21;
        return `A${seatNum}`;
    }
}

// Custom metrics
const successCount = new Counter('success_count');
const conflictCount = new Counter('conflict_count');
const errorCount = new Counter('error_count');
const reserveDuration = new Trend('reserve_duration_ms');
const confirmDuration = new Trend('confirm_duration_ms');
const totalDuration = new Trend('total_duration_ms');

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
    const totalStart = Date.now();
    const seatLabel = selectSeat();

    // Step 1: Reserve
    const reservePayload = JSON.stringify({
        eventId: parseInt(EVENT_ID),
        userId: userId,
        seatLabel: seatLabel,
    });

    const reserveParams = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: { name: 'reserve' },
    };

    const reserveStart = Date.now();
    const reserveRes = http.post(`${BASE_URL}/reservations`, reservePayload, reserveParams);
    const reserveDur = Date.now() - reserveStart;
    reserveDuration.add(reserveDur);

    check(reserveRes, {
        'reserve: status is 201 or 409': (r) => r.status === 201 || r.status === 409,
    });

    if (reserveRes.status !== 201) {
        // Reserve failed → count as conflict
        conflictCount.add(1);
        totalDuration.add(Date.now() - totalStart);
        return;
    }

    // Parse reservation response
    let reservationData;
    try {
        reservationData = JSON.parse(reserveRes.body);
    } catch (e) {
        errorCount.add(1);
        totalDuration.add(Date.now() - totalStart);
        return;
    }

    const reservationId = reservationData.reservationId;
    const fencingToken = reservationData.fencingToken;

    if (!reservationId || !fencingToken) {
        errorCount.add(1);
        totalDuration.add(Date.now() - totalStart);
        return;
    }

    // Step 2: Confirm (immediately, no user delay simulation for benchmark)
    const confirmPayload = JSON.stringify({
        fencingToken: fencingToken,
    });

    const confirmParams = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: { name: 'confirm' },
    };

    const confirmStart = Date.now();
    const confirmRes = http.post(
        `${BASE_URL}/reservations/${reservationId}/confirm`,
        confirmPayload,
        confirmParams
    );
    const confirmDur = Date.now() - confirmStart;
    confirmDuration.add(confirmDur);

    const totalDur = Date.now() - totalStart;
    totalDuration.add(totalDur);

    check(confirmRes, {
        'confirm: status is 200 or 409': (r) => r.status === 200 || r.status === 409,
    });

    if (confirmRes.status === 200) {
        successCount.add(1);
    } else if (confirmRes.status === 409) {
        conflictCount.add(1);
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
    console.log('  ---');
    console.log(`  Reserve p50:  ${getTrendValue(data, 'reserve_duration_ms', 'p(50)')}ms`);
    console.log(`  Reserve p95:  ${getTrendValue(data, 'reserve_duration_ms', 'p(95)')}ms`);
    console.log(`  Confirm p50:  ${getTrendValue(data, 'confirm_duration_ms', 'p(50)')}ms`);
    console.log(`  Confirm p95:  ${getTrendValue(data, 'confirm_duration_ms', 'p(95)')}ms`);
    console.log(`  Total p50:    ${getTrendValue(data, 'total_duration_ms', 'p(50)')}ms`);
    console.log(`  Total p95:    ${getTrendValue(data, 'total_duration_ms', 'p(95)')}ms`);
    console.log(`  Total p99:    ${getTrendValue(data, 'total_duration_ms', 'p(99)')}ms`);

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
