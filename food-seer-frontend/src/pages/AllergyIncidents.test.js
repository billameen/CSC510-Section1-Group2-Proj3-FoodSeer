import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AllergyIncidents from './AllergyIncidents';
import {
  getCurrentUser,
  getAllFoods,
  getIncidents,
  reportRestaurantIncident,
  acknowledgeIncident,
  confirmIncident,
  dismissIncident,
} from '../services/api';

jest.mock('../services/api', () => ({
  getCurrentUser: jest.fn(),
  getAllFoods: jest.fn(),
  getIncidents: jest.fn(),
  reportRestaurantIncident: jest.fn(),
  acknowledgeIncident: jest.fn(),
  confirmIncident: jest.fn(),
  dismissIncident: jest.fn(),
}));

const openIncident = {
  id: 4,
  source: 'CUSTOMER',
  allergen: 'PEANUT',
  sourceOrderId: 8,
  suspectFoodIds: [30],
  suspectFoodNames: ['COOKIES'],
  windowStart: '2026-10-12T10:00:00',
  windowEnd: '2026-10-12T14:00:00',
  reportedAt: '2026-10-12T12:30:00',
  exposedOrderCount: 5,
  notifiedCount: 2,
  restaurantAcknowledgedAt: null,
  acknowledgedBy: null,
  remedy: null,
  status: 'ACTIVE',
  reviewedBy: 'staff1',
};

// A customer's reaction that staff have not reviewed yet: nobody warned.
const pendingIncident = {
  ...openIncident,
  id: 5,
  sourceOrderId: 9,
  suspectFoodIds: [30, 31],
  suspectFoodNames: ['COOKIES', 'FRUIT SALAD'],
  exposedOrderCount: 0,
  notifiedCount: 0,
  status: 'PENDING_REVIEW',
  reviewedBy: null,
};

// The card for one incident, so its dish checkboxes are not confused with the
// report form's dish checkboxes, which use the same dish names.
const findCard = async (title) => (await screen.findByText(title)).closest('.trace-card');

const renderPage = () => render(
  <MemoryRouter>
    <AllergyIncidents />
  </MemoryRouter>
);

describe('AllergyIncidents', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getCurrentUser.mockResolvedValue({ username: 'staff', role: 'ROLE_STAFF' });
    getAllFoods.mockResolvedValue([
      { id: 30, foodName: 'COOKIES' },
      { id: 31, foodName: 'FRUIT SALAD' },
    ]);
    getIncidents.mockResolvedValue([openIncident]);
  });

  test('lists incidents with counts and no customer names', async () => {
    renderPage();

    expect(await screen.findByText('Incident #4: PEANUT')).toBeInTheDocument();
    expect(screen.getByText(/a customer, after order #8/)).toBeInTheDocument();
    expect(screen.getByText('⏳ Needs a response')).toBeInTheDocument();
  });

  test('requires an allergen before reporting', async () => {
    renderPage();

    userEvent.click(await screen.findByText('Trace and warn customers'));

    expect(await screen.findByText('Please enter the allergen.')).toBeInTheDocument();
    expect(reportRestaurantIncident).not.toHaveBeenCalled();
  });

  test('requires at least one dish before reporting', async () => {
    renderPage();

    userEvent.type(await screen.findByPlaceholderText('For example: Peanuts'), 'Peanuts');
    userEvent.click(screen.getByText('Trace and warn customers'));

    expect(await screen.findByText('Please select at least one dish.')).toBeInTheDocument();
    expect(reportRestaurantIncident).not.toHaveBeenCalled();
  });

  test('reports an incident and shows the counts', async () => {
    reportRestaurantIncident.mockResolvedValue({ incidentId: 9, exposedOrderCount: 3, notifiedCount: 1 });

    renderPage();
    userEvent.type(await screen.findByPlaceholderText('For example: Peanuts'), 'Peanuts');
    userEvent.click(screen.getByLabelText('COOKIES'));
    userEvent.click(screen.getByText('Trace and warn customers'));

    expect(await screen.findByText(/Incident #9 saved\. 3 orders fell in the window, and 1 customer/))
      .toBeInTheDocument();
    expect(reportRestaurantIncident).toHaveBeenCalledWith('Peanuts', [30], expect.any(String), expect.any(String));
  });

  test('shows the backend message when reporting fails', async () => {
    reportRestaurantIncident.mockRejectedValue(new Error('The window start must not be after the window end.'));

    renderPage();
    userEvent.type(await screen.findByPlaceholderText('For example: Peanuts'), 'Peanuts');
    userEvent.click(screen.getByLabelText('COOKIES'));
    userEvent.click(screen.getByText('Trace and warn customers'));

    expect(await screen.findByText('The window start must not be after the window end.')).toBeInTheDocument();
  });

  test('acknowledging needs an offer, then sends it', async () => {
    acknowledgeIncident.mockResolvedValue({ ...openIncident, remedy: 'Full refund' });

    renderPage();
    userEvent.click(await screen.findByText('Acknowledge'));
    expect(await screen.findByText(/Please describe what the restaurant offers/)).toBeInTheDocument();
    expect(acknowledgeIncident).not.toHaveBeenCalled();

    userEvent.type(screen.getByPlaceholderText(/What do you offer/), 'Full refund');
    userEvent.click(screen.getByText('Acknowledge'));

    await waitFor(() => expect(acknowledgeIncident).toHaveBeenCalledWith(4, 'Full refund'));
  });

  test('a pending customer report says nobody was warned and cannot be acknowledged yet', async () => {
    getIncidents.mockResolvedValue([pendingIncident]);

    renderPage();
    const card = await findCard('Incident #5: PEANUT');

    expect(within(card).getByText('🔎 Waiting for review')).toBeInTheDocument();
    expect(within(card).getByText(/Nobody has been warned yet/)).toBeInTheDocument();
    expect(within(card).queryByText('Acknowledge')).not.toBeInTheDocument();
    expect(within(card).getByLabelText('COOKIES')).not.toBeChecked();
    expect(within(card).getByLabelText('FRUIT SALAD')).not.toBeChecked();
  });

  test('confirming needs a ticked dish', async () => {
    getIncidents.mockResolvedValue([pendingIncident]);

    renderPage();
    const card = await findCard('Incident #5: PEANUT');
    userEvent.click(within(card).getByText('Confirm and warn customers'));

    expect(await screen.findByText(/Please tick the dish that carried the allergen/)).toBeInTheDocument();
    expect(confirmIncident).not.toHaveBeenCalled();
  });

  test('confirming sends only the ticked dish and shows the counts', async () => {
    getIncidents.mockResolvedValue([pendingIncident]);
    confirmIncident.mockResolvedValue({ incidentId: 5, exposedOrderCount: 1, notifiedCount: 1 });

    renderPage();
    const card = await findCard('Incident #5: PEANUT');
    userEvent.click(within(card).getByLabelText('COOKIES'));
    userEvent.click(within(card).getByText('Confirm and warn customers'));

    expect(await screen.findByText(/Incident #5 confirmed\. 1 order fell in the window, and 1 customer/))
      .toBeInTheDocument();
    expect(confirmIncident).toHaveBeenCalledWith(5, [30]);
    expect(getIncidents).toHaveBeenCalledTimes(2);
  });

  test('shows the backend message when confirming fails', async () => {
    getIncidents.mockResolvedValue([pendingIncident]);
    confirmIncident.mockRejectedValue(new Error('Food 99 was not in the order this reaction was reported on.'));

    renderPage();
    const card = await findCard('Incident #5: PEANUT');
    userEvent.click(within(card).getByLabelText('COOKIES'));
    userEvent.click(within(card).getByText('Confirm and warn customers'));

    expect(await screen.findByText('Food 99 was not in the order this reaction was reported on.'))
      .toBeInTheDocument();
  });

  test('dismissing asks first and does nothing if staff cancel', async () => {
    getIncidents.mockResolvedValue([pendingIncident]);
    const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(false);

    renderPage();
    const card = await findCard('Incident #5: PEANUT');
    userEvent.click(within(card).getByText('Dismiss'));

    expect(confirmSpy).toHaveBeenCalled();
    expect(dismissIncident).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  test('dismissing closes the report without warning anyone', async () => {
    getIncidents.mockResolvedValue([pendingIncident]);
    dismissIncident.mockResolvedValue({ ...pendingIncident, status: 'DISMISSED', reviewedBy: 'staff' });
    const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true);

    renderPage();
    const card = await findCard('Incident #5: PEANUT');
    userEvent.click(within(card).getByText('Dismiss'));

    expect(await screen.findByText('Incident #5 dismissed. Nobody was warned.')).toBeInTheDocument();
    expect(dismissIncident).toHaveBeenCalledWith(5);
    confirmSpy.mockRestore();
  });

  test('a dismissed report shows who dismissed it and has no actions', async () => {
    getIncidents.mockResolvedValue([{ ...pendingIncident, status: 'DISMISSED', reviewedBy: 'staff1' }]);

    renderPage();
    const card = await findCard('Incident #5: PEANUT');

    expect(within(card).getByText('✕ Dismissed')).toBeInTheDocument();
    expect(within(card).getByText('Dismissed by staff1')).toBeInTheDocument();
    expect(within(card).queryByText('Acknowledge')).not.toBeInTheDocument();
    expect(within(card).queryByText('Dismiss')).not.toBeInTheDocument();
  });
});
