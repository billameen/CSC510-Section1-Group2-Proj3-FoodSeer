package FoodSeer.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a customer's saved restrictions are affected by an allergen
 * named in an incident, and whether a food's allergen tags include it.
 *
 * Matching is never exact string equality. Every term is first normalized
 * (case, punctuation, plural endings, filler words such as "free" or "allergic
 * to"), then expanded to a set of specific substances ("atoms"). Two terms
 * match when their atom sets overlap. For example:
 * <ul>
 * <li>"peanut", "Peanuts" and "PEANUTS" all become the same term.</li>
 * <li>"nuts" covers peanuts and every tree nut, so a "nuts" incident affects a
 * peanut-allergic customer, but a "peanut" incident does not affect a
 * customer who only avoids tree nuts.</li>
 * <li>"fish" and "shellfish" never match each other, even though one word
 * contains the other.</li>
 * </ul>
 * When a term is not in the tables below, it matches only itself after
 * normalization. The tables lean toward matching too much, because a missed
 * warning is worse than an extra one.
 */
public final class AllergenMatcher {

    /** Words that may appear before an allergen and carry no meaning. */
    private static final Set<String> LEADING_FILLER = new HashSet<>();

    /** Words that may appear after an allergen and carry no meaning. */
    private static final Set<String> TRAILING_FILLER = new HashSet<>();

    /** Alternate spellings and synonyms, mapped to the canonical term. */
    private static final Map<String, String> ALIASES = new HashMap<>();

    /** For group terms, every specific substance the group covers. */
    private static final Map<String, Set<String>> GROUPS = new HashMap<>();

    /** Words that start a food tag saying the food does NOT contain something. */
    private static final Set<String> NEGATING_PREFIX = Set.of("NO", "NON", "WITHOUT");

    static {
        LEADING_FILLER.add("NO");
        LEADING_FILLER.add("NON");
        LEADING_FILLER.add("AVOID");
        LEADING_FILLER.add("WITHOUT");
        LEADING_FILLER.add("ALLERGIC");
        LEADING_FILLER.add("ALLERGY");
        LEADING_FILLER.add("ALLERGIES");
        LEADING_FILLER.add("INTOLERANT");
        LEADING_FILLER.add("INTOLERANCE");
        LEADING_FILLER.add("TO");

        TRAILING_FILLER.add("FREE");
        TRAILING_FILLER.add("ALLERGY");
        TRAILING_FILLER.add("ALLERGIES");
        TRAILING_FILLER.add("ALLERGIC");
        TRAILING_FILLER.add("INTOLERANCE");
        TRAILING_FILLER.add("INTOLERANT");

        ALIASES.put("DAIRY", "MILK");
        ALIASES.put("LACTOSE", "MILK");
        ALIASES.put("COW MILK", "MILK");
        ALIASES.put("SOYA", "SOY");
        ALIASES.put("SOYBEAN", "SOY");
        ALIASES.put("SESAME SEED", "SESAME");
        ALIASES.put("GROUNDNUT", "PEANUT");
        ALIASES.put("GROUND NUT", "PEANUT");
        ALIASES.put("TREENUT", "TREE NUT");
        ALIASES.put("SHELL FISH", "SHELLFISH");
        ALIASES.put("PRAWN", "SHRIMP");
        ALIASES.put("CRAWFISH", "CRAYFISH");
        ALIASES.put("SULPHITE", "SULFITE");
        ALIASES.put("WHEAT FLOUR", "WHEAT");
        ALIASES.put("TAHINI", "SESAME");

        // Groups are listed so that a group is defined after the groups it contains.
        addGroup("TREE NUT", "ALMOND", "WALNUT", "CASHEW", "PECAN", "PISTACHIO", "HAZELNUT", "MACADAMIA",
                "BRAZIL NUT", "PINE NUT", "CHESTNUT");
        addGroup("NUT", "TREE NUT", "PEANUT");
        addGroup("CRUSTACEAN", "SHRIMP", "CRAB", "LOBSTER", "CRAYFISH");
        addGroup("MOLLUSK", "CLAM", "OYSTER", "MUSSEL", "SCALLOP", "SQUID");
        addGroup("SHELLFISH", "CRUSTACEAN", "MOLLUSK");
        addGroup("FISH", "SALMON", "TUNA", "COD", "ANCHOVY", "TROUT", "TILAPIA", "HALIBUT", "HADDOCK", "SARDINE",
                "MACKEREL", "BASS", "SNAPPER");
        addGroup("GLUTEN", "WHEAT", "BARLEY", "RYE");
        addGroup("POULTRY", "CHICKEN", "TURKEY", "DUCK");
        addGroup("MEAT", "BEEF", "PORK", "LAMB", "POULTRY");
    }

    /** Utility class, not meant to be created. */
    private AllergenMatcher() {
        // prevent instantiation
    }

    /**
     * Registers a group term and the specific terms it covers.
     *
     * @param head
     *            the group term, for example "TREE NUT"
     * @param members
     *            terms the group covers; if a member is itself a group that was
     *            registered earlier, all of its substances are included
     */
    private static void addGroup(final String head, final String... members) {
        final Set<String> atoms = new LinkedHashSet<>();
        atoms.add(head);
        for (final String member : members) {
            final Set<String> memberAtoms = GROUPS.get(member);
            if (memberAtoms != null) {
                atoms.addAll(memberAtoms);
            } else {
                atoms.add(member);
            }
        }
        GROUPS.put(head, atoms);
    }

    /**
     * Turns one raw term into its normalized form: upper case, punctuation
     * treated as spaces, filler words removed, plural endings removed.
     *
     * @param raw
     *            raw text such as "Tree-Nuts" or "peanut allergy"
     * @return the normalized term such as "TREE NUT" or "PEANUT"; an empty
     *         string if the input is null, blank, or only filler words
     */
    public static String normalizeTerm(final String raw) {
        if (raw == null) {
            return "";
        }
        final String upper = raw.toUpperCase(Locale.ROOT);
        final String lettersOnly = upper.replaceAll("[^A-Z0-9]+", " ").trim();
        if (lettersOnly.isEmpty()) {
            return "";
        }

        final List<String> words = new ArrayList<>();
        for (final String word : lettersOnly.split(" ")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }

        while (!words.isEmpty() && LEADING_FILLER.contains(words.get(0))) {
            words.remove(0);
        }
        while (!words.isEmpty() && TRAILING_FILLER.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }

        final StringBuilder joined = new StringBuilder();
        for (final String word : words) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(singularize(word));
        }
        return joined.toString();
    }

    /**
     * Removes a plural ending from a single word.
     *
     * @param word
     *            an upper case word
     * @return the word without a plural ending; words ending in SS or US (such
     *         as BASS or HUMMUS) are left alone
     */
    private static String singularize(final String word) {
        if (word.length() > 4 && word.endsWith("IES")) {
            return word.substring(0, word.length() - 3) + "Y";
        }
        if (word.length() > 3 && word.endsWith("S") && !word.endsWith("SS") && !word.endsWith("US")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /**
     * Splits free text such as "PEANUTS, TREE-NUTS and gluten free" into
     * normalized terms.
     *
     * @param text
     *            the saved restrictions text, may be null
     * @return normalized terms in the order they appeared, without duplicates
     *         or empty entries
     */
    public static List<String> parseTerms(final String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final String upper = text.toUpperCase(Locale.ROOT);
        final String[] pieces = upper.split("[,;:|/&+\\n\\r]|\\bAND\\b|\\bOR\\b");
        final Set<String> terms = new LinkedHashSet<>();
        for (final String piece : pieces) {
            final String term = normalizeTerm(piece);
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * Expands a normalized term to the set of specific substances it stands for.
     *
     * A multi-word term that is not a known alias or group, such as "EGG
     * WHITE" or "SOY SAUCE", also covers the substances of each of its words,
     * so it overlaps "EGG" or "SOY".
     *
     * @param normalizedTerm
     *            a term already passed through {@link #normalizeTerm(String)}
     * @return the substances covered; the term itself if it is not a known
     *         group, plus the substances of each word if it has several words
     */
    static Set<String> atomsOf(final String normalizedTerm) {
        final Set<String> known = knownAtomsOf(normalizedTerm);
        if (known != null || !normalizedTerm.contains(" ")) {
            return known != null ? known : Collections.singleton(normalizedTerm);
        }
        final Set<String> atoms = new LinkedHashSet<>();
        atoms.add(normalizedTerm);
        for (final String word : normalizedTerm.split(" ")) {
            final Set<String> wordAtoms = knownAtomsOf(word);
            if (wordAtoms != null) {
                atoms.addAll(wordAtoms);
            } else {
                atoms.add(word);
            }
        }
        return atoms;
    }

    /**
     * Looks a term up in the alias and group tables.
     *
     * @param term
     *            a normalized term or a single normalized word
     * @return the term's group substances, the canonical term in a singleton
     *         set if it is an alias, or null if the tables don't know it
     */
    private static Set<String> knownAtomsOf(final String term) {
        final String canonical = ALIASES.containsKey(term) ? ALIASES.get(term) : term;
        final Set<String> group = GROUPS.get(canonical);
        if (group != null) {
            return group;
        }
        if (!canonical.equals(term)) {
            return Collections.singleton(canonical);
        }
        return null;
    }

    /**
     * Checks whether any of a customer's saved restrictions is touched by an
     * incident allergen.
     *
     * @param restrictionsText
     *            the customer's saved restrictions, such as "PEANUTS, GLUTEN";
     *            may be null or blank
     * @param incidentAllergen
     *            the allergen named in the incident
     * @return true if at least one restriction overlaps the allergen; false if
     *         either side is empty
     */
    public static boolean restrictionsAffectedBy(final String restrictionsText, final String incidentAllergen) {
        final String incidentTerm = normalizeTerm(incidentAllergen);
        if (incidentTerm.isEmpty()) {
            return false;
        }
        final Set<String> incidentAtoms = atomsOf(incidentTerm);
        for (final String restriction : parseTerms(restrictionsText)) {
            if (!Collections.disjoint(atomsOf(restriction), incidentAtoms)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a food's allergen tags include an incident allergen.
     *
     * @param foodTags
     *            the allergen tags on the food, such as ["TREE-NUTS", "PEANUTS"];
     *            may be null
     * @param incidentAllergen
     *            the allergen named in the incident
     * @return true if at least one tag overlaps the allergen
     */
    public static boolean foodDeclaresAllergen(final Collection<String> foodTags, final String incidentAllergen) {
        final String incidentTerm = normalizeTerm(incidentAllergen);
        if (incidentTerm.isEmpty() || foodTags == null) {
            return false;
        }
        final Set<String> incidentAtoms = atomsOf(incidentTerm);
        for (final String tag : foodTags) {
            final String term = normalizeTerm(removeFreeFromClaims(tag));
            if (term.isEmpty()) {
                continue;
            }
            if (!Collections.disjoint(atomsOf(term), incidentAtoms)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the "free-from" part of a food tag, because a free-from word
     * means the opposite on a food than in a customer's restrictions. In a
     * restriction, "dairy-free" means "avoid dairy", so {@link #normalizeTerm}
     * drops the filler and keeps DAIRY. On a food, "dairy-free" means "contains
     * no dairy", so DAIRY must be dropped instead.
     * <ul>
     * <li>"DAIRY-FREE", "NO NUTS", "NON-DAIRY CREAMER": nothing is declared.</li>
     * <li>"GLUTEN FREE BREAD": only "BREAD" is kept.</li>
     * <li>"SUGAR-FREE PEANUT BUTTER": "PEANUT BUTTER" is kept.</li>
     * <li>"FREE RANGE EGGS": unchanged, because FREE does not follow an
     * ingredient here.</li>
     * </ul>
     *
     * @param rawTag
     *            a food's allergen tag as stored, may be null
     * @return the part of the tag that names what the food does contain, still
     *         to be normalized; empty if the whole tag is a free-from claim
     */
    static String removeFreeFromClaims(final String rawTag) {
        if (rawTag == null) {
            return "";
        }
        final String[] words = rawTag.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim().split(" ");
        if (NEGATING_PREFIX.contains(words[0])) {
            return "";
        }
        int start = 0;
        for (int i = 1; i < words.length; i++) {
            if ("FREE".equals(words[i])) {
                start = i + 1;
            }
        }
        return String.join(" ", Arrays.asList(words).subList(start, words.length));
    }
}
