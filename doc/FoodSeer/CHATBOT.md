### Backend (Java/Spring Boot)
- ✅ `ChatRequestDto` & `ChatResponseDto` - DTOs for chat communication
- ✅ `ChatService` & `ChatServiceImpl` - Service to communicate with Ollama API
- ✅ `ChatController` - REST endpoint at `/api/chat`
- ✅ Communicates with Ollama running on `localhost:11434`

### Frontend (React)
- ✅ `Chatbot.js` - Interactive chat component
- ✅ Asks 3 questions about the user's day (mood, hunger, preference)
- ✅ Filters foods based on user's budget and dietary restrictions
- ✅ Uses AI to recommend ONE perfect food item
- ✅ Direct "Order Now" button to create order immediately
- ✅ Beautiful chat UI with typing indicators and animations
- ✅ Added to navigation as "🤖 AI Assistant"



## How It Works

1. **User Input Collection:** The chatbot asks 3 questions to understand your current state
2. **Preference Integration:** It fetches your saved budget and dietary restrictions from your profile
3. **Food Filtering:** Foods are filtered based on:
   - Your budget (budget/moderate/premium/no-limit)
   - Your dietary restrictions (vegan/vegetarian/gluten-free)
4. **AI Analysis:** The filtered food list + your responses are sent to Ollama's qwen2.5:1.5b model
5. **Recommendation:** The AI selects the best food match and explains why it's perfect for you
6. **Quick Order:** You can order directly from the recommendation
