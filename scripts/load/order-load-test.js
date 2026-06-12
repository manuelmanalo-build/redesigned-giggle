import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 5);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);
const MAX_DURATION = __ENV.MAX_DURATION || '5m';
const INCLUDE_INVALID = (__ENV.INCLUDE_INVALID || 'true').toLowerCase() !== 'false';
const PAUSE_SECONDS = Number(__ENV.PAUSE_SECONDS || 0.05);
const SETTLE_SECONDS = Number(__ENV.SETTLE_SECONDS || 2);

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 400));

const orderSubmitLatency = new Trend('trade_order_submit_latency', true);
const queryLatency = new Trend('trade_query_latency', true);
const expectedSuccess = new Rate('trade_expected_success');
const expectedFailure = new Rate('trade_expected_failure');
const orderSubmissions = new Counter('trade_order_submissions');
const queryRequests = new Counter('trade_query_requests');

export const options = {
  scenarios: {
    order_load: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: MAX_DURATION,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { endpoint: 'health' } });
  check(health, {
    'application is healthy or reachable': (response) => response.status === 200 || response.status === 503,
  });
  return {
    runId: `${Date.now()}-${Math.floor(Math.random() * 100000)}`,
  };
}

export default function (data) {
  const scenario = selectScenario(__ITER, INCLUDE_INVALID);
  const clientOrderId = `K6-${data.runId}-${__VU}-${__ITER}`;
  const payload = buildOrderPayload(clientOrderId, scenario);

  const submitStart = Date.now();
  const submitResponse = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify(payload),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `k6-${clientOrderId}`,
        'X-Correlation-Id': `k6-${data.runId}-${__VU}-${__ITER}`,
      },
      tags: {
        endpoint: 'submit_order',
        scenario: scenario.name,
      },
    }
  );
  orderSubmitLatency.add(Date.now() - submitStart, { scenario: scenario.name });
  orderSubmissions.add(1, { scenario: scenario.name });

  const expectedStatus = scenario.expectInvalid ? 400 : 201;
  const matchedExpectedStatus = submitResponse.status === expectedStatus;
  expectedSuccess.add(matchedExpectedStatus);
  expectedFailure.add(!matchedExpectedStatus);
  check(submitResponse, {
    [`${scenario.name} returned expected status`]: () => matchedExpectedStatus,
  });

  if (!scenario.expectInvalid && submitResponse.status === 201) {
    const order = submitResponse.json();
    queryOperationalViews(order.orderId, payload.accountId, payload.symbol);
  } else {
    queryOrders(payload.accountId, payload.symbol);
  }

  sleep(PAUSE_SECONDS);
}

export function teardown() {
  if (SETTLE_SECONDS > 0) {
    sleep(SETTLE_SECONDS);
  }
  logMetric('trade.orders.submitted');
  logMetric('trade.orders.rejected');
  logMetric('trade.execution_reports.created');
  logMetric('trade.trades.created');
  logMetric('trade.messages.processing.failures');
  logMetric('trade.messages.processing.duration');
  logMetric('hikaricp.connections.active');
  logMetric('hikaricp.connections.idle');
  logMetric('hikaricp.connections.pending');
  logMetric('hikaricp.connections.acquire');
}

function selectScenario(iteration, includeInvalid) {
  const scenarios = includeInvalid
    ? [
        { name: 'market_fill', type: 'MARKET', side: 'BUY' },
        { name: 'fillable_limit_buy', type: 'LIMIT', side: 'BUY', limitPrice: 110.0 },
        { name: 'non_fillable_limit_buy', type: 'LIMIT', side: 'BUY', limitPrice: 90.0 },
        { name: 'invalid_suspended_account', type: 'LIMIT', side: 'BUY', limitPrice: 100.0, accountId: 'ACC-002', expectInvalid: true },
      ]
    : [
        { name: 'market_fill', type: 'MARKET', side: 'BUY' },
        { name: 'fillable_limit_buy', type: 'LIMIT', side: 'BUY', limitPrice: 110.0 },
        { name: 'non_fillable_limit_buy', type: 'LIMIT', side: 'BUY', limitPrice: 90.0 },
      ];
  return scenarios[iteration % scenarios.length];
}

function buildOrderPayload(clientOrderId, scenario) {
  const payload = {
    clientOrderId,
    accountId: scenario.accountId || 'ACC-001',
    symbol: scenario.symbol || 'AAPL',
    side: scenario.side,
    type: scenario.type,
    quantity: 100,
  };
  if (scenario.type === 'LIMIT') {
    payload.limitPrice = scenario.limitPrice;
  }
  return payload;
}

function queryOperationalViews(orderId, accountId, symbol) {
  queryOrders(accountId, symbol);
  queryEndpoint(`/api/v1/orders/${encodeURIComponent(orderId)}/execution-reports`, 'order_execution_reports');
  queryEndpoint(`/api/v1/orders/${encodeURIComponent(orderId)}/trades`, 'order_trades');
  queryEndpoint(`/api/v1/execution-reports?orderId=${encodeURIComponent(orderId)}&page=0&size=20`, 'search_execution_reports');
  queryEndpoint(`/api/v1/trades?orderId=${encodeURIComponent(orderId)}&page=0&size=20`, 'search_trades');
}

function queryOrders(accountId, symbol) {
  const path = `/api/v1/orders?accountId=${encodeURIComponent(accountId)}&symbol=${encodeURIComponent(symbol)}&page=0&size=20&sortBy=createdAt&sortDirection=desc`;
  queryEndpoint(path, 'search_orders');
}

function queryEndpoint(path, endpointName) {
  const startedAt = Date.now();
  const response = http.get(`${BASE_URL}${path}`, { tags: { endpoint: endpointName } });
  queryLatency.add(Date.now() - startedAt, { endpoint: endpointName });
  queryRequests.add(1, { endpoint: endpointName });
  const ok = response.status >= 200 && response.status < 300;
  expectedSuccess.add(ok);
  expectedFailure.add(!ok);
  check(response, {
    [`${endpointName} returned 2xx`]: () => ok,
  });
}

function logMetric(metricName) {
  const response = http.get(`${BASE_URL}/actuator/metrics/${metricName}`, { tags: { endpoint: 'actuator_metric' } });
  if (response.status !== 200) {
    console.log(`metric ${metricName}: unavailable status=${response.status}`);
    return;
  }
  const body = response.json();
  const measurements = body.measurements || [];
  const summary = measurements.map((measurement) => `${measurement.statistic}=${measurement.value}`).join(', ');
  console.log(`metric ${metricName}: ${summary}`);
}
