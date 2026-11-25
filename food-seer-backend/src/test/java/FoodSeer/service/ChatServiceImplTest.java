package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import FoodSeer.chat.ChatModelClient;
import FoodSeer.chat.ChatTurn;
import FoodSeer.dto.ChatFoodDto;
import FoodSeer.dto.ChatRequestDto;
import FoodSeer.dto.ChatResponseDto;
import FoodSeer.dto.ChatTurnDto;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.exception.ChatUnavailableException;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.service.impl.ChatServiceImpl;

class ChatServiceImplTest {

    private static final Food SALAD = food(1, "GARDEN SALAD", 5, 8);
    private static final Food COOKIES = food(2, "COOKIES", 5, 4, "MILK", "EGGS", "PEANUTS");
    private static final Food TEA = food(3, "TEA", 5, 2, "CAFFEINE");
    private static final Food STEAK = food(4, "STEAK", 5, 25, "MEAT", "BEEF");
    private static final Food SALMON = food(5, "SALMON", 0, 18, "FISH");

    private FoodRepository foodRepository;
    private UserService userService;
    private ChatModelClient model;
    private ChatServiceImpl chatService;

    private static Food food(final long id, final String name, final int stock, final int price,
            final String... tags) {
        final Food food = new Food(name, stock, price, new ArrayList<>(Arrays.asList(tags)));
        food.setId(id);
        return food;
    }

    @BeforeEach
    void setUp() {
        foodRepository = mock(FoodRepository.class);
        userService = mock(UserService.class);
        model = mock(ChatModelClient.class);
        when(foodRepository.findByActiveTrue()).thenReturn(List.of(SALAD, COOKIES, TEA, STEAK, SALMON));
        signedInWith("PEANUTS");
        chatService = new ChatServiceImpl(foodRepository, userService, model, 6);
    }

    private void signedInWith(final String restrictions) {
        when(userService.getCurrentUser())
                .thenReturn(User.builder().username("sam").dietaryRestrictions(restrictions).build());
    }

    private void modelAnswers(final String raw) {
        when(model.complete(anyList())).thenReturn(raw);
    }

    @SuppressWarnings("unchecked")
    private List<ChatTurn> sentConversation() {
        final ArgumentCaptor<List<ChatTurn>> sent = ArgumentCaptor.forClass(List.class);
        verify(model).complete(sent.capture());
        return sent.getValue();
    }

    private static List<Long> cardIds(final ChatResponseDto response) {
        return response.getFoods().stream().map(ChatFoodDto::getId).toList();
    }

    @Test
    void aGreetingGetsTextAndNoCards() {
        modelAnswers("{\"reply\": \"Hi! What are you in the mood for?\", \"foodIds\": []}");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("hello"));

        assertEquals("Hi! What are you in the mood for?", response.getMessage());
        assertTrue(response.getFoods().isEmpty());
        assertNull(response.getNotice());
    }

    @Test
    void theModelAlwaysSeesTheMenuWithoutATriggerPhrase() {
        modelAnswers("{\"reply\": \"ok\", \"foodIds\": []}");

        chatService.sendMessage(new ChatRequestDto("I'm hungry, any ideas?"));

        final String system = sentConversation().get(0).content();
        assertEquals(ChatTurn.SYSTEM, sentConversation().get(0).role());
        assertTrue(system.contains("1 | GARDEN SALAD | $8"), system);
        assertTrue(system.contains("4 | STEAK | $25"), system);
    }

    @Test
    void theModelOnlySeesInStockDishesThatAreSafeForTheCustomer() {
        modelAnswers("{\"reply\": \"ok\", \"foodIds\": []}");

        chatService.sendMessage(new ChatRequestDto("what should I eat?"));

        final String system = sentConversation().get(0).content();
        assertFalse(system.contains("COOKIES"), "peanut dish shown to a peanut-allergic customer");
        assertFalse(system.contains("SALMON"), "out-of-stock dish shown");
        assertTrue(system.contains("Customer allergies and dietary restrictions: PEANUTS"));
    }

    @Test
    void cardsAreTheModelsIdsAfterTheSafetyFilter() {
        modelAnswers("{\"reply\": \"Try these.\", \"foodIds\": [2, 999, 5, 1, 3]}");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("food please"));

        assertEquals(List.of(1L, 3L), cardIds(response));
        assertEquals("Try these.", response.getMessage());
        assertEquals("I left out 1 suggestion that contains something on your allergy list. "
                + "I left out 2 suggestions that aren't available on the menu right now.", response.getNotice());
    }

    @Test
    void aCardCarriesWhatThePageNeeds() {
        modelAnswers("{\"reply\": \"Steak!\", \"foodIds\": [4]}");

        final ChatFoodDto card = chatService.sendMessage(new ChatRequestDto("dinner")).getFoods().get(0);

        assertEquals(4L, card.getId());
        assertEquals("STEAK", card.getFoodName());
        assertEquals(25, card.getPrice());
        assertEquals(List.of("MEAT", "BEEF"), card.getAllergies());
    }

    @Test
    void aReplyNamingABlockedDishIsReplaced() {
        modelAnswers("{\"reply\": \"The cookies are perfect for you!\", \"foodIds\": [2, 1]}");

        final ChatResponseDto response = chatService
                .sendMessage(new ChatRequestDto("I'm allergic to peanuts but I really want cookies"));

        assertEquals(List.of(1L), cardIds(response));
        assertFalse(response.getMessage().toLowerCase().contains("cookie"), response.getMessage());
        assertTrue(response.getNotice().contains("allergy list"));
    }

    @Test
    void aReplyNamingABlockedDishWithNothingSafeLeftSaysSo() {
        modelAnswers("{\"reply\": \"Have a cookie.\", \"foodIds\": [2]}");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("cookies"));

        assertTrue(response.getFoods().isEmpty());
        assertTrue(response.getMessage().startsWith("I couldn't find a dish"), response.getMessage());
    }

    @Test
    void textOutsideJsonIsShownWithoutCards() {
        modelAnswers("I think the steak would be great tonight!");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("dinner?"));

        assertEquals("I think the steak would be great tonight!", response.getMessage());
        assertTrue(response.getFoods().isEmpty(), "names in free text never become cards");
    }

    @Test
    void cardsWithoutTextGetADefaultReply() {
        modelAnswers("{\"foodIds\": [1]}");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("lunch"));

        assertEquals(List.of(1L), cardIds(response));
        assertFalse(response.getMessage().isBlank());
    }

    @Test
    void anEmptyAnswerGetsAFriendlyReply() {
        modelAnswers("");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("lunch"));

        assertFalse(response.getMessage().isBlank());
        assertTrue(response.getFoods().isEmpty());
    }

    @Test
    void recentHistoryIsSentBetweenTheSystemMessageAndTheNewQuestion() {
        modelAnswers("{\"reply\": \"The salad is cheaper.\", \"foodIds\": [1]}");
        final ChatRequestDto request = new ChatRequestDto("anything cheaper?");
        request.setHistory(List.of(new ChatTurnDto("assistant", "Hi! I'm FoodSeer."),
                new ChatTurnDto("user", "something filling"), new ChatTurnDto("assistant", "Try the steak.")));

        chatService.sendMessage(request);

        final List<ChatTurn> conversation = sentConversation();
        assertEquals(5, conversation.size());
        assertEquals(ChatTurn.SYSTEM, conversation.get(0).role());
        assertEquals(ChatTurn.user("something filling"), conversation.get(2));
        assertEquals(ChatTurn.assistant("Try the steak."), conversation.get(3));
        assertEquals(ChatTurn.user("anything cheaper?"), conversation.get(4));
    }

    @Test
    void aCustomerWithNoRestrictionsSeesTheWholeInStockMenu() {
        signedInWith(null);
        modelAnswers("{\"reply\": \"Cookies!\", \"foodIds\": [2]}");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("sweet"));

        assertEquals(List.of(2L), cardIds(response));
        assertTrue(sentConversation().get(0).content().contains("COOKIES"));
    }

    @Test
    void withoutPrefilteringTheModelSeesUnsafeDishesButTheyAreStillBlocked() {
        chatService = new ChatServiceImpl(foodRepository, userService, model, 6, false);
        modelAnswers("{\"reply\": \"Here.\", \"foodIds\": [2, 3]}");

        final ChatResponseDto response = chatService.sendMessage(new ChatRequestDto("sweet"));

        assertTrue(sentConversation().get(0).content().contains("COOKIES"));
        assertEquals(List.of(3L), cardIds(response));
    }

    @Test
    void anUnavailableModelIsNotDisguisedAsAReply() {
        when(model.complete(anyList()))
                .thenThrow(new ChatUnavailableException("The food assistant is unavailable right now."));

        // GlobalExceptionHandler turns this into 503.
        assertThrows(ChatUnavailableException.class, () -> chatService.sendMessage(new ChatRequestDto("hello")));
    }

    @Test
    void blankMessagesAreRejectedWithoutCallingTheModel() {
        for (final String blank : Arrays.asList(null, "", "   ")) {
            assertThrows(IllegalArgumentException.class, () -> chatService.sendMessage(new ChatRequestDto(blank)));
        }
        assertThrows(IllegalArgumentException.class, () -> chatService.sendMessage(null));
        verify(model, never()).complete(anyList());
    }

    @Test
    void overlongMessagesAreRejectedWithoutCallingTheModel() {
        final String tooLong = "a".repeat(ChatServiceImpl.MAX_MESSAGE_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> chatService.sendMessage(new ChatRequestDto(tooLong)));
        verify(model, never()).complete(anyList());
    }

    @Test
    void aMessageAtTheLimitIsAccepted() {
        modelAnswers("{\"reply\": \"ok\", \"foodIds\": []}");

        assertEquals("ok", chatService
                .sendMessage(new ChatRequestDto("a".repeat(ChatServiceImpl.MAX_MESSAGE_LENGTH))).getMessage());
    }

    @Test
    void mentionsMatchesWholeDishNamesIncludingTheSingular() {
        assertTrue(ChatServiceImpl.mentions("A cookie would be nice", "COOKIES"));
        assertTrue(ChatServiceImpl.mentions("Get the COOKIES.", "COOKIES"));
        assertTrue(ChatServiceImpl.mentions("try the ice cream!", "ICE CREAM"));
        assertFalse(ChatServiceImpl.mentions("a steak dinner", "TEA"));
        assertFalse(ChatServiceImpl.mentions("pineapple", "APPLE"));
        assertFalse(ChatServiceImpl.mentions("anything", null));
    }
}
