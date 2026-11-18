# M0 eval: allergy trace-back

Claim: alert recall >= 0.95 and alert precision >= 0.70 on simulated incidents.

Result: recall 0.890 (FAIL), precision 0.287 (FAIL).

Incidents: 500 (294 restaurant reports, 206 customer reports) over seeds [1, 2, 3, 4, 5]; 2000 customers and 4000 orders per simulated day; 40% of customers typed free text.

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | 0.890 | 0.287 | 3531 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.446 | 0.277 | 1831 | 1138 |
| Every allergic customer who ordered that day | 0.926 | 0.018 | 58683 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.960 | 0.024 | 46447 | 1138 |

Trace-back by who reported:

| Reported by | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| Restaurant | 0.889 | 0.610 | 970 | 666 |
| Customer | 0.892 | 0.164 | 2561 | 472 |

Trace-back by seed:

| Seed | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| 1 | 0.882 | 0.300 | 719 | 245 |
| 2 | 0.853 | 0.248 | 750 | 218 |
| 3 | 0.897 | 0.278 | 756 | 234 |
| 4 | 0.907 | 0.288 | 677 | 215 |
| 5 | 0.912 | 0.328 | 629 | 226 |

Reproduce with: mvn -Dtest=M0TraceEval test
