const API_BASE_URL = 'http://localhost:8080';

const TOKEN_KEY = 'token';
const USERNAME_KEY = 'username';

// Helper function to get token from localStorage
const getAuthToken = () => {
  return localStorage.getItem(TOKEN_KEY);
};

const clearSession = () => {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USERNAME_KEY);
};

/**
 * Pulls a readable message out of a failed response. The backend answers with
 * {"message": ...} (GlobalExceptionHandler), {"error": ...} (sign-up) or plain
 * text (a few older endpoints).
 */
const readErrorMessage = async (response, fallback) => {
  let text = '';
  try {
    text = await response.text();
  } catch (readError) {
    return fallback;
  }
  if (!text) {
    return fallback;
  }
  try {
    const data = JSON.parse(text);
    if (data && typeof data === 'object') {
      return data.message || data.error || fallback;
    }
    return typeof data === 'string' ? data : fallback;
  } catch (notJson) {
    // Plain-text body, e.g. "Food is part of an unfulfilled order".
    return text.length < 300 ? text : fallback;
  }
};

/**
 * The one place the frontend talks to the backend.
 *
 * - attaches the saved token (unless auth is false)
 * - on 401 with a token, the session is over: clears the token and goes to
 *   /login, so every page handles an expired login the same way
 * - on any other error, throws an Error with the backend's message (or the
 *   fallback) and the HTTP status on error.status
 * - returns parsed JSON, plain text, or null for an empty body
 *
 * @param {string} path e.g. '/api/orders/my'
 * @param {object} options { method, body, auth, fallbackError }
 */
export const request = async (path, { method = 'GET', body, auth = true, fallbackError } = {}) => {
  const headers = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const token = auth ? getAuthToken() : null;
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 401 && token) {
    clearSession();
    if (window.location.pathname !== '/login' && window.location.pathname !== '/') {
      window.location.assign('/login');
    }
    const expired = new Error('Your session has expired. Please log in again.');
    expired.status = 401;
    throw expired;
  }

  if (!response.ok) {
    const message = await readErrorMessage(response, fallbackError || `Request failed (${response.status})`);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }

  const text = await response.text();
  if (!text) {
    return null;
  }
  const contentType = response.headers.get('Content-Type') || '';
  if (contentType.includes('application/json')) {
    return JSON.parse(text);
  }
  try {
    return JSON.parse(text);
  } catch (notJson) {
    return text;
  }
};

// Auth API calls
export const login = async (username, password) => {
  try {
    const data = await request('/auth/login', {
      method: 'POST',
      body: { username, password },
      auth: false,
      fallbackError: 'Login failed',
    });

    // Store token in localStorage
    if (data && data.accessToken) {
      localStorage.setItem(TOKEN_KEY, data.accessToken);
      localStorage.setItem(USERNAME_KEY, username);
      // The user may have typed their email; keep the real username.
      try {
        const me = await getCurrentUser();
        if (me && me.username) {
          localStorage.setItem(USERNAME_KEY, me.username);
        }
      } catch (lookupError) {
        // Keep what they typed; the token is still valid.
      }
    }

    return data;
  } catch (error) {
    console.error('Login error:', error);
    throw error;
  }
};

export const register = async (username, email, password, role) => {
  try {
    return await request('/auth/register', {
      method: 'POST',
      body: { username, email, password, role },
      auth: false,
      fallbackError: 'Registration failed',
    });
  } catch (error) {
    console.error('Registration error:', error);
    throw error;
  }
};

export const logout = () => {
  clearSession();
};

export const isAuthenticated = () => {
  return !!getAuthToken();
};

// User API calls
export const getCurrentUser = async () => {
  try {
    return await request('/api/users/me', { fallbackError: 'Failed to fetch user' });
  } catch (error) {
    console.error('Get current user error:', error);
    throw error;
  }
};

export const updateUserPreferences = async (costPreference, dietaryRestrictions, dailyCalorieGoal = null) => {
  try {
    return await request('/api/users/me/preferences', {
      method: 'PUT',
      body: { costPreference, dietaryRestrictions, dailyCalorieGoal },
      fallbackError: 'Failed to update preferences',
    });
  } catch (error) {
    console.error('Update preferences error:', error);
    throw error;
  }
};

export const getCalorieBudget = async () => {
  try {
    return await request('/api/users/me/calorieBudget', { fallbackError: 'Failed to fetch calorie budget' });
  } catch (error) {
    console.error('Get calorie budget error:', error);
    throw error;
  }
};

// fetches the signed-in user's per-day calorie consumption history, most recent day first
export const getCalorieHistory = async () => {
  try {
    return await request('/api/users/me/calorieHistory', { fallbackError: 'Failed to fetch calorie history' });
  } catch (error) {
    // the History page shows its own error state
    console.error('Get calorie history error:', error);
    throw error;
  }
};

export const getAllergens = async () => {
  try {
    return await request('/api/allergens', { fallbackError: 'Failed to fetch allergens' });
  } catch (error) {
    console.error('Get allergens error:', error);
    throw error;
  }
};

// Staff and admin: add a name to the shared allergen list.
export const addAllergen = async (name) => {
  try {
    return await request('/api/allergens', {
      method: 'POST',
      body: { name },
      fallbackError: 'Failed to add allergen',
    });
  } catch (error) {
    console.error('Add allergen error:', error);
    throw error;
  }
};

export const logMeal = async (mealType, calories) => {
  try {
    return await request('/api/users/me/mealLog', {
      method: 'POST',
      body: { mealType, calories },
      fallbackError: 'Failed to log meal',
    });
  } catch (error) {
    console.error('Log meal error:', error);
    throw error;
  }
};

// Food API calls
export const getAllFoods = async () => {
  try {
    return await request('/api/foods', { fallbackError: 'Failed to fetch foods' });
  } catch (error) {
    console.error('Get foods error:', error);
    throw error;
  }
};

export const getFoodsWithinCalorieBudget = async () => {
  try {
    return await request('/api/foods/withinCalorieBudget', {
      fallbackError: 'Failed to fetch calorie-budget-aware foods',
    });
  } catch (error) {
    console.error('Get calorie-budget foods error:', error);
    throw error;
  }
};

export const getFoodById = async (id) => {
  try {
    return await request(`/api/foods/${id}`, { fallbackError: 'Failed to fetch food' });
  } catch (error) {
    console.error('Get food error:', error);
    throw error;
  }
};

export const createFood = async (foodData) => {
  try {
    return await request('/api/foods', { method: 'POST', body: foodData, fallbackError: 'Failed to create food' });
  } catch (error) {
    console.error('Create food error:', error);
    throw error;
  }
};

export const updateFood = async (foodData) => {
  try {
    return await request('/api/foods/updateFood', {
      method: 'POST',
      body: foodData,
      fallbackError: 'Failed to update food',
    });
  } catch (error) {
    console.error('Update food error:', error);
    throw error;
  }
};

export const deleteFood = async (id) => {
  try {
    // 409 carries the reason (the food is in an unfulfilled order).
    return await request(`/api/foods/${id}`, { method: 'DELETE', fallbackError: 'Failed to delete food' });
  } catch (error) {
    console.error('Delete food error:', error);
    throw error;
  }
};

// Inventory API calls
export const getInventory = async () => {
  try {
    return await request('/api/inventory', { fallbackError: 'Failed to fetch inventory' });
  } catch (error) {
    console.error('Get inventory error:', error);
    throw error;
  }
};

export const updateInventory = async (inventoryData) => {
  try {
    return await request('/api/inventory', {
      method: 'POST',
      body: inventoryData,
      fallbackError: 'Failed to update inventory',
    });
  } catch (error) {
    console.error('Update inventory error:', error);
    throw error;
  }
};

// Order API calls

// Staff and admin: every order.
export const getAllOrders = async () => {
  try {
    return await request('/api/orders', { fallbackError: 'Failed to fetch orders' });
  } catch (error) {
    console.error('Get orders error:', error);
    throw error;
  }
};

// Customer: your own orders.
export const getMyOrders = async () => {
  try {
    return await request('/api/orders/my', { fallbackError: 'Failed to fetch my orders' });
  } catch (error) {
    console.error('Get my orders error:', error);
    throw error;
  }
};

export const getFulfilledOrders = async () => {
  try {
    return await request('/api/orders/fulfilledOrders', { fallbackError: 'Failed to fetch fulfilled orders' });
  } catch (error) {
    console.error('Get fulfilled orders error:', error);
    throw error;
  }
};

export const getUnfulfilledOrders = async () => {
  try {
    return await request('/api/orders/unfulfilledOrders', { fallbackError: 'Failed to fetch unfulfilled orders' });
  } catch (error) {
    console.error('Get unfulfilled orders error:', error);
    throw error;
  }
};

export const getOrderById = async (id) => {
  try {
    return await request(`/api/orders/${id}`, { fallbackError: 'Failed to fetch order' });
  } catch (error) {
    console.error('Get order error:', error);
    throw error;
  }
};

export const createOrder = async (orderData) => {
  try {
    return await request('/api/orders', { method: 'POST', body: orderData, fallbackError: 'Failed to create order' });
  } catch (error) {
    console.error('Create order error:', error);
    throw error;
  }
};

// Customers can cancel their own order while it is still placed; staff and
// admins can cancel anything not yet delivered. Cancelling puts the stock back.
export const cancelOrder = async (orderId) => {
  try {
    return await request(`/api/orders/${orderId}/cancel`, {
      method: 'POST',
      fallbackError: 'Failed to cancel order',
    });
  } catch (error) {
    console.error('Cancel order error:', error);
    throw error;
  }
};

// Admin API calls
export const getAllUsers = async () => {
  try {
    return await request('/api/users', { fallbackError: 'Failed to fetch users' });
  } catch (error) {
    console.error('Get users error:', error);
    throw error;
  }
};

// Admin: create an account of any role. This is the only way to create
// staff and admin accounts; public sign-up is customers and drivers only.
export const createUser = async (username, email, password, role) => {
  try {
    return await request('/api/users', {
      method: 'POST',
      body: { username, email, password, role },
      fallbackError: 'Failed to create account',
    });
  } catch (error) {
    console.error('Create user error:', error);
    throw error;
  }
};

export const getUserById = async (id) => {
  try {
    return await request(`/api/users/${id}`, { fallbackError: 'Failed to fetch user' });
  } catch (error) {
    console.error('Get user error:', error);
    throw error;
  }
};

export const updateUserRole = async (id, role) => {
  try {
    return await request(`/api/users/${id}/role`, {
      method: 'PUT',
      body: { role },
      fallbackError: 'Failed to update user role',
    });
  } catch (error) {
    console.error('Update user role error:', error);
    throw error;
  }
};

export const deleteUser = async (id) => {
  try {
    const result = await request(`/api/users/${id}`, { method: 'DELETE', fallbackError: 'Failed to delete user' });
    return result === null ? { success: true } : result;
  } catch (error) {
    console.error('Delete user error:', error);
    throw error;
  }
};

// Chat API calls
/**
 * Asks the food assistant.
 *
 * @param {string} message what the customer typed
 * @param {Array<{role: string, content: string}>} history earlier turns,
 *   oldest first; the backend keeps only the last few
 * @returns {Promise<{message: string, foods: object[], notice: string|null}>}
 *   a 503 (assistant unavailable) rejects with error.status === 503
 */
export const sendChatMessage = async (message, history = []) => {
  try {
    return await request('/api/chat', {
      method: 'POST',
      body: { message, history },
      fallbackError: 'Failed to send message to AI',
    });
  } catch (error) {
    console.error('Chat error:', error);
    throw error;
  }
};

export const rateFoodItem = async (orderId, foodId, rating) => {
  try {
    // rating is a query parameter (?rating=5.0); a repeat or an undelivered
    // order comes back with the backend's reason.
    return await request(`/api/foods/orders/${orderId}/${foodId}/rate?rating=${rating}`, {
      method: 'POST',
      fallbackError: 'Failed to submit rating',
    });
  } catch (error) {
    console.error('Rate food error:', error);
    throw error;
  }
};

// Driver API calls
export const fetchDriverDashboard = async (user) => {
  return request(`/api/driverStats?username=${encodeURIComponent(user)}`, {
    fallbackError: 'Failed to fetch driver dashboard data',
  });
};

export const acknowledgeAllergyHandoff = async (orderId) => {
  return request(`/api/orders/${orderId}/allergy-handoff/acknowledge`, {
    method: 'POST',
    fallbackError: 'Failed to acknowledge allergy warning',
  });
};

export const getAllergyHandoffStatus = async (orderId) => {
  return request(`/api/orders/${orderId}/allergy-handoff/status`, {
    fallbackError: 'Failed to fetch allergy handoff status',
  });
};

export const claimOrder = async (orderId) => {
  return request(`/api/orders/${orderId}/claim`, { method: 'POST', fallbackError: 'Failed to claim order' });
};

export const updateOrderStatus = async (orderId, status, username) => {
  try {
    return await request(`/api/orders/${orderId}`, {
      method: 'POST',
      body: { username, status },
      fallbackError: 'Failed to update order status',
    });
  } catch (error) {
    console.error('Update order status error:', error);
    throw error;
  }
};

export const getAvailableOrders = async () => {
  try {
    return await request('/api/orders/availableOrders', { fallbackError: 'Failed to fetch available orders' });
  } catch (error) {
    console.error('Get available orders error:', error);
    throw error;
  }
};

export const getActiveOrders = async (username) => {
  try {
    return await request(`/api/orders/activeOrders/${encodeURIComponent(username)}`, {
      fallbackError: 'Failed to fetch active orders',
    });
  } catch (error) {
    console.error('Get active orders error:', error);
    throw error;
  }
};

// Allergy trace-back API calls

// Staff: report that dishes may carry an allergen during a time window.
export const reportRestaurantIncident = async (allergen, foodIds, windowStart, windowEnd) => {
  return request('/api/incidents', {
    method: 'POST',
    body: { allergen, foodIds, windowStart, windowEnd },
    fallbackError: 'Failed to report the incident',
  });
};

// Customer: report a reaction after one of your own orders.
export const reportCustomerReaction = async (orderId, allergen) => {
  return request('/api/incidents/reaction', {
    method: 'POST',
    body: { orderId, allergen },
    fallbackError: 'Failed to report the reaction',
  });
};

// Staff: list every incident, newest first.
export const getIncidents = async () => {
  return request('/api/incidents', { fallbackError: 'Failed to load incidents' });
};

// Staff: acknowledge an incident and say what the restaurant offers.
export const acknowledgeIncident = async (incidentId, remedy) => {
  return request(`/api/incidents/${incidentId}/acknowledge`, {
    method: 'POST',
    body: { remedy },
    fallbackError: 'Failed to acknowledge the incident',
  });
};

// Staff: confirm which dishes in a pending customer report were at fault.
// Only those dishes are traced, and affected customers are warned.
export const confirmIncident = async (incidentId, foodIds) => {
  return request(`/api/incidents/${incidentId}/confirm`, {
    method: 'POST',
    body: { foodIds },
    fallbackError: 'Failed to confirm the report',
  });
};

// Staff: close a pending customer report without warning anyone.
export const dismissIncident = async (incidentId) => {
  return request(`/api/incidents/${incidentId}/dismiss`, {
    method: 'POST',
    fallbackError: 'Failed to dismiss the report',
  });
};

// Customer: your own safety notices, newest first.
export const getMyNotifications = async () => {
  return request('/api/notifications/my', { fallbackError: 'Failed to load notices' });
};

// Customer: how many of your notices you have not acknowledged yet.
export const getMyUnreadNotificationCount = async () => {
  const data = await request('/api/notifications/my/unread-count', {
    fallbackError: 'Failed to load the notice count',
  });
  return data.count;
};

// Customer: acknowledge one of your notices.
export const acknowledgeNotification = async (notificationId) => {
  return request(`/api/notifications/${notificationId}/acknowledge`, {
    method: 'POST',
    fallbackError: 'Failed to acknowledge the notice',
  });
};
