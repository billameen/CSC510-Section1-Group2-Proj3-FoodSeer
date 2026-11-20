"""Use Case #10 -- Update order status (pick up / deliver).

Needs the backend running and the seeded admin account (admin / admin123).

Since Milestone 3 every status change goes through one state machine:
Placed -> Claimed -> Picked Up -> Delivered, and anything not delivered can
be Cancelled. A move the table doesn't allow is a 409, a status that isn't
one of these is a 400, and acting on someone else's order is a 403. The
extension tests below used to document the bugs; they now check the fix.
"""

import pytest

from api_helpers import (
    api_request,
    admin_token,
    register_and_login,
    require_backend,
)


@pytest.fixture(scope="module", autouse=True)
def backend():
    require_backend()


@pytest.fixture(scope="module")
def admin():
    return admin_token()


def get_in_stock_food(token):
    status, foods = api_request("GET", "/api/foods", token=token)
    assert status == 200, foods
    in_stock = [f for f in foods if isinstance(f.get("amount"), int) and f["amount"] > 0]
    assert in_stock, "no in-stock foods available for this test"
    return in_stock[0]


def create_order(token, food, name):
    # Only the items: the server prices the order and sets its status.
    payload = {"name": name, "foods": [{"id": food["id"]}]}
    status, body = api_request("POST", "/api/orders", payload, token=token)
    assert status == 200, body
    return body


def claim(order_id, token):
    return api_request("POST", f"/api/orders/{order_id}/claim", token=token)


def get_order(order_id, token):
    status, body = api_request("GET", f"/api/orders/{order_id}", token=token)
    assert status == 200, body
    return body


def update_status(order_id, status, username, token):
    return api_request(
        "POST", f"/api/orders/{order_id}",
        {"status": status, "username": username},
        token=token,
    )


def driver_earnings(username, token):
    status, body = api_request("GET", f"/api/driverStats?username={username}", token=token)
    assert status == 200, body
    return body["totalEarning"]


def test_a_driver_can_pick_up_and_deliver_an_order():
    """This proves the main success scenario: a driver claims, picks up then
    delivers an order, and their earnings are credited exactly once."""
    customer, customer_token = register_and_login("customer", "main_scenario_customer")
    driver, driver_token = register_and_login("driver", "main_scenario_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Main Scenario Order")

    claim_status, claim_body = claim(order["id"], driver_token)
    assert claim_status == 200, claim_body
    assert claim_body["status"] == "Claimed", claim_body

    pickup_status, pickup_body = update_status(
        order["id"], "Picked Up", driver["username"], driver_token
    )
    assert pickup_status == 200, pickup_body

    earnings_before = driver_earnings(driver["username"], driver_token)

    deliver_status, deliver_body = update_status(
        order["id"], "Delivered", driver["username"], driver_token
    )
    assert deliver_status == 200, deliver_body
    assert deliver_body["status"] == "Delivered", deliver_body
    assert deliver_body["isFulfilled"] is True, deliver_body

    earnings_after = driver_earnings(driver["username"], driver_token)
    assert earnings_after == pytest.approx(earnings_before + order["deliveryCost"]), (
        earnings_before, earnings_after, order["deliveryCost"]
    )


def test_a_second_driver_cannot_take_an_order_another_driver_claimed():
    """Extension 2b: once driver A has claimed an order, driver B can neither
    claim it (409) nor pick it up (403)."""
    customer, customer_token = register_and_login("customer", "reassign_customer")
    driver_a, token_a = register_and_login("driver", "reassign_driver_a")
    driver_b, token_b = register_and_login("driver", "reassign_driver_b")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Reassignment Order")

    assert claim(order["id"], token_a)[0] == 200

    second_claim, second_claim_body = claim(order["id"], token_b)
    assert second_claim == 409, second_claim_body

    pickup_status, pickup_body = update_status(
        order["id"], "Picked Up", driver_b["username"], token_b
    )
    assert pickup_status == 403, pickup_body
    assert get_order(order["id"], token_a)["driverUsername"] == driver_a["username"]


def test_delivered_by_the_wrong_driver_is_rejected():
    """Extension 5a: "Delivered" from a driver who isn't the order's assigned
    driver is a 403, and the order stays picked up."""
    customer, customer_token = register_and_login("customer", "wrong_driver_customer")
    assigned_driver, assigned_token = register_and_login("driver", "assigned_driver")
    other_driver, other_token = register_and_login("driver", "other_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Wrong Driver Order")

    assert claim(order["id"], assigned_token)[0] == 200
    assert update_status(order["id"], "Picked Up", assigned_driver["username"], assigned_token)[0] == 200

    deliver_status, deliver_body = update_status(
        order["id"], "Delivered", other_driver["username"], other_token
    )

    assert deliver_status == 403, deliver_body
    assert get_order(order["id"], assigned_token)["status"] == "Picked Up"


def test_delivering_an_order_no_one_has_claimed_is_a_conflict():
    """Extension 5b: "Delivered" for an order that was never claimed or picked
    up. This was an unhandled 500, then a 403 in Milestone 2; the state machine
    now rejects the move itself with a 409."""
    customer, customer_token = register_and_login("customer", "unassigned_customer")
    driver, driver_token = register_and_login("driver", "phantom_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Unassigned Driver Order")

    status, body = update_status(order["id"], "Delivered", driver["username"], driver_token)

    assert status == 409, body
    assert get_order(order["id"], customer_token)["status"] == "Placed"


def test_a_missing_status_value_is_a_bad_request():
    """Extension 5c: no status field at all. This was an unhandled 500; it is
    now a 400 before anything about the order changes."""
    customer, customer_token = register_and_login("customer", "missing_status_customer")
    driver, driver_token = register_and_login("driver", "missing_status_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Missing Status Order")
    assert claim(order["id"], driver_token)[0] == 200

    status, body = api_request(
        "POST", f"/api/orders/{order['id']}",
        {"username": driver["username"]},
        token=driver_token,
    )

    assert status == 400, body
    assert get_order(order["id"], driver_token)["status"] == "Claimed"


def test_an_unrecognized_status_value_is_rejected():
    """Extension 5d: an arbitrary string used to be saved as the status. Only
    the known statuses are accepted now, so "Bananas" is a 400."""
    customer, customer_token = register_and_login("customer", "garbage_status_customer")
    driver, driver_token = register_and_login("driver", "garbage_status_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Garbage Status Order")
    assert claim(order["id"], driver_token)[0] == 200

    status, body = update_status(order["id"], "Bananas", driver["username"], driver_token)

    assert status == 400, body
    assert get_order(order["id"], driver_token)["status"] == "Claimed"


def deliver(order, driver, driver_token):
    assert claim(order["id"], driver_token)[0] == 200
    assert update_status(order["id"], "Picked Up", driver["username"], driver_token)[0] == 200
    assert update_status(order["id"], "Delivered", driver["username"], driver_token)[0] == 200


def test_a_second_delivered_call_is_rejected():
    """Extension 5e: reprocessing "Delivered" on a delivered order used to be
    accepted and credit the driver again. It is now a 409."""
    customer, customer_token = register_and_login("customer", "double_credit_customer")
    driver, driver_token = register_and_login("driver", "double_credit_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Double Credit Order")
    deliver(order, driver, driver_token)

    second_status, second_body = update_status(
        order["id"], "Delivered", driver["username"], driver_token
    )

    assert second_status == 409, second_body


def test_a_second_delivered_call_should_not_double_credit_earnings():
    """Extension 5e: reprocessing "Delivered" must not credit earnings again."""
    customer, customer_token = register_and_login("customer", "should_not_double_credit_customer")
    driver, driver_token = register_and_login("driver", "should_not_double_credit_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Should Not Double Credit Order")
    deliver(order, driver, driver_token)
    earnings_after_first = driver_earnings(driver["username"], driver_token)

    update_status(order["id"], "Delivered", driver["username"], driver_token)
    earnings_after_second = driver_earnings(driver["username"], driver_token)

    assert earnings_after_second == earnings_after_first, (
        f"earnings should not change on a reprocessed 'Delivered' call: "
        f"{earnings_after_first} -> {earnings_after_second}"
    )


def test_a_stale_pickup_cannot_revert_a_delivered_order():
    """Extension 5f: a stale "Pick Up" click on a delivered order used to
    silently revert it. It is now a 409, the order stays delivered, and the
    earnings are unchanged."""
    customer, customer_token = register_and_login("customer", "stale_pickup_customer")
    driver, driver_token = register_and_login("driver", "stale_pickup_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC10 Stale Pickup Order")
    deliver(order, driver, driver_token)
    earnings_after_delivery = driver_earnings(driver["username"], driver_token)

    status, body = update_status(order["id"], "Picked Up", driver["username"], driver_token)

    assert status == 409, body
    assert get_order(order["id"], driver_token)["status"] == "Delivered"
    assert driver_earnings(driver["username"], driver_token) == earnings_after_delivery
