<!--
Step 3c, Milestone 1 (#2): Attempt 2 (precision). Narrowed the customer-report window from ±2h to ±1h
by temporarily setting TraceServiceImpl.DEFAULT_WINDOW_HOURS = 1, which the eval now uses (wired in step 3b).
The change was reverted to 2 after this run, so this file is the only record of it.
Compared with 03-phrase-matching.md (±2h):
  - precision 0.285 -> 0.403, still FAIL (target 0.70)
  - recall    0.960 -> 0.880, now FAIL (target 0.95). We lose the recall pass from step 2.
  - Customer reports: recall 0.966 -> 0.775, precision 0.164 -> 0.255
  - Restaurant reports: unchanged (0.955 / 0.607). Restaurants give their own window, so this setting doesn't touch them.
  - Ceiling ("Everyone who ordered a suspect dish in the window") drops 0.960 -> 0.880: a 1h window can't
    reach 0.95 recall even with a perfect matcher.
Conclusion: the window is a seesaw between recall and precision, not a fix. Restaurant precision (0.607) is untouched,
so no window value alone can reach 0.70 overall.
Command: set DEFAULT_WINDOW_HOURS = 1, then cd food-seer-backend && mvn -Dtest=M0TraceEval -Dsurefire.failIfNoSpecifiedTests=false test (JDK 21)
The report below is copied unedited from target/m0-eval/m0-report.md.
-->

# M0 eval: allergy trace-back

Claim: alert recall >= 0.95 and alert precision >= 0.70 on simulated incidents.

Result: recall 0.880 (FAIL), precision 0.403 (FAIL).

Incidents: 500 (294 restaurant reports, 206 customer reports) over seeds [1, 2, 3, 4, 5]; 2000 customers and 4000 orders per simulated day; 40% of customers typed free text.

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | 0.880 | 0.403 | 2485 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.408 | 0.391 | 1186 | 1138 |
| Every allergic customer who ordered that day | 1.000 | 0.018 | 63646 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.880 | 0.033 | 30374 | 1138 |

Trace-back by who reported:

| Reported by | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| Restaurant | 0.955 | 0.607 | 1047 | 666 |
| Customer | 0.775 | 0.255 | 1438 | 472 |

Trace-back by seed:

| Seed | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| 1 | 0.841 | 0.414 | 498 | 245 |
| 2 | 0.890 | 0.357 | 543 | 218 |
| 3 | 0.880 | 0.392 | 526 | 234 |
| 4 | 0.865 | 0.408 | 456 | 215 |
| 5 | 0.929 | 0.455 | 462 | 226 |

Reproduce with: mvn -Dtest=M0TraceEval test
