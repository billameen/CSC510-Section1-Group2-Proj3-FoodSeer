import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import userEvent from '@testing-library/user-event';
import OrderTimeline from './OrderTimeline';
import { getOrderEvents } from '../services/api';

jest.mock('../services/api', () => ({ getOrderEvents: jest.fn() }));

const event = (id, type, extra = {}) => ({
  id,
  type,
  fromStatus: null,
  toStatus: 'Placed',
  actorUsername: 'alice',
  actorRole: 'CUSTOMER',
  occurredAt: '2026-11-22T12:00:00',
  detail: null,
  ...extra,
});

describe('OrderTimeline', () => {
  beforeEach(() => jest.clearAllMocks());

  test('loads only when opened and lists who did what', async () => {
    getOrderEvents.mockResolvedValue([
      event(1, 'PLACED'),
      event(2, 'CLAIMED', { actorUsername: 'dan', actorRole: 'DRIVER' }),
      event(3, 'ALLERGY_ACKNOWLEDGED', { actorUsername: 'sam', actorRole: 'STAFF',
        detail: 'Restaurant staff acknowledged the allergy warning.' }),
    ]);
    render(<OrderTimeline orderId={4} status="Claimed" />);
    expect(getOrderEvents).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Show timeline' }));

    expect(getOrderEvents).toHaveBeenCalledWith(4);
    expect(await screen.findByText(/Order placed by alice \(customer\)/)).toBeInTheDocument();
    expect(screen.getByText(/Claimed by dan \(driver\)/)).toBeInTheDocument();
    expect(screen.getByText(/Restaurant staff acknowledged the allergy warning\. by sam/)).toBeInTheDocument();
  });

  test('reloads when the order status changes while open', async () => {
    getOrderEvents.mockResolvedValue([event(1, 'PLACED')]);
    const { rerender } = render(<OrderTimeline orderId={4} status="Placed" />);
    await userEvent.click(screen.getByRole('button', { name: 'Show timeline' }));
    await screen.findByText(/Order placed/);

    getOrderEvents.mockResolvedValue([event(1, 'PLACED'), event(2, 'CANCELLED')]);
    rerender(<OrderTimeline orderId={4} status="Cancelled" />);

    expect(await screen.findByText(/Cancelled by alice/)).toBeInTheDocument();
    expect(getOrderEvents).toHaveBeenCalledTimes(2);
  });

  test('older orders say their history starts later', async () => {
    getOrderEvents.mockResolvedValue([event(1, 'IMPORTED', { actorUsername: null, actorRole: null })]);
    render(<OrderTimeline orderId={4} status="Delivered" />);

    await userEvent.click(screen.getByRole('button', { name: 'Show timeline' }));

    expect(await screen.findByText('Earlier history wasn’t recorded')).toBeInTheDocument();
  });

  test('shows the error if it can’t load', async () => {
    getOrderEvents.mockRejectedValue(new Error('You can only view your own orders.'));
    render(<OrderTimeline orderId={4} status="Placed" />);

    await userEvent.click(screen.getByRole('button', { name: 'Show timeline' }));

    expect(await screen.findByText('You can only view your own orders.')).toBeInTheDocument();
  });
});
