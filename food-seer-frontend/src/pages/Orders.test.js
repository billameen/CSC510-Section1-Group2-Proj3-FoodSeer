import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Orders from './Orders';
import { getMyOrders, getCurrentUser, cancelOrder } from '../services/api';

jest.mock('../services/api', () => ({
  getMyOrders: jest.fn(),
  getCurrentUser: jest.fn(),
  cancelOrder: jest.fn(),
  rateFoodItem: jest.fn(),
  reportCustomerReaction: jest.fn(),
  updateUserPreferences: jest.fn(),
}));

const order = (id, status) => ({
  id,
  name: `Order ${id}`,
  status,
  foods: [{ id: 10 + id, foodName: 'SOUP', price: 5, allergies: [] }],
  ratedFoodIds: [],
});

const renderPage = () => render(
  <MemoryRouter>
    <Orders />
  </MemoryRouter>
);

describe('Orders', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getCurrentUser.mockResolvedValue({ username: 'customer', dietaryRestrictions: '' });
    jest.spyOn(window, 'confirm').mockReturnValue(true);
    jest.spyOn(window, 'alert').mockImplementation(() => {});
  });

  afterEach(() => {
    window.confirm.mockRestore();
    window.alert.mockRestore();
  });

  test('only a placed order can be cancelled', async () => {
    getMyOrders.mockResolvedValue([order(1, 'Placed'), order(2, 'Claimed'), order(3, 'Delivered')]);

    renderPage();

    expect(await screen.findByText('Order 1')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Cancel order' })).toHaveLength(1);
    expect(screen.getByText('✓ Delivered')).toBeInTheDocument();
  });

  test('cancelling calls the API and reloads the orders', async () => {
    getMyOrders
      .mockResolvedValueOnce([order(1, 'Placed')])
      .mockResolvedValueOnce([order(1, 'Cancelled')]);
    cancelOrder.mockResolvedValue(order(1, 'Cancelled'));

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel order' }));

    expect(cancelOrder).toHaveBeenCalledWith(1);
    expect(await screen.findByText('✕ Cancelled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  test('a refused cancel shows the server reason', async () => {
    getMyOrders.mockResolvedValue([order(1, 'Placed')]);
    cancelOrder.mockRejectedValue(new Error('Order is already Claimed.'));

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel order' }));

    await waitFor(() => expect(window.alert).toHaveBeenCalledWith('Order is already Claimed.'));
  });

  test('cancelled orders have their own filter', async () => {
    getMyOrders.mockResolvedValue([order(1, 'Placed'), order(2, 'Cancelled')]);

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Cancelled (1)' }));

    expect(screen.getByText('Order 2')).toBeInTheDocument();
    expect(screen.queryByText('Order 1')).not.toBeInTheDocument();
  });
});
