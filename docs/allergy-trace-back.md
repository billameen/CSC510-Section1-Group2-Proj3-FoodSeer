# Allergy Incident Trace-Back

## Why

When one customer has an allergic reaction, others who ate the same dish around the same time are often at risk too, and nobody tells them. Delivery apps let you save an allergy, but in our market survey we found none that trace an incident back through the kitchen and warn the other people it may have reached.

FoodSeer now does. A report from the restaurant or from a customer starts a trace. Customers whose orders were in the window and whose saved allergies match get a private notice. The notice never names anyone else, and the restaurant attaches its own offer (a refund or a remake), so the notice reads as the restaurant's own quiet recall rather than a public accusation.

## How it works

1. **A report comes in.**
   * Staff report that dishes may carry an allergen during a time window (Allergy Incidents page). This is traced straight away.
   * A customer reports a reaction on one of their own orders (My Orders page). The window is 2 hours either side of that order (`foodseer.trace.window-hours`). If no dish in the order declares the allergen, every dish in it is a candidate. The report waits for staff review and warns nobody yet.
2. **Review (customer reports only).** On the Allergy Incidents page, staff tick the dish that carried the allergen, chosen from the candidates, and press **Confirm and warn customers**. Only the ticked dishes are traced, over the window saved with the report. Staff can instead **Dismiss** the report, and nobody is warned. Incidents saved before review existed count as confirmed.
3. **Trace.** Every order that contains a suspect dish and was placed inside the window is exposed.
4. **Match.** A customer is warned if their saved restrictions overlap the allergen. Matching understands case, plurals, filler words and groups, so "peanut", "Peanuts" and "peanut allergy" match, "nuts" covers peanuts and tree nuts, and "fish" never matches "shellfish". Exact string matching, which FoodSeer used before, misses these; in the M0 eval it found under half of the truly exposed customers.
5. **Notify.** Each affected customer gets one private notice. The reporter is never notified about their own report.
6. **Respond.** Staff see every incident with its status and counts only, acknowledge it and attach an offer. Customers then see that offer on their notice.

## API

| Method and path | Who | What |
| :- | :- | :- |
| `POST /api/incidents` | Staff, Admin | Report dishes, allergen and window. Returns the incident id and counts. |
| `POST /api/incidents/reaction` | Customer | Report a reaction on your own order. It waits for staff review. Returns the incident id only. |
| `GET /api/incidents` | Staff, Admin | Every incident, newest first, with its status (`PENDING_REVIEW`, `ACTIVE` or `DISMISSED`), counts and never customer names. |
| `POST /api/incidents/{id}/confirm` | Staff, Admin | Confirm which candidate dishes in a pending customer report were at fault, as `{ "foodIds": [30] }`. Traces them and returns the incident id and counts. |
| `POST /api/incidents/{id}/dismiss` | Staff, Admin | Close a pending customer report without warning anyone. |
| `POST /api/incidents/{id}/acknowledge` | Staff, Admin | Acknowledge and attach an offer (1 to 500 characters). |
| `GET /api/notifications/my` | Customer | Your notices, newest first, with the restaurant's offer. |
| `GET /api/notifications/my/unread-count` | Customer | How many notices you have not acknowledged. |
| `POST /api/notifications/{id}/acknowledge` | Customer | Acknowledge one of your notices. |

Example restaurant report:

```json
{ "allergen": "peanut", "foodIds": [30], "windowStart": "2026-10-12T11:00", "windowEnd": "2026-10-12T13:00" }
```

## Try it

1. Log in as a customer with "Peanuts" saved in preferences and order COOKIES.
2. Log in as staff, open **Allergy Incidents**, enter "Peanuts", tick COOKIES, keep the default window and press **Trace and warn customers**.
3. Log back in as the customer: a banner and **Safety Notices** show the notice.
4. As staff, type an offer and press **Acknowledge**. The customer's notice now shows it.
5. As a customer with "Sesame" saved, order FRUIT SALAD only. As a second customer, order GARDEN SALAD and FRUIT SALAD, then on **My Orders** report a reaction to "Sesame" on that order. Neither salad declares sesame, so both are candidates. Nobody is warned yet.
6. As staff, the report shows **Waiting for review** with both salads to choose from. Tick GARDEN SALAD and press **Confirm and warn customers**. The first customer is not warned, because they did not order GARDEN SALAD. Had staff ticked FRUIT SALAD, they would have been.

## Code map

| Piece | File |
| :- | :- |
| Matching rules | `food-seer-backend/src/main/java/FoodSeer/service/AllergenMatcher.java` |
| Trace and notify | `food-seer-backend/src/main/java/FoodSeer/service/impl/TraceServiceImpl.java` |
| Customer notices | `food-seer-backend/src/main/java/FoodSeer/service/impl/NotificationServiceImpl.java` |
| Endpoints | `IncidentController.java`, `NotificationController.java` |
| Staff page | `food-seer-frontend/src/pages/AllergyIncidents.js` |
| Customer pages | `food-seer-frontend/src/pages/SafetyNotices.js`, report button in `Orders.js` |

## M0: the risk-killer eval

**Claim (revised in Milestone 1):** alert recall of at least 0.95, alert precision of at least 0.28 (a no-regression floor), and better than every baseline, on simulated incidents. The baselines are exact string matching, alerting every allergic customer who ordered that day, and alerting without an allergy check. The original claim required precision of at least 0.70. [Why the claim changed](#why-the-claim-changed) explains the revision, and every report still prints whether 0.70 is met.

**Instrument:** `food-seer-backend/src/test/java/FoodSeer/eval/M0TraceEval.java`. It runs the real `TraceServiceImpl` on 5 seeded simulated days with 500 incidents. The class Javadoc explains the simulation. Customer reports wait for staff review, and the simulated staff confirm every candidate dish, because the eval can't model staff judgment. That gives the review no credit for picking the right dish. It runs in the **M0 Eval** GitHub workflow, which posts the report to the job summary. The class name does not end in "Test", so the normal build does not include it.

```
cd food-seer-backend
mvn -Dtest=M0TraceEval test
cat target/m0-eval/m0-report.md
```

The numbers come from fixed seeds, so they are the same on every machine.

### Latest result (November 16, 2026): the revised claim passes

Measured with `mvn -Dtest=M0TraceEval test` on the same 500 simulated incidents ([run 11](m0-runs/11-revised-claim.md)).

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | **0.960** | **0.285** | 3828 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.446 | 0.277 | 1831 | 1138 |
| Every allergic customer who ordered that day | 1.000 | 0.018 | 63646 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.960 | 0.024 | 46447 | 1138 |

| Reported by | Recall | Precision |
| :- | -: | -: |
| Restaurant | 0.955 | 0.607 |
| Customer | 0.966 | 0.164 |

Recall passes 0.95. Precision passes the 0.28 floor but **does not meet the original 0.70 target**. Trace-back still finds more than twice as many exposed customers as exact string matching, and it is more than ten times as precise as either no-trace baseline.

### First result (October 12, 2026): the original claim failed

Measured with `mvn -Dtest=M0TraceEval test` on 500 simulated incidents (294 restaurant reports, 206 customer reports).

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | 0.890 | 0.287 | 3531 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.446 | 0.277 | 1831 | 1138 |
| Every allergic customer who ordered that day | 0.926 | 0.018 | 58683 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.960 | 0.024 | 46447 | 1138 |

| Reported by | Recall | Precision |
| :- | -: | -: |
| Restaurant | 0.889 | 0.610 |
| Customer | 0.892 | 0.164 |

Trace-back doubles the recall of exact string matching and is more than ten times as precise as either no-trace baseline. It still misses both targets.

**What the eval found:**

1. Customer reports are the main precision problem (0.164). When no dish declares the allergen, every dish in the reporter's order becomes a suspect, and the 2 hour window on each side is wide, so many customers who ate an innocent dish get a notice.
2. Recall loses about 11%. Some free-text restrictions are phrases the matcher does not know ("no dairy products", "milk protein", "egg whites", "soy sauce", "tahini", "peanut butter", "finned fish"), and some restaurant windows are guessed too narrow.
3. Restaurant reports that name an innocent extra dish cost precision.

**Spiral review:** recorded in the team's GitHub issue "M0 spiral review: trace-back misses its recall and precision targets". The options are: (a) for customer reports with several undeclared dishes, alert only after staff confirm the dish; (b) narrow the customer window; (c) match known allergen words inside longer phrases; (d) revise the claim. Tuning the matcher to the simulator's own phrase list would flatter the next run, so new held-out phrasings must be added before measuring again.

### What the spiral review tried (Milestone 1)

Every run is saved in [`docs/m0-runs/`](m0-runs/) with the unedited report. The held-out phrasings were written as unit tests before the matcher changed ([run 02](m0-runs/02-heldout-tests-before.md)).

| Run | Option | Change | Recall | Precision |
| :- | :- | :- | -: | -: |
| [01](m0-runs/01-baseline.md) | | Baseline | 0.890 | 0.287 |
| [03](m0-runs/03-phrase-matching.md) | (c) | A multi-word restriction also covers each word's allergens; TAHINI means sesame | **0.960** | 0.285 |
| [07](m0-runs/07-window-1h.md) | (b) | Customer window ±1 hour instead of ±2 (reverted) | 0.880 | 0.403 |
| [08](m0-runs/08-staff-review-pending-only.md) | (a) | Customer reports wait for staff review. The eval had no staff, so they never alerted | 0.559 | 0.607 |
| [09](m0-runs/09-ceiling-experiment.md) | | Ceiling experiment: perfect inputs, one part at a time (not merged) | see below | |
| [10](m0-runs/10-eval-staff-confirm-all.md) | | The eval's staff confirm every candidate dish | 0.960 | 0.285 |
| [11](m0-runs/11-revised-claim.md) | (d) | Claim revised: precision floor 0.28 instead of 0.70 | 0.960 ✅ | 0.285 ✅ |

Phrase matching also showed that food tags such as `DAIRY-FREE` and `GLUTEN FREE BREAD` were read as containing the allergen. That was fixed with held-out tests ([run 05](m0-runs/05-heldout-tests-after-free-fix.md)). Two held-out phrasings still fail, because they are ingredient names rather than allergen words: `whey` (milk) and `edamame` (soy).

### Why the claim changed

Narrowing the window traded recall for precision (run 07). Before trying anything else, we checked whether 0.70 was reachable at all. The ceiling experiment ([run 09](m0-runs/09-ceiling-experiment.md)) made the eval's inputs perfect, one part at a time, on the same 500 incidents:

* **With perfect input everywhere, recall is 0.998 and precision 1.000.** The matcher and the trace send no wrong alerts; all the lost precision comes from the reports.
* **With the restaurant reports this simulation produces, no customer window from 30 to 120 minutes reaches precision 0.70, even if staff always confirm the true dish.** The best is 0.659, and only where recall falls to 0.765. Restaurant reports alone stop at 0.607, because they pad the window and sometimes name an innocent extra dish.
* The original claim passes only if restaurant reports are also perfect and the customer window is about ±90 minutes (recall 0.960, precision 0.704). Those reports are the eval's held-out input, which we don't edit to pass.

So no honest change to the service could pass 0.70. We chose option (d): recall stays at 0.95, because missing an exposed customer is the safety failure, and precision becomes a no-regression floor. The floor of 0.28 is the 0.285 measured when the claim was revised, rounded down. It fails if a change adds more than about 70 wrong alerts across the 500 incidents.

**What the eval can't measure:** how well staff choose the dish. If staff always chose the true dish, precision would be 0.488 at the same recall (run 09). Raising the precision target again needs better input rather than better matching, for example restaurant reports that name the exact dish and time.
