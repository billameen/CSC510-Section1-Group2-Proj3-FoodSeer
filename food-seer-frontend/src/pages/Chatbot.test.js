import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Chatbot, {
  historyFor,
  SLOW_AFTER_MS,
  UNAVAILABLE_MESSAGE,
  OFFLINE_MESSAGE,
  GENERIC_ERROR_MESSAGE,
} from './Chatbot';
import { sendChatMessage, getCurrentUser } from '../services/api';

jest.mock('../services/api', () => ({
  sendChatMessage: jest.fn(),
  getCurrentUser: jest.fn(),
}));

const mockNavigate = jest.fn();
jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => mockNavigate,
}));

const STEAK = { id: 4, foodName: 'STEAK', price: 25, calories: 700, allergies: ['MEAT', 'BEEF'] };
const SALAD = { id: 1, foodName: 'GARDEN SALAD', price: 8, calories: 250, allergies: [] };

const renderPage = () => render(
  <MemoryRouter>
    <Chatbot />
  </MemoryRouter>
);

const ask = async (text) => {
  await userEvent.type(screen.getByPlaceholderText("Tell me what you're craving..."), text);
  await userEvent.click(screen.getByRole('button', { name: 'Send' }));
};

describe('Chatbot', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    getCurrentUser.mockResolvedValue({ id: 7, username: 'sam' });
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    console.error.mockRestore();
  });

  test('a reply that mentions steak shows no TEA card', async () => {
    sendChatMessage.mockResolvedValue({ message: 'I recommend the steak tonight!', foods: [], notice: null });
    renderPage();
    await screen.findByText("Hi! I'm FoodSeer. Tell me what you're craving.");

    await ask('dinner?');

    expect(await screen.findByText('I recommend the steak tonight!')).toBeInTheDocument();
    expect(screen.queryByTestId('recommendation-card')).not.toBeInTheDocument();
    expect(screen.queryByText('TEA')).not.toBeInTheDocument();
  });

  test('a reply that mentions pineapple shows no APPLE card', async () => {
    sendChatMessage.mockResolvedValue({ message: 'Pineapple is not on the menu, sorry.', foods: [], notice: null });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('pineapple?');

    expect(await screen.findByText('Pineapple is not on the menu, sorry.')).toBeInTheDocument();
    expect(screen.queryByText('APPLE')).not.toBeInTheDocument();
  });

  test('cards come from the response foods and the reply text is still shown', async () => {
    sendChatMessage.mockResolvedValue({ message: 'Both are good picks.', foods: [STEAK, SALAD], notice: null });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('something filling');

    expect(await screen.findByText('Both are good picks.')).toBeInTheDocument();
    const cards = screen.getAllByTestId('recommendation-card');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('STEAK');
    expect(cards[0]).toHaveTextContent('$25.00');
    expect(cards[0]).toHaveTextContent('Contains: MEAT, BEEF');
    expect(cards[1]).toHaveTextContent('GARDEN SALAD');
    expect(cards[1]).toHaveTextContent('No common allergens');
  });

  test('shows the notice when the backend left suggestions out', async () => {
    sendChatMessage.mockResolvedValue({
      message: 'Here are some options that fit your allergy list.',
      foods: [SALAD],
      notice: 'I left out 1 suggestion that contains something on your allergy list.',
    });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('cookies please');

    expect(await screen.findByText('I left out 1 suggestion that contains something on your allergy list.'))
      .toBeInTheDocument();
  });

  test('a follow-up question sends the earlier turns as history', async () => {
    sendChatMessage
      .mockResolvedValueOnce({ message: 'Try the steak.', foods: [STEAK], notice: null })
      .mockResolvedValueOnce({ message: 'The salad is cheaper.', foods: [SALAD], notice: null });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('something filling');
    await screen.findByText('Try the steak.');
    await ask('anything cheaper?');
    await screen.findByText('The salad is cheaper.');

    expect(sendChatMessage).toHaveBeenNthCalledWith(1, 'something filling', [
      { role: 'assistant', content: "Hi! I'm FoodSeer. Tell me what you're craving." },
    ]);
    expect(sendChatMessage).toHaveBeenNthCalledWith(2, 'anything cheaper?', [
      { role: 'assistant', content: "Hi! I'm FoodSeer. Tell me what you're craving." },
      { role: 'user', content: 'something filling' },
      { role: 'assistant', content: 'Try the steak.' },
    ]);
  });

  test('Order This Now adds that exact dish to the cart', async () => {
    sendChatMessage.mockResolvedValue({ message: 'Steak!', foods: [STEAK], notice: null });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('dinner');
    await userEvent.click(await screen.findByRole('button', { name: 'Order This Now' }));

    expect(mockNavigate).toHaveBeenCalledWith('/create-order', { state: { addToCart: STEAK } });
  });

  test('restores a saved conversation and drops the old saved format', async () => {
    localStorage.setItem('chatbotState_7', JSON.stringify({
      messages: [{ role: 'system', content: 'recommendation-card', food: { foodName: 'TEA', price: 2 } }],
    }));
    localStorage.setItem('chatbotState_v2_7', JSON.stringify({
      version: 2,
      messages: [{ role: 'assistant', content: 'Welcome back.', foods: [SALAD], notice: null }],
    }));

    renderPage();

    expect(await screen.findByText('Welcome back.')).toBeInTheDocument();
    expect(screen.getByTestId('recommendation-card')).toHaveTextContent('GARDEN SALAD');
    expect(screen.queryByText('recommendation-card')).not.toBeInTheDocument();
    expect(localStorage.getItem('chatbotState_7')).toBeNull();
  });

  test('saves the conversation in the new format', async () => {
    sendChatMessage.mockResolvedValue({ message: 'Salad!', foods: [SALAD], notice: null });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('lunch');
    await screen.findByText('Salad!');

    await waitFor(() => {
      const saved = JSON.parse(localStorage.getItem('chatbotState_v2_7'));
      expect(saved.version).toBe(2);
      expect(saved.messages[saved.messages.length - 1]).toEqual(
        { role: 'assistant', content: 'Salad!', foods: [SALAD], notice: null }
      );
    });
  });
});

describe('Chatbot errors and loading', () => {
  const httpError = (status, message) => Object.assign(new Error(message), { status });

  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    getCurrentUser.mockResolvedValue({ id: 7, username: 'sam' });
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    console.error.mockRestore();
    jest.useRealTimers();
  });

  test('a 503 says the assistant is unavailable instead of showing the raw error', async () => {
    sendChatMessage.mockRejectedValue(
      httpError(503, "The food assistant's model (qwen2.5:1.5b) is not installed on the server.")
    );
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('hello');

    expect(await screen.findByText(UNAVAILABLE_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByText(/qwen2.5/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Browse All Foods' })).toBeEnabled();
  });

  test('the input works again after a 503 and errors are not sent as history', async () => {
    sendChatMessage
      .mockRejectedValueOnce(httpError(503, 'down'))
      .mockResolvedValueOnce({ message: 'Back now.', foods: [], notice: null });
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('hello');
    await screen.findByText(UNAVAILABLE_MESSAGE);
    await ask('hello again');

    expect(await screen.findByText('Back now.')).toBeInTheDocument();
    const secondHistory = sendChatMessage.mock.calls[1][1];
    expect(secondHistory.map((t) => t.content)).not.toContain(UNAVAILABLE_MESSAGE);
    expect(secondHistory.map((t) => t.content)).toContain('hello');
  });

  test('a network failure says FoodSeer could not be reached', async () => {
    sendChatMessage.mockRejectedValue(new TypeError('Failed to fetch'));
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('hello');

    expect(await screen.findByText(OFFLINE_MESSAGE)).toBeInTheDocument();
  });

  test('a 400 shows the backend reason', async () => {
    sendChatMessage.mockRejectedValue(httpError(400, 'Messages to the assistant can be at most 1000 characters.'));
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('x');

    expect(await screen.findByText('Messages to the assistant can be at most 1000 characters.'))
      .toBeInTheDocument();
  });

  test('any other error gets a generic message', async () => {
    sendChatMessage.mockRejectedValue(httpError(500, 'stack trace here'));
    renderPage();
    await screen.findByText(/I'm FoodSeer/);

    await ask('x');

    expect(await screen.findByText(GENERIC_ERROR_MESSAGE)).toBeInTheDocument();
  });

  test('a slow answer shows the typing indicator, then a still-thinking hint', async () => {
    sendChatMessage.mockReturnValue(new Promise(() => {}));
    renderPage();
    await screen.findByText(/I'm FoodSeer/);
    jest.useFakeTimers();

    fireEvent.change(screen.getByPlaceholderText("Tell me what you're craving..."), { target: { value: 'hi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(screen.getByLabelText('The assistant is typing')).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Tell me what you're craving...")).toBeDisabled();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    act(() => {
      jest.advanceTimersByTime(SLOW_AFTER_MS);
    });

    expect(screen.getByRole('status')).toHaveTextContent('Still thinking');
  });
});

describe('historyFor', () => {
  test('keeps user and assistant text and skips errors', () => {
    expect(historyFor([
      { role: 'assistant', content: 'Hi' },
      { role: 'user', content: 'pizza' },
      { role: 'assistant', content: 'Sorry, something went wrong.', error: true },
      { role: 'assistant', content: 'Pizza it is.', foods: [STEAK], notice: 'x' },
    ])).toEqual([
      { role: 'assistant', content: 'Hi' },
      { role: 'user', content: 'pizza' },
      { role: 'assistant', content: 'Pizza it is.' },
    ]);
  });
});
