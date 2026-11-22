#!/usr/bin/env node
// Load and latency test for the live order stream (Milestone 6).
//
//   node tools/live-load-test.mjs [--streams 200] [--minutes 5] [--api http://localhost:8080]
//
// Needs a running backend and the admin password in FOODSEER_ADMIN_PASSWORD.
// It registers throwaway customers, a driver and staff, opens the requested
// number of streams (one per customer plus 10 staff), then for the whole run
// places an order about once a second and has the driver claim it. For each
// change it measures the time from the HTTP response (the commit has happened
// by then) to the event arriving on the customer's stream, and reports p50,
// p95 and max, plus any stream that dropped or errored. Node 18+ (built-in fetch).

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith('--') ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), [])
);
const API = args.api || 'http://localhost:8080';
const STREAMS = Number(args.streams || 200);
const MINUTES = Number(args.minutes || 5);
const STAFF_STREAMS = Math.min(10, Math.max(1, Math.floor(STREAMS / 20)));
const CUSTOMER_STREAMS = STREAMS - STAFF_STREAMS;
const ADMIN_PASSWORD = process.env.FOODSEER_ADMIN_PASSWORD;
const RUN = Array.from({ length: 5 }, () => String.fromCharCode(97 + Math.floor(Math.random() * 26))).join('');

if (!ADMIN_PASSWORD) {
  console.error('Set FOODSEER_ADMIN_PASSWORD to the admin password.');
  process.exit(2);
}

const call = async (method, path, body, token) => {
  const response = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path} -> ${response.status} ${text.slice(0, 120)}`);
  return text ? JSON.parse(text) : null;
};

const login = async (username, password = 'password1') =>
  (await call('POST', '/auth/login', { username, password })).accessToken;

// Letters only: usernames can't contain digits.
const letters = (n) => {
  let out = '';
  do {
    out = String.fromCharCode(97 + (n % 26)) + out;
    n = Math.floor(n / 26);
  } while (n > 0);
  return out;
};

const pending = new Map(); // "orderId:EVENT" -> { at, resolve }
const latencies = [];
const problems = [];
let received = 0;

const expect = (orderId, event, at) => {
  const key = `${orderId}:${event}`;
  const timer = setTimeout(() => {
    if (pending.delete(key)) problems.push(`no ${event} for order ${orderId} within 10 s`);
  }, 10000);
  pending.set(key, { at, timer });
};

// Reads one SSE stream with fetch and reports order events.
const openStream = async (token, label) => {
  const { ticket } = await call('POST', '/api/stream/ticket', undefined, token);
  const response = await fetch(`${API}/api/stream?ticket=${encodeURIComponent(ticket)}`, {
    headers: { Accept: 'text/event-stream' },
  });
  if (response.status !== 200) throw new Error(`stream ${label} -> ${response.status}`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const stream = { label, open: true };
  (async () => {
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let cut;
        while ((cut = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, cut);
          buffer = buffer.slice(cut + 2);
          const name = (block.match(/^event:(.*)$/m) || [])[1]?.trim();
          const data = (block.match(/^data:(.*)$/m) || [])[1];
          if (name === 'order.event' && data) {
            received += 1;
            const payload = JSON.parse(data);
            const key = `${payload.orderId}:${payload.event}`;
            const waiting = pending.get(key);
            if (waiting && label.startsWith('customer')) {
              clearTimeout(waiting.timer);
              pending.delete(key);
              latencies.push(performance.now() - waiting.at);
            }
          }
        }
      }
      if (stream.open) problems.push(`stream ${label} closed by the server`);
    } catch (error) {
      if (stream.open) problems.push(`stream ${label} errored: ${error.message}`);
    }
  })();
  stream.close = () => {
    stream.open = false;
    reader.cancel().catch(() => {});
  };
  return stream;
};

const percentile = (values, p) => {
  if (!values.length) return NaN;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
};

const main = async () => {
  console.log(`Live stream load test: ${STREAMS} streams (${CUSTOMER_STREAMS} customers, ${STAFF_STREAMS} staff) for ${MINUTES} min against ${API}`);
  const admin = await login('admin', ADMIN_PASSWORD);
  const food = await call('POST', '/api/foods', { foodName: `LOAD SOUP ${RUN.toUpperCase()}`, amount: 100000, price: 5, allergies: [] }, admin);

  console.log('Registering accounts...');
  const customers = [];
  for (let i = 0; i < CUSTOMER_STREAMS; i += 10) {
    const batch = await Promise.all(
      Array.from({ length: Math.min(10, CUSTOMER_STREAMS - i) }, async (_, j) => {
        const username = `load${RUN}${letters(i + j)}`;
        await call('POST', '/auth/register', { username, email: `${username}@example.test`, password: 'password1', role: 'CUSTOMER' });
        return { username, token: await login(username) };
      })
    );
    customers.push(...batch);
  }
  const driverName = `loaddriver${RUN}`;
  await call('POST', '/auth/register', { username: driverName, email: `${driverName}@example.test`, password: 'password1', role: 'DRIVER' });
  const driver = await login(driverName);
  const staff = [];
  for (let i = 0; i < STAFF_STREAMS; i++) {
    const username = `loadstaff${RUN}${letters(i)}`;
    await call('POST', '/api/users', { username, email: `${username}@example.test`, password: 'password1', role: 'STAFF' }, admin);
    staff.push(await login(username));
  }

  console.log('Opening streams...');
  const streams = [];
  for (const [i, customer] of customers.entries()) streams.push(await openStream(customer.token, `customer ${i}`));
  for (const [i, token] of staff.entries()) streams.push(await openStream(token, `staff ${i}`));
  console.log(`${streams.length} streams open.`);

  const requestTimes = [];
  const endAt = Date.now() + MINUTES * 60 * 1000;
  let orders = 0;
  let lastReport = Date.now();
  while (Date.now() < endAt) {
    const customer = customers[Math.floor(Math.random() * customers.length)];
    try {
      let started = performance.now();
      const order = await call('POST', '/api/orders', { name: 'Load', foods: [{ id: food.id }] }, customer.token);
      requestTimes.push(performance.now() - started);
      expect(order.id, 'PLACED', performance.now());

      started = performance.now();
      await call('POST', `/api/orders/${order.id}/claim`, undefined, driver);
      requestTimes.push(performance.now() - started);
      expect(order.id, 'CLAIMED', performance.now());
      orders += 1;
    } catch (error) {
      problems.push(error.message);
    }
    if (Date.now() - lastReport > 30000) {
      lastReport = Date.now();
      console.log(`  ${orders} orders, ${latencies.length} timed events, p95 ${percentile(latencies, 95).toFixed(0)} ms, ${problems.length} problems`);
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }

  await new Promise((resolve) => setTimeout(resolve, 3000));
  streams.forEach((s) => s.close());
  await call('DELETE', `/api/foods/${food.id}`, undefined, admin).catch(() => {});

  const report = {
    streams: streams.length,
    minutes: MINUTES,
    orders,
    timedEvents: latencies.length,
    eventsReceivedAcrossAllStreams: received,
    latencyMs: {
      p50: Math.round(percentile(latencies, 50)),
      p95: Math.round(percentile(latencies, 95)),
      max: Math.round(Math.max(...latencies)),
    },
    requestMs: { p50: Math.round(percentile(requestTimes, 50)), p95: Math.round(percentile(requestTimes, 95)) },
    problems: problems.length,
  };
  console.log(JSON.stringify(report, null, 2));
  if (problems.length) console.log('Problems:\n  ' + problems.slice(0, 20).join('\n  '));
  process.exit(problems.length ? 1 : 0);
};

main().catch((error) => {
  console.error(error);
  process.exit(2);
});
