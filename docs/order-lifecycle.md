# Order Lifecycle (Milestone 3)

Orders used to carry a free-text `status` string plus a separate `isFulfilled` flag. Any caller could set the string to anything, the two fields could disagree, a second "Delivered" credited the driver twice, a stale "Pick Up" could reopen a delivered order, and stock was checked when an order was placed but taken later in a separate step. Milestone 3 replaces all of that with one state machine.

## States and moves

```
            claim            pick up            deliver
 PLACED ───────────▶ CLAIMED ───────────▶ PICKED_UP ───────────▶ DELIVERED
   │                    │                     │
   └────────────────────┴─────────────────────┴──────▶ CANCELLED
```

| From | To | Who | Extra rule |
| :- | :- | :- | :- |
| Placed | Claimed | Any driver | The first driver to claim wins; a second claim is a 409. |
| Claimed | Picked Up | The assigned driver | If the customer has dietary restrictions, staff and then the driver must acknowledge the allergy hand-off first. |
| Picked Up | Delivered | The assigned driver | The driver is credited the delivery cost once, here and nowhere else. |
| Placed | Cancelled | The customer who owns it, staff, admin | |
| Claimed, Picked Up | Cancelled | Staff, admin | |
| Delivered, Cancelled | (none) | | Final. |

Every move goes through `OrderStateMachine.transition(order, to, actor)`, and the table itself lives in `OrderStatus`. Any move not in the table is a **409**, a status that isn't one of the five is a **400**, and acting on an order that isn't yours is a **403**.

## Stock

* **Placing an order reserves stock in the same transaction.** Every line is checked first, with the food rows locked, and only then is anything taken. So if item 2 of a 2-item order is out of stock, item 1's stock is untouched, and the response is a 400 that names the item. An empty order is also a 400, and so is an order for a food that has been deleted.
* **Cancelling puts the stock back**, once. Cancelling an order that is already cancelled is a 409, so stock can't be returned twice.
* The inventory numbers staff see are now what's actually left after open orders. Before, stock only went down when staff pressed Fulfill.

## Concurrency

Orders have a `@Version` column. If two drivers claim the same order at the same moment, one commit wins and the other gets a **409** ("Someone else changed this order at the same time"). `OrderTransactionsTest` runs that race ten times with two real threads and checks exactly one claim succeeds every time.

## Staff "Fulfill" removed

The staff **Fulfill Order** button and `POST /api/orders/fulfillOrder` are gone. They marked orders done without a driver ever touching them, skipped the driver's earnings, and gave a second way to finish an order that the rest of the code had to keep in step with. Delivered is now the only way an order is fulfilled. Staff instead get a **Cancel** button for orders that can't be completed. `isFulfilled` is still in the API and database, but it is always `status == Delivered`.

## Deleting foods and users

Both are soft deletes now (an `active` flag).

* **A deleted food** leaves the menu and can't be ordered, but its row stays. Each order keeps a snapshot of its lines (name, price, allergens, calories) taken when it was placed, so past orders show what was actually ordered, ratings still point at a real dish, and allergy trace-back still finds orders after their food is deleted. An order already in progress keeps its items and can still be delivered.
* **A deleted user** can't sign in and leaves the user list, but their orders stay for history and trace-back. Their username and email stay taken.

Trace-back skips cancelled orders, since nobody ate them. The M0 eval is unchanged (recall 0.960, precision 0.285).

## API changes

| Method and path | Change |
| :- | :- |
| `POST /api/orders` | Body is just `{ "name": ..., "foods": [{ "id": 1 }, ...] }`. Repeat an id to order more than one. Only customers can place orders. |
| `POST /api/orders/{id}/claim` | Driver claims a placed order. 409 if it isn't placed any more. |
| `POST /api/orders/{id}` | Driver moves their order to `Picked Up` or `Delivered`. Any other value is a 400. |
| `POST /api/orders/{id}/cancel` | **New.** Rules as in the table above. |
| `POST /api/orders/fulfillOrder` | **Removed.** |
| `GET /api/orders/fulfilledOrders`, `/my/fulfilled` | Delivered orders. |
| `GET /api/orders/unfulfilledOrders`, `/my/unfulfilled` | Placed, claimed and picked-up orders. Cancelled orders are in neither list. |

Statuses are sent as `Placed`, `Claimed`, `Picked Up`, `Delivered` and `Cancelled`, the same labels as before, so existing clients keep working. The server also accepts the enum names (`PICKED_UP`). In each order, `foods` is now a list of line records (`id`, `foodName`, `price`, `allergies`, `calories`) instead of the full food entity.

## Upgrading an existing database

No migration script is needed. With `ddl-auto=update`, Hibernate adds the new columns (`version`, `active` on foods and users, and the `order_lines` table) with defaults. Old status strings such as `Picked Up` are read as-is. A row with no status is read as Delivered if it was fulfilled and Placed otherwise, and it is written back as the enum name the next time it changes. Orders placed before this milestone have no line snapshot, so they show their foods as they are now.

## Tests

* `OrderStatusTest` and `OrderStateMachineTest`: every (from, to) pair, allowed pairs succeed, the rest are 409; who may make each move; earnings credited once; a null delivery cost.
* `StockServiceTest` and `OrderTransactionsTest` (real transactions): the 2-item rollback, stock taken once, cancel returns it once, the two-driver claim race, and trace-back after a food is deleted.
* `AuthorizationMatrixTest`: the cancel row for every role.
* Project 1 pytest: `test_update_order_status_pytest.py` and `test_fulfilled_order_pytest.py` now assert the fixed behavior for each extension they used to document as a bug, and `test_cancel_order_pytest.py` covers cancelling. `test_rejects_order_without_food` and `test_rejects_order_quantity_above_available_stock` pass.

## Known limits

* Deleting a user doesn't cancel their open orders. A deleted customer's placed order can still be claimed, and a deleted driver's claimed order stays with them until staff cancel it.
