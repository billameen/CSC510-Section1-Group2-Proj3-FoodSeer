import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { sendChatMessage, getCurrentUser } from '../services/api';

const WELCOME = "Hi! I'm FoodSeer. Tell me what you're craving.";

// Saved conversations use this key. Version 1 (`chatbotState_<id>`) stored
// dish cards as separate "system" messages matched by name; it is dropped on
// load because it cannot be shown in the new format.
const STATE_VERSION = 2;
const stateKey = (userId) => `chatbotState_v${STATE_VERSION}_${userId}`;
const legacyStateKey = (userId) => `chatbotState_${userId}`;

const welcomeMessages = () => [{ role: 'assistant', content: WELCOME }];

/**
 * The turns the backend should see: the customer's messages and the
 * assistant's real replies, without error messages.
 */
export const historyFor = (messages) =>
  messages
    .filter((m) => (m.role === 'user' || m.role === 'assistant') && !m.error && m.content)
    .map((m) => ({ role: m.role, content: m.content }));

const Chatbot = () => {
  const navigate = useNavigate();
  const messagesEndRef = useRef(null);

  const [currentUserId, setCurrentUserId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [inputMessage, setInputMessage] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [stateLoaded, setStateLoaded] = useState(false);

  // Load saved state
  const loadState = (userId) => {
    if (!userId) return null;
    try {
      localStorage.removeItem(legacyStateKey(userId));
      const saved = localStorage.getItem(stateKey(userId));
      const parsed = saved ? JSON.parse(saved) : null;
      return parsed && parsed.version === STATE_VERSION && Array.isArray(parsed.messages) ? parsed : null;
    } catch (err) {
      console.error('Error loading state:', err);
      return null;
    }
  };

  // Load user + state
  useEffect(() => {
    const loadUserAndState = async () => {
      try {
        const user = await getCurrentUser();
        setCurrentUserId(user.id);

        const saved = loadState(user.id);
        setMessages(saved && saved.messages.length ? saved.messages : welcomeMessages());
        setStateLoaded(true);
      } catch (err) {
        console.error(err);
        navigate('/');
      }
    };
    loadUserAndState();
  }, [navigate]);

  // Save state
  useEffect(() => {
    if (!currentUserId || !stateLoaded) return;
    localStorage.setItem(stateKey(currentUserId), JSON.stringify({ version: STATE_VERSION, messages }));
  }, [messages, currentUserId, stateLoaded]);

  // Auto scroll
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView?.({ behavior: 'smooth' });
  }, [messages]);

  // Handle chat send
  const handleSendMessage = async () => {
    const text = inputMessage.trim();
    if (!text || isLoading) return;

    const history = historyFor(messages);
    setMessages((prev) => [...prev, { role: 'user', content: text }]);
    setInputMessage('');
    setIsLoading(true);

    try {
      const reply = await sendChatMessage(text, history);
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: reply.message,
          foods: Array.isArray(reply.foods) ? reply.foods : [],
          notice: reply.notice || null,
        },
      ]);
    } catch (err) {
      console.error(err);
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: 'Sorry, something went wrong.', error: true },
      ]);
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  const handleOrderFood = (food) => {
    if (food) {
      navigate('/create-order', { state: { addToCart: food } });
    }
  };

  const handleStartOver = () => {
    const reset = welcomeMessages();
    setMessages(reset);
    if (currentUserId) {
      localStorage.setItem(stateKey(currentUserId), JSON.stringify({ version: STATE_VERSION, messages: reset }));
    }
  };

  const renderFoodCard = (food) => (
    <div key={food.id} className="recommendation-card" data-testid="recommendation-card">
      <h3>🎯 Recommended for You</h3>
      <div className="food-card">
        <h4>{food.foodName}</h4>
        <p className="food-price">${Number(food.price).toFixed(2)}</p>
        <p className="food-allergies">
          {food.allergies?.length
            ? `Contains: ${food.allergies.join(', ')}`
            : 'No common allergens'}
        </p>
        <div className="recommendation-actions">
          <button
            onClick={() => handleOrderFood(food)}
            className="btn-primary"
          >
            Order This Now
          </button>
          <button onClick={handleStartOver} className="btn-secondary">
            New Suggestion
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <div className="chatbot-container">
      <div className="chatbot-header">
        <h2>🤖 FoodSeer AI Assistant</h2>
        <p>Tell me your mood, cravings, or food style. I'll suggest the best dish.</p>
      </div>

      <div className="chatbot-messages">
        {messages.map((msg, index) => (
          <React.Fragment key={index}>
            <div className={`message ${msg.role}${msg.error ? ' error' : ''}`}>
              <div className="message-avatar">
                {msg.role === 'user' ? '👤' : '🤖'}
              </div>
              <div className="message-content">
                {msg.content}
                {msg.notice && <p className="chat-notice">{msg.notice}</p>}
              </div>
            </div>
            {msg.foods?.map(renderFoodCard)}
          </React.Fragment>
        ))}

        {isLoading && (
          <div className="message assistant">
            <div className="message-avatar">🤖</div>
            <div className="message-content typing">
              <span></span><span></span><span></span>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      <div className="chatbot-input">
        <input
          type="text"
          value={inputMessage}
          onChange={(e) => setInputMessage(e.target.value)}
          onKeyPress={handleKeyPress}
          placeholder="Tell me what you're craving..."
          disabled={isLoading}
          maxLength={1000}
        />
        <button
          onClick={handleSendMessage}
          disabled={isLoading || !inputMessage.trim()}
          className="btn-send"
        >
          Send
        </button>
      </div>

      <div className="chatbot-footer">
        <button
          onClick={() => navigate('/recommendations')}
          className="btn-link"
        >
          Browse All Foods
        </button>
      </div>
    </div>
  );
};

export default Chatbot;
