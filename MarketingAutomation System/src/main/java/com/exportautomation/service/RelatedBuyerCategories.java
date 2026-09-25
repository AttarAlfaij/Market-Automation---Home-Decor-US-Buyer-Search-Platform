package com.exportautomation.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Product -&gt; RELATED-INDUSTRY / USE-CASE buyer categories.
 *
 * <p>A physical decor product is not only bought through importer / distributor /
 * wholesaler / retailer channels. It is also bought by the ADJACENT businesses
 * that use or resell it: a singing bowl is stocked by yoga studios, meditation
 * centers and sound-healing practitioners; a lantern by patio stores, hotels and
 * event decor companies; a candle holder by interior designers and spas.
 *
 * <p>This catalog supplies those adjacent buyer categories so the buyer-search
 * planner can fire extra queries for them ALONGSIDE - never instead of - the
 * existing product + buyer-intent queries. It is pure data plus lookup: no
 * Firecrawl, no database, no Spring, and no knowledge of any single product
 * beyond the mapping itself.
 *
 * <p>Resolution is normalization-driven, so "Singing Bowls", "singing bowl",
 * "Tibetan Singing Bowls" and "sound healing bowls" all map to the same list:
 * <ol>
 *   <li>the product text is normalized (lowercased, punctuation flattened,
 *       whitespace collapsed, plural tokens singularized);</li>
 *   <li>an exact normalized-key lookup resolves the documented product names;</li>
 *   <li>otherwise the ordered family detectors below resolve the family, most
 *       specific first, so "votive holders" never inherits the plain "candles"
 *       list and "candle holders" never inherits it either.</li>
 * </ol>
 * A product that matches nothing returns an empty list, so an unmapped product
 * generates no extra queries and behaves exactly as it did before.
 */
public final class RelatedBuyerCategories {

    private RelatedBuyerCategories() {
    }

    // ------------------------------------------------------------------
    // THE MAPPING: normalized product key -> related buyer categories,
    // in the order the categories are searched.
    // ------------------------------------------------------------------

    // Singing bowls: the wellness / meditation / therapy trades that genuinely
    // stock bowls, ordered from the most specific use case to the broadest.
    private static final List<String> SINGING_BOWL_CATEGORIES = List.of(
            "yoga studios",
            "meditation centers",
            "wellness centers",
            "sound healing practitioners",
            "sound therapy centers",
            "spiritual shops",
            "buddhist stores",
            "holistic wellness stores",
            "spas",
            "retreat centers",
            "yoga teachers"
    );

    // Decorated / decorative jars (with a handle): the gift, craft and floral
    // trades that resell them as display vessels.
    private static final List<String> DECORATIVE_JAR_CATEGORIES = List.of(
            "home decor stores",
            "gift shops",
            "handicraft stores",
            "interior decor stores",
            "lifestyle stores",
            "garden decor stores",
            "floral shops",
            "wedding event decor companies",
            "boutique retailers"
    );

    // Lanterns: outdoor/patio, hospitality and event decor buyers.
    private static final List<String> LANTERN_CATEGORIES = List.of(
            "home decor stores",
            "garden decor stores",
            "outdoor living stores",
            "patio stores",
            "lighting retailers",
            "furniture stores",
            "interior decor stores",
            "gift shops",
            "hotels",
            "restaurants",
            "event decor companies"
    );

    // Candles: home-fragrance, gifting, hospitality and event buyers.
    private static final List<String> CANDLE_CATEGORIES = List.of(
            "candle shops",
            "home decor stores",
            "gift shops",
            "lifestyle stores",
            "spas",
            "hotels",
            "restaurants",
            "wedding event companies",
            "wellness stores",
            "boutique retailers"
    );

    // Candle holders: the decor trade plus the specifiers/designers who buy them.
    private static final List<String> CANDLE_HOLDER_CATEGORIES = List.of(
            "home decor stores",
            "gift shops",
            "furniture decor stores",
            "candle retailers",
            "interior designers",
            "wedding event decor companies",
            "hotels",
            "restaurants",
            "spas",
            "lifestyle stores"
    );

    // Votive holders: candle/religious buyers, hospitality and event decor.
    private static final List<String> VOTIVE_HOLDER_CATEGORIES = List.of(
            "candle shops",
            "home decor stores",
            "gift shops",
            "wedding event decor companies",
            "restaurants",
            "hotels",
            "spas",
            "interior decor stores",
            "religious spiritual stores",
            "boutique retailers"
    );

    // Exact normalized-key lookup. Keys are ALREADY normalized (lowercase,
    // singular tokens, single spaces) - the family detectors below cover every
    // phrasing that is not listed here.
    private static final Map<String, List<String>> CATEGORIES_BY_PRODUCT =
            new LinkedHashMap<>();

    static {
        // ---- Singing / wellness bowls ----
        put("singing bowl", SINGING_BOWL_CATEGORIES);
        put("singing bowl set", SINGING_BOWL_CATEGORIES);
        put("tibetan singing bowl", SINGING_BOWL_CATEGORIES);
        put("himalayan singing bowl", SINGING_BOWL_CATEGORIES);
        put("crystal singing bowl", SINGING_BOWL_CATEGORIES);
        put("buddhist singing bowl", SINGING_BOWL_CATEGORIES);
        put("meditation singing bowl", SINGING_BOWL_CATEGORIES);
        put("sound healing bowl", SINGING_BOWL_CATEGORIES);

        // ---- Decorated / decorative jars (with a handle) ----
        put("jar", DECORATIVE_JAR_CATEGORIES);
        put("decorative jar", DECORATIVE_JAR_CATEGORIES);
        put("decorated jar", DECORATIVE_JAR_CATEGORIES);
        put("jar with handle", DECORATIVE_JAR_CATEGORIES);
        put("decorated jar with handle", DECORATIVE_JAR_CATEGORIES);
        put("decorative jar with handle", DECORATIVE_JAR_CATEGORIES);
        put("decorative glass jar", DECORATIVE_JAR_CATEGORIES);
        put("glass jar", DECORATIVE_JAR_CATEGORIES);
        put("apothecary jar", DECORATIVE_JAR_CATEGORIES);
        put("storage jar", DECORATIVE_JAR_CATEGORIES);
        put("display jar", DECORATIVE_JAR_CATEGORIES);
        put("glass canister", DECORATIVE_JAR_CATEGORIES);

        // ---- Lanterns ----
        put("lantern", LANTERN_CATEGORIES);
        put("decorative lantern", LANTERN_CATEGORIES);
        put("metal lantern", LANTERN_CATEGORIES);
        put("glass lantern", LANTERN_CATEGORIES);
        put("hanging lantern", LANTERN_CATEGORIES);
        put("garden lantern", LANTERN_CATEGORIES);
        put("wall lantern", LANTERN_CATEGORIES);
        put("lantern holder", LANTERN_CATEGORIES);

        // ---- Candles ----
        put("candle", CANDLE_CATEGORIES);
        put("decorative candle", CANDLE_CATEGORIES);
        put("scented candle", CANDLE_CATEGORIES);
        put("pillar candle", CANDLE_CATEGORIES);
        put("votive candle", CANDLE_CATEGORIES);
        put("tealight candle", CANDLE_CATEGORIES);
        put("soy candle", CANDLE_CATEGORIES);
        put("beeswax candle", CANDLE_CATEGORIES);
        put("aromatherapy candle", CANDLE_CATEGORIES);
        put("candle set", CANDLE_CATEGORIES);

        // ---- Candle holders ----
        put("candle holder", CANDLE_HOLDER_CATEGORIES);
        put("candle stand", CANDLE_HOLDER_CATEGORIES);
        put("crystal candle holder", CANDLE_HOLDER_CATEGORIES);
        put("decorative candle holder", CANDLE_HOLDER_CATEGORIES);
        put("pillar candle holder", CANDLE_HOLDER_CATEGORIES);
        put("candle sconce", CANDLE_HOLDER_CATEGORIES);
        put("candle pedestal", CANDLE_HOLDER_CATEGORIES);

        // ---- Votive holders ----
        put("votive holder", VOTIVE_HOLDER_CATEGORIES);
        put("votive holder set", VOTIVE_HOLDER_CATEGORIES);
        put("decorative votive holder", VOTIVE_HOLDER_CATEGORIES);
        put("glass votive holder", VOTIVE_HOLDER_CATEGORIES);
        put("crystal votive holder", VOTIVE_HOLDER_CATEGORIES);
        put("votive stand", VOTIVE_HOLDER_CATEGORIES);
    }

    private static void put(String key, List<String> categories) {
        CATEGORIES_BY_PRODUCT.put(key, categories);
    }

    // ------------------------------------------------------------------
    // Family detectors, evaluated in this order (most specific first) when the
    // exact key is unknown, so a narrower product never inherits a broader
    // family's categories.
    // ------------------------------------------------------------------

    // Both the singular and the plural form of every family word are listed, so
    // detection never depends on normalization alone.
    private static final List<String> VOTIVE_WORDS = List.of("votive", "votives");
    private static final List<String> TEALIGHT_WORDS =
            List.of("tealight", "tealights", "tea light", "tea lights");
    private static final List<String> HOLDER_WORDS = List.of(
            "holder", "holders", "stand", "stands", "sconce", "sconces",
            "pedestal", "pedestals");
    private static final List<String> CANDLE_WORDS = List.of(
            "candle", "candles", "candela", "candlestick", "candlesticks",
            "candelabra");
    private static final List<String> JAR_WORDS = List.of(
            "jar", "jars", "canister", "canisters", "decanter", "decanter");
    private static final List<String> LANTERN_WORDS = List.of(
            "lantern", "lanterns", "lanterna", "hurricane light", "hurricane lights");
    // A "bowl" only counts when it is a WELLNESS bowl: a plain kitchen bowl is a
    // different product and must never inherit the sound-therapy categories.
    private static final List<String> BOWL_WORDS = List.of("bowl", "bowls", "bason", "basons");
    private static final List<String> BOWL_QUALIFIERS = List.of(
            "singing", "sound", "meditation", "yoga", "healing", "therapy",
            "tibetan", "himalayan", "buddhist", "chakra", "crystal", "zen",
            "kundalini", "reiki", "solfeggio");

    private static final Pattern PUNCTUATION = Pattern.compile("[^a-z0-9\\s]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The related-industry / use-case buyer categories for a product, in search
     * order. Never null; empty when the product maps to no known family, in
     * which case the buyer search is planned exactly as it was before.
     */
    public static List<String> categoriesFor(String product) {
        if (product == null || product.isBlank()) {
            return List.of();
        }
        String normalized = normalize(product);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> categories = CATEGORIES_BY_PRODUCT.get(normalized);
        if (categories != null) {
            return categories;
        }
        categories = detectFamily(normalized);
        return categories == null ? List.of() : categories;
    }

    /**
     * Family detectors, most specific first. Returns null when nothing matches,
     * so an unmapped product adds no queries.
     */
    private static List<String> detectFamily(String normalized) {
        // 1. Votive / tealight holders - checked before the broader candle
        //    families so "votive holders" never falls through to plain candles.
        if (containsWord(normalized, VOTIVE_WORDS) || containsWord(normalized, TEALIGHT_WORDS)) {
            if (containsWord(normalized, HOLDER_WORDS) || containsWord(normalized, CANDLE_WORDS)) {
                return VOTIVE_HOLDER_CATEGORIES;
            }
        }
        // 2. Candle holders / candle stands.
        if (containsWord(normalized, CANDLE_WORDS) && containsWord(normalized, HOLDER_WORDS)) {
            return CANDLE_HOLDER_CATEGORIES;
        }
        // 3. Candles themselves.
        if (containsWord(normalized, CANDLE_WORDS)) {
            return CANDLE_CATEGORIES;
        }
        // 4. Decorative jars / canisters.
        if (containsWord(normalized, JAR_WORDS)) {
            return DECORATIVE_JAR_CATEGORIES;
        }
        // 5. Lanterns.
        if (containsWord(normalized, LANTERN_WORDS)) {
            return LANTERN_CATEGORIES;
        }
        // 6. Wellness / singing bowls - only with a wellness qualifier.
        if (containsWord(normalized, BOWL_WORDS) && containsWord(normalized, BOWL_QUALIFIERS)) {
            return SINGING_BOWL_CATEGORIES;
        }
        return null;
    }

    /**
     * Lowercases, flattens punctuation to single spaces, collapses whitespace
     * and singularizes plural tokens, so "Decorated Jars with Handle",
     * "decorated jar with handles" and "decorated jars with handle" all
     * normalize to the same key. The singularization is deliberately
     * conservative - a word genuinely ending in "ss", "us" or "is" (glass,
     * cactus, iris) is left untouched. Unlike the search planner's own stem
     * function it also strips four-letter plurals ("jars" -&gt; "jar"), which the
     * mapping needs: a family word must normalize to its catalog key.
     */
    static String normalize(String product) {
        if (product == null) {
            return "";
        }
        String lower = product.toLowerCase(Locale.ROOT);
        String flattened = PUNCTUATION.matcher(lower).replaceAll(" ");
        String collapsed = WHITESPACE.matcher(flattened).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return "";
        }
        String[] tokens = collapsed.split(" ");
        StringBuilder out = new StringBuilder(collapsed.length());
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(singularize(tokens[i]));
        }
        return out.toString();
    }

    private static String singularize(String token) {
        int length = token.length();
        if (length > 3 && token.endsWith("s")
                && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, length - 1);
        }
        return token;
    }

    /** Whole-word containment, so "candle" never matches inside "candelabra". */
    private static boolean containsWord(String normalized, List<String> words) {
        for (String word : words) {
            if (normalized.contains(" " + word + " ")) {
                return true;
            }
        }
        return false;
    }
}
