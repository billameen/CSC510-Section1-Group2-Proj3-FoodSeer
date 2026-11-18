<!--
Step 5, Milestone 1 (#2): ceiling experiment. NOT a change to this branch.
The eval was patched in a throwaway detached worktree made from 45f9bd2 and never committed or merged.
The patch is at the bottom of this file. The held-out eval data and thresholds on this branch are untouched.
-->

# M0 ceiling experiment: how good can the inputs be before M0 passes?

## Question

Steps 2 to 4 changed the service and M0 still failed. Before tuning anything else, we asked a different question: if parts of the **input** were perfect, would M0 pass? If even perfect inputs fail, no change to the code can pass it. If only perfect inputs pass, the missing precision is in the reports themselves, not in our code.

## Setup

The eval was patched in a copy of the repository, switched by the environment variable `M0_CEILING`:

* **perfect restaurant**: restaurant reports name only the true dish (no innocent extra dish, which the eval adds 25% of the time) and give the true window (no padding of 20 minutes too narrow to 40 minutes too wide).
* **perfect staff**: every customer report is confirmed at once, choosing exactly the true dish (`confirmIncident` from step 4b).
* **true window**: perfect staff also correct the customer window to the true contamination window.
* `M0_WINDOW_MINUTES`: sets the customer window to ± that many minutes, so windows finer than whole hours can be tested.

Every variant still makes the same random draws in the same order, so all variants simulate exactly the same 500 incidents. Only the inputs handed to the service change.

**Sanity check:** with every switch off (`none`), the report is identical to [run 08](08-staff-review-pending-only.md). The 60- and 120-minute sweep rows are identical to the ±1h and ±2h runs.

## Result 1: which input limits precision

Customer window ±2h, the current default.

| Variant | Recall | Precision | Restaurant recall / precision | Customer recall / precision |
| :- | -: | -: | -: | -: |
| `none`: current code (= run 08) | 0.559 | 0.607 | 0.955 / 0.607 | 0.000 / 1.000 (0 alerts) |
| `restaurant`: perfect restaurant | 0.585 | **1.000** | 1.000 / 1.000 | 0.000 / 1.000 (0 alerts) |
| `staff`: perfect staff | 0.959 | 0.488 | 0.955 / 0.607 | 0.964 / 0.383 |
| `restaurant+staff` | 0.985 | 0.604 | 1.000 / 1.000 | 0.964 / 0.383 |
| `restaurant+staff+window` | **0.998** | **1.000** | 1.000 / 1.000 | 0.996 / 1.000 |

* With perfect input everywhere, recall is 0.998 and precision 1.000. **The matcher and the trace send no wrong alerts.** All the lost precision comes from the inputs.
* Perfect staff, who always pick the right dish, still only reach precision 0.383 on customer reports. The customer window (4 hours wide) is much wider than the true contamination window (30 to 180 minutes), and the service cannot know when contamination started.
* Recall is 0.996, not 1.000, for customer reports because in 2 of 206 of them another dish in the order **declares** the allergen. Only declaring dishes become candidates, so the true cross-contact dish can't be chosen and staff can only dismiss the report.

## Result 2: customer window sweep with perfect restaurant and perfect staff

| Customer window | Recall | Precision | Customer recall / precision |
| :- | -: | -: | -: |
| ±60 min | 0.904 ❌ | 0.817 ✅ | 0.769 / 0.612 |
| ±70 min | 0.927 ❌ | 0.780 ✅ | 0.824 / 0.566 |
| ±80 min | 0.946 ❌ | 0.742 ✅ | 0.869 / 0.523 |
| **±90 min** | **0.960 ✅** | **0.704 ✅** | 0.905 / 0.481 |
| ±100 min | 0.971 ✅ | 0.668 ❌ | 0.930 / 0.444 |
| ±110 min | 0.976 ✅ | 0.634 ❌ | 0.943 / 0.409 |
| ±120 min | 0.985 ✅ | 0.604 ❌ | 0.964 / 0.383 |

M0 **can** pass, but only when every input is perfect and the window is about ±90 minutes. Within 10 minutes either side it fails again, and the precision margin is 0.004.

## Result 3: the same sweep with the eval's own restaurant reports

Perfect staff, but restaurant reports as the eval models them (padded windows, sometimes an extra dish).

| Customer window | Recall | Precision | Customer recall / precision |
| :- | -: | -: | -: |
| ±30 min | 0.765 ❌ | **0.659** ❌ | 0.498 / 0.855 |
| ±45 min | 0.837 ❌ | 0.647 ❌ | 0.669 / 0.744 |
| ±60 min | 0.878 ❌ | 0.609 ❌ | 0.769 / 0.612 |
| ±75 min | 0.914 ❌ | 0.582 ❌ | 0.856 / 0.547 |
| ±90 min | 0.934 ❌ | 0.550 ❌ | 0.905 / 0.481 |
| ±105 min | 0.947 ❌ | 0.516 ❌ | 0.936 / 0.425 |
| ±120 min | 0.959 ✅ | 0.488 ❌ | 0.964 / 0.383 |

With the restaurant reports the eval actually uses, **no window from 30 to 120 minutes reaches precision 0.70, even with perfect staff**. The best is 0.659, and only at a window where recall drops to 0.765. Restaurant reports alone hold precision at 0.607.

## What this means

1. Our code isn't the limit: with perfect input it is perfect.
2. M0 passes only if restaurant reports are also perfect. Those reports are the eval's held-out input data, and the issue rules out editing them to pass.
3. So no honest change to the service can pass the claim as written. The remaining options are to revise the claim (spiral option (d)) or to leave M0 failing and say why.
4. Not tested here: shapes other than a symmetric window around the customer's order (for example, more time before the order than after it).

## Commands

```
git worktree add --detach <scratch>/m0-ceiling 45f9bd2
# apply the patch below to the worktree's M0TraceEval.java, then, from its food-seer-backend:
M0_CEILING=<variant> [M0_WINDOW_MINUTES=<n>] mvn -Dtest=M0TraceEval -Dsurefire.failIfNoSpecifiedTests=false test
```

Variants run: `none`, `restaurant`, `staff`, `restaurant+staff`, `restaurant+staff+window`. Sweep 2 used `restaurant+staff` at 60 to 120 minutes in steps of 10. Sweep 3 used `staff` at 30 to 120 minutes in steps of 15. JDK 21.

## Experiment patch (worktree only, never merged)

```diff
diff --git a/food-seer-backend/src/test/java/FoodSeer/eval/M0TraceEval.java b/food-seer-backend/src/test/java/FoodSeer/eval/M0TraceEval.java
index db01f53..3a53685 100644
--- a/food-seer-backend/src/test/java/FoodSeer/eval/M0TraceEval.java
+++ b/food-seer-backend/src/test/java/FoodSeer/eval/M0TraceEval.java
@@ -90,6 +90,15 @@ public class M0TraceEval {
     /** The claim: alert recall must reach this. */
     static final double RECALL_THRESHOLD = 0.95;
 
+    // CEILING EXPERIMENT ONLY (throwaway worktree, never merged). Which inputs are made perfect.
+    static final String CEILING = System.getenv().getOrDefault("M0_CEILING", "none");
+    static final boolean PERFECT_RESTAURANT = CEILING.contains("restaurant");
+    static final boolean ORACLE_STAFF = CEILING.contains("staff");
+    static final boolean ORACLE_WINDOW = CEILING.contains("window");
+    static int oracleCouldNotPickTrueDish = 0;
+    static final int WINDOW_MINUTES = Integer.parseInt(System.getenv().getOrDefault("M0_WINDOW_MINUTES", "0"));
+    static int oracleConfirmed = 0;
+
     /** The claim: alert precision must reach this. */
     static final double PRECISION_THRESHOLD = 0.70;
 
@@ -308,6 +317,8 @@ public class M0TraceEval {
     // Simulation
 
     static EvalResult runAllSeeds() {
+        oracleConfirmed = 0;
+        oracleCouldNotPickTrueDish = 0;
         final EvalResult result = new EvalResult();
         for (final long seed : SEEDS) {
             runSeed(seed, result);
@@ -429,13 +440,39 @@ public class M0TraceEval {
 
         final TraceResult traced;
         if (customerReport) {
-            traced = service.reportCustomerReaction(reporter, reporterOrder.getId(), typedAllergen);
+            TraceResult pending = service.reportCustomerReaction(reporter, reporterOrder.getId(), typedAllergen);
+            if (ORACLE_STAFF) {
+                if (WINDOW_MINUTES > 0) {
+                    // Sweep: a customer window of +/- WINDOW_MINUTES around the order, finer than whole hours.
+                    pending.getIncident().setWindowStart(reporterOrder.getCreatedAt().minusMinutes(WINDOW_MINUTES));
+                    pending.getIncident().setWindowEnd(reporterOrder.getCreatedAt().plusMinutes(WINDOW_MINUTES));
+                }
+                if (ORACLE_WINDOW) {
+                    pending.getIncident().setWindowStart(trueStart);
+                    pending.getIncident().setWindowEnd(trueEnd);
+                }
+                if (pending.getIncident().getSuspectFoodIds().contains(dish.getId())) {
+                    pending = service.confirmIncident(pending.getIncident().getId(), "staff",
+                            List.of(dish.getId()));
+                    oracleConfirmed = oracleConfirmed + 1;
+                } else {
+                    // A dish in the order declares the allergen, so only it is a candidate and the
+                    // true (cross-contact) dish cannot be chosen. Oracle staff dismiss instead.
+                    service.dismissIncident(pending.getIncident().getId(), "staff");
+                    oracleCouldNotPickTrueDish = oracleCouldNotPickTrueDish + 1;
+                }
+            }
+            traced = pending;
             result.customerReports = result.customerReports + 1;
         } else {
             final int padStart = -20 + rng.nextInt(61);
             final int padEnd = -20 + rng.nextInt(61);
-            final LocalDateTime reportedStart = trueStart.minusMinutes(padStart);
+            LocalDateTime reportedStart = trueStart.minusMinutes(padStart);
             LocalDateTime reportedEnd = trueEnd.plusMinutes(padEnd);
+            if (PERFECT_RESTAURANT) {
+                reportedStart = trueStart;
+                reportedEnd = trueEnd;
+            }
             if (reportedEnd.isBefore(reportedStart)) {
                 reportedEnd = reportedStart;
             }
@@ -446,7 +483,9 @@ public class M0TraceEval {
                 while (extra == dish) {
                     extra = menu.get(rng.nextInt(menu.size()));
                 }
-                suspectIds.add(extra.getId());
+                if (!PERFECT_RESTAURANT) {
+                    suspectIds.add(extra.getId());
+                }
             }
             traced = service.reportRestaurantIncident(reporter, typedAllergen, suspectIds, reportedStart,
                     reportedEnd);
@@ -606,6 +645,9 @@ public class M0TraceEval {
         final IncidentRepository incidentRepository = mock(IncidentRepository.class);
         final NotificationRepository notificationRepository = mock(NotificationRepository.class);
         final long[] nextIncidentId = {1L};
+        final Map<Long, Incident> savedIncidents = new HashMap<>();
+        when(incidentRepository.findById(any())).thenAnswer(
+                invocation -> Optional.ofNullable(savedIncidents.get((Long) invocation.getArgument(0))));
 
         when(orderRepository.findOrdersContainingFood(any(Food.class))).thenAnswer(invocation -> {
             final Food food = invocation.getArgument(0);
@@ -625,8 +667,11 @@ public class M0TraceEval {
         });
         when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> {
             final Incident incident = invocation.getArgument(0);
-            incident.setId(nextIncidentId[0]);
-            nextIncidentId[0] = nextIncidentId[0] + 1;
+            if (incident.getId() == null) {
+                incident.setId(nextIncidentId[0]);
+                nextIncidentId[0] = nextIncidentId[0] + 1;
+            }
+            savedIncidents.put(incident.getId(), incident);
             return incident;
         });
         when(notificationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
@@ -645,6 +690,11 @@ public class M0TraceEval {
         final boolean precisionPass = ours.precision() >= PRECISION_THRESHOLD;
         final StringBuilder out = new StringBuilder();
         out.append("# M0 eval: allergy trace-back\n\n");
+        out.append(String.format(Locale.ROOT,
+                "CEILING EXPERIMENT variant: %s, customer window minutes=%d (perfect restaurant=%s, oracle staff=%s, oracle window=%s);"
+                        + " oracle confirmed %d, oracle could not pick the true dish %d.%n%n",
+                CEILING, WINDOW_MINUTES, PERFECT_RESTAURANT, ORACLE_STAFF, ORACLE_WINDOW, oracleConfirmed,
+                oracleCouldNotPickTrueDish));
         out.append(String.format(Locale.ROOT,
                 "Claim: alert recall >= %.2f and alert precision >= %.2f on simulated incidents.%n%n",
                 RECALL_THRESHOLD, PRECISION_THRESHOLD));
```

<details>
<summary>Full report: the only passing variant (perfect restaurant, perfect staff, ±90 min), copied unedited</summary>

# M0 eval: allergy trace-back

CEILING EXPERIMENT variant: restaurant+staff, customer window minutes=90 (perfect restaurant=true, oracle staff=true, oracle window=false); oracle confirmed 204, oracle could not pick the true dish 2.

Claim: alert recall >= 0.95 and alert precision >= 0.70 on simulated incidents.

Result: recall 0.960 (PASS), precision 0.704 (PASS).

Incidents: 500 (294 restaurant reports, 206 customer reports) over seeds [1, 2, 3, 4, 5]; 2000 customers and 4000 orders per simulated day; 40% of customers typed free text.

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | 0.960 | 0.704 | 1553 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.440 | 0.691 | 725 | 1138 |
| Every allergic customer who ordered that day | 1.000 | 0.018 | 63646 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.960 | 0.056 | 19645 | 1138 |

Trace-back by who reported:

| Reported by | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| Restaurant | 1.000 | 1.000 | 666 | 666 |
| Customer | 0.905 | 0.481 | 887 | 472 |

Trace-back by seed:

| Seed | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| 1 | 0.967 | 0.703 | 337 | 245 |
| 2 | 0.940 | 0.641 | 320 | 218 |
| 3 | 0.970 | 0.725 | 313 | 234 |
| 4 | 0.935 | 0.718 | 280 | 215 |
| 5 | 0.987 | 0.736 | 303 | 226 |

Reproduce with: mvn -Dtest=M0TraceEval test

</details>
