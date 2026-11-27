# Order Event Log and Live Updates (Milestone 6)

Since Milestone 3, every order status change goes through `OrderStateMachine`. Milestone 6 records each change in a timeline and pushes it, as it happens, to everyone who should see it. Before this:

* nothing recorded who changed an order or when
* no page updated by itself: the driver dashboard reloaded the whole page after every click, and customers and staff saw stale status until they refreshed
* allergy safety notices from trace-back only showed up when the customer next opened the Safety Notices page

## How it fits together

```
 OrderStateMachine / createOrder / allergy hand-off / trace-back
          │   one database transaction
          ├── order_events  (the timeline, append-only)
          └── outbox_events (what to announce, and to whom)
                       │  after commit, every 200 ms
               OutboxDispatcher ──▶ StreamBroker ──▶ GET /api/stream (SSE) ──▶ browser
                                                         ▲
                                       POST /api/stream/ticket (JWT → one-time ticket)
```

### The timeline

`OrderEvent` rows are only ever inserted. Each one is written in the same transaction as the change it records. A move the state machine refuses (409 or 403) writes nothing, and a move that fails later (say, paying the driver throws) rolls its event back with the order. Recorded events:

| Event | Written by |
| :- | :- |
| `PLACED` | `OrderServiceImpl.createOrder` |
| `CLAIMED`, `PICKED_UP`, `DELIVERED`, `CANCELLED` | `OrderStateMachine.transition` |
| `ALLERGY_ACKNOWLEDGED` | `AllergyHandoffService.acknowledge` (staff and driver) |
| `IMPORTED` | `OrderEventBackfill` at startup, once, for orders placed before this milestone |

`GET /api/orders/{id}/events` returns an order's timeline, oldest first. The order's customer, its assigned driver, staff and admins can see it, the same people who can see the order. Another customer, or a driver not assigned to it, gets 403. The UI shows it under **Show timeline** on My Orders and Order Management.

### The transactional outbox

Saving a change and then sending a message about it can go wrong in two ways: the message goes out but the change rolls back, or the change commits and the message is lost. So nothing is sent directly. The message is saved as an `OutboxEvent` row in the same transaction as the change, and `OutboxDispatcher` sends committed rows afterwards, oldest first, and stamps them as sent.

* A change that rolls back never produces a message, because its outbox row rolls back too.
* If sending fails, the row stays unsent and the next run retries it, so a message can be late but is never lost. The dispatcher stops at the first failure, so messages never overtake each other.
* Delivery is at least once. A message can arrive twice, so clients drop repeats by event id.
* Sent messages are kept for 24 hours (`app.outbox.retention-hours`) so reconnecting clients can catch up, then purged hourly.

### Who hears what

| Message | Sent to |
| :- | :- |
| `order.event` | The order's customer, its driver, and all staff and admins. All drivers too while the order enters or leaves the pool of available orders (placed, then claimed or cancelled), so every Available Orders list stays current. |
| `safety.notice` | Only the customer the notice is for |
| `incident.reported` | Staff and admins |

Order messages carry the order id, what happened, the status and the driver, and never any customer details. That's why they're safe to broadcast to drivers.

### The stream

`GET /api/stream` uses Server-Sent Events: a long-lived HTTP response the server writes events into, which browsers support natively (`EventSource`). Each event carries its outbox id.

* **Tickets instead of a JWT in the URL.** `EventSource` can't send an `Authorization` header, and a JWT in the URL would leak a 7-day credential into server logs and browser history. So the client calls `POST /api/stream/ticket` with its JWT and gets back a random 256-bit ticket. The ticket works once, for 30 seconds, for that user only. A used, expired, missing or made-up ticket is 401.
* **Resume.** A client that reconnects with the last event id it saw (`?lastEventId=` or the `Last-Event-ID` header) first gets everything it missed, replayed from the outbox. Ids are handed out at insert but transactions can commit in a different order, so the replay starts 50 ids early and the client drops what it already has. Live messages that arrive during the replay are held back until it finishes, so nothing slips in between.
* **Limits.** At most 3 open streams per user; a 4th is 429. A heartbeat every 15 s stops proxies from closing idle streams and lets the server notice dead connections. Streams close after 30 minutes and the client reconnects.

### The browser

There's one stream per browser tab, opened by `LiveUpdatesProvider` (in `src/live/`) and shared by every page. One stream per page would hit the 3-stream limit after two tabs. `LiveConnection`:

1. gets a ticket and opens the stream
2. drops repeats by event id
3. on any error, closes the stream and reconnects with a fresh ticket and the last event id, backing off 1 s, 2 s, 4 s … up to 30 s
4. after 3 failures in a row, also polls every 30 s until the stream comes back

Pages call `useLiveRefresh(['order.event'], refresh)`. A burst of events becomes one refetch, so each page keeps using the same REST calls it always did.

* **Driver dashboard:** no more page reloads. An order another driver claims disappears from Available Orders straight away, and the restaurant's allergy acknowledgement shows up on its own.
* **My Orders and Order Management:** status badges update live, and the timeline follows along when it's open.
* **Navigation:** a new safety notice updates the unread count and shows the warning banner on whatever page the customer is on.
* **Status pill:** each page shows **● Live**, **Reconnecting…** or **Updating every 30 s**.

## Measured

All runs used the backend on H2 on one machine, with the dispatcher on its default 200 ms interval. To re-run: `FOODSEER_ADMIN_PASSWORD=… node tools/live-load-test.mjs --streams 200 --minutes 5`.

The load test opens the streams (one per customer, plus 10 staff), then places an order about once a second and has a driver claim it. For each change, it times from the HTTP response, by which point the change has committed, to the event arriving on the customer's stream.

| | Result |
| :- | :- |
| Open streams | 200 (190 customers, 10 staff) for 5 minutes |
| Orders placed and claimed | 295 (590 timed events; 6,490 events delivered across all streams) |
| Commit → received, p50 | **104 ms** |
| Commit → received, p95 | **193 ms** |
| Commit → received, max | 204 ms |
| API requests while 200 streams were open, p50 / p95 | 9 ms / 14 ms |
| Dropped streams or missed events | **0** |

The latency is almost all the dispatcher's polling interval: an event waits on average half of the 200 ms before it goes out.

In the browser, from the driver clicking Claim, Pick Up or Delivered to the customer's badge changing took about 0.8 s. That includes the driver's own request, the 250 ms batching window and the page refetching the order. A trace-back alert reached the customer's banner 186 ms after staff reported the incident.

### A bug the load test caught

Spring Boot's default "open entity manager in view" keeps a request's database session open until the request finishes. A live stream doesn't finish for up to 30 minutes, so each open tab held one of the pool's 10 database connections. The 10th open tab froze every other request, which then waited 30 s for a connection and failed. `OpenEntityManagerInViewConfig` keeps that behaviour for every endpoint except `/api/stream`. The stream only makes short repository calls, so it doesn't need it. `LiveStreamIntegrationTest.manyOpenStreamsDontStarveTheDatabase` holds 15 streams open and checks that other requests still get a connection straight away.

## Tests

| Test | What it proves |
| :- | :- |
| `OrderStateMachineTest` | Every (from, to) pair: an allowed move records exactly one event with its from-status; a refused one records none. A refused actor records none. |
| `OrderEventLogTest` (real commits) | A delivered order's full timeline in order with each actor; refused moves add nothing; cancels record who cancelled; the backfill gives old orders one `IMPORTED` event, once. |
| `OrderEventRollbackTest` | If paying the driver fails after the `DELIVERED` event is written, the event and its outbox message roll back with the order. |
| `OutboxTest` (real commits) | Each change writes one event and one message with the right recipients; refused changes write neither; a message that fails to send 3 times is delivered on the next run; a trace-back alert goes only to the exposed customer. |
| `OutboxDispatcherTest` | Messages go out in order; a failure mid-batch holds back everything after it; purge drops sent messages older than 24 h. |
| `StreamTicketServiceTest` | Tickets work once, expire after 30 s, and are random 256-bit values. |
| `LiveStreamIntegrationTest` (real HTTP, dispatcher every 200 ms) | The customer hears a claim within 1 s and another customer hears nothing. Drivers see new and claimed orders but nothing after that. Reconnecting with the last event id replays exactly the 3 missed events, in order. Ticket reuse and a missing ticket are 401, and a 4th stream is 429. Heartbeats free a closed stream's slot. 15 open streams don't starve the database. |
| `AuthorizationMatrixTest` | Rows for `GET /api/orders/{id}/events`, `POST /api/stream/ticket` and `GET /api/stream` for every role, plus ownership checks on the timeline. |
| `LiveOrderUpdatesTest` (Selenium, two browsers) | The driver claims, picks up and delivers while the customer's My Orders page shows each step, and the page is never reloaded. |
| Jest | `liveConnection.test.js`: repeats dropped, reconnect with the last id, backoff, polling fallback and recovery. `LiveUpdates.test.js`: one stream per tab, bursts batched. `DriverDashboard.test.js`: a claimed order disappears live. `OrderTimeline.test.js`. `noFullPageReloads.test.js`: no `window.location.reload` anywhere in `src/`. |

PIT kills 100% of mutants in `OrderStateMachine`, `OrderEventRecorder`, `OutboxDispatcher`, `StreamTicketService` and `OrderServiceImpl`.

## Settings

| Property | Default | Meaning |
| :- | :- | :- |
| `app.live.scheduling-enabled` | `true` | Runs the dispatcher, purge and heartbeats. The tests turn it off in `src/test/resources/application-default.properties`: Spring keeps every test context alive for the whole run and they share one database, so a live dispatcher in any of them would send (and stamp as sent) other tests' messages. Tests that need the jobs run them on their own thread and stop it afterwards. |
| `app.outbox.dispatch-interval-ms` | `200` | How often committed messages are sent |
| `app.outbox.retention-hours` | `24` | How long sent messages are kept for resuming |
| `app.stream.ticket-ttl-seconds` | `30` | How long a stream ticket is valid |
| `app.stream.max-per-user` | `3` | Open streams per user |
| `app.stream.heartbeat-ms` | `15000` | Keep-alive interval |
| `app.stream.timeout-ms` | `1800000` | When a stream closes and the client reconnects |

## Database

`ddl-auto=update` creates the two new tables. Once Milestone 5 adds Flyway, they move into a migration; `docs/sql/m6-order-events-and-outbox.sql` has the DDL ready.

## Limits

* **One backend instance.** Open streams live in that server's memory. Running several instances would need a shared broker (Redis pub/sub, for example), and each instance's dispatcher would need to claim rows so that only one sends each message.
* **Proxies.** Anything between the browser and the backend must not buffer the stream. The response sets `X-Accel-Buffering: no`; an nginx proxy also needs `proxy_buffering off` and a long `proxy_read_timeout`.
* **Polling interval.** Delivery latency is bounded by the dispatcher's polling interval (200 ms). A database notification (`LISTEN/NOTIFY` on Postgres) could make it near-instant, but MySQL has no equivalent.
