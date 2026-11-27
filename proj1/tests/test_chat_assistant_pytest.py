"""Use Case #15 -- Chat with the food assistant.

ChatServiceImpl talks to a local Ollama at localhost:11434 using qwen2.5:1.5b.
Half of this file only makes sense with Ollama up and half only makes sense
with it down, so the tests skip themselves accordingly. To see the interesting
failure, stop Ollama and run it again.
"""

import urllib.error
import urllib.request

import pytest

from api_helpers import api_request, register_and_login, require_backend

OLLAMA_TAGS_URL = "http://localhost:11434/api/tags"


def ollama_is_up():
    try:
        with urllib.request.urlopen(OLLAMA_TAGS_URL, timeout=2):
            return True
    except (urllib.error.URLError, OSError):
        return False


OLLAMA_UP = ollama_is_up()


@pytest.fixture(scope="module", autouse=True)
def backend():
    require_backend()


@pytest.fixture(scope="module")
def customer():
    _, token = register_and_login("customer", "chatter")
    return token


def test_the_assistant_cannot_be_used_without_logging_in():
    """This proves the endpoint is behind authentication."""
    status, body = api_request("POST", "/api/chat", {"message": "hello"})

    assert status == 401, body


def test_a_driver_cannot_use_the_assistant():
    """This proves extension 1a.

    Drivers are left out on purpose: the assistant recommends dishes to order
    and only customers can place orders. See the Chat section of
    docs/authorization-matrix.md.
    """
    _, driver = register_and_login("driver", "hungry_driver")

    status, body = api_request("POST", "/api/chat", {"message": "hello"}, token=driver)

    assert status == 403, body


def test_a_blank_message_is_rejected(customer):
    """A blank question is a 400 and never reaches the model, so this needs no Ollama."""
    status, body = api_request("POST", "/api/chat", {"message": "   "}, token=customer)

    assert status == 400, body


@pytest.mark.skipif(not OLLAMA_UP, reason="no Ollama on localhost:11434")
def test_suggested_dishes_are_real_menu_dishes(customer):
    """Every card in the answer is a dish on the menu, with its id.

    The response always has reply text, a list of dish cards, and a notice that
    is only set when a suggestion was left out (see docs/chatbot-safety.md).
    """
    status, body = api_request(
        "POST", "/api/chat", {"message": "what should I eat?", "history": []},
        token=customer, timeout=120,
    )

    assert status == 200, body
    assert body["message"].strip()
    assert isinstance(body["foods"], list)
    assert "notice" in body
    _, menu = api_request("GET", "/api/foods", token=customer)
    menu_ids = {food["id"] for food in menu}
    for card in body["foods"]:
        assert card["id"] in menu_ids, card


@pytest.mark.skipif(not OLLAMA_UP, reason="no Ollama on localhost:11434")
def test_the_assistant_answers_a_greeting(customer):
    """This proves the main success scenario, when the model is actually there."""
    status, body = api_request(
        "POST", "/api/chat", {"message": "hello"}, token=customer, timeout=120
    )

    assert status == 200, body
    assert body["message"].strip(), "the assistant returned an empty reply"
    assert not body["message"].startswith("Error:"), body["message"]


@pytest.mark.skipif(
    OLLAMA_UP, reason="only meaningful with Ollama stopped -- stop it and rerun"
)
def test_an_unreachable_model_is_reported_as_a_failure(customer):
    """This proves extension 4a.

    This used to fail: ChatServiceImpl caught every exception and returned 200
    with "Error: Connection refused" in the field a real answer uses. It now
    answers 503 with a JSON message the frontend can show as "assistant
    unavailable".
    """
    status, body = api_request(
        "POST", "/api/chat", {"message": "hello"}, token=customer, timeout=60
    )

    assert status == 503, (
        f"backend hid an unreachable model behind {status} "
        f"with body {body.get('message')!r}"
    )
    assert "unavailable" in body["message"].lower(), body
