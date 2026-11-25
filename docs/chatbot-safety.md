# Grounded, allergy-safe chat assistant

Milestone 4. The AI Assistant page now recommends only real, in-stock dishes,
never one that contains something on the customer's allergy list, and says so
plainly when the model is down.

## What was wrong

| Problem | Before | Now |
| :- | :- | :- |
| Fresh install | Code asked for `qwen2.5:1.5b`, INSTALL said to pull `gemma3:1b`, so chat failed out of the box | One model, `qwen2.5:1.5b`, in code, INSTALL and README; configurable |
| Allergies | The model was never told the customer's allergies, and nothing checked its picks | Unsafe dishes are hidden from the model and every suggestion is checked again with `AllergenMatcher` |
| Menu | Sent only if the message contained "what should i eat", "recommend food" or "suggest food" | Always sent: the in-stock dishes that are safe for this customer, with ids |
| Memory | Every message was sent alone | The last 6 turns go with each question, so "anything cheaper?" works |
| Cards | The page matched dish names inside the reply text, so "steak" showed a TEA card and "pineapple" an APPLE card, and the reply text was dropped whenever a card matched | The model answers with dish ids; cards come from those ids after the safety check, and the reply text is always shown |
| Errors | Ollama down gave 200 with "Error: Connection refused" shown as if the assistant said it | 503 with a JSON message; the page says the assistant is unavailable |
| Timeouts | None; a stuck model hung the request | Read timeout `foodseer.ollama.timeout-ms` (default 60 s), connect timeout 5 s |

## How one question is answered

1. **Check the question.** Blank or longer than 1000 characters gives 400.
2. **Load the customer and the menu.** Their saved restrictions and budget
   come from their profile.
3. **Build the system message** (`ChatPromptBuilder`): the rules, the
   customer's restrictions and budget, and a table of the dishes they may be
   offered (`id | name | price | calories | contains`). Only active, in-stock
   dishes that pass `AllergenMatcher` for this customer are listed.
4. **Add the last 6 turns** (`ChatHistory`). Only "user" and "assistant"
   turns are kept, each cut to 1000 characters, so a client cannot inject its
   own "system" message.
5. **Ask Ollama** (`OllamaChatClient`) for `{"reply": "...", "foodIds": [..]}`
   using Ollama's JSON-schema structured output and a low temperature.
6. **Parse defensively** (`ChatReplyParser`). Code fences, prose around the
   JSON, ids as strings or objects are all accepted. If no JSON object can be
   found, the text is shown and no dish is suggested: ids are never guessed
   from free text.
7. **Check every suggestion** (`ChatSafetyFilter`). An id is kept only if it
   is an active dish, in stock, and none of its tags touch the customer's
   restrictions. At most 3 cards are shown. If anything was removed, the
   response carries a `notice`, for example "I left out 1 suggestion that
   contains something on your allergy list."
8. **Check the reply text.** If it names any dish the customer is allergic to
   (for example the model wrote "Try the cookies!" as plain text), it is
   replaced with a neutral sentence, so the words never recommend what the
   cards would block.

Steps 3 and 7 both apply the allergy check. Step 3 keeps the model from
seeing unsafe dishes at all; step 7 is the guarantee, because a model can
always invent an id or repeat one from the conversation.

## API

`POST /api/chat`, see [INSTALL.md](../INSTALL.md#api-endpoints) for an example.

| Field | |
| :- | :- |
| request `message` | Required, 1 to 1000 characters |
| request `history` | Optional, earlier `{role, content}` turns, oldest first |
| response `message` | Always present |
| response `foods` | Dish cards (`id`, `foodName`, `price`, `calories`, `allergies`), already checked |
| response `notice` | Set only when suggestions were left out |

| Status | When |
| :- | :- |
| 200 | Answered |
| 400 | Blank or too long message |
| 401 / 403 | Not signed in / a driver (see below) |
| 503 | Ollama not running, too slow, model not pulled, or no usable answer |

**Who can use it:** customers, staff and admins. Drivers get 403 on purpose:
the assistant recommends dishes to order and only customers can order. The
reasoning is in the Chat section of [authorization-matrix.md](authorization-matrix.md).

**Settings** (also as environment variables): `foodseer.ollama.url`,
`foodseer.ollama.model`, `foodseer.ollama.timeout-ms`, and
`foodseer.chat.history-turns` (default 6).

## The eval

**Claim:** whatever the model answers, 0 dishes that contain one of the
customer's allergens, are out of stock, or are not on the menu reach the
response, and the reply text never names a dish the customer is allergic to.

**Instrument:** `food-seer-backend/src/test/java/FoodSeer/eval/ChatSafetyEval.java`.
It runs the real `ChatServiceImpl` against a mocked model that is adversarial
on purpose: 15 allergy profiles (checkboxes and free text such as "peanut
allergy", "no dairy products", "egg whites", "soy sauce", "tahini") x 12
prompts x 12 attacks (every dish, only the unsafe ones, invented ids, ids as
strings or objects, JSON in a code fence, duplicates, 5000 ids, a reply
praising an unsafe dish, broken JSON naming dishes, an empty answer). Ground
truth does not use `AllergenMatcher`: each profile lists the allergens the
customer really has, and a separate table says what each menu tag means, so a
matcher mistake shows up as a failure instead of agreeing with itself. It runs
in CI as **Chat Safety Eval** and needs no Ollama.

```
cd food-seer-backend
mvn -Dtest=ChatSafetyEval test
cat target/chat-eval/chat-safety-report.md
```

### Result: the claim holds

2,160 responses (15 profiles x 12 prompts x 12 attacks; the 2 known-gap profiles are counted separately below):

| | Proposed by the model | Reached the customer |
| :- | -: | -: |
| Dishes with the customer's allergen | 9,288 | **0** |
| Invented or removed dishes | 895,320 | **0** |
| Out-of-stock dishes | 2,700 | **0** |

| Check | Result |
| :- | -: |
| Replies that name an unsafe dish | 0 |
| Unsafe dishes shown to the model in the menu | 0 |
| Suggestions left out without a notice | 0 |
| Responses with no reply text | 0 |
| Safe suggestions kept (up to 3 per answer) | 4,320 of 4,320 |

The last row matters as much as the zeros: the filter does not get its safety
by throwing away good suggestions.

### Does the eval catch a broken guard?

A safety eval that passes is only worth something if it fails when the guard is
removed. Each guard was broken on purpose, one at a time, and the eval rerun
(not committed):

| Guard removed | Eval result |
| :- | :- |
| Post-filter ignores allergies | FAIL: 1,908 unsafe dishes reached the customer |
| Reply text is not checked | FAIL: 336 replies named an unsafe dish |
| Unsafe dishes are not hidden from the model | FAIL: 12,384 unsafe dishes shown to the model |

### Known gaps

* **Ingredient names.** "whey" (milk) and "edamame" (soy) are the Milestone 1
  known gaps in `AllergenMatcher`. The eval measures them without asserting:
  for those two profiles 288 of 1,836 unsafe suggestions reached the customer.
  Invented and out-of-stock dishes are still blocked for them. The fix belongs
  in the matcher, not the chat, and will flow through to both.
* **Diets are not allergens.** "vegetarian" or "vegan" saved as a restriction
  does not hide MEAT dishes, because the matcher only knows allergens. The
  model is told the restriction, but nothing enforces it.
* **Budget is advice, not a rule.** The customer's budget is in the prompt;
  dishes over it are not removed.
* **The reply-text check is by dish name.** A reply that describes an unsafe
  dish without naming it ("the peanut one") is not caught. The cards, which
  are what the customer can order from, are always checked.

## The live eval (real model)

`ChatSafetyLiveEval` runs 7 profiles x 6 prompts against a real Ollama, twice:
as the app runs, and with the whole in-stock menu shown to the model. It
reports how often the model itself suggests an allergen, an invented dish, or
invalid JSON (a measure of model quality), and asserts that after the filter
the count is 0. It is opt-in because it needs Ollama and takes a few minutes:

```
FOODSEER_LIVE_OLLAMA=1 mvn -Dtest=ChatSafetyLiveEval test
cat target/chat-eval/live-report.md
```

| `qwen2.5:1.5b` | As the app runs | Whole menu shown |
| :- | -: | -: |
| Answers that were not valid JSON | not measured yet | not measured yet |
| Before the filter: suggestions with the customer's allergen | not measured yet | not measured yet |
| Before the filter: invented dishes | not measured yet | not measured yet |
| After the filter: bad dishes that reached the customer | must be 0 | must be 0 |
| Latency p50 / p95 | not measured yet | not measured yet |

Fill this table from `live-report.md` after a run on a machine with Ollama.

## Code map

| Piece | File |
| :- | :- |
| Service | `food-seer-backend/src/main/java/FoodSeer/service/impl/ChatServiceImpl.java` |
| Ollama client, settings, timeouts | `food-seer-backend/src/main/java/FoodSeer/chat/OllamaChatClient.java` |
| System message | `.../chat/ChatPromptBuilder.java` |
| History | `.../chat/ChatHistory.java` |
| Reply parsing | `.../chat/ChatReplyParser.java` |
| Safety filter | `.../chat/ChatSafetyFilter.java` |
| 503 | `.../exception/ChatUnavailableException.java`, `GlobalExceptionHandler.java` |
| Page | `food-seer-frontend/src/pages/Chatbot.js` |
| Evals | `food-seer-backend/src/test/java/FoodSeer/eval/ChatSafetyEval.java`, `ChatSafetyLiveEval.java` |
