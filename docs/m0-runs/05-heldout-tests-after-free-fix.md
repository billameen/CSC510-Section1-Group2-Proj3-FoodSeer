# Held-out matcher tests, after the free-from fix (step 2b)

**Change:** `AllergenMatcher.foodDeclaresAllergen` now strips the free-from part of a **food tag** before matching (`removeFreeFromClaims`). Restrictions are unchanged: "dairy-free" in a customer's restrictions still means "avoid dairy".
**M0:** re-run, and the report is identical to `03-phrase-matching.md` (recall 0.960 PASS, precision 0.285 FAIL). The simulator's menu has no free-from tags, so M0 can't see this bug or its fix.
**Full backend suite** (excluding Selenium; H2 in place of MySQL): 325 run, 322 pass. The only 3 failures are the M0 precision claim and the two known gaps below.

| Test | `02` before | `04` after phrase matching | After free-from fix |
| :- | :- | :- | :- |
| `egg yolk` → EGG | ❌ | ✅ | ✅ |
| `sesame oil` → SESAME | ❌ | ✅ | ✅ |
| `peanut oil` → PEANUT | ❌ | ✅ | ✅ |
| `whey` → MILK | ❌ | ❌ | ❌ known gap: ingredient names |
| `edamame` → SOY | ❌ | ❌ | ❌ known gap: ingredient names |
| food tag `GLUTEN FREE BREAD` ↛ WHEAT | ✅ | ❌ regression | ✅ fixed |
| food tag `DAIRY-FREE` ↛ MILK | ❌ existing bug | ❌ | ✅ fixed |

New tests added with the fix (all pass):
- `testFreeFromStillMeansAvoidInARestriction`: "dairy-free" / "gluten free bread" as a *restriction* still match.
- `testNegatedFoodTagsDeclareNothing`: `NO NUTS`, `NON-DAIRY CREAMER`, `Peanut-Free`.
- `testWordsAfterAFreeFromClaimAreStillDeclared`: `SUGAR-FREE PEANUT BUTTER` still declares peanut.
- `testFreeAtTheStartIsNotAFreeFromClaim`: `FREE RANGE EGGS` still declares egg. This guards against over-correcting, which would hide a real allergen.

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
[ERROR]   AllergenMatcherTest.testHeldOutEdamameMeansSoy:296 Edamame is soybeans, so an 'edamame' customer should be warned about a soy incident. ==> expected: <true> but was: <false>
[ERROR]   AllergenMatcherTest.testHeldOutWheyMeansMilk:290 Whey is a milk protein, so a 'whey' customer should be warned about a milk incident. ==> expected: <true> but was: <false>
[INFO] 
[ERROR] Tests run: 45, Failures: 2, Errors: 0, Skipped: 0
```
