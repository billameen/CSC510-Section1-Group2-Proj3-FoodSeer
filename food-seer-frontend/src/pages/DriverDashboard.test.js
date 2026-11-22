import React from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import DriverDashboard from './DriverDashboard';
import { LiveUpdatesProvider } from '../live/LiveUpdates';
import {
  getCurrentUser,
  fetchDriverDashboard,
  getAvailableOrders,
  getActiveOrders,
  claimOrder,
} from '../services/api';

jest.mock('../services/api', () => ({
  API_BASE_URL: 'http://api',
  isAuthenticated: () => true,
  getStreamTicket: jest.fn(),
  getCurrentUser: jest.fn(),
  fetchDriverDashboard: jest.fn(),
  getAvailableOrders: jest.fn(),
  getActiveOrders: jest.fn(),
  updateOrderStatus: jest.fn(),
  claimOrder: jest.fn(),
  acknowledgeAllergyHandoff: jest.fn(),
  getAllergyHandoffStatus: jest.fn(),
}));

const order = (id, status = 'Placed') => ({
  id,
  name: `Order ${id}`,
  status,
  cost: 12,
  foods: [{ id: 1, foodName: 'SOUP', price: 5, allergies: [] }],
  allergyRestrictions: '',
  customerUsername: 'customer',
});

// Lets the test push live events into the provider.
let live;
const createConnection = (options) => {
  live = options;
  return { start: () => options.onStatus('live'), stop: () => {} };
};

const renderPage = () =>
  render(
    <MemoryRouter>
      <LiveUpdatesProvider createConnection={createConnection}>
        <DriverDashboard />
      </LiveUpdatesProvider>
    </MemoryRouter>
  );

describe('DriverDashboard live updates', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getCurrentUser.mockResolvedValue({ username: 'dan' });
    fetchDriverDashboard.mockResolvedValue({ totalDeliveries: 0, totalEarning: 0 });
    getActiveOrders.mockResolvedValue([]);
    jest.spyOn(window, 'alert').mockImplementation(() => {});
  });

  afterEach(() => window.alert.mockRestore());

  test('an order another driver claims disappears without a reload', async () => {
    getAvailableOrders.mockResolvedValueOnce([order(1), order(2)]).mockResolvedValue([order(2)]);
    renderPage();
    expect(await screen.findByText(/Order #1/)).toBeInTheDocument();

    act(() => live.onEvent({ type: 'order.event', id: 10, data: { orderId: 1, event: 'CLAIMED' } }));

    await waitFor(() => expect(screen.queryByText(/Order #1/)).not.toBeInTheDocument());
    expect(screen.getByText(/Order #2/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Live');
  });

  test('claiming moves the order into Active Orders in place', async () => {
    getAvailableOrders.mockResolvedValueOnce([order(3)]).mockResolvedValue([]);
    getActiveOrders.mockResolvedValueOnce([]).mockResolvedValue([order(3, 'Claimed')]);
    claimOrder.mockResolvedValue(order(3, 'Claimed'));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /Claim/ }));

    expect(claimOrder).toHaveBeenCalledWith(3);
    expect(await screen.findByText('CLAIMED')).toBeInTheDocument();
  });

  test('losing a claim race explains why and refreshes the lists', async () => {
    getAvailableOrders.mockResolvedValueOnce([order(4)]).mockResolvedValue([]);
    claimOrder.mockRejectedValue(new Error('Order is already Claimed.'));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /Claim/ }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith('Order is already Claimed.'));
    await waitFor(() => expect(screen.queryByText(/Order #4/)).not.toBeInTheDocument());
  });
});
