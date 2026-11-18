# Held-out matcher tests, before any matcher change

**Step:** Milestone 1, step 1: write held-out tests before touching `AllergenMatcher`.
**Code under test:** `AllergenMatcher.java`, unchanged since baseline commit `9bde2b8`.
**M0 eval:** not re-run, because no production code changed. The numbers are still those in `01-baseline.md`.

None of these phrases are in `M0TraceEval.FREE_TEXT`. They check whether a matcher change generalises to how real customers type, or only fits the simulator's own phrase list.

| Test | Phrase | Expected | Result now |
| :- | :- | :- | :- |
| `testHeldOutEggYolkMeansEgg` | restriction `egg yolk` vs EGG | match | ❌ no match |
| `testHeldOutSesameOilMeansSesame` | restriction `sesame oil` vs SESAME | match | ❌ no match |
| `testHeldOutPeanutOilMeansPeanut` | restriction `peanut oil` vs PEANUT | match | ❌ no match |
| `testHeldOutWheyMeansMilk` | restriction `whey` vs MILK | match | ❌ no match |
| `testHeldOutEdamameMeansSoy` | restriction `edamame` vs SOY | match | ❌ no match |
| `testHeldOutGlutenFreeFoodTagDoesNotDeclareWheat` | food tag `GLUTEN FREE BREAD` vs WHEAT | no match | ✅ passes (guard for step 2) |
| `testHeldOutDairyFreeFoodTagDoesNotDeclareMilk` | food tag `DAIRY-FREE` vs MILK | no match | ❌ **matches: existing bug** |

**What this shows**
- Multi-word phrases (`egg yolk`, `sesame oil`, `peanut oil`) miss, because the matcher only knows whole terms. This is the same gap that costs M0 recall ("egg whites", "soy sauce", "peanut butter").
- Ingredient names (`whey`, `edamame`) miss, because the matcher has no ingredient knowledge.
- `DAIRY-FREE` is a bug that already exists. "FREE" is stripped as filler, which is right for a restriction ("dairy-free" means *avoid* dairy) but backwards for a food tag ("dairy-free" means *no* dairy inside). So a dairy-free dish is treated as containing milk.

## Command

```
cd food-seer-backend
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=AllergenMatcherTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Output (verbatim)

```
[INFO] Results:
[INFO] 
[ERROR] Failures: 
[ERROR]   AllergenMatcherTest.testHeldOutDairyFreeFoodTagDoesNotDeclareMilk:308 A dish tagged 'DAIRY-FREE' must not count as containing milk. ==> expected: <false> but was: <true>
[ERROR]   AllergenMatcherTest.testHeldOutEdamameMeansSoy:296 Edamame is soybeans, so an 'edamame' customer should be warned about a soy incident. ==> expected: <true> but was: <false>
[ERROR]   AllergenMatcherTest.testHeldOutEggYolkMeansEgg:272 A customer who typed 'egg yolk' should be warned about an egg incident. ==> expected: <true> but was: <false>
[ERROR]   AllergenMatcherTest.testHeldOutPeanutOilMeansPeanut:284 A customer who typed 'peanut oil' should be warned about a peanut incident. ==> expected: <true> but was: <false>
[ERROR]   AllergenMatcherTest.testHeldOutSesameOilMeansSesame:278 A customer who typed 'sesame oil' should be warned about a sesame incident. ==> expected: <true> but was: <false>
[ERROR]   AllergenMatcherTest.testHeldOutWheyMeansMilk:290 Whey is a milk protein, so a 'whey' customer should be warned about a milk incident. ==> expected: <true> but was: <false>
[INFO] 
[ERROR] Tests run: 41, Failures: 6, Errors: 0, Skipped: 0
```
