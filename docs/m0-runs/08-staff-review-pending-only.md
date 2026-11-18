<!--
Step 4a, Milestone 1 (#2): Attempt 3 (precision), spiral review option (a).
Change: customer-submitted incidents are saved as PENDING_REVIEW and alert nobody until staff confirm the
dish (new IncidentStatus enum + Incident.status). Restaurant reports are ACTIVE and alert straight away, as before.
Compared with 03-phrase-matching.md:
  - recall    0.960 -> 0.559 (FAIL). Every customer-report alert is gone: the eval only counts alerts sent
    at report time, and it has no staff in it, so no customer report is ever confirmed.
  - precision 0.285 -> 0.607 (FAIL). That's the restaurant-only precision; customer reports were the noisy half.
  - Customer row: recall 0.000 with precision 1.000. The 1.000 is not a success: Tally.precision() returns 1.0
    when zero alerts are sent. Zero alerts counts as "perfectly precise".
  - Even with every noisy customer alert removed, precision is 0.607 < 0.70, so this option alone can't pass.
Existing unit tests that also broke (TraceServiceImplTest), each because customer reports no longer alert at report time:
  testCustomWindowHoursAreUsed:508                                    expected: <[bob]> but was: <[]>
  testCustomerReportNarrowsToFoodsThatDeclareTheAllergen:430          expected: <[bob]> but was: <[]>
  testCustomerReportWithUndeclaredAllergenTreatsEveryFoodAsSuspect:447 expected: <[bob, carol]> but was: <[]>
  testCustomerWindowIsTwoHoursEachSideByDefault:491                   expected: <[bob, carol]> but was: <[]>
  testReporterIsNotNotifiedButTheirOrderIsStillCounted:475            expected: <[1]> but was: <[]>
  testZeroWindowHoursOnlyMatchesTheSameInstant:523                    expected: <[bob]> but was: <[]>
  (Tests run: 81 across TraceServiceImplTest, IncidentControllerTest, NotificationServiceImplTest; Failures: 6)
Command: cd food-seer-backend && mvn -Dtest=M0TraceEval -Dsurefire.failIfNoSpecifiedTests=false test (JDK 21)
The report below is copied unedited from target/m0-eval/m0-report.md.
-->

# M0 eval: allergy trace-back

Claim: alert recall >= 0.95 and alert precision >= 0.70 on simulated incidents.

Result: recall 0.559 (FAIL), precision 0.607 (FAIL).

Incidents: 500 (294 restaurant reports, 206 customer reports) over seeds [1, 2, 3, 4, 5]; 2000 customers and 4000 orders per simulated day; 40% of customers typed free text.

| Approach | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| FoodSeer trace-back (this feature) | 0.559 | 0.607 | 1047 | 1138 |
| Same trace, exact string match (old FoodSeer matching) | 0.263 | 0.606 | 493 | 1138 |
| Every allergic customer who ordered that day | 1.000 | 0.018 | 63646 | 1138 |
| Everyone who ordered a suspect dish in the window | 0.559 | 0.048 | 13115 | 1138 |

Trace-back by who reported:

| Reported by | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| Restaurant | 0.955 | 0.607 | 1047 | 666 |
| Customer | 0.000 | 1.000 | 0 | 472 |

Trace-back by seed:

| Seed | Recall | Precision | Alerts sent | Truly exposed |
| :- | -: | -: | -: | -: |
| 1 | 0.465 | 0.616 | 185 | 245 |
| 2 | 0.518 | 0.579 | 195 | 218 |
| 3 | 0.577 | 0.563 | 240 | 234 |
| 4 | 0.581 | 0.676 | 185 | 215 |
| 5 | 0.659 | 0.616 | 242 | 226 |

Reproduce with: mvn -Dtest=M0TraceEval test
