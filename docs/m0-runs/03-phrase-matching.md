<!--
Step 2a, Milestone 1 (#2): phrase matching in AllergenMatcher.atomsOf
(a multi-word term also covers the substances of each word) plus the alias TAHINI -> SESAME.
Compared with 01-baseline.md:
  - recall 0.890 -> 0.960 (PASS); precision 0.287 -> 0.285 (still FAIL)
  - "Truly exposed" is unchanged at 1138, so the matcher change did not shift the simulated ground truth
  - "Every allergic customer who ordered that day" recall went 0.926 -> 1.000. That baseline uses
    AllergenMatcher too, so the matcher now recognises every free-text phrase the simulator uses.
    This is a sign we may be fitting the simulator's phrase list. Compare 04-heldout-tests-after-phrase-matching.md.
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
