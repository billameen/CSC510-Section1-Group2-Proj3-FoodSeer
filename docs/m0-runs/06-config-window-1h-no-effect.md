# Changing the trace window in config does not move M0 (step 3a)

**Step:** Milestone 1, step 3: Attempt 2 (precision) by narrowing the customer-report window.
**Code:** same as after step 2b. Nothing was edited for this run.

**What I tried:** set the production setting `foodseer.trace.window-hours` to 1 (default 2), using Spring's environment-variable form, then re-ran the eval.

```
cd food-seer-backend
FOODSEER_TRACE_WINDOWHOURS=1 JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  mvn -q -o -B -Dtest=M0TraceEval -Dsurefire.failIfNoSpecifiedTests=false test
diff <(sed 1,13d ../docs/m0-runs/03-phrase-matching.md) target/m0-eval/m0-report.md && echo "IDENTICAL to 03: the config change did nothing"
```

**Result:** the report is byte-for-byte identical to `03-phrase-matching.md` (recall 0.960, precision 0.285).

```
Result: recall 0.960 (PASS), precision 0.285 (FAIL).
...
| FoodSeer trace-back (this feature) | 0.960 | 0.285 | 3828 | 1138 |
IDENTICAL to 03: the config change did nothing
```

**Why:** the eval never starts Spring. `M0TraceEval.buildService` builds the service by hand with a hardcoded window:

```java
return new TraceServiceImpl(orderRepository, foodRepository, incidentRepository, notificationRepository, 2);
```

So the eval measures "a 2-hour window", not "whatever window production is configured with". If someone changed the production default, M0 would keep reporting numbers for a window the app no longer uses.

**Next (3b):** move the default into one constant, `TraceServiceImpl.DEFAULT_WINDOW_HOURS`, used by both the `@Value` default and the eval. This changes wiring only; the eval's data and thresholds are untouched.
