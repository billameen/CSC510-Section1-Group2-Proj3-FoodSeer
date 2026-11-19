# Authorization matrix

Milestone 2 (security hardening): who may call each backend endpoint. This table is the rule.
`AuthorizationMatrixTest` runs every row of it against the real security
configuration (JWT filter, URL rules and `@PreAuthorize`), with the services
mocked, so a change that opens or closes an endpoint fails the build until this
file and the test are updated together.

## How to read it

| Symbol | Meaning |
|---|---|
| ✅ | Allowed: the request reaches the controller and returns 2xx |
| 401 | Not signed in (no token, or an invalid or expired token) |
| 403 | Signed in, but this role may not call the endpoint |

Columns are the caller: **Anon** (no token), **Customer**, **Driver**, **Staff**, **Admin**.

Two rules apply to every row:

* Only `/auth/**` and CORS preflight (`OPTIONS`) are open without a token. Every
  other request must be authenticated at the URL layer **and** carry its own
  `@PreAuthorize`, so no endpoint is protected by only one of the two.
* An allowed role can still get a business error (400, 404, 409) from the
  service. Those are not authorization results and are not part of this table.

## Auth

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| POST | `/auth/register` | ✅ | ✅ | ✅ | ✅ | ✅ | Only `CUSTOMER` or `DRIVER` can be registered here; any other role is 400 |
| POST | `/auth/login` | ✅ | ✅ | ✅ | ✅ | ✅ | Username or email |

## Users

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/api/users` | 401 | 403 | 403 | 403 | ✅ | |
| POST | `/api/users` | 401 | 403 | 403 | 403 | ✅ | Admin creates any role, including staff and admin |
| GET | `/api/users/{id}` | 401 | 403 | 403 | 403 | ✅ | |
| PUT | `/api/users/{id}/role` | 401 | 403 | 403 | 403 | ✅ | Unknown or empty role is 400; demoting the last admin is 409 |
| DELETE | `/api/users/{id}` | 401 | 403 | 403 | 403 | ✅ | Deleting yourself or the last admin is 409 |
| GET | `/api/users/me` | 401 | ✅ | ✅ | ✅ | ✅ | |
| PUT | `/api/users/me/preferences` | 401 | ✅ | 403 | 403 | 403 | |
| GET | `/api/users/me/calorieBudget` | 401 | ✅ | 403 | 403 | 403 | |
| POST | `/api/users/me/mealLog` | 401 | ✅ | 403 | 403 | 403 | |
| GET | `/api/users/me/calorieHistory` | 401 | ✅ | 403 | 403 | 403 | |

## Menu, allergens and inventory

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/api/foods` | 401 | ✅ | ✅ | ✅ | ✅ | |
| GET | `/api/foods/{id}` | 401 | ✅ | ✅ | ✅ | ✅ | |
| GET | `/api/foods/withinCalorieBudget` | 401 | ✅ | 403 | 403 | 403 | |
| POST | `/api/foods` | 401 | 403 | 403 | ✅ | ✅ | |
| POST | `/api/foods/updateFood` | 401 | 403 | 403 | ✅ | ✅ | |
| DELETE | `/api/foods/{id}` | 401 | 403 | 403 | ✅ | ✅ | |
| POST | `/api/foods/orders/{orderId}/{foodId}/rate` | 401 | ✅ own order | 403 | 403 | 403 | Another customer's order is 403. The order must be delivered (409) and each dish is rated once per order (409) |
| GET | `/api/allergens` | 401 | ✅ | ✅ | ✅ | ✅ | |
| POST | `/api/allergens` | 401 | 403 | 403 | ✅ | ✅ | Customers keep custom allergies on their own profile |
| GET | `/api/inventory` | 401 | ✅ | 403 | ✅ | ✅ | |
| POST | `/api/inventory` | 401 | 403 | 403 | ✅ | ✅ | |

## Orders

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/api/orders` | 401 | 403 | 403 | ✅ | ✅ | |
| GET | `/api/orders/fulfilledOrders` | 401 | 403 | 403 | ✅ | ✅ | |
| GET | `/api/orders/unfulfilledOrders` | 401 | 403 | 403 | ✅ | ✅ | |
| GET | `/api/orders/my` | 401 | ✅ | 403 | 403 | 403 | The caller's own orders |
| GET | `/api/orders/my/fulfilled` | 401 | ✅ | 403 | 403 | 403 | |
| GET | `/api/orders/my/unfulfilled` | 401 | ✅ | 403 | 403 | 403 | |
| POST | `/api/orders` | 401 | ✅ | 403 | 403 | 403 | Server sets cost, delivery cost and status (`Placed`) |
| POST | `/api/orders/fulfillOrder` | 401 | 403 | 403 | ✅ | ✅ | |
| GET | `/api/orders/{id}` | 401 | ✅ own order | ✅ assigned driver | ✅ | ✅ | Another customer, or a driver not assigned to it, is 403 |
| POST | `/api/orders/{id}` | 401 | 403 | ✅ assigned driver | 403 | 403 | Delivery status update; an unassigned driver is 403 |
| POST | `/api/orders/{id}/claim` | 401 | 403 | ✅ | 403 | 403 | |
| GET | `/api/orders/availableOrders` | 401 | 403 | ✅ | 403 | ✅ | |
| GET | `/api/orders/activeOrders/{username}` | 401 | 403 | ✅ self | 403 | ✅ | Another driver's username is 403 |
| POST | `/api/orders/{orderId}/allergy-handoff/acknowledge` | 401 | 403 | ✅ | ✅ | 403 | |
| GET | `/api/orders/{orderId}/allergy-handoff/status` | 401 | 403 | ✅ | ✅ | 403 | |

## Drivers

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| GET | `/api/driverStats?username={u}` | 401 | 403 | ✅ self | 403 | ✅ | Another driver is 403; an unknown driver is 404 for an admin |

## Allergy incidents and notices

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| POST | `/api/incidents` | 401 | 403 | 403 | ✅ | ✅ | |
| POST | `/api/incidents/reaction` | 401 | ✅ | 403 | 403 | 403 | Own order only (403 otherwise, checked in the service) |
| GET | `/api/incidents` | 401 | 403 | 403 | ✅ | ✅ | |
| POST | `/api/incidents/{id}/acknowledge` | 401 | 403 | 403 | ✅ | ✅ | |
| POST | `/api/incidents/{id}/confirm` | 401 | 403 | 403 | ✅ | ✅ | |
| POST | `/api/incidents/{id}/dismiss` | 401 | 403 | 403 | ✅ | ✅ | |
| GET | `/api/notifications/my` | 401 | ✅ | 403 | 403 | 403 | |
| GET | `/api/notifications/my/unread-count` | 401 | ✅ | 403 | 403 | 403 | |
| POST | `/api/notifications/{id}/acknowledge` | 401 | ✅ | 403 | 403 | 403 | Own notices only (checked in the service) |

## Chat

| Method | Path | Anon | Customer | Driver | Staff | Admin | Notes |
|---|---|---|---|---|---|---|---|
| POST | `/api/chat` | 401 | ✅ | 403 | ✅ | ✅ | |

## Errors

Error bodies never include stack traces or SQL. `GlobalExceptionHandler` maps:

| Exception | Status |
|---|---|
| `AccessDeniedException`, `SecurityException` | 403 |
| `ResourceNotFoundException` | 404 |
| `IllegalStateException` | 409 |
| `IllegalArgumentException`, unreadable body, missing or mistyped parameter | 400 |
| Anything else | 500 with a generic message; the details go to the server log only |

## What changed from before Milestone 2

| Endpoint | Before | Now |
|---|---|---|
| `POST /auth/register` with `role: staff` | Created a staff account | 400; an admin creates staff with `POST /api/users` |
| `GET /api/orders`, `fulfilledOrders`, `GET /api/orders/{id}` | Any customer could read every order, including other customers' allergies | Staff and admin, or the order's own customer or assigned driver |
| `GET /api/orders/**`, `POST /api/orders/**` | `permitAll` at the URL layer | Authenticated, plus a role rule on each method |
| `POST /api/orders/fulfillOrder` | Customers could fulfill orders | Staff and admin |
| `POST /api/orders` | Trusted the browser's `cost`, `deliveryCost` and `status` | Computed on the server; always starts as `Placed` |
| `POST /api/foods`, `updateFood`, `DELETE /api/foods/{id}` | No role check | Staff and admin |
| `POST /api/inventory` | Customers could rewrite inventory | Staff and admin |
| `POST /api/allergens` | Any signed-in user | Staff and admin |
| Rating a dish | Any signed-in user, any order | The order's own customer, once per dish |
| `/api/my-orders...` | `GET /api/orders/my-orders...` | `GET /api/orders/my...` |
| HTTP Basic | Enabled alongside JWT | Disabled |
