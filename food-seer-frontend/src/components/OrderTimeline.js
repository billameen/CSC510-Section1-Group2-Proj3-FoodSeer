import React, { useCallback, useEffect, useState } from 'react';
import { getOrderEvents } from '../services/api';

const WHAT = {
  PLACED: 'Order placed',
  CLAIMED: 'Claimed',
  PICKED_UP: 'Picked up',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  IMPORTED: 'Earlier history wasn’t recorded',
};

const formatTime = (value) => {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
};

const who = (event) => {
  if (!event.actorUsername) return '';
  const role = event.actorRole ? ` (${event.actorRole.toLowerCase()})` : '';
  return ` by ${event.actorUsername}${role}`;
};

// Who changed an order and when, from GET /api/orders/{id}/events. Collapsed
// until opened; reloads when `status` changes, so an open timeline follows
// live updates.
const OrderTimeline = ({ orderId, status }) => {
  const [open, setOpen] = useState(false);
  const [events, setEvents] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      setEvents(await getOrderEvents(orderId));
      setError(null);
    } catch (err) {
      setError(err.message || 'Could not load the timeline.');
    }
  }, [orderId]);

  useEffect(() => {
    if (open) load();
  }, [open, status, load]);

  return (
    <div className="order-timeline">
      <button type="button" className="timeline-toggle" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? 'Hide timeline' : 'Show timeline'}
      </button>
      {open && error && <p className="timeline-error">{error}</p>}
      {open && !error && events === null && <p className="timeline-loading">Loading…</p>}
      {open && events && (
        <ol className="timeline-list">
          {events.map((event) => (
            <li key={event.id} className={`timeline-item ${event.type.toLowerCase()}`}>
              <span className="timeline-when">{formatTime(event.occurredAt)}</span>
              <span className="timeline-what">
                {event.type === 'ALLERGY_ACKNOWLEDGED' ? event.detail : WHAT[event.type] || event.type}
                {event.type !== 'IMPORTED' && who(event)}
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
};

export default OrderTimeline;
