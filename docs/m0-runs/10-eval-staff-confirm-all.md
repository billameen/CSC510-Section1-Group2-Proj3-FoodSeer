<!--
Step 6a, Milestone 1 (#2): model staff review in the eval. No threshold or held-out data changed.
Why: since step 4 a customer report alerts nobody until staff confirm the dish, and the eval had no staff,
so every customer report counted as zero alerts (recall 0.559, run 08). That measured "staff never act".
Change (M0TraceEval only): after each customer report, simulated staff confirm EVERY candidate dish.
The eval can't model staff judgment, so this gives the review no credit for picking the right dish.
(If staff always picked the true dish, run 09 measured recall 0.959 / precision 0.488: an upper bound, not gated.)
Compared with 08-staff-review-pending-only.md:
  - recall    0.559 -> 0.960 (PASS)
  - precision 0.607 -> 0.285 (FAIL)
Sanity check: the report below is byte-for-byte identical to 03-phrase-matching.md. Confirming every
candidate traces exactly what a customer report traced before review existed, as it should.
Command: cd food-seer-backend && mvn -Dtest=M0TraceEval -Dsurefire.failIfNoSpecifiedTests=false test (JDK 21)
The report below is copied unedited from target/m0-eval/m0-report.md.
-->

# M0 eval: allergy trace-back

Claim: alert recall >= 0.95 and alert precision >= 0.70 on simulated incidents.

Result: recall 0.960 (PASS), precision 0.285 (FAIL).

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
