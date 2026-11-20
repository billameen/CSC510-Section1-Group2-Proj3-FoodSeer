"""Shared HTTP helpers for the Project 1 pytest files.

Standard library only, on purpose. We are running these on four different
machines and nobody wants to debug a virtualenv at midnight -- `python3 -m
pytest` should just work.

Point the tests somewhere else with API_BASE_URL if your backend is not on 8080.
"""

import itertools
import json
import os
import time
import urllib.error
import urllib.request

API_BASE_URL = os.environ.get("API_BASE_URL", "http://localhost:8080")
ADMIN_USERNAME = os.environ.get("FOODSEER_ADMIN", "admin")
ADMIN_PASSWORD = os.environ.get("FOODSEER_ADMIN_PASSWORD", "admin123")

_suffix_counter = itertools.count()


def api_request(method, path, body=None, token=None, timeout=15):
    """Send one request and return (status_code, parsed_body).

    HTTP errors come back as ordinary return values rather than exceptions --
    most of these tests are checking *which* error the backend gives, so an
    exception here would just get caught again in every caller.
    """
    headers = {"Content-Type": "application/json"}
    data = json.dumps(body).encode("utf-8") if body is not None else None

    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(
        f"{API_BASE_URL}{path}", data=data, headers=headers, method=method
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, _parse_body(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, _parse_body(error.read().decode("utf-8"))


def _parse_body(text):
    if not text:
        return {}
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"raw": text}


def unique_suffix():
    """Letters-only unique string.

    AuthServiceImpl rejects any username containing a digit, so the usual
    timestamp suffix does not work here -- base 26 it is. time.time() only
    has 1-second resolution and os.getpid() is constant for the process, so
    a counter is mixed in too -- otherwise every call inside the same second
    of the same pytest run (i.e. most of a test file) collided on one name.
    """
    number = int(time.time()) * 10_000_000 + os.getpid() * 100 + (next(_suffix_counter) % 100)
    letters = []

    while number:
        number, remainder = divmod(number, 26)
        letters.append(chr(ord("a") + remainder))

    return "".join(reversed(letters)) or "a"


def unique_name(prefix):
    return f"{prefix}_{unique_suffix()}"


def new_account(role, prefix=None):
    username = unique_name(prefix or role.lower())
    return {
        "username": username,
        "email": f"{username}@example.com",
        "password": "testpass",
        "role": role,
    }


def login(username, password):
    status, body = api_request(
        "POST", "/auth/login", {"username": username, "password": password}
    )
    if status != 200:
        return None
    return body.get("accessToken")


def register_and_login(role, prefix=None):
    """Create a throwaway account of the given role, return (account, token).

    Customers and drivers use the public sign-up. Since Milestone 2 the
    public form rejects staff and admin, so those are created by the seeded
    admin through POST /api/users, the same way the app does it.
    """
    account = new_account(role, prefix)

    if role.lower() in ("staff", "admin"):
        status, body = api_request("POST", "/api/users", account, token=admin_token())
    else:
        status, body = api_request("POST", "/auth/register", account)
    if status != 200:
        raise AssertionError(f"could not register a {role}: {status} {body}")

    token = login(account["username"], account["password"])
    if not token:
        raise AssertionError(f"registered {account['username']} but could not log in")

    return account, token


def deliver_order(order_id):
    """Take an order through the real driver flow: claim, pick up, deliver.

    Rating needs a delivered order (Milestone 2); staff fulfilling an order
    only means it is ready. Test customers have no saved allergies, so no
    allergy hand-off acknowledgement is needed before pickup.
    """
    driver, token = register_and_login("driver", "delivery_driver")
    status, body = api_request("POST", f"/api/orders/{order_id}/claim", token=token)
    assert status == 200, body
    for step in ("Picked Up", "Delivered"):
        status, body = api_request(
            "POST",
            f"/api/orders/{order_id}",
            {"status": step, "username": driver["username"]},
            token=token,
        )
        assert status == 200, body
    return body


def admin_token():
    token = login(ADMIN_USERNAME, ADMIN_PASSWORD)
    if not token:
        raise AssertionError(
            "could not log in as the seeded admin -- is DataInitializer running?"
        )
    return token


def find_user_id(admin, username):
    status, users = api_request("GET", "/api/users", token=admin)
    assert status == 200, users

    for user in users:
        if user["username"] == username:
            return user["id"]

    raise AssertionError(f"{username} is not in the user list")


def require_backend():
    """Fail the whole module early if nothing is listening, instead of 20 confusing errors."""
    try:
        api_request("POST", "/auth/login", {"username": "ping", "password": "ping"}, timeout=5)
    except urllib.error.URLError as error:
        import pytest

        pytest.fail(
            f"no backend at {API_BASE_URL} ({error}). "
            "Start it with: cd food-seer-backend && mvn spring-boot:run"
        )
