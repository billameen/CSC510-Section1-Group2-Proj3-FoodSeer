import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { API_BASE_URL, getStreamTicket, isAuthenticated } from '../services/api';
import { LiveConnection } from './liveConnection';

// One live stream per browser tab, shared by every page through this context.
// (The server allows 3 streams per user, so one per page would run out after
// two tabs.) Outside the provider, as in page tests, subscribing is a no-op.
const LiveContext = createContext({ status: 'off', subscribe: () => () => {} });

export const LiveUpdatesProvider = ({ children, createConnection }) => {
  const location = useLocation();
  // Re-read on every navigation, so logging in or out starts or stops the stream.
  const signedIn = isAuthenticated();
  const listeners = useRef(new Set());
  const [status, setStatus] = useState('off');

  useEffect(() => {
    if (!signedIn) {
      setStatus('off');
      return undefined;
    }
    const options = {
      getTicket: async () => (await getStreamTicket()).ticket,
      baseUrl: API_BASE_URL,
      onEvent: (event) => listeners.current.forEach((listener) => listener(event)),
      onStatus: setStatus,
    };
    const connection = createConnection ? createConnection(options) : new LiveConnection(options);
    connection.start();
    return () => connection.stop();
  }, [signedIn, createConnection]);

  const subscribe = useCallback((listener) => {
    listeners.current.add(listener);
    return () => listeners.current.delete(listener);
  }, []);

  // location is read so the provider re-renders (and re-checks the session) on navigation.
  return (
    <LiveContext.Provider value={{ status, subscribe, path: location.pathname }}>
      {children}
    </LiveContext.Provider>
  );
};

export const useLiveStatus = () => useContext(LiveContext).status;

// Calls refresh when one of the given event types arrives, and on 'poll' and
// 'resync' (fallback polling and recovery after a gap). Bursts are collapsed
// into one refresh.
export const useLiveRefresh = (types, refresh, delayMs = 250) => {
  const { subscribe } = useContext(LiveContext);
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  const key = types.join(',');

  useEffect(() => {
    let timer = null;
    const wanted = new Set([...key.split(','), 'poll', 'resync']);
    const unsubscribe = subscribe((event) => {
      if (!wanted.has(event.type)) return;
      clearTimeout(timer);
      timer = setTimeout(() => refreshRef.current(event), delayMs);
    });
    return () => {
      clearTimeout(timer);
      unsubscribe();
    };
  }, [key, subscribe, delayMs]);
};

const LABELS = {
  live: { text: '● Live', className: 'live' },
  connecting: { text: '○ Connecting…', className: 'connecting' },
  reconnecting: { text: '○ Reconnecting…', className: 'connecting' },
  polling: { text: '↻ Updating every 30 s', className: 'polling' },
};

// A small pill saying whether this page is updating live.
export const LiveStatus = () => {
  const status = useLiveStatus();
  const label = LABELS[status];
  if (!label) return null;
  return (
    <span className={`live-status ${label.className}`} role="status" aria-live="polite">
      {label.text}
    </span>
  );
};
