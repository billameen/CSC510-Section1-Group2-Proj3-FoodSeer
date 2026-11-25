package FoodSeer.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import FoodSeer.chat.ChatHistory;
import FoodSeer.chat.ChatModelClient;
import FoodSeer.chat.ChatTurn;
import FoodSeer.dto.ChatRequestDto;
import FoodSeer.dto.ChatResponseDto;
import FoodSeer.entity.Food;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.service.ChatService;

@Service
public class ChatServiceImpl implements ChatService {

    private final FoodRepository foodRepository;
    private final ChatModelClient chatModelClient;
    private final int historyTurns;

    /**
     * Creates the service.
     *
     * @param foodRepository
     *            the menu
     * @param chatModelClient
     *            the chat model, Ollama in production
     * @param historyTurns
     *            how many earlier messages to send with each question
     *            (foodseer.chat.history-turns, default 6)
     */
    public ChatServiceImpl(final FoodRepository foodRepository, final ChatModelClient chatModelClient,
            @Value("${foodseer.chat.history-turns:" + ChatHistory.DEFAULT_MAX_TURNS + "}") final int historyTurns) {
        this.foodRepository = foodRepository;
        this.chatModelClient = chatModelClient;
        this.historyTurns = historyTurns;
    }

    /**
     * {@inheritDoc}
     *
     * @throws FoodSeer.exception.ChatUnavailableException
     *             if the model cannot answer, which the API reports as 503
     */
    @Override
    public ChatResponseDto sendMessage(final ChatRequestDto chatRequest) {
        final List<ChatTurn> conversation = new ArrayList<>();
        conversation.add(ChatTurn.system(
            "You are FoodSeer AI. Respond naturally to greetings and normal conversation. " +
            "Only provide food recommendations when the user explicitly asks for it. " +
            "Do not add emojis, marketing text, or default food suggestions. Keep answers concise and factual."
        ));
        conversation.addAll(ChatHistory.recent(chatRequest.getHistory(), historyTurns));
        conversation.add(ChatTurn.user(chatRequest.getMessage()));

        // Determine if user explicitly asked for a recommendation
        String msgLower = chatRequest.getMessage().toLowerCase();
        if (msgLower.contains("what should i eat") ||
            msgLower.contains("recommend food") ||
            msgLower.contains("suggest food")) {

            // Fetch menu from DB
            List<Food> foods = foodRepository.findByActiveTrue();
            String menuString = foods.stream()
                    .map(f -> f.getFoodName() + " ($" + f.getPrice() + ")"
                            + (f.getAllergies() != null && !f.getAllergies().isEmpty()
                                ? " Contains: " + String.join(", ", f.getAllergies())
                                : " No common allergens"))
                    .collect(Collectors.joining("; "));

            conversation.add(ChatTurn.system("Available foods with prices and allergies: " + menuString));
        }

        return new ChatResponseDto(chatModelClient.complete(conversation));
    }
}
