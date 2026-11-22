// One live connection to GET /api/stream (Server-Sent Events), with no React in
// it so it can be tested on its own.
//
// - Opens with a single-use ticket (EventSource can't send the JWT header).
// - Remembers the last event id and resumes from it after a drop.
// - Drops repeats by event id: the server replays a little early to cover
//   out-of-order commits, and delivery is at least once.
// - Reconnects with backoff (1 s, 2 s, 4 s ... 30 s). After 3 failures in a row
//   it also starts polling, sending a 'poll' event every 30 s so pages refetch,
//   until the stream comes back.

export const LIVE_EVENT_TYPES = ['order.event', 'safety.notice', 'incident.reported'];

const MAX_REMEMBERED_IDS = 1000;

export class LiveConnection {
  constructor({
    getTicket,
    baseUrl,
    onEvent,
    onStatus = () => {},
    EventSourceImpl = typeof window !== 'undefined' ? window.EventSource : undefined,
    pollMs = 30000,
    failuresBeforePolling = 3,
    maxBackoffMs = 30000,
  }) {
    this.getTicket = getTicket;
    this.baseUrl = baseUrl;
    this.onEvent = onEvent;
    this.onStatus = onStatus;
    this.EventSourceImpl = EventSourceImpl;
    this.pollMs = pollMs;
    this.failuresBeforePolling = failuresBeforePolling;
    this.maxBackoffMs = maxBackoffMs;

    this.lastEventId = null;
    this.seen = new Set();
    this.failures = 0;
    this.everLive = false;
    this.source = null;
    this.retryTimer = null;
    this.pollTimer = null;
    this.stopped = true;
    this.status = 'off';
  }

  start() {
    this.stopped = false;
    this.setStatus('connecting');
    if (!this.EventSourceImpl) {
      // No EventSource (very old browser): polling is all we can do.
      this.startPolling();
      return;
    }
    this.connect();
  }

  stop() {
    this.stopped = true;
    if (this.source) {
      this.source.close();
      this.source = null;
    }
    clearTimeout(this.retryTimer);
    this.stopPolling();
    this.setStatus('off');
  }

  async connect() {
    if (this.stopped) return;
    let ticket;
    try {
      ticket = await this.getTicket();
    } catch (error) {
      this.fail();
      return;
    }
    if (this.stopped) return;

    const resume = this.lastEventId ? `&lastEventId=${this.lastEventId}` : '';
    const source = new this.EventSourceImpl(
      `${this.baseUrl}/api/stream?ticket=${encodeURIComponent(ticket)}${resume}`
    );
    this.source = source;

    source.addEventListener('ready', () => {
      if (this.source !== source) return;
      const recovering = this.everLive || this.pollTimer;
      this.failures = 0;
      this.everLive = true;
      this.stopPolling();
      this.setStatus('live');
      // After a gap, let pages refetch once in case something fell outside
      // the server's replay window.
      if (recovering) this.onEvent({ type: 'resync' });
    });
    LIVE_EVENT_TYPES.forEach((type) => {
      source.addEventListener(type, (message) => {
        if (this.source === source) this.handle(type, message);
      });
    });
    source.onerror = () => {
      if (this.source !== source) return;
      // The ticket is single use, so EventSource's own retry would only get a
      // 401; close it and reconnect with a fresh ticket instead.
      source.close();
      this.source = null;
      this.fail();
    };
  }

  handle(type, message) {
    const id = Number(message.lastEventId);
    if (id) {
      if (this.seen.has(id)) return;
      this.seen.add(id);
      if (this.seen.size > MAX_REMEMBERED_IDS) {
        this.seen.delete(this.seen.values().next().value);
      }
      if (!this.lastEventId || id > this.lastEventId) this.lastEventId = id;
    }
    let data;
    try {
      data = JSON.parse(message.data);
    } catch (notJson) {
      return;
    }
    this.onEvent({ type, id: id || null, data });
  }

  fail() {
    if (this.stopped) return;
    this.failures += 1;
    if (this.failures >= this.failuresBeforePolling) {
      this.startPolling();
    } else {
      this.setStatus('reconnecting');
    }
    const delay = Math.min(this.maxBackoffMs, 1000 * 2 ** (this.failures - 1));
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }

  startPolling() {
    this.setStatus('polling');
    if (this.pollTimer) return;
    this.pollTimer = setInterval(() => this.onEvent({ type: 'poll' }), this.pollMs);
    this.onEvent({ type: 'poll' });
  }

  stopPolling() {
    clearInterval(this.pollTimer);
    this.pollTimer = null;
  }

  setStatus(status) {
    if (status !== this.status) {
      this.status = status;
      this.onStatus(status);
    }
  }
}
