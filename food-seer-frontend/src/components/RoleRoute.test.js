import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import RoleRoute, { homeFor } from './RoleRoute';
import { getCurrentUser, isAuthenticated } from '../services/api';

jest.mock('../services/api', () => ({
  getCurrentUser: jest.fn(),
  isAuthenticated: jest.fn(),
}));

const renderAt = () => render(
  <MemoryRouter initialEntries={['/users']}>
    <Routes>
      <Route path="/" element={<div>Login page</div>} />
      <Route path="/preferences" element={<div>Customer home</div>} />
      <Route path="/order-management" element={<div>Staff home</div>} />
      <Route
        path="/users"
        element={<RoleRoute roles={['ROLE_ADMIN']}><div>User management</div></RoleRoute>}
      />
    </Routes>
  </MemoryRouter>
);

describe('RoleRoute', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    isAuthenticated.mockReturnValue(true);
  });

  test('shows the page to an allowed role', async () => {
    getCurrentUser.mockResolvedValue({ username: 'admin', role: 'ROLE_ADMIN' });

    renderAt();

    expect(await screen.findByText('User management')).toBeInTheDocument();
  });

  test('sends another role to its own home page', async () => {
    getCurrentUser.mockResolvedValue({ username: 'sid', role: 'ROLE_CUSTOMER' });

    renderAt();

    expect(await screen.findByText('Customer home')).toBeInTheDocument();
    expect(screen.queryByText('User management')).not.toBeInTheDocument();
  });

  test('sends staff to order management, not user management', async () => {
    getCurrentUser.mockResolvedValue({ username: 'staff', role: 'ROLE_STAFF' });

    renderAt();

    expect(await screen.findByText('Staff home')).toBeInTheDocument();
  });

  test('sends a signed-out visitor to the login page without calling the API', async () => {
    isAuthenticated.mockReturnValue(false);

    renderAt();

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(getCurrentUser).not.toHaveBeenCalled();
  });

  test('treats a failed user lookup as signed out', async () => {
    getCurrentUser.mockRejectedValue(new Error('401'));

    renderAt();

    expect(await screen.findByText('Login page')).toBeInTheDocument();
  });

  test('maps each role to its home page', () => {
    expect(homeFor('ROLE_ADMIN')).toBe('/order-management');
    expect(homeFor('ROLE_STAFF')).toBe('/order-management');
    expect(homeFor('ROLE_DRIVER')).toBe('/driver-dashboard');
    expect(homeFor('ROLE_CUSTOMER')).toBe('/preferences');
    expect(homeFor(undefined)).toBe('/');
  });
});
