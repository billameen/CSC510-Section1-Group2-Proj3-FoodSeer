"""Milestone 3 -- Cancel an order.

Needs the backend running and the seeded admin account (admin / admin123).

A customer can cancel their own order while it is still Placed. Staff and
admins can cancel any order that isn't delivered. Drivers can't cancel.
Cancelling puts the reserved stock back.
"""

import pytest

from api_helpers import (
    api_request,
    admin_token,
    deliver_order,
    register_and_login,
    require_backend,
    unique_name,
)


@pytest.fixture(scope="module", autouse=True)
def backend():
    require_backend()


@pytest.fixture(scope="module")
def admin():
    return admin_token()


def add_food(admin, amount=5):
    food = {"foodName": unique_name("cancel_item"), "amount": amount, "price": 4, "allergies": []}
    status, body = api_request("POST", "/api/foods", food, token=admin)
    assert status == 200, body
    return body


def stock_of(food_id, token):
    status, body = api_request("GET", f"/api/foods/{food_id}", token=token)
    assert status == 200, body
    return body["amount"]


def place_order(token, food, quantity=1):
    payload = {"name": "Cancel Test Order", "foods": [{"id": food["id"]}] * quantity}
    status, body = api_request("POST", "/api/orders", payload, token=token)
    assert status == 200, body
    return body


def cancel(order_id, token):
    return api_request("POST", f"/api/orders/{order_id}/cancel", token=token)


def test_placing_an_order_reserves_stock_and_cancelling_returns_it(admin):
    food = add_food(admin, amount=5)
    _, customer = register_and_login("customer", "cancel_own_customer")

    order = place_order(customer, food, quantity=2)
    assert stock_of(food["id"], admin) == 3

    status, body = cancel(order["id"], customer)

    assert status == 200, body
    assert body["status"] == "Cancelled", body
    assert stock_of(food["id"], admin) == 5


def test_cancelling_twice_does_not_return_stock_twice(admin):
    food = add_food(admin, amount=5)
    _, customer = register_and_login("customer", "cancel_twice_customer")
    order = place_order(customer, food)
    assert cancel(order["id"], customer)[0] == 200

    status, body = cancel(order["id"], admin)

    assert status == 409, body
    assert stock_of(food["id"], admin) == 5


def test_a_customer_cannot_cancel_once_a_driver_has_claimed_it(admin):
    food = add_food(admin)
    _, customer = register_and_login("customer", "cancel_late_customer")
    _, driver = register_and_login("driver", "cancel_late_driver")
    order = place_order(customer, food)
    assert api_request("POST", f"/api/orders/{order['id']}/claim", token=driver)[0] == 200

    status, body = cancel(order["id"], customer)

    assert status == 409, body


def test_staff_can_cancel_a_claimed_order_but_not_a_delivered_one(admin):
    food = add_food(admin, amount=5)
    _, customer = register_and_login("customer", "cancel_staff_customer")
    _, staff = register_and_login("staff", "cancel_staff")
    _, driver = register_and_login("driver", "cancel_staff_driver")

    claimed = place_order(customer, food)
    assert api_request("POST", f"/api/orders/{claimed['id']}/claim", token=driver)[0] == 200
    assert cancel(claimed["id"], staff)[0] == 200

    delivered = place_order(customer, food)
    deliver_order(delivered["id"])
    status, body = cancel(delivered["id"], staff)

    assert status == 409, body
    assert stock_of(food["id"], admin) == 4


def test_nobody_else_can_cancel_a_customers_order(admin):
    food = add_food(admin)
    _, owner = register_and_login("customer", "cancel_owner")
    _, stranger = register_and_login("customer", "cancel_stranger")
    _, driver = register_and_login("driver", "cancel_driver")
    order = place_order(owner, food)

    assert cancel(order["id"], stranger)[0] == 403
    assert cancel(order["id"], driver)[0] == 403
    assert cancel(order["id"], None)[0] == 401
