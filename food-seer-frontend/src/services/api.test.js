import { request, login, deleteUser, cancelOrder, sendChatMessage } from './api';

const jsonResponse = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: () => 'application/json' },
  text: () => Promise.resolve(body === undefined ? '' : JSON.stringify(body)),
});

const textResponse = (status, text) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: () => 'text/plain' },
  text: () => Promise.resolve(text),
});

describe('request()', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    global.fetch = jest.fn();
    localStorage.clear();
    delete window.location;
    window.location = { pathname: '/orders', assign: jest.fn() };
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    window.location = originalLocation;
    console.error.mockRestore();
  });

  test('attaches the saved token', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(jsonResponse(200, { ok: true }));

    await request('/api/orders/my');

    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('http://localhost:8080/api/orders/my');
    expect(options.headers.Authorization).toBe('Bearer abc');
  });

  test('sends no token when auth is false', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(jsonResponse(200, {}));

    await request('/auth/login', { method: 'POST', body: {}, auth: false });

    expect(fetch.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  test('a 401 with a token clears the session and goes to /login', async () => {
    localStorage.setItem('token', 'expired');
    localStorage.setItem('username', 'sid');
    fetch.mockResolvedValue(jsonResponse(401, { message: 'Unauthorized' }));

    await expect(request('/api/users/me')).rejects.toMatchObject({ status: 401 });

    expect(localStorage.getItem('token')).toBeNull();
    expect(localStorage.getItem('username')).toBeNull();
    expect(window.location.assign).toHaveBeenCalledWith('/login');
  });

  test('a 401 from a failed login does not redirect', async () => {
    fetch.mockResolvedValue(jsonResponse(401, { message: 'Unauthorized: Bad credentials' }));

    await expect(login('sid', 'wrong')).rejects.toThrow('Unauthorized: Bad credentials');

    expect(window.location.assign).not.toHaveBeenCalled();
  });

  test('uses the backend message from message, error or plain text', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValueOnce(jsonResponse(403, { message: 'Access Denied' }));
    await expect(request('/api/orders')).rejects.toMatchObject({ message: 'Access Denied', status: 403 });

    fetch.mockResolvedValueOnce(jsonResponse(400, { error: 'Email already in use' }));
    await expect(request('/auth/register', { auth: false })).rejects.toThrow('Email already in use');

    fetch.mockResolvedValueOnce(textResponse(409, 'Food is part of an unfulfilled order'));
    await expect(request('/api/foods/1', { method: 'DELETE' })).rejects.toThrow('Food is part of an unfulfilled order');
  });

  test('falls back when the error body is empty', async () => {
    fetch.mockResolvedValue(textResponse(500, ''));

    await expect(request('/api/foods', { fallbackError: 'Failed to fetch foods' }))
      .rejects.toThrow('Failed to fetch foods');
  });

  test('returns JSON, text, or null for an empty body', async () => {
    fetch.mockResolvedValueOnce(jsonResponse(200, [1, 2]));
    await expect(request('/a')).resolves.toEqual([1, 2]);

    fetch.mockResolvedValueOnce(textResponse(200, 'Food deleted successfully.'));
    await expect(request('/b')).resolves.toBe('Food deleted successfully.');

    fetch.mockResolvedValueOnce(textResponse(200, ''));
    await expect(request('/c')).resolves.toBeNull();
  });

  test('login keeps the real username after an email login', async () => {
    fetch
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'tok', tokenType: 'Bearer' }))
      .mockResolvedValueOnce(jsonResponse(200, { username: 'sid', role: 'ROLE_CUSTOMER' }));

    await login('sid@example.com', 'password1');

    expect(localStorage.getItem('token')).toBe('tok');
    expect(localStorage.getItem('username')).toBe('sid');
  });

  test('deleteUser still resolves for an empty 200', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(textResponse(200, ''));

    await expect(deleteUser(4)).resolves.toEqual({ success: true });
  });

  test('cancelOrder posts to the cancel endpoint', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(jsonResponse(200, { id: 7, status: 'Cancelled' }));

    await expect(cancelOrder(7)).resolves.toMatchObject({ status: 'Cancelled' });

    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('http://localhost:8080/api/orders/7/cancel');
    expect(options.method).toBe('POST');
  });

  test('cancelOrder passes on the reason for a 409', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(jsonResponse(409, { message: 'Order is already Claimed.' }));

    await expect(cancelOrder(7)).rejects.toMatchObject({ status: 409, message: 'Order is already Claimed.' });
  });

  test('sendChatMessage posts the message with the conversation history', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(jsonResponse(200, { message: 'Hi', foods: [], notice: null }));
    const history = [{ role: 'user', content: 'something sweet' }];

    const reply = await sendChatMessage('anything cheaper?', history);

    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('http://localhost:8080/api/chat');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body)).toEqual({ message: 'anything cheaper?', history });
    expect(reply.message).toBe('Hi');
  });

  test('sendChatMessage passes on a 503 so the page can say the assistant is down', async () => {
    localStorage.setItem('token', 'abc');
    fetch.mockResolvedValue(jsonResponse(503, { message: 'The food assistant is unavailable right now.' }));

    await expect(sendChatMessage('hi')).rejects.toMatchObject({
      status: 503,
      message: 'The food assistant is unavailable right now.',
    });
  });
});
