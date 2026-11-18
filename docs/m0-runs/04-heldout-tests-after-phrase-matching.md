# Held-out matcher tests, after phrase matching (step 2a)

**Change:** in `AllergenMatcher.atomsOf`, a multi-word term that isn't a known alias or group also covers the substances of each of its words. Plus the alias `TAHINI → SESAME`.
**M0 result for the same code:** `03-phrase-matching.md` (recall 0.960 PASS, precision 0.285 FAIL).

| Test | Before (`02`) | After |
| :- | :- | :- |
| `egg yolk` → EGG | ❌ | ✅ fixed |
| `sesame oil` → SESAME | ❌ | ✅ fixed |
| `peanut oil` → PEANUT | ❌ | ✅ fixed |
| `whey` → MILK | ❌ | ❌ still fails (ingredient name, no shared word) |
| `edamame` → SOY | ❌ | ❌ still fails (ingredient name, no shared word) |
| food tag `GLUTEN FREE BREAD` ↛ WHEAT | ✅ | ❌ **new regression**: the per-word split sees `GLUTEN` and ignores the `FREE` after it |
| food tag `DAIRY-FREE` ↛ MILK | ❌ | ❌ still fails (existing filler bug) |

**What this shows:** phrase matching generalises to new multi-word phrasings that share a word with an allergen. It doesn't help with ingredient names, and it creates a new false positive on "free-from" food tags. M0 can't see any of this, because the simulator's menu has no "free-from" tags.

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
[ERROR]   AllergenMatcherTest.testHeldOutGlutenFreeFoodTagDoesNotDeclareWheat:302 A dish tagged 'GLUTEN FREE BREAD' must not count as containing wheat. ==> expected: <false> but was: <true>
[ERROR]   AllergenMatcherTest.testHeldOutWheyMeansMilk:290 Whey is a milk protein, so a 'whey' customer should be warned about a milk incident. ==> expected: <true> but was: <false>
[INFO] 
[ERROR] Tests run: 41, Failures: 4, Errors: 0, Skipped: 0
```
