"""Use Case #6 -- Fulfilled Order.

Needs the backend running and the seeded admin account (admin / admin123).

Since Milestone 3 there is one way for an order to be fulfilled: the
assigned driver marks it Delivered. The old staff "fulfill" button and its
POST /api/orders/fulfillOrder endpoint are gone, because they marked orders
done without a driver ever touching them and skipped the earnings credit.
"Fulfilled" in the order lists now means status Delivered.
"""

import pytest

from api_helpers import (
    api_request,
    admin_token,
    deliver_order,
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
    payload = {"name": name, "foods": [{"id": food["id"]}]}
    status, body = api_request("POST", "/api/orders", payload, token=token)
    assert status == 200, body
    return body


def test_a_delivered_order_shows_up_in_both_fulfilled_lists(admin):
    """This proves the main success scenario: once the driver delivers an
    order it is fulfilled, and it appears in /api/orders/fulfilledOrders and
    in the owning customer's /my/fulfilled."""
    customer, customer_token = register_and_login("customer", "fulfill_customer")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC6 Fulfill Order")

    delivered = deliver_order(order["id"])
    assert delivered["status"] == "Delivered", delivered
    assert delivered["isFulfilled"] is True, delivered

    all_fulfilled_status, all_fulfilled = api_request(
        "GET", "/api/orders/fulfilledOrders", token=admin
    )
    assert all_fulfilled_status == 200, all_fulfilled
    assert any(o["id"] == order["id"] for o in all_fulfilled), all_fulfilled

    my_fulfilled_status, my_fulfilled = api_request(
        "GET", "/api/orders/my/fulfilled", token=customer_token
    )
    assert my_fulfilled_status == 200, my_fulfilled
    assert any(o["id"] == order["id"] for o in my_fulfilled), my_fulfilled


def test_an_undelivered_order_is_not_in_the_fulfilled_list(admin):
    """A placed order is in the unfulfilled list and not the fulfilled one."""
    _, customer_token = register_and_login("customer", "unfulfilled_customer")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC6 Still Placed Order")

    _, fulfilled = api_request("GET", "/api/orders/fulfilledOrders", token=admin)
    _, unfulfilled = api_request("GET", "/api/orders/unfulfilledOrders", token=admin)

    assert all(o["id"] != order["id"] for o in fulfilled), fulfilled
    assert any(o["id"] == order["id"] for o in unfulfilled), unfulfilled


def test_staff_can_no_longer_fulfill_an_order_directly(admin):
    """Extension 2a used to be "nonexistent order -> 412". The staff fulfill
    endpoint itself is gone, so any call to it is refused and the order is
    left untouched."""
    _, customer_token = register_and_login("customer", "fulfill_removed_customer")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC6 No Staff Fulfill Order")

    status, body = api_request(
        "POST", "/api/orders/fulfillOrder", {"id": order["id"]}, token=admin
    )

    # The old path now falls through to POST /api/orders/{id}, where
    # "fulfillOrder" isn't a valid id, so it is a 400 rather than a 404.
    assert status in (400, 404, 405), (status, body)
    _, after = api_request("GET", f"/api/orders/{order['id']}", token=admin)
    assert after["status"] == "Placed", after


def test_delivering_an_already_delivered_order_is_a_conflict():
    """Extension 2b: finishing an order that is already finished is refused.
    It was a 410 from the staff endpoint; the state machine answers 409."""
    _, customer_token = register_and_login("customer", "fulfill_customer_two")
    driver, driver_token = register_and_login("driver", "fulfill_twice_driver")
    food = get_in_stock_food(customer_token)
    order = create_order(customer_token, food, "UC6 Already Fulfilled Order")

    assert api_request("POST", f"/api/orders/{order['id']}/claim", token=driver_token)[0] == 200
    for step in ("Picked Up", "Delivered"):
        step_status, step_body = api_request(
            "POST", f"/api/orders/{order['id']}",
            {"status": step, "username": driver["username"]}, token=driver_token,
        )
        assert step_status == 200, step_body

    status, body = api_request(
        "POST", f"/api/orders/{order['id']}",
        {"status": "Delivered", "username": driver["username"]}, token=driver_token,
    )

    assert status == 409, body
