package FoodSeer.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import FoodSeer.chat.ChatHistory;
import FoodSeer.chat.ChatModelClient;
import FoodSeer.chat.ChatPromptBuilder;
import FoodSeer.chat.ChatReplyParser;
import FoodSeer.chat.ChatReplyParser.ParsedReply;
import FoodSeer.chat.ChatSafetyFilter;
import FoodSeer.chat.ChatTurn;
import FoodSeer.dto.ChatFoodDto;
import FoodSeer.dto.ChatRequestDto;
import FoodSeer.dto.ChatResponseDto;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.service.ChatService;
import FoodSeer.service.UserService;

/**
 * The food assistant.
 *
 * Every question goes through the same steps:
 * <ol>
 * <li>Load the menu and the customer's saved restrictions and budget.</li>
 * <li>Show the model only in-stock dishes that are safe for those
 * restrictions, each with its id.</li>
 * <li>Send the system message, the last few turns, and the new question, and
 * ask for {"reply": ..., "foodIds": [..]}.</li>
 * <li>Parse the answer, falling back to text only if it is not valid JSON.</li>
 * <li>Check every suggested id again with {@link ChatSafetyFilter}, whatever
 * the model said, and tell the customer if anything was left out.</li>
 * </ol>
 */
@Service
public class ChatServiceImpl implements ChatService {

    /** Reply used when the model suggested dishes but wrote no text. */
    static final String DEFAULT_SUGGESTION_REPLY = "Here's what I'd suggest.";

    /** Reply used when the model wrote nothing usable. */
    static final String EMPTY_REPLY = "Sorry, I didn't catch that. Could you ask again?";

    /** Replaces a reply that names a dish the customer is allergic to. */
    static final String UNSAFE_REPLY_WITH_OPTIONS = "Here are some options that fit your allergy list.";

    /** Replaces a reply that names a dish the customer is allergic to, when nothing safe is left. */
    static final String UNSAFE_REPLY_WITHOUT_OPTIONS = "I couldn't find a dish for that which is safe for your "
            + "allergy list. Try asking for something else, or browse all foods.";

    private static final Logger logger = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final FoodRepository foodRepository;
    private final UserService userService;
    private final ChatModelClient chatModelClient;
    private final int historyTurns;
    private final boolean prefilterMenu;

    /**
     * Creates the service the application uses.
     *
     * @param foodRepository
     *            the menu
     * @param userService
     *            finds the signed-in customer
     * @param chatModelClient
     *            the chat model, Ollama in production
     * @param historyTurns
     *            how many earlier messages to send with each question
     *            (foodseer.chat.history-turns, default 6)
     */
    @Autowired
    public ChatServiceImpl(final FoodRepository foodRepository, final UserService userService,
            final ChatModelClient chatModelClient,
            @Value("${foodseer.chat.history-turns:" + ChatHistory.DEFAULT_MAX_TURNS + "}") final int historyTurns) {
        this(foodRepository, userService, chatModelClient, historyTurns, true);
    }

    /**
     * Creates the service, choosing whether unsafe dishes are hidden from the
     * model. The application always hides them; the live chat eval turns this
     * off to measure how often the model itself would suggest an unsafe dish.
     *
     * @param foodRepository
     *            the menu
     * @param userService
     *            finds the signed-in customer
     * @param chatModelClient
     *            the chat model
     * @param historyTurns
     *            how many earlier messages to send
     * @param prefilterMenu
     *            true to show the model only dishes that are safe for the
     *            customer
     */
    public ChatServiceImpl(final FoodRepository foodRepository, final UserService userService,
            final ChatModelClient chatModelClient, final int historyTurns, final boolean prefilterMenu) {
        this.foodRepository = foodRepository;
        this.userService = userService;
        this.chatModelClient = chatModelClient;
        this.historyTurns = historyTurns;
        this.prefilterMenu = prefilterMenu;
    }

    /**
     * {@inheritDoc}
     *
     * @throws FoodSeer.exception.ChatUnavailableException
     *             if the model cannot answer, which the API reports as 503
     */
    @Override
    public ChatResponseDto sendMessage(final ChatRequestDto chatRequest) {
        final String message = chatRequest.getMessage() == null ? "" : chatRequest.getMessage().trim();

        final User user = userService.getCurrentUser();
        final String restrictions = user == null ? null : user.getDietaryRestrictions();

        final List<Food> menu = foodRepository.findByActiveTrue();
        final List<Food> offered = new ArrayList<>();
        for (final Food food : menu) {
            if (ChatSafetyFilter.isInStock(food)
                    && (!prefilterMenu || ChatSafetyFilter.isSafeFor(food, restrictions))) {
                offered.add(food);
            }
        }

        final List<ChatTurn> conversation = new ArrayList<>();
        conversation.add(ChatTurn.system(ChatPromptBuilder.systemPrompt(offered, user, prefilterMenu)));
        conversation.addAll(ChatHistory.recent(chatRequest.getHistory(), historyTurns));
        conversation.add(ChatTurn.user(message));

        final ParsedReply parsed = ChatReplyParser.parse(chatModelClient.complete(conversation));
        final ChatSafetyFilter.Result result = ChatSafetyFilter.filter(parsed.foodIds(), menu, restrictions);

        if (!parsed.structured()) {
            logger.debug("Chat model answered without the requested JSON; showing its text only");
        }
        if (result.droppedCount() > 0) {
            logger.info("Chat post-filter removed {} of {} suggested dishes ({})", result.droppedCount(),
                    parsed.foodIds().size(), result.dropped());
        }

        final List<ChatFoodDto> cards = new ArrayList<>();
        for (final Food food : result.safeFoods()) {
            cards.add(ChatFoodDto.of(food));
        }
        return new ChatResponseDto(replyText(parsed.reply(), result), cards, ChatSafetyFilter.noticeFor(result));
    }

    /**
     * Picks the text to show. The model's reply is used as is, unless it is
     * empty or it names a dish that was just blocked for the customer's
     * allergies, so the text never recommends what the cards left out.
     */
    static String replyText(final String reply, final ChatSafetyFilter.Result result) {
        final boolean hasCards = !result.safeFoods().isEmpty();
        if (reply == null || reply.isBlank()) {
            return hasCards ? DEFAULT_SUGGESTION_REPLY : EMPTY_REPLY;
        }
        for (final Food blocked : result.blockedForAllergen()) {
            if (mentions(reply, blocked.getFoodName())) {
                return hasCards ? UNSAFE_REPLY_WITH_OPTIONS : UNSAFE_REPLY_WITHOUT_OPTIONS;
            }
        }
        return reply;
    }

    /**
     * True if the text names the dish as a whole word or phrase, ignoring case
     * and a plural "s" ("cookie" names COOKIES).
     */
    public static boolean mentions(final String text, final String dishName) {
        if (dishName == null || dishName.isBlank()) {
            return false;
        }
        final String name = dishName.trim().toLowerCase(Locale.ROOT);
        final String stem = name.endsWith("s") && name.length() > 3 ? name.substring(0, name.length() - 1) : name;
        final String pattern = "(?<![\\p{L}\\p{N}])" + Pattern.quote(stem) + "s?(?![\\p{L}\\p{N}])";
        return Pattern.compile(pattern).matcher(text.toLowerCase(Locale.ROOT)).find();
    }
}
