"""Use Case #9 -- Remove a food item from the menu.

Needs the backend running and the seeded admin account (admin / admin123).
Overlaps with Use Case #12's authorization suite for the missing-role-check
angle; this file adds the historical-order-consistency and dangling-rating
findings that are specific to this use case's own extensions.

Since Milestone 3, deleting a food is a soft delete: it leaves the menu and
can't be ordered any more, but its row stays. Orders keep a snapshot of each
line (name, price, allergens) taken when they were placed, so deleting a food
no longer rewrites past orders or leaves ratings pointing at nothing, and the
old Inventory FK conflict (a 500, then a 409 in Milestone 2) can't happen.
An order already in progress keeps its items and can still be delivered.
"""

import pytest

from api_helpers import (
    api_request,
    admin_token,
    register_and_login,
    deliver_order,
    require_backend,
    unique_name,
)


@pytest.fixture(scope="module", autouse=True)
def backend():
    require_backend()


@pytest.fixture(scope="module")
def admin():
    return admin_token()


def add_food(admin, price=5, amount=5):
    food = {
        "foodName": unique_name("removable_item"),
        "amount": amount,
        "price": price,
        "allergies": [],
    }
    status, body = api_request("POST", "/api/foods", food, token=admin)
    assert status == 200, body
    return body


def place_order(token, food, name):
    payload = {"name": name, "foods": [{"id": food["id"]}]}
    status, body = api_request("POST", "/api/orders", payload, token=token)
    assert status == 200, body
    return body


def menu_ids(token):
    status, foods = api_request("GET", "/api/foods", token=token)
    assert status == 200, foods
    return {f["id"] for f in foods}


def get_order(order_id, token):
    status, body = api_request("GET", f"/api/orders/{order_id}", token=token)
    assert status == 200, body
    return body


def test_customer_should_not_be_able_to_delete_a_food_item(admin):
    """This asserts the correct behavior for extension 1a and is expected to FAIL
    today: FoodController has no @PreAuthorize on deleteFood(), so a customer's
    request is never stopped at 403. Today it actually 500s (see module
    docstring) rather than succeeding outright, but either way the request
    reaches the database, which is the extension 1a claim: nothing stops it
    at the authorization layer."""
    food = add_food(admin)
    _, customer = register_and_login("customer", "sneaky_delete_customer")

    status, body = api_request("DELETE", f"/api/foods/{food['id']}", token=customer)

    assert status == 403, f"a CUSTOMER account's delete request was not rejected at the authorization layer: {status} {body}"


def test_deleting_a_food_in_an_active_order_keeps_the_order_intact(admin):
    """Extension 4a: the item is in an order that hasn't been delivered yet.
    This used to be refused with 409. With order lines snapshotted, the food
    can leave the menu while the order keeps its line and can still be
    delivered."""
    food = add_food(admin)
    _, customer = register_and_login("customer", "blocking_order_customer")
    order = place_order(customer, food, "Order In Progress")

    status, body = api_request("DELETE", f"/api/foods/{food['id']}", token=admin)

    assert status == 200, body
    assert food["id"] not in menu_ids(admin)
    after = get_order(order["id"], customer)
    assert [line["id"] for line in after["foods"]] == [food["id"]], after
    assert deliver_order(order["id"])["status"] == "Delivered"


def test_a_deleted_food_cannot_be_ordered(admin):
    """Once deleted, the food is off the menu and a new order for it is a 400."""
    food = add_food(admin)
    _, customer = register_and_login("customer", "deleted_food_customer")
    assert api_request("DELETE", f"/api/foods/{food['id']}", token=admin)[0] == 200

    status, body = api_request(
        "POST", "/api/orders", {"name": "Too Late", "foods": [{"id": food["id"]}]}, token=customer
    )

    assert status == 400, body


def test_deleting_a_food_in_a_delivered_order_leaves_the_order_unchanged(admin):
    """Extension 5a: deleting a food used to strip it out of past orders and
    leave their cost stale (and over HTTP it hit the Inventory FK first). The
    delivered order now keeps the same items and cost."""
    food = add_food(admin, price=7)
    _, customer = register_and_login("customer", "stale_price_customer")
    order = place_order(customer, food, "Delivered Order Kept As Is")
    deliver_order(order["id"])
    before = get_order(order["id"], customer)

    delete_status, delete_body = api_request("DELETE", f"/api/foods/{food['id']}", token=admin)

    assert delete_status == 200, delete_body
    after = get_order(order["id"], customer)
    assert after["foods"] == before["foods"], after
    assert after["cost"] == before["cost"], after


def test_deleting_a_rated_food_keeps_the_rating_on_the_order(admin):
    """Extension 5b: the rating used to be left pointing at a deleted row.
    The food's row stays now, so the order still shows the dish and that it
    was rated."""
    food = add_food(admin, price=4)
    _, customer = register_and_login("customer", "dangling_rating_customer")
    order = place_order(customer, food, "Order To Be Rated")
    deliver_order(order["id"])

    rate_status, rate_body = api_request(
        "POST", f"/api/foods/orders/{order['id']}/{food['id']}/rate?rating=4.5", token=customer
    )
    assert rate_status == 200, rate_body

    delete_status, delete_body = api_request("DELETE", f"/api/foods/{food['id']}", token=admin)

    assert delete_status == 200, delete_body
    after = get_order(order["id"], customer)
    assert food["id"] in after["ratedFoodIds"], after
    assert [line["id"] for line in after["foods"]] == [food["id"]], after
