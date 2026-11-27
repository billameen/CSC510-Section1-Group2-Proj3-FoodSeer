import { LiveConnection } from './liveConnection';

// A stand-in for the browser's EventSource that the test drives by hand.
class FakeEventSource {
  static instances = [];

  constructor(url) {
    this.url = url;
    this.listeners = {};
    this.closed = false;
    this.onerror = null;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type, listener) {
    (this.listeners[type] = this.listeners[type] || []).push(listener);
  }

  emit(type, { id, data = {} } = {}) {
    (this.listeners[type] || []).forEach((l) => l({ lastEventId: id ? String(id) : '', data: JSON.stringify(data) }));
  }

  fail() {
    this.onerror && this.onerror(new Event('error'));
  }

  close() {
    this.closed = true;
  }

  static latest() {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }
}

const flush = () => new Promise((resolve) => jest.requireActual('timers').setImmediate(resolve));

describe('LiveConnection', () => {
  let events;
  let statuses;
  let tickets;
  let connection;

  const make = (overrides = {}) => {
    connection = new LiveConnection({
      getTicket: jest.fn(async () => `t${++tickets}`),
      baseUrl: 'http://api',
      onEvent: (e) => events.push(e),
      onStatus: (s) => statuses.push(s),
      EventSourceImpl: FakeEventSource,
      ...overrides,
    });
    return connection;
  };

  beforeEach(() => {
    jest.useFakeTimers();
    FakeEventSource.instances = [];
    events = [];
    statuses = [];
    tickets = 0;
  });

  afterEach(() => {
    connection && connection.stop();
    jest.useRealTimers();
  });

  test('opens the stream with a ticket and goes live on hello', async () => {
    make().start();
    await flush();

    expect(FakeEventSource.latest().url).toBe('http://api/api/stream?ticket=t1');
    FakeEventSource.latest().emit('ready');
    expect(statuses).toEqual(['connecting', 'live']);
  });

  test('passes events on and drops repeats by id', async () => {
    make().start();
    await flush();
    const source = FakeEventSource.latest();
    source.emit('ready');

    source.emit('order.event', { id: 5, data: { orderId: 1, event: 'CLAIMED' } });
    source.emit('order.event', { id: 5, data: { orderId: 1, event: 'CLAIMED' } });
    source.emit('safety.notice', { id: 6, data: { noticeId: 9 } });

    expect(events).toEqual([
      { type: 'order.event', id: 5, data: { orderId: 1, event: 'CLAIMED' } },
      { type: 'safety.notice', id: 6, data: { noticeId: 9 } },
    ]);
  });

  test('reconnects with a fresh ticket and the last event id, then drops replayed repeats', async () => {
    make().start();
    await flush();
    const first = FakeEventSource.latest();
    first.emit('ready');
    first.emit('order.event', { id: 7, data: { orderId: 1 } });

    first.fail();
    expect(first.closed).toBe(true);
    expect(statuses[statuses.length - 1]).toBe('reconnecting');

    jest.advanceTimersByTime(1000);
    await flush();
    const second = FakeEventSource.latest();
    expect(second).not.toBe(first);
    expect(second.url).toBe('http://api/api/stream?ticket=t2&lastEventId=7');

    second.emit('ready');
    // The server replays a little early, so 7 comes again; 8 is new.
    second.emit('order.event', { id: 7, data: { orderId: 1 } });
    second.emit('order.event', { id: 8, data: { orderId: 2 } });

    expect(events.map((e) => e.type === 'order.event' ? e.id : e.type)).toEqual([7, 'resync', 8]);
    expect(statuses[statuses.length - 1]).toBe('live');
  });

  test('backs off 1 s, 2 s, 4 s and falls back to polling after 3 failures', async () => {
    make({ pollMs: 30000 }).start();
    await flush();

    FakeEventSource.latest().fail();
    jest.advanceTimersByTime(999);
    await flush();
    expect(FakeEventSource.instances).toHaveLength(1);
    jest.advanceTimersByTime(1);
    await flush();
    expect(FakeEventSource.instances).toHaveLength(2);

    FakeEventSource.latest().fail();
    jest.advanceTimersByTime(2000);
    await flush();
    expect(FakeEventSource.instances).toHaveLength(3);

    FakeEventSource.latest().fail();
    expect(statuses[statuses.length - 1]).toBe('polling');
    expect(events).toEqual([{ type: 'poll' }]);

    jest.advanceTimersByTime(30000);
    expect(events.filter((e) => e.type === 'poll').length).toBeGreaterThanOrEqual(2);
  });

  test('stops polling once the stream is back', async () => {
    make({ failuresBeforePolling: 1 }).start();
    await flush();
    FakeEventSource.latest().fail();
    expect(statuses[statuses.length - 1]).toBe('polling');

    jest.advanceTimersByTime(1000);
    await flush();
    FakeEventSource.latest().emit('ready');
    const polls = events.filter((e) => e.type === 'poll').length;
    jest.advanceTimersByTime(90000);

    expect(statuses[statuses.length - 1]).toBe('live');
    expect(events.filter((e) => e.type === 'poll').length).toBe(polls);
    expect(events[events.length - 1]).toEqual({ type: 'resync' });
  });

  test('a ticket request that fails counts as a failed connection', async () => {
    make({ getTicket: jest.fn().mockRejectedValue(new Error('offline')) }).start();
    await flush();

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(statuses[statuses.length - 1]).toBe('reconnecting');
  });

  test('without EventSource it polls', () => {
    make({ EventSourceImpl: undefined }).start();

    expect(statuses[statuses.length - 1]).toBe('polling');
    expect(events).toEqual([{ type: 'poll' }]);
  });

  test('stop closes the stream and cancels retries', async () => {
    make().start();
    await flush();
    const source = FakeEventSource.latest();
    source.fail();

    connection.stop();
    jest.advanceTimersByTime(60000);
    await flush();

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(statuses[statuses.length - 1]).toBe('off');
  });
});
