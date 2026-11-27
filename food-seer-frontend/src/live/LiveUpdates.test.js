import React from 'react';
import { act, render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import { LiveUpdatesProvider, LiveStatus, useLiveRefresh } from './LiveUpdates';

jest.mock('../services/api', () => ({
  API_BASE_URL: 'http://api',
  getStreamTicket: jest.fn(),
  isAuthenticated: jest.fn(),
}));
const api = require('../services/api');

// A connection the test controls: it hands back the options the provider gave it.
const fakeConnection = () => {
  const handle = { started: false, stopped: false };
  const create = (options) => {
    handle.options = options;
    return {
      start: () => { handle.started = true; options.onStatus('live'); },
      stop: () => { handle.stopped = true; },
    };
  };
  return { handle, create };
};

const Watcher = ({ types, onRefresh }) => {
  useLiveRefresh(types, onRefresh, 0);
  return <LiveStatus />;
};

describe('LiveUpdatesProvider', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  test('opens one stream when signed in and refreshes pages on matching events', () => {
    api.isAuthenticated.mockReturnValue(true);
    const { handle, create } = fakeConnection();
    const onOrders = jest.fn();
    const onNotices = jest.fn();

    render(
      <MemoryRouter>
        <LiveUpdatesProvider createConnection={create}>
          <Watcher types={['order.event']} onRefresh={onOrders} />
          <Watcher types={['safety.notice']} onRefresh={onNotices} />
        </LiveUpdatesProvider>
      </MemoryRouter>
    );

    expect(handle.started).toBe(true);
    expect(screen.getAllByText('● Live')).toHaveLength(2);

    act(() => {
      handle.options.onEvent({ type: 'order.event', id: 1, data: {} });
      jest.runAllTimers();
    });
    expect(onOrders).toHaveBeenCalledTimes(1);
    expect(onNotices).not.toHaveBeenCalled();

    act(() => {
      handle.options.onEvent({ type: 'poll' });
      jest.runAllTimers();
    });
    expect(onOrders).toHaveBeenCalledTimes(2);
    expect(onNotices).toHaveBeenCalledTimes(1);
  });

  test('a burst of events causes one refresh', () => {
    api.isAuthenticated.mockReturnValue(true);
    const { handle, create } = fakeConnection();
    const onOrders = jest.fn();
    const Debounced = () => {
      useLiveRefresh(['order.event'], onOrders, 250);
      return null;
    };

    render(
      <MemoryRouter>
        <LiveUpdatesProvider createConnection={create}>
          <Debounced />
        </LiveUpdatesProvider>
      </MemoryRouter>
    );
    act(() => {
      for (let id = 1; id <= 5; id++) handle.options.onEvent({ type: 'order.event', id, data: {} });
      jest.advanceTimersByTime(250);
    });

    expect(onOrders).toHaveBeenCalledTimes(1);
  });

  test('does nothing when signed out', () => {
    api.isAuthenticated.mockReturnValue(false);
    const { handle, create } = fakeConnection();

    render(
      <MemoryRouter>
        <LiveUpdatesProvider createConnection={create}>
          <LiveStatus />
        </LiveUpdatesProvider>
      </MemoryRouter>
    );

    expect(handle.started).toBe(false);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  test('outside the provider, subscribing is harmless', () => {
    const onRefresh = jest.fn();
    render(<Watcher types={['order.event']} onRefresh={onRefresh} />);

    expect(onRefresh).not.toHaveBeenCalled();
  });
});
