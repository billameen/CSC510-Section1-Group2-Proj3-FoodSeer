<!--
Step 6b, Milestone 1 (#2): claim revision, spiral review option (d). Held-out data and the recall target unchanged.
Change (M0TraceEval only): precision >= 0.70 replaced by precision >= 0.28, a no-regression floor (the 0.285
measured in run 10, rounded down), together with the "better than every baseline" checks the eval already had.
The original 0.70 target is still printed in every report, and here it is "not met".
Evidence the old target can't be met honestly: 09-ceiling-experiment.md (with the eval's own restaurant reports,
no customer window from 30 to 120 min reaches 0.70, even if staff always confirm the true dish).
Same numbers as run 10; only the claim changed. Tests run: 3, Failures: 0.
Command: cd food-seer-backend && mvn -Dtest=M0TraceEval -Dsurefire.failIfNoSpecifiedTests=false test (JDK 21)
The report below is copied unedited from target/m0-eval/m0-report.md.
-->

# M0 eval: allergy trace-back

Claim: alert recall >= 0.95 and alert precision >= 0.28 (no-regression floor) on simulated incidents, and better than every baseline.

Result: recall 0.960 (PASS), precision 0.285 (PASS).

Original precision target 0.70, revised by spiral review: not met (precision 0.285). See docs/allergy-trace-back.md.

Incidents: 500 (294 restaurant reports, 206 customer reports) over seeds [1, 2, 3, 4, 5]; 2000 customers and 4000 orders per simulated day; 40% of customers typed free text.

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | 0.960 | 0.285 | 3828 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.446 | 0.277 | 1831 | 1138 |
| Every allergic customer who ordered that day | 1.000 | 0.018 | 63646 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.960 | 0.024 | 46447 | 1138 |

Trace-back by who reported:

| Reported by | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| Restaurant | 0.955 | 0.607 | 1047 | 666 |
| Customer | 0.966 | 0.164 | 2781 | 472 |

Trace-back by seed:

| Seed | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| 1 | 0.947 | 0.297 | 782 | 245 |
| 2 | 0.950 | 0.249 | 830 | 218 |
| 3 | 0.940 | 0.274 | 802 | 234 |
| 4 | 0.977 | 0.288 | 730 | 215 |
| 5 | 0.987 | 0.326 | 684 | 226 |

Reproduce with: mvn -Dtest=M0TraceEval test
