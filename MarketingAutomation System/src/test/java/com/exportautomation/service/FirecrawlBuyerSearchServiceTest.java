package com.exportautomation.service;

import com.exportautomation.dto.BuyerSearchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the Firecrawl buyer-search strategy (no Spring context,
 * no network). Exercises private static-state methods through reflection:
 *   - sourceUrl (exact Firecrawl page) preserved separately from the
 *     normalized root website and used FIRST for product evidence
 *   - whole-budget fair allocation across multiple products (2->5/5,
 *     3->4/3/3, 4->3/3/2/2) still capped at MAX_FIRECRAWL_REQUESTS_PER_SEARCH
 *   - article/content pages never accepted as buyers
 */
class FirecrawlBuyerSearchServiceTest {

    private static final String SVC_CLASS = "com.exportautomation.service.FirecrawlBuyerSearchService";

    private static final Set<String> CORE_QUERY_INTENTS = Set.of(
            "importer", "exporter", "distributor", "wholesaler", "retailer",
            "store", "shop", "supplier", "wholesale", "manufacturer", "trading",
            "dealer", "stockist", "vendor", "b2b", "buyer", "bulk",
            "online store", "boutique",
            "contact us", "wholesale inquiries");

    /** A BLANK word is the deliberate wordless "product + city + state"
     *  location-only form, not a missing intent. */
    private static boolean expectedQueryIntent(String word) {
        if (word == null) {
            return false;
        }
        if (word.isBlank()) {
            return true;
        }
        String normalized = word.toLowerCase(Locale.ROOT);
        return CORE_QUERY_INTENTS.contains(normalized)
                || normalized.endsWith(" store")
                || normalized.endsWith(" shop")
                || normalized.endsWith(" boutique")
                || normalized.endsWith(" retailer")
                || normalized.endsWith(" center")
                || normalized.equals("artisan market");
    }

    // --- reflection helpers -------------------------------------------------

    private static Class<?> svc() throws Exception {
        return Class.forName(SVC_CLASS);
    }

    private static Object newService() throws Exception {
        Object service = svc().getDeclaredConstructor().newInstance();
        Field field = FirecrawlBuyerSearchService.class.getDeclaredField("emailDiscoveryService");
        field.setAccessible(true);
        field.set(service, mock(EmailDiscoveryService.class));
        return service;
    }

    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Object invokeStatic(String name, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method m = svc().getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Object newSlot(String phrase, String word) throws Exception {
        Class<?> slot = Class.forName(SVC_CLASS + "$Slot");
        Constructor<?> c = slot.getDeclaredConstructor(
                String.class, String.class, String.class, List.class);
        c.setAccessible(true);
        return c.newInstance(phrase, word, null, null);
    }

    private static String slotPhrase(Object slot) throws Exception {
        Field f = slot.getClass().getDeclaredField("phrase");
        f.setAccessible(true);
        return (String) f.get(slot);
    }

    private static String slotWord(Object slot) throws Exception {
        Field f = slot.getClass().getDeclaredField("word");
        f.setAccessible(true);
        return (String) f.get(slot);
    }

    private static String generatedQuery(Object candidate) throws Exception {
        Field field = candidate.getClass().getDeclaredField("query");
        field.setAccessible(true);
        return (String) field.get(candidate);
    }

    private static List<?> generatedQueries(Object svc, String product) throws Exception {
        return (List<?>) invoke(svc, "generateProductSearchQueries",
                new Class<?>[]{String.class}, product);
    }

    private static int requestBudget() throws Exception {
        Field f = svc().getDeclaredField("MAX_FIRECRAWL_REQUESTS_PER_SEARCH");
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** How many distinct buyer-role intents one search cycles through. */
    private static int locationBuyerIntentCount() throws Exception {
        Field f = svc().getDeclaredField("LOCATION_BUYER_INTENTS");
        f.setAccessible(true);
        return ((String[]) f.get(null)).length;
    }

    private static BuyerSearchResult singingBowlCandidate(String rawTitle, String snippet) {
        BuyerSearchResult result = new BuyerSearchResult();
        result.setCompanyName("Acme Imports");
        result.setWebsite("https://acme.example");
        result.setSourceUrl("https://acme.example");
        result.setProductName("Singing Bowls");
        result.setSearchProduct("singing bowls");
        result.setCountry("USA");
        result.setRawTitle(rawTitle);
        result.setSnippet(snippet);
        return result;
    }

    private static Object newSearchTally() throws Exception {
        Constructor<?> constructor = Class
                .forName("com.exportautomation.service.FirecrawlBuyerSearchService$SearchTally")
                .getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static boolean relevant(Object svc, BuyerSearchResult result, String homepage,
                                    Object tally) throws Exception {
        Class<?> tallyClass = Class.forName("com.exportautomation.service.FirecrawlBuyerSearchService$SearchTally");
        Map<String, String> homepageCache = new HashMap<>();
        homepageCache.put(result.getWebsite(), homepage);
        return (Boolean) invoke(svc, "isRelevantBuyer",
                new Class<?>[]{BuyerSearchResult.class, Map.class, Map.class, tallyClass},
                result, homepageCache, new HashMap<>(), tally);
    }

    private static int tallyCount(Object tally, String name) throws Exception {
        Field field = tally.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(tally)).intValue();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = FirecrawlBuyerSearchService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static List<?> productPlan(Object svc, String product, int slotCount) throws Exception {
        return (List<?>) invoke(svc, "buildProductPlan",
                new Class<?>[]{String.class, int.class}, product, slotCount);
    }

    private static String callString(Object svc, String name, Class<?>[] paramTypes, Object... args)
            throws Exception {
        return (String) invoke(svc, name, paramTypes, args);
    }

    // --- query strategy -----------------------------------------------------

    @Test
    void commercialQueryStrategyFiresOnlyCommercialIntentWords() throws Exception {
        Object svc = newService();
        for (String product : new String[]{"Singing Bowls", "Scented Candles",
                "Decorative Jars", "Custom Plastic Bottles"}) {
            List<?> slots = productPlan(svc, product, requestBudget());
            assertEquals(requestBudget(), slots.size(),
                    product + ": one product's web plan must fill the whole request budget");
            for (Object slot : slots) {
                String word = slotWord(slot);
                assertNotNull(word, product + ": every slot must carry a buyer-role word");
                assertTrue(expectedQueryIntent(word),
                        product + " fired an unexpected intent word: '" + word + "'");
                assertFalse(word.contains("himalayan") || word.contains("crystal")
                                || word.equals("sourcing"),
                        product + ": weak query strategy still present in slot word '" + word + "'");
            }
        }
    }

    @Test
    void singleProductPlanFiresEveryBuyerIntentInPriorityOrder() throws Exception {
        // Every buyer type is searched for the user's own product, strongest
        // roles first, then the wordless product + city + state form.
        Object svc = newService();
        List<?> slots = productPlan(svc, "Singing Bowls", requestBudget());
        List<String> expectedIntents = List.of("shop", "store", "retailer", "wholesaler",
                "supplier", "distributor", "importer", "exporter", "manufacturer",
                "trading", "dealer", "stockist", "vendor", "b2b", "wholesale",
                "buyer", "bulk");
        for (int i = 0; i < expectedIntents.size(); i++) {
            assertEquals(expectedIntents.get(i), slotWord(slots.get(i)),
                    "slot " + i + " must fire the expected buyer intent in priority order");
            assertEquals("Singing Bowls", slotPhrase(slots.get(i)),
                    "every buyer intent must fire on the exact user product");
        }
        // The wordless location-only form: "Singing Bowls" + city + state.
        int wordlessSlot = expectedIntents.size();
        assertEquals("Singing Bowls", slotPhrase(slots.get(wordlessSlot)));
        assertEquals("", slotWord(slots.get(wordlessSlot)),
                "the product + city + state form must carry no buyer-intent word");
    }

    @Test
    void everyRequestedBuyerIntentFiresForEverySupportedProduct() throws Exception {
        Object svc = newService();
        List<String> required = List.of("shop", "store", "retailer", "wholesaler",
                "supplier", "distributor", "importer", "exporter", "manufacturer",
                "trading", "dealer", "stockist", "vendor", "b2b", "wholesale",
                "buyer", "bulk");
        for (String product : new String[]{"Singing Bowls", "Scented Candles",
                "Wooden Wall Decor", "Votive Holders", "Decorative Jars"}) {
            Set<String> fired = new HashSet<>();
            for (Object slot : productPlan(svc, product, requestBudget())) {
                fired.add(slotWord(slot));
            }
            assertTrue(fired.containsAll(required),
                    product + " did not fire every required buyer intent, got " + fired);
        }
    }

    @Test
    void singleProductPlanWidensCoverageInsideTheSameProductFamily() throws Exception {
        Object svc = newService();
        List<?> slots = productPlan(svc, "Singing Bowls", requestBudget());
        List<String> intents = new ArrayList<>();
        Set<String> phrases = new HashSet<>();
        for (Object slot : slots) {
            intents.add(slotWord(slot));
            phrases.add(slotPhrase(slot));
        }
        // Every trailing slot still fires a real buyer-role word, never a
        // synonym or a contact word in place of a role.
        for (String intent : intents.subList(6, intents.size())) {
            assertTrue(expectedQueryIntent(intent),
                    "trailing slot fired an unexpected intent word: '" + intent + "'");
        }
        assertTrue(phrases.size() <= 5, "query plan should avoid synonym-heavy padding");
    }

    @Test
    void singularProductSpellingDoesNotCreateNearDuplicateVariants() throws Exception {
        Object svc = newService();
        for (Object slot : productPlan(svc, "Singing Bowl", requestBudget())) {
            assertFalse(slotPhrase(slot).equalsIgnoreCase("singing bowls"));
        }
    }

    @Test
    void productPlansPreserveCandleAndJarAttributes() throws Exception {
        Object svc = newService();
        for (Object slot : productPlan(svc, "Scented Candles", requestBudget())) {
            String phrase = slotPhrase(slot).toLowerCase(Locale.ROOT);
            assertFalse(phrase.contains("holder") || phrase.contains("stand"),
                    "scented candles must not broaden into holders or stands: " + phrase);
        }
        for (Object slot : productPlan(svc, "Votive Holders", requestBudget())) {
            String phrase = slotPhrase(slot).toLowerCase(Locale.ROOT);
            assertTrue(phrase.contains("votive") && phrase.contains("holder"),
                    "votive-holder variants must remain votive holders: " + phrase);
        }
        for (Object slot : productPlan(svc, "Decorative Jars with Handles", requestBudget())) {
            String phrase = slotPhrase(slot).toLowerCase(Locale.ROOT);
            assertTrue(phrase.contains("handle"),
                    "handled-jar variants must retain the handle attribute: " + phrase);
        }
    }

    @Test
    void verificationErrorFallbackRequiresNonSnippetProductAnchor() throws Exception {
        Object svc = newService();
        BuyerSearchResult snippetOnly = singingBowlCandidate(
                "Bestsellers | Acme", "Singing bowls");
        assertFalse((Boolean) invoke(svc, "hasSafeCheapProductAnchor",
                new Class<?>[]{BuyerSearchResult.class}, snippetOnly));

        BuyerSearchResult anchored = singingBowlCandidate(
                "Singing Bowls | Acme", "Singing bowls");
        assertTrue((Boolean) invoke(svc, "hasSafeCheapProductAnchor",
                new Class<?>[]{BuyerSearchResult.class}, anchored));
    }

    @Test
    void ordinarySupportedListingDefersToHomepageValidation() throws Exception {
        Object svc = newService();
        Class<?>[] productSignature = {String.class, String.class, String.class, String.class,
                String.class, String.class};
        Class<?>[] anySignature = {String.class, String.class, String.class, String.class,
                List.class, String.class};
        String title = "Bestsellers | Acme Store";
        String snippet = "Shop online";
        String website = "https://acme.example";
        String query = "\"singing bowls\" retailer";
        assertNull(invoke(svc, "isProductRelevant", productSignature,
                title, snippet, website, null, "Singing Bowls", query));
        assertTrue((Boolean) invoke(svc, "isProductRelevantForAny", anySignature,
                title, snippet, website, null, List.of("Singing Bowls"), query));
    }

    @Test
    void homeDecorWebsiteEvidenceIsAcceptedDirectly() throws Exception {
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = new BuyerSearchResult();
        result.setCompanyName("Artisan Home Decor");
        result.setWebsite("https://artisanhome.example");
        result.setSourceUrl("https://artisanhome.example");
        result.setProductName("Wooden Wall Decor");
        result.setSearchProduct("wall art");
        result.setCountry("USA");
        result.setRawTitle("Artisan Home Decor");
        result.setSnippet("Handmade home decor and wall art");

        assertTrue(relevant(svc, result,
                "Artisan home decor. Handcrafted pieces. Contact us. Austin, Texas 78701.",
                tally));
        assertEquals("OTHER", result.getClassification());
        assertEquals(0, tallyCount(tally, "potential"));
        assertEquals(0, tallyCount(tally, "unverified"));
        assertEquals(1, tallyCount(tally, "verified"));

        BuyerSearchResult crystalResult = new BuyerSearchResult();
        crystalResult.setCompanyName("Lumina Home Decor");
        crystalResult.setWebsite("https://luminahome.example");
        crystalResult.setSourceUrl("https://luminahome.example");
        crystalResult.setProductName("Crystal Singing Bowl");
        crystalResult.setSearchProduct("crystal singing bowl");
        crystalResult.setCountry("USA");
        crystalResult.setRawTitle("Lumina Home Decor");
        crystalResult.setSnippet("Handmade home decor");
        Class<?>[] signature = {String.class, String.class, String.class, String.class,
                String.class, String.class};
        assertNotNull(invoke(svc, "isProductRelevant", signature,
                crystalResult.getRawTitle(), crystalResult.getSnippet(),
                crystalResult.getSourceUrl(), null, crystalResult.getProductName(),
                "\"crystal singing bowl\" retailer"));
        assertTrue(relevant(svc, crystalResult,
                "Handmade home decor. Decorative pieces. Contact us.", tally));
        assertEquals(2, tallyCount(tally, "verified"));
    }

    @Test
    void familyCommerceEvidenceUsesEnteredProductWhenSearchVariantIsGeneric() throws Exception {
        Object svc = newService();
        BuyerSearchResult result = new BuyerSearchResult();
        result.setProductName("Wooden Wall Decor");
        result.setSearchProduct("home decor");
        String siteText = "We sell wooden wall decor and offer custom pieces.";
        assertTrue((Boolean) invoke(svc, "hasCommerceNearProductPhrase",
                new Class<?>[]{BuyerSearchResult.class, String.class}, result, siteText));
        result.setSearchProduct(null);
        assertTrue((Boolean) invoke(svc, "hasCommerceNearProductPhrase",
                new Class<?>[]{BuyerSearchResult.class, String.class}, result, siteText));
    }

    @Test
    void wallDecorPlanStaysWithinWallProductFamily() throws Exception {
        Object svc = newService();
        Set<String> phrases = new HashSet<>();
        boolean hasWallArt = false;
        for (Object slot : productPlan(svc, "Wooden Wall Decor", requestBudget())) {
            String phrase = slotPhrase(slot).toLowerCase(Locale.ROOT);
            phrases.add(phrase);
            assertTrue(phrase.contains("wall decor") || phrase.contains("wall art"),
                    "wall-decor variants must stay in the wall product family: " + phrase);
            hasWallArt |= phrase.contains("wall art");
        }
        assertTrue(hasWallArt, "wall decor should retain a wall art discovery variant");
        assertTrue(phrases.contains("wooden wall decor"));
    }

    @Test
    void candleProductQueriesRemainWithinRequestedType() throws Exception {
        Object svc = newService();
        for (Object candidate : generatedQueries(svc, "Votive Holders")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("votive"), "votive query changed type: " + query);
            assertFalse(query.contains("tealight"), "votive query contains tealight: " + query);
        }
        for (Object candidate : generatedQueries(svc, "Tealight Holders")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("tealight"), "tealight holder query changed type: " + query);
            assertTrue(query.contains("holder") || query.contains("stand"),
                    "tealight holder query lost its holder noun: " + query);
            assertFalse(query.contains("votive"), "tealight holder query contains votive: " + query);
        }
        for (Object candidate : generatedQueries(svc, "Tealight Candles")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("candle"), "tealight candle query changed type: " + query);
            assertFalse(query.contains("holder") || query.contains("stand"),
                    "tealight candle query broadened into a holder: " + query);
        }
        for (Object candidate : generatedQueries(svc, "Crystal Candle Holder")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("crystal"), "crystal holder query lost crystal: " + query);
            assertTrue(query.contains("holder") || query.contains("stand"),
                    "crystal holder query lost its holder noun: " + query);
            assertFalse(query.contains("crystal candle set"),
                    "crystal holder query generated an unrelated candle set: " + query);
        }
    }

    @Test
    void requiredAttributeGeneratedQueriesPreserveRequestedAttributes() throws Exception {
        Object svc = newService();
        for (Object candidate : generatedQueries(svc, "Crystal Tealight Holder")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("crystal") && query.contains("tealight")
                            && (query.contains("holder") || query.contains("stand")),
                    "crystal tealight-holder query lost an attribute or noun: " + query);
        }
        for (Object candidate : generatedQueries(svc, "Crystal Votive Holder")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("crystal") && query.contains("votive") && query.contains("holder"),
                    "crystal votive-holder query lost an attribute or noun: " + query);
        }
        for (Object candidate : generatedQueries(svc, "Wooden Wall Lantern Holder")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("wall") && (query.contains("holder") || query.contains("stand")),
                    "wall-lantern query lost wall or its holder noun: " + query);
        }
        for (Object candidate : generatedQueries(svc, "Decorative Jars with Handles")) {
            String query = generatedQuery(candidate).toLowerCase(Locale.ROOT);
            assertTrue(query.contains("jar") && query.contains("handle"),
                    "handled-jar query lost jar or handle: " + query);
        }
    }

    @Test
    void votiveHolderRejectsTealightOnlyPages() throws Exception {
        Object svc = newService();
        Class<?>[] signature = {String.class, String.class, String.class, String.class,
                String.class, String.class};
        assertNull(invoke(svc, "isProductRelevant", signature,
                "Tealight Holder Store", "Shop our tealight holders",
                "https://tealight.example", null, "Votive Holders",
                "\"votive holders\" retailer"));
        assertNull(invoke(svc, "isProductRelevant", signature,
                "Tealight Holder Store", "Shop our tealight holders",
                "https://tealight.example", "We sell tealight holders online.",
                "Votive Holders", "\"votive holders\" retailer"));
    }

    @Test
    void everySlotIsAUniqueProductPlusRoleCombination() throws Exception {
        Object svc = newService();
        List<?> slots = productPlan(svc, "Singing Bowls", requestBudget());
        Set<String> pairs = new HashSet<>();
        for (Object slot : slots) {
            assertTrue(pairs.add(slotPhrase(slot) + "|" + slotWord(slot)),
                    "duplicate (phrase, role) pair: " + slotPhrase(slot) + " / " + slotWord(slot));
        }
        assertEquals(requestBudget(), pairs.size());
    }

    // --- request budget & fair multi-product allocation ---------------------

    @Test
    void requestBudgetIsGenerousEnoughToCoverEveryBuyerRole() throws Exception {
        int budget = requestBudget();
        // One search must be able to fire EVERY buyer-type intent, otherwise the
        // extra roles would never be searched and the result count would stay low.
        int intentCount = locationBuyerIntentCount();
        assertTrue(budget >= intentCount + 1,
                "budget " + budget + " must cover all " + intentCount
                        + " buyer intents plus the wordless product/location form");
        assertEquals(20, budget, "the per-search request budget is 20");
    }

    @Test
    void budgetExhaustsOnlyAfterAllRequestsAreSpent() throws Exception {
        Class<?> budget = Class.forName(SVC_CLASS + "$SearchBudget");
        Object b = budget.getDeclaredConstructor(int.class).newInstance(requestBudget());
        Method tryAcquire = budget.getDeclaredMethod("tryAcquire");
        Method exhausted = budget.getDeclaredMethod("exhausted");
        tryAcquire.setAccessible(true);
        exhausted.setAccessible(true);
        // Every web request fires first...
        for (int i = 0; i < requestBudget(); i++) {
            assertTrue((Boolean) tryAcquire.invoke(b), "request " + i + " must be acquired");
        }
        // ...so once the web plan fills the budget the budget is spent and the
        // trailing social search can no longer consume any request.
        assertTrue((Boolean) exhausted.invoke(b), "budget spent after all web requests");
        assertFalse((Boolean) tryAcquire.invoke(b), "the next request exceeds the budget");
    }

    @Test
    void retryConsumesOneOfTheSharedRequestSlots() throws Exception {
        Class<?> budget = Class.forName(SVC_CLASS + "$SearchBudget");
        Constructor<?> constructor = budget.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object value = constructor.newInstance(2);
        Method tryAcquire = budget.getDeclaredMethod("tryAcquire");
        Method tryAcquireRetry = budget.getDeclaredMethod("tryAcquireRetry");
        Method exhausted = budget.getDeclaredMethod("exhausted");
        tryAcquire.setAccessible(true);
        tryAcquireRetry.setAccessible(true);
        exhausted.setAccessible(true);
        assertTrue((Boolean) tryAcquire.invoke(value));
        assertTrue((Boolean) tryAcquireRetry.invoke(value));
        assertTrue((Boolean) exhausted.invoke(value));
    }

    @Test
    void multiProductAllocatesWholeBudgetFairly() throws Exception {
        // Each entered product gets an equal share; every product is covered.
        // The split is derived from the live budget so it stays correct when the
        // budget changes: no product may get more than the budget allows.
        int budget = requestBudget();
        assertFairSplit(2, List.of(budget / 2, budget - budget / 2));
        assertFairSplit(3, fairSplit(3));
        assertFairSplit(4, fairSplit(4));
    }

    /** The expected equal-ish per-product split of the whole request budget. */
    private static List<Integer> fairSplit(int productCount) {
        int budget = 20;
        try {
            budget = requestBudget();
        } catch (Exception ignored) {
            // fall back to the documented budget
        }
        int base = budget / productCount;
        int remainder = budget % productCount;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            out.add(base + (i < remainder ? 1 : 0));
        }
        return out;
    }

    @Test
    void multiProductInterleaveCoversEveryStrategyTier() throws Exception {
        List<List<Object>> plans = new ArrayList<>();
        for (int p = 0; p < 2; p++) {
            List<Object> plan = new ArrayList<>();
            for (int i = 0; i < requestBudget(); i++) {
                plan.add(newSlot("PROD-" + p, "tier-" + i));
            }
            plans.add(plan);
        }
        List<?> interleaved = (List<?>) invokeStatic("interleavePlans",
                new Class<?>[]{List.class, int.class}, plans, requestBudget());
        List<String> tiers = new ArrayList<>();
        for (Object slot : interleaved) {
            tiers.add(slotWord(slot));
        }
        assertEquals(requestBudget(), tiers.size(),
                "the interleave must cover the whole budget");
        // Every tier of EVERY product must appear exactly once: no strategy
        // level is dropped and none is fired twice.
        Set<String> expectedTiers = new HashSet<>();
        for (int p = 0; p < 2; p++) {
            for (int i = 0; i < requestBudget(); i++) {
                expectedTiers.add("tier-" + i);
            }
        }
        assertEquals(new HashSet<>(tiers), expectedTiers,
                "the interleave must cover every strategy tier of both products");
        assertEquals(requestBudget(), new HashSet<>(tiers).size(),
                "no tier may be repeated by the interleave");
    }

    private void assertFairSplit(int productCount, List<Integer> expected) throws Exception {
        List<List<Object>> plans = new ArrayList<>();
        for (int p = 0; p < productCount; p++) {
            List<Object> plan = new ArrayList<>();
            for (int i = 0; i < requestBudget(); i++) {
                plan.add(newSlot("PROD-" + p + "-" + i, "retailer"));
            }
            plans.add(plan);
        }
        List<?> interleaved = (List<?>) invokeStatic("interleavePlans",
                new Class<?>[]{List.class, int.class}, plans, requestBudget());
        assertEquals(requestBudget(), interleaved.size(), "total must equal the 10-request budget");
        List<Integer> counts = new ArrayList<>();
        for (int p = 0; p < productCount; p++) {
            int count = 0;
            for (Object slot : interleaved) {
                if (slotPhrase(slot).startsWith("PROD-" + p + "-")) {
                    count++;
                }
            }
            counts.add(count);
            assertTrue(count > 0, "product " + p + " must receive at least one request");
        }
        assertEquals(expected, counts, "round-robin must split the whole budget " + expected);
    }

    // --- sourceUrl vs normalized website ------------------------------------

    @Test
    void sourceUrlStaysExactAndWebsiteNormalizesToRoot() throws Exception {
        Object svc = newService();
        String raw = "https://www.musiciansfriend.com/meditation-singing-bowls?page=4";
        String root = (String) invoke(svc, "normalizeWebsite",
                new Class<?>[]{String.class}, raw);
        assertEquals("https://musiciansfriend.com", root,
                "website must be the normalized ROOT domain of the exact result URL");
        assertEquals("https://musiciansfriend.com",
                invoke(svc, "normalizeWebsite", new Class<?>[]{String.class},
                        "https://musiciansfriend.com"),
                "normalizing the root is idempotent");
        assertFalse(raw.equals(root), "exact source URL and normalized root must never be conflated");
    }

    @Test
    void productPageEvidenceInspectedFromExactSourceUrl() throws Exception {
        Object svc = newService();
        // Regression: the EXACT Firecrawl product URL the engine returned must
        // stay the inspection target while the business website is only the
        // normalized root domain - the root alone carries none of this evidence.
        String sourceUrl = "https://www.musiciansfriend.com/meditation-singing-bowls?page=4";

        // 1) The exact URL path (hyphens normalized to words) carries the product.
        String resultPath = callString(svc, "shopPathText", new Class<?>[]{String.class}, sourceUrl);
        assertTrue(resultPath.contains("singing bowls"),
                "exact result path must keep the product phrase: '" + resultPath + "'");
        String websitePath = callString(svc, "shopPathText", new Class<?>[]{String.class},
                "https://musiciansfriend.com");
        assertEquals("", websitePath, "the normalized root has no product path");

        // 2) website is the root; sourceUrl keeps the exact URL.
        assertEquals("https://musiciansfriend.com",
                callString(svc, "normalizeWebsite", new Class<?>[]{String.class}, sourceUrl));

        // 3) A real product page passes the cheap screen (never treated as an
        //    article/blog page).
        assertNull(invoke(svc, "classifyPage", new Class<?>[]{String.class, String.class, String.class},
                        sourceUrl, "Meditation Singing Bowls | Musician's Friend", "singing bowls"),
                "a real product page must pass the cheap screen");

        // 4) GATE-2 commercial evidence reads the EXACT sourceUrl page: the
        //    same call isRelevantBuyer makes at verdict time passes with a
        //    realistic product-listing page and its path text, even though the
        //    root website alone carries none of it.
        BuyerSearchResult r = new BuyerSearchResult();
        r.setWebsite("https://musiciansfriend.com");
        r.setSourceUrl(sourceUrl);
        r.setProductName("Singing Bowls");
        r.setSearchProduct("singing bowls");
        r.setRawTitle("Meditation Singing Bowls | Musician's Friend");
        r.setSnippet("Shop meditation singing bowls at Musician's Friend, in stock now");
        // titleText/snippetText are unused by commercialProductEvidenceOf (the
        // same literals isRelevantBuyer's gate passes); the decisive inputs are
        // the exact sourceUrl PATH and the result-page + homepage site text.
        String siteText = "Our online store sells Meditation Singing Bowls. Price $199.99, "
                + "In Stock, Add to Cart, Free Shipping, our shop is open 7 days.";
        String fullText = r.getRawTitle() + " " + r.getSnippet() + " " + siteText;
        assertNotNull(invoke(svc, "commercialProductEvidenceOf",
                        new Class<?>[]{BuyerSearchResult.class, String.class, String.class,
                                String.class, String.class, String.class, String.class},
                        r, fullText, "", "", siteText, websitePath, resultPath),
                "verification must accept the product page via the exact sourceUrl page "
                        + "(sourceUrl path + site text), not only the root homepage");
    }

    @Test
    void productNamedUrlEvidenceRequiresTheSourceUrlNotTheRoot() throws Exception {
        Object svc = newService();
        // A SHOPPING-path sourceUrl provides product evidence...
        BuyerSearchResult r = new BuyerSearchResult();
        r.setWebsite("https://musiciansfriend.com");
        r.setSourceUrl("https://www.musiciansfriend.com/collections/meditation-singing-bowls?page=4");
        r.setProductName("Singing Bowls");
        r.setSearchProduct("singing bowls");
        assertTrue((Boolean) invoke(svc, "hasWebsiteUrlProductEvidence",
                        new Class<?>[]{BuyerSearchResult.class}, r),
                "product evidence must be inspected on the exact sourceUrl page");
        // ...and so does a PLAIN product-named sourceUrl path (no /product/ or
        // /collections/ marker). /meditation-singing-bowls names the product
        // directly, so it is product-page evidence on the EXACT sourceUrl even
        // when the fetch result page is unavailable - verification must NOT
        // reject it because the root homepage lacks a shopping marker.
        BuyerSearchResult plainNamed = new BuyerSearchResult();
        plainNamed.setWebsite("https://musiciansfriend.com");
        plainNamed.setSourceUrl("https://www.musiciansfriend.com/meditation-singing-bowls?page=4");
        plainNamed.setProductName("Singing Bowls");
        plainNamed.setSearchProduct("singing bowls");
        assertTrue((Boolean) invoke(svc, "hasWebsiteUrlProductEvidence",
                        new Class<?>[]{BuyerSearchResult.class}, plainNamed),
                "a product-named exact sourceUrl path must count as product URL evidence");
        // ...while the bare root homepage carries none (both sourceUrl and
        // website are the root) - proving the exact URL is what is inspected.
        BuyerSearchResult rootOnly = new BuyerSearchResult();
        rootOnly.setWebsite("https://musiciansfriend.com");
        rootOnly.setSourceUrl("https://musiciansfriend.com");
        rootOnly.setProductName("Singing Bowls");
        rootOnly.setSearchProduct("singing bowls");
        assertFalse((Boolean) invoke(svc, "hasWebsiteUrlProductEvidence",
                new Class<?>[]{BuyerSearchResult.class}, rootOnly),
                "the normalized root alone must not be treated as product URL evidence");
    }

    // --- musiciansfriend critical regression --------------------------------

    @Test
    void musiciansfriendRegressionWebsiteRootSourceUrlExactAndProductEvidence()
            throws Exception {
        Object svc = newService();
        // CRITICAL REGRESSION: for a real accepted candidate the log must show
        // SOURCE_URL = the exact Firecrawl result URL while NORMALIZED_WEBSITE =
        // the dedup/DB root, and verification inspects the exact sourceUrl page.
        String sourceUrl = "https://www.musiciansfriend.com/meditation-singing-bowls?page=4";
        String root = (String) invoke(svc, "normalizeWebsite",
                new Class<?>[]{String.class}, sourceUrl);
        assertTrue(root.equals("https://musiciansfriend.com")
                        && !root.equals(sourceUrl),
                "website root and exact sourceUrl must be preserved distinctly");

        BuyerSearchResult r = new BuyerSearchResult();
        r.setWebsite(root);
        r.setSourceUrl(sourceUrl);
        r.setProductName("Singing Bowls");
        r.setSearchProduct("singing bowls");
        r.setRawTitle("Meditation Singing Bowls | Musician's Friend");
        r.setSnippet("Shop meditation singing bowls at Musician's Friend, in stock now");

        // 1) The plain product-named exact sourceUrl path IS URL product evidence.
        assertTrue((Boolean) invoke(svc, "hasWebsiteUrlProductEvidence",
                        new Class<?>[]{BuyerSearchResult.class}, r),
                "/meditation-singing-bowls on the exact sourceUrl must be URL product evidence");

        // 2) The same page must pass the cheap article/blog screen.
        assertNull(invoke(svc, "classifyPage", new Class<?>[]{String.class, String.class, String.class},
                        sourceUrl, "Meditation Singing Bowls | Musician's Friend", "singing bowls"),
                "a real product page must pass the cheap screen");

        // 3) GATE-2 commercial evidence accepts the product page through the
        //    exact sourceUrl page (path + product site text), never rejected
        //    because the root homepage alone lacks product text.
        String siteText = "Our online store sells Meditation Singing Bowls. Price $199.99, "
                + "In Stock, Add to Cart, Free Shipping, our shop is open 7 days.";
        String fullText = r.getRawTitle() + " " + r.getSnippet() + " " + siteText;
        String websitePath = callString(svc, "shopPathText", new Class<?>[]{String.class}, root);
        String resultPath = callString(svc, "shopPathText", new Class<?>[]{String.class}, sourceUrl);
        assertNotNull(invoke(svc, "commercialProductEvidenceOf",
                        new Class<?>[]{BuyerSearchResult.class, String.class, String.class,
                                String.class, String.class, String.class, String.class},
                        r, fullText, "", "", siteText, websitePath, resultPath),
                "the musiciansfriend product page must be accepted via the exact sourceUrl page");
    }

    @Test
    void cheapScreenProductMatchReadsTheExactSourceUrlPath() throws Exception {
        Object svc = newService();
        // CRITICAL REGRESSION: CONDITION 1 (cheap product screen) must inspect
        // the EXACT Firecrawl result URL path - a valid product/category page
        // whose URL carries the product but whose title does not must pass the
        // screen. Passing the normalized root instead would read an empty path
        // and wrongly discard it.
        String title = "Bestsellers | Musician's Friend"; // no product word in the title
        String snippet = "";
        String exactUrl = "https://www.musiciansfriend.com/meditation-singing-bowls?page=4";
        Class<?>[] sig = {String.class, String.class, String.class, String.class,
                String.class, String.class};
        assertNotNull(invoke(svc, "isProductRelevant", sig,
                        title, snippet, exactUrl, null, "Singing Bowls", "singing bowls"),
                "the exact sourceUrl path '/meditation-singing-bowls' must pass the cheap product screen");
        assertNull(invoke(svc, "isProductRelevant", sig,
                        title, snippet, "https://musiciansfriend.com", null, "Singing Bowls", "singing bowls"),
                "the normalized root domain has no product path - it must NOT pass the screen "
                        + "(proof the exact sourceUrl is what is verified)");
    }

    @Test
    void onlyExplicitHomeDecorPhrasesPassRelatedCheapScreen() throws Exception {
        Object svc = newService();
        Class<?>[] sig = {String.class, String.class, String.class, String.class,
                String.class, String.class};
        assertNull(invoke(svc, "isProductRelevant", sig,
                "Decor", "Wholesale decor and retail gifts",
                "https://example.com/collections/decor", null,
                "Wooden Wall Decor", "\"Wooden Wall Decor\" wholesaler"));
        assertNull(invoke(svc, "isProductRelevant", sig,
                "Gift Decor", "Wholesale gifts and decor",
                "https://example.com/collections/decor", null,
                "Wooden Wall Decor", "\"Wooden Wall Decor\" wholesaler"));
        assertNotNull(invoke(svc, "isProductRelevant", sig,
                "Home Goods Decor", "Wholesale home goods and decor",
                "https://example.com/collections/home-goods-decor", null,
                "Wooden Wall Decor", "\"Wooden Wall Decor\" wholesaler"));
        assertNotNull(invoke(svc, "isProductRelevant", sig,
                "Home Decor Store", "Wholesale home decor and retail gifts",
                "https://example.com/collections/home-decor", null,
                "Wooden Wall Decor", "\"Wooden Wall Decor\" wholesaler"));
        assertNotNull(invoke(svc, "isProductRelevant", sig,
                "Home D\u00e9cor Store", "Wholesale d\u00e9cor and retail gifts",
                "https://example.com/collections/decor", null,
                "Wooden Wall Decor", "\"Wooden Wall Decor\" wholesaler"));
        assertNotNull(invoke(svc, "isProductRelevant", sig,
                "Interior Decor Store", "Wholesale interior decor",
                "https://interiordecor.example/collections/decor", null,
                "Wooden Wall Decor", "\"Wooden Wall Decor\" wholesaler"));
    }

    @Test
    void productEvidenceAcceptsWithoutCommercialBusinessOrLocationEvidence() throws Exception {
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Imports", "Acme Imports product catalog");
        assertTrue(relevant(svc, result,
                "Singing bowls. Sound healing classes and retreats. Contact us.", tally));
        assertEquals("OTHER", result.getClassification());
        assertEquals(0, tallyCount(tally, "commercialRejects"));
        assertEquals(0, tallyCount(tally, "businessTypeRejects"));
        assertEquals(0, tallyCount(tally, "locationRejects"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void unavailableWebsiteWithProductAnchorIsAccepted() throws Exception {
        Object svc = newService();
        EmailDiscoveryService emailService = mock(EmailDiscoveryService.class);
        when(emailService.fetchHomepage("https://acme.example")).thenReturn(null);
        setField(svc, "emailDiscoveryService", emailService);
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Imports", "Acme Imports product catalog");
        assertTrue(relevant(svc, result, null, tally));
        assertEquals("OTHER", result.getClassification());
        assertEquals("UNKNOWN", result.getLocationClass());
        assertEquals(0, tallyCount(tally, "potential"));
        assertEquals(0, tallyCount(tally, "unverified"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void unavailableLocationDoesNotDowngradeOrRejectCandidate() throws Exception {
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Store", "Singing bowls for sale");
        result.setState("Texas");
        result.setCity("Austin");
        assertTrue(relevant(svc, result,
                "We sell singing bowls in our online store. Add to cart. Contact us.", tally));
        assertEquals("Retailer", result.getClassification());
        assertEquals("UNKNOWN", result.getLocationClass());
        assertNull(result.getState());
        assertNull(result.getCity());
        assertNull(result.getLocationSource());
        assertEquals("Unknown", result.getLocation());
        assertEquals(0, tallyCount(tally, "unverified"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void knownWrongStateRejectsProductRelevantCandidate() throws Exception {
        // Requirement 5: the state must be RESPECTED. A candidate whose
        // resolved address is in a DIFFERENT state than the requested one is a
        // real conflict and is never saved, however well it matches the product.
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Store", "Singing bowls for sale");
        result.setState("New York");
        result.setSearchAddress("Austin, Texas 78701");
        assertFalse(relevant(svc, result,
                "We sell singing bowls in our online store. Add to cart. Austin, Texas 78701.",
                tally));
        assertEquals(1, tallyCount(tally, "locationRejects"));
        assertEquals(0, tallyCount(tally, "verified"));
    }

    @Test
    void otherCityInTheSameStateIsAcceptedAndRanked() throws Exception {
        // Requirement 3 + 4: a different city inside the requested state is
        // NEVER a conflict - it is accepted and ranked as SAME_STATE behind the
        // exact-city results.
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Store", "Singing bowls for sale");
        result.setState("Texas");
        result.setCity("Corpus Christi");
        result.setSearchAddress("Houston, Texas 77002");
        assertTrue(relevant(svc, result,
                "We sell singing bowls. Add to cart. Houston, Texas 77002.", tally));
        assertEquals("SAME_STATE", result.getLocationClass());
        assertEquals(0, tallyCount(tally, "locationRejects"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void exactCityInTheRequestedStateIsClassifiedAsExactCity() throws Exception {
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Store", "Singing bowls for sale");
        result.setState("Texas");
        result.setCity("Corpus Christi");
        result.setSearchAddress("Corpus Christi, Texas 78402");
        assertTrue(relevant(svc, result,
                "We sell singing bowls. Add to cart. Corpus Christi, Texas 78402.", tally));
        assertEquals("EXACT_CITY", result.getLocationClass());
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void foreignLocationRejectsProductRelevantWebsite() throws Exception {
        // Requirement 5: a Texas search must not return a UK wholesaler.
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | London House", "Singing bowls");
        result.setWebsite("https://london-house.example.co.uk");
        result.setSourceUrl("https://london-house.example.co.uk");
        result.setSearchAddress("London, United Kingdom");
        assertFalse(relevant(svc, result,
                "<html><body>Singing bowls. Add to cart. Contact us. "
                        + "Based in London, United Kingdom.</body></html>",
                tally));
        assertEquals(1, tallyCount(tally, "locationRejects"));
        assertEquals(0, tallyCount(tally, "verified"));
    }

    @Test
    void unknownCityInTheRequestedStateIsStillAccepted() throws Exception {
        // Requirement 4: a genuinely product-relevant website is NEVER
        // hard-rejected just because its city could not be extracted - it is
        // saved as UNKNOWN and ranked last.
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Acme Store", "Singing bowls for sale");
        result.setState("Texas");
        result.setCity("Corpus Christi");
        assertTrue(relevant(svc, result,
                "We ship singing bowls nationwide. Add to cart. Contact us.", tally));
        assertEquals("UNKNOWN", result.getLocationClass());
        assertEquals(0, tallyCount(tally, "locationRejects"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    // --- LIGHT BUSINESS VERIFICATION + GRADED UNKNOWN LOCATION --------------

    @Test
    void websiteWithNoBusinessOrStoreEvidenceIsRejected() throws Exception {
        // Requirement 2: a page that shows no generic business / store / supplier
        // evidence at all is not a business that could buy the product. The
        // product words are present, so this is the light business gate firing.
        Object svc = newService();
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls", "Singing bowls");
        result.setWebsite("https://bowl-notes.example");
        result.setSourceUrl("https://bowl-notes.example");
        assertFalse(relevant(svc, result,
                "<html><body>Singing bowls 432 Hz, 7.5 inches, set of seven. "
                        + "Cobalt finish. Each bowl is individually tested.</body></html>",
                tally));
        assertEquals(1, tallyCount(tally, "businessTypeRejects"));
        assertEquals(0, tallyCount(tally, "verified"));
    }

    @Test
    void genericBusinessWordAloneVerifiesTheBusiness() throws Exception {
        // Requirement 2 / 6: ONE generic business word is enough. No specific
        // business type (importer / wholesaler / retailer / ...) is required,
        // and no email is required either.
        Object svc = newService();
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Harmonic Goods", "Singing bowls");
        result.setWebsite("https://harmonic-goods.example");
        result.setSourceUrl("https://harmonic-goods.example");
        assertTrue(relevant(svc, result,
                "<html><body>We make singing bowls. Contact us to ask a question. "
                        + "Each bowl is 432 Hz.</body></html>", tally));
        assertEquals(0, tallyCount(tally, "businessTypeRejects"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void lightBusinessEvidenceNeverRequiresASpecificRoleOrEmail() throws Exception {
        // Requirement 6: the light check accepts ANY role and never looks for an
        // email address, a business type or a specific buyer keyword. A boutique
        // and a trading company are equally acceptable.
        Object svc = newService();
        Class<?>[] signature = {BuyerSearchResult.class, String.class, String.class,
                String.class, String.class, String.class};
        BuyerSearchResult result = singingBowlCandidate("Singing Bowls", "Singing bowls");
        String boutique = callString(svc, "lightBusinessEvidenceOf", signature, result,
                "A small boutique. Each piece 432 Hz. No email address on the page.",
                "/", "/", "harmonic-goods.example", null);
        assertNotNull(boutique, "a generic boutique word must verify the business");
        String trading = callString(svc, "lightBusinessEvidenceOf", signature, result,
                "A small trading company. Each piece 432 Hz. No email address on the page.",
                "/", "/", "harmonic-goods.example", null);
        assertNotNull(trading, "any role must verify the business, not one specific role");
        assertTrue(boutique.contains("boutique"),
                "the boutique must be recognised on its own terms: " + boutique);
        assertTrue(trading.contains("trading") || trading.contains("company"),
                "the trading company must be recognised on its own terms: " + trading);
        assertFalse(boutique.contains("email"),
                "the light check must never require an email address: " + boutique);
    }

    @Test
    void unknownLocationIsRejectedWhenOnlyTheListingProvesTheProduct() throws Exception {
        // Requirement 5: an UNKNOWN location is accepted only when the product /
        // business relevance is strong. Here the product is named in the URL and
        // the listing headline, but the fetched page is a thin product spec sheet
        // with no store, catalogue, cart or business evidence - nothing on the
        // WEBSITE backs the relevance, so it is dropped.
        Object svc = newService();
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowl Specs | Reference Notes", "Singing bowls");
        result.setWebsite("https://reference-notes.example");
        result.setSourceUrl("https://reference-notes.example/singing-bowl");
        result.setState("Texas");
        result.setCity("Corpus Christi");
        assertFalse(relevant(svc, result,
                "<html><body>Singing bowls. Diameter 8 inches. Weight 1.2 kg. "
                        + "Origin: quartz crystal. Frequency: 432 Hz.</body></html>",
                tally));
        assertEquals(0, tallyCount(tally, "verified"));
    }

    @Test
    void unknownLocationIsAcceptedWhenTheWebsiteItselfSellsTheProduct() throws Exception {
        // Requirement 5: strong product/business relevance keeps an UNKNOWN
        // location acceptable - the site itself demonstrates it sells the
        // product, so the missing city is only a ranking penalty.
        Object svc = newService();
        setField(svc, "usaVerificationService", new UsaVerificationService());
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Singing Bowls | Harmonic Goods", "Singing bowls");
        result.setWebsite("https://harmonic-goods.example");
        result.setSourceUrl("https://harmonic-goods.example/collections/singing-bowls");
        result.setState("Texas");
        result.setCity("Corpus Christi");
        assertTrue(relevant(svc, result,
                "Our singing bowl catalogue. Add to cart. In stock. Contact us.",
                tally));
        assertEquals("UNKNOWN", result.getLocationClass());
        assertEquals(0, tallyCount(tally, "locationRejects"));
        assertEquals(1, tallyCount(tally, "verified"));
    }

    @Test
    void unrelatedWebsiteIsStillRejected() throws Exception {
        Object svc = newService();
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Garden Architecture Studio", "Landscape design and construction");
        assertFalse(relevant(svc, result,
                "<html><body>Landscape design and construction</body></html>", tally));
        assertEquals(1, tallyCount(tally, "productRejects"));
        assertEquals(0, tallyCount(tally, "verified"));
    }

    @Test
    void productMentionOnlyInListingSnippetDoesNotOverrideNonProductWebsite() throws Exception {
        Object svc = newService();
        Object tally = newSearchTally();
        BuyerSearchResult result = singingBowlCandidate(
                "Garden Architecture Studio", "Handmade home decor and singing bowls");
        result.setWebsite("https://garden.example");
        result.setSourceUrl("https://garden.example");
        assertFalse(relevant(svc, result,
                "<html><body>Architecture services, landscape design and construction.</body></html>",
                tally));
        assertEquals(1, tallyCount(tally, "productRejects"));
        assertEquals(0, tallyCount(tally, "locationRejects"));
    }

    // --- article / content rejection ----------------------------------------

    @Test
    void articleAndContentPagesStillRejected() throws Exception {
        Object svc = newService();
        assertTrue((Boolean) invoke(svc, "isArticleLike",
                new Class<?>[]{String.class, String.class, String.class},
                "soundjourneys.com", "/blog/how-to-choose-a-meditation-singing-bowl",
                "how to choose a meditation singing bowl"));
        assertNotNull(invoke(svc, "classifyPage",
                        new Class<?>[]{String.class, String.class, String.class},
                        "https://soundjourneys.com/blog/how-to-choose-a-meditation-singing-bowl",
                        "How to Choose a Singing Bowl", "singing bowls"),
                "article/blog pages must be rejected as buyers");
    }

    @Test
    void exampleComBlogArticleAboutSingingBowlsRejected() throws Exception {
        Object svc = newService();
        // CRITICAL REGRESSION: example.com/blog/article-about-singing-bowls is a
        // content/article page and must be rejected even though its path sings
        // "singing bowls" - article-like pages are never accepted as buyers.
        assertTrue((Boolean) invoke(svc, "isArticleLike",
                        new Class<?>[]{String.class, String.class, String.class},
                        "example.com", "/blog/article-about-singing-bowls",
                        "the complete guide to meditation singing bowls and how to choose one"),
                "a /blog/article-about-singing-bowls path must be detected as article-like");
        assertNotNull(invoke(svc, "classifyPage",
                        new Class<?>[]{String.class, String.class, String.class},
                        "https://example.com/blog/article-about-singing-bowls",
                        "The Complete Guide to Meditation Singing Bowls",
                        "singing bowls"),
                "example.com/blog/article-about-singing-bowls must be rejected as a buyer");
    }

    // --- LIGHT IRRELEVANT-CONTENT FILTER ------------------------------------

    private static String irrelevantReason(Object svc, String url, String title, String snippet,
                                           String homepage, boolean storeEvidence)
            throws Exception {
        Object prepared = invoke(svc, "prepareText", new Class<?>[]{String[].class},
                (Object) new String[]{title, snippet, homepage});
        return callString(svc, "irrelevantContentReason",
                new Class<?>[]{String.class, String.class, String.class, String.class,
                        boolean.class},
                url, prepared, prepared, prepared, storeEvidence);
    }

    @Test
    void musicNewsTourismAndEventPagesAreRejectedAsIrrelevantContent() throws Exception {
        Object svc = newService();
        // music / song / artist sites - the product words are the band or track name
        assertNotNull(irrelevantReason(svc, "https://singingbowls.band",
                "Singing Bowl - Ambient Meditation Music Band", "Listen to our debut album",
                "Now playing. Discography. Vinyl record.", false),
                "a music/artist site that merely names the product must be rejected");
        assertNotNull(irrelevantReason(svc, "https://open.spotify.com/artist/singing-bowls",
                "Singing Bowl | Spotify", "Songs to relax", null, false),
                "a music streaming platform must be rejected");
        assertNotNull(irrelevantReason(svc, "https://bowls.example/music",
                "Singing Bowls", "Our music", null, false),
                "a /music section must be rejected even with store evidence");
        // government / tourism
        assertNotNull(irrelevantReason(svc, "https://visittexas-tourism.example",
                "Singing Bowls in Marble Falls", "Things to do, attractions, plan your trip",
                "Visitor centre. Guided tours.", false),
                "a tourism/visitor-board page must be rejected");
        // event / exhibitor / registration
        assertNotNull(irrelevantReason(svc, "https://holidayfair.example",
                "Singing Bowl Fair 2026", "Buy tickets, exhibitors, register now",
                "Save the date. Trade show booth.", false),
                "an event/exhibitor/registration page must be rejected");
        // academic / medical
        assertNotNull(irrelevantReason(svc, "https://med.example",
                "Singing Bowl Therapy", "Our patients, treatment options",
                "Clinical trial. Medical center.", false),
                "a medical page must be rejected");
        // clearly unrelated industries
        assertNotNull(irrelevantReason(svc, "https://threads-co.example",
                "Singing Bowl Hoodies", "Plus size clothing and apparel",
                "Sweatshirts and jeans.", false),
                "a fashion/apparel page must be rejected");
        assertNotNull(irrelevantReason(svc, "https://saasco.example",
                "Singing Bowl API", "Software as a service platform",
                "Domain registration, developer portal, our SDK.", false),
                "a technology/software page must be rejected");
        assertNotNull(irrelevantReason(svc, "https://capital-loans.example",
                "Singing Bowl Loans", "Get a personal loan", "Credit card, mortgage, "
                        + "investment account.", false),
                "a finance page must be rejected");
    }

    @Test
    void relevantStoresAreNeverRejectedByTheLightIrrelevantFilter() throws Exception {
        Object svc = newService();
        // Home Decor / Wellness / Spiritual / Meditation / Yoga / Gift stores stay.
        assertNull(irrelevantReason(svc, "https://homeharmony.example/collections/singing-bowls",
                "Home Decor & Gift Shop", "Wholesale home decor and singing bowls",
                "Handmade home decor. Contact us.", true),
                "a home decor store with store evidence must never be dropped");
        assertNull(irrelevantReason(svc, "https://stillmind.example",
                "Still Mind Meditation - Sound Healing Bowls", "Spiritual decor and yoga gifts",
                "We sell singing bowls. Add to cart. Contact us.", false),
                "a meditation/spiritual store that sells the product must be accepted "
                        + "even without a shopping URL path");
        assertNull(irrelevantReason(svc, "https://vinylart.example/collections/wall-art",
                "Vinyl Record Wall Art", "Home decor and housewares",
                "Add to cart. Free shipping.", true),
                "a decor store that happens to sell vinyl wall art must be kept");
        assertNull(irrelevantReason(svc, "https://breathewell.example/classes/sound-bath",
                "Breathe Well - Singing Bowls & Sound Baths", "Sound healing classes and "
                        + "retreats, gift certificates",
                "Singing bowls. Add to cart. Contact us.", true),
                "a sound-healing business that ALSO sells bowls stays a valid buyer");
        // a product-named path is never mistaken for an irrelevant section
        assertNull(irrelevantReason(svc, "https://songbowls.example/collections/song-bowls",
                "Song Bowls", "Singing bowl shop", null, true),
                "'/collections/song-bowls' must not be read as a '/songs' section");
        assertNull(irrelevantReason(svc, "https://musicboxdecor.example/collections/music-boxes",
                "Music Box Decor", "Home decor music boxes", "Add to cart.", true),
                "'/collections/music-boxes' must not be read as a '/music' section");
    }

    // --- CHEAP RAW PRE-FILTER (URL + title + snippet only) -------------------

    private static String preFilterReason(Object svc, String url, String title, String snippet)
            throws Exception {
        return callString(svc, "rawResultPreFilterReason",
                new Class<?>[]{String.class, String.class, String.class},
                url, title, snippet);
    }

    @Test
    void rawPreFilterRejectsTheWrongKindOfPageBeforeProductRelevance() throws Exception {
        Object svc = newService();
        // news / article
        assertNotNull(preFilterReason(svc, "https://dailysign.example/a/singing-bowl-trend",
                "Singing bowls are trending - breaking news", "The latest news on singing bowls"),
                "a news/article page must be rejected by the raw pre-filter");
        // conference / event / registration
        assertNotNull(preFilterReason(svc, "https://holidayfair.example/register",
                "Singing Bowl Fair 2026", "Event registration is open, buy tickets now"),
                "an event/registration page must be rejected");
        // attendee / exhibitor list
        assertNotNull(preFilterReason(svc, "https://expo.example/exhibitors",
                "Singing Bowl Expo - List of Exhibitors",
                "Find every exhibitor listed at the expo"),
                "an exhibitor list page must be rejected");
        assertNotNull(preFilterReason(svc, "https://expo.example/attendees",
                "Singing Bowl Summit", "Download the attendee list of past events"),
                "an attendee list page must be rejected");
        // medical / academic
        assertNotNull(preFilterReason(svc, "https://clinic.example/therapies",
                "Singing Bowl Therapy", "Our patients, treatment options and prescriptions"),
                "a medical page must be rejected");
        assertNotNull(preFilterReason(svc, "https://journals.example/papers",
                "The Effects of Singing Bowls: a research paper",
                "Abstract, citation and et al. Peer reviewed study."),
                "an academic/research page must be rejected");
        // government / tourism
        assertNotNull(preFilterReason(svc, "https://texas.gov/parks",
                "Singing Bowls at the State Park", "Things to do, attractions, plan your trip"),
                "a .gov host must be rejected");
        assertNotNull(preFilterReason(svc, "https://visitmarblefalls.example",
                "Singing Bowls in Marble Falls", "Visitor centre, guided tours, sightseeing"),
                "a tourism/visitor-board page must be rejected");
        // music / song / artist
        assertNotNull(preFilterReason(svc, "https://singingbowls.band",
                "Singing Bowl - Ambient Meditation Music Band", "Now playing, tour dates"),
                "a music/artist page must be rejected");
        assertNotNull(preFilterReason(svc, "https://open.spotify.com/artist/singing-bowls",
                "Singing Bowl | Spotify", "Songs to relax"),
                "a music streaming platform must be rejected");
        // unrelated directory
        assertNotNull(preFilterReason(svc, "https://bizlist.example/directory",
                "Singing Bowl Businesses - Business Directory",
                "A list of companies and supplier directory entries near me"),
                "an unrelated business directory must be rejected");
    }

    @Test
    void rawPreFilterKeepsLegitimateProductUrlsAndRealStores() throws Exception {
        Object svc = newService();
        for (String path : new String[]{"/product", "/products", "/shop", "/store",
                "/collection", "/catalog", "/catalogues"}) {
            assertNull(preFilterReason(svc, "https://homeharmony.example" + path + "/singing-bowls",
                    "Singing Bowls", "Now playing, tour dates, list of exhibitors"),
                    "a legitimate product URL (" + path + ") must always be kept");
        }
        // Genuine home-decor / wellness / spiritual / gift stores stay.
        assertNull(preFilterReason(svc, "https://homeharmony.example/collections/singing-bowls",
                "Home Decor & Gift Shop", "Wholesale home decor and singing bowls"),
                "a home decor store must never be dropped by the raw pre-filter");
        assertNull(preFilterReason(svc, "https://stillmind.example",
                "Still Mind Meditation - Sound Healing Bowls", "Spiritual decor and yoga gifts"),
                "a meditation/spiritual store must never be dropped by the raw pre-filter");
        assertNull(preFilterReason(svc, "https://breathewell.example/classes/sound-bath",
                "Breathe Well - Singing Bowls & Sound Baths", "Sound healing classes and retreats"),
                "a sound-healing business that also sells bowls stays a valid buyer");
        assertNull(preFilterReason(svc, "https://songbowls.example/collections/song-bowls",
                "Song Bowls", "Singing bowl shop"),
                "'/collections/song-bowls' must not be read as a '/songs' section");
        assertNull(preFilterReason(svc, "https://musicboxdecor.example/collections/music-boxes",
                "Music Box Decor", "Home decor music boxes"),
                "'/collections/music-boxes' must not be read as a '/music' section");
        // The exact Firecrawl product URL of a real store still passes.
        assertNull(preFilterReason(svc,
                "https://www.musiciansfriend.com/meditation-singing-bowls?page=4",
                "Meditation Singing Bowls | Musician's Friend", "Bestsellers"),
                "a real product listing URL must survive the raw pre-filter");
    }

    @Test
    void rawPreFilterNeedsNoStoreEvidenceOrRoleWord() throws Exception {
        Object svc = newService();
        // A plain, wording-free result must be KEPT: the pre-filter never asks
        // for commercial evidence, a business type, a buyer role, an email or a
        // location - only the product-relevance gate (which runs after it) and
        // full-page verification can reject the candidate.
        assertNull(preFilterReason(svc, "https://quietglow.example/singing-bowls",
                "Quiet Glow", ""),
                "the pre-filter must not reject a plain result on its own");
    }

    // --- HOSPITAL / TRAVEL / NEWS ARE NEVER BUYERS ---------------------------

    @Test
    void hospitalPagesAreRejectedAsNonBuyerPages() throws Exception {
        Object svc = newService();
        // A hospital never buys a physical decor product, whatever it is called.
        assertNotNull(preFilterReason(svc, "https://stmarys-hospital.example/wellness",
                "Wellness Institute", "Singing bowls in our hospital spa. Our patients. "
                        + "Emergency room. Inpatient care."),
                "a hospital page must be rejected by the raw pre-filter");
        assertNotNull(preFilterReason(svc, "https://mayoclinic.example/patients",
                "Patient Portal", "Find a doctor, book an appointment, medical records"),
                "a hospital network host must be rejected by host identity");
        assertNotNull(preFilterReason(svc, "https://cityhospital.example/emergency-room",
                "Emergency Department", "Emergency room and urgent care clinic"),
                "an /emergency-room section must be rejected");
        assertNotNull(irrelevantReason(svc, "https://stmarys-hospital.example/spa",
                "Singing Bowl Spa at St Marys", "Singing bowls, add to cart",
                "Emergency room. Inpatient care. Find a doctor.", true),
                "a hospital spa that also sells the product must still be rejected");
        assertNotNull(irrelevantReason(svc, "https://healthsystem.example/cardiology",
                "Cardiology Department", "Our specialists, patient portal",
                "Insurance accepted. Referral required.", false),
                "a hospital department page must be rejected");
    }

    @Test
    void travelAndBookingPagesAreRejectedAsNonBuyerPages() throws Exception {
        Object svc = newService();
        // A hotel, resort or booking platform sells a stay or a seat, not a product.
        assertNotNull(preFilterReason(svc, "https://www.marriott.com/hotels/austin",
                "Austin Marriott Downtown", "Book now, check in date, per night. "
                        + "Reservations. Guest rooms."),
                "a hotel group must be rejected by host identity");
        assertNotNull(preFilterReason(svc, "https://www.expedia.example/Hotel-Search",
                "Hotels in Austin", "Book your stay, free cancellation"),
                "a travel booking platform must be rejected by host identity");
        assertNotNull(preFilterReason(svc, "https://lakesideresort.example/rooms",
                "Lakeside Resort & Spa", "Book a room, check availability. Spa menu with "
                        + "singing bowls. Per night."),
                "a resort page must be rejected even when its spa names the product");
        assertNotNull(preFilterReason(svc, "https://resort.example/reservations",
                "Reserve Your Stay", "Make a reservation, all inclusive. Spa, singing bowls."),
                "a /reservations section must be rejected");
        assertNotNull(irrelevantReason(svc, "https://hilton.example/spa",
                "Singing Bowl Therapy at the Spa", "Wellness journey, book now",
                "Per night. All inclusive. Itinerary.", true),
                "a hotel spa page with store evidence must still be rejected");
    }

    @Test
    void newsAndEditorialPagesAreRejectedAsNonBuyerPages() throws Exception {
        Object svc = newService();
        assertNotNull(preFilterReason(svc, "https://www.housebeautiful.example/singing-bowls",
                "Singing Bowls: the latest news", "Latest news, press release"),
                "a lifestyle/editorial magazine must be rejected by host identity");
        assertNotNull(preFilterReason(svc, "https://www.nypost.example/shopping/singing-bowls",
                "Shoppers are buying singing bowls", "Breaking news on this trend"),
                "a news outlet must be rejected by host identity");
        assertNotNull(preFilterReason(svc, "https://thespruce.example/how-to-clean-a-crystal",
                "How to Clean a Crystal", "Singing bowls are trending in the news"),
                "an article/how-to page must be rejected");
    }

    @Test
    void realDecorStoresSurviveTheHospitalTravelAndNewsRules() throws Exception {
        Object svc = newService();
        // The new rules must not cost a single real buyer.
        assertNull(preFilterReason(svc, "https://homeharmony.example/collections/singing-bowls",
                "Home Decor & Gift Shop", "Wholesale home decor and singing bowls"),
                "a home decor store must never be dropped");
        assertNull(preFilterReason(svc, "https://crystalshop.example/shop/amethyst",
                "Amethyst Healing Crystals", "Crystal shop, add to cart, free shipping"),
                "a crystal shop must never be dropped by the healthcare host rule");
        assertNull(preFilterReason(svc, "https://medicalsupplies.example/store/braces",
                "Home Care Supplies", "Braces, bandages, add to cart"),
                "a medical SUPPLY retailer is a real seller and must be kept");
        assertNull(preFilterReason(svc, "https://hometreasures.example/collections/vases",
                "Vase Outlet", "Ceramic vases, wholesale, add to cart"),
                "a vase outlet must never be dropped by the news host rule");
        assertNull(preFilterReason(svc, "https://artsyhome.example/store/wall-art",
                "Wall Art Studio", "Canvas prints, book now a design consultation"),
                "a store that says 'book now' for a consultation must be kept");
        assertNull(irrelevantReason(svc, "https://artsyhome.example/store/wall-art",
                "Wall Art Studio", "Canvas prints, book now a design consultation",
                "Add to cart. Free shipping.", true),
                "a store that says 'book now' for a consultation must be kept");
        assertNull(irrelevantReason(svc, "https://homeharmony.example/collections/singing-bowls",
                "Home Decor & Gift Shop", "Wholesale home decor and singing bowls",
                "Handmade home decor. Contact us.", true),
                "a home decor store must never be dropped by the light filter");
    }

    // --- ALTERNATE PRODUCT NAMES --------------------------------------------

    private static List<String> alternateNames(Object svc, String product) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) invoke(svc, "homeGoodAlternateNames",
                new Class<?>[]{String.class}, product);
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
    }

    @Test
    void alternateProductNamesUseTheWordsRetailersUseForTheSameProduct()
            throws Exception {
        Object svc = newService();
        // A "Vase" search must also surface a "Glass Vases" shop, and a
        // "Vase" shop must also be found by a "Glass Vases" search.
        assertTrue(alternateNames(svc, "Vase").contains("glass vases"),
                "a vase search must find a glass vase store: " + alternateNames(svc, "Vase"));
        assertTrue(alternateNames(svc, "Glass Vases").contains("ceramic vases")
                        || alternateNames(svc, "Glass Vases").contains("flower vases"),
                "a glass vase search must find a vase store under other materials: "
                        + alternateNames(svc, "Glass Vases"));
        // A real synonym, not just a qualified duplicate.
        assertTrue(alternateNames(svc, "Area Rug").contains("wool rugs"),
                "an area rug search must find a rug store: "
                        + alternateNames(svc, "Area Rug"));
        assertTrue(alternateNames(svc, "Rugs").contains("area rugs"),
                "a rug search must find an area rug store: "
                        + alternateNames(svc, "Rugs"));
        assertTrue(alternateNames(svc, "Wall Mirror").contains("wall mirrors"),
                "a wall mirror search must find a mirror store: "
                        + alternateNames(svc, "Wall Mirror"));
        assertTrue(alternateNames(svc, "Vase").contains("ceramic vases"),
                "a vase search must find a ceramic vase store: "
                        + alternateNames(svc, "Vase"));
    }

    @Test
    void alternateProductNamesNeverChangeTheProduct() throws Exception {
        Object svc = newService();
        // The regression this whole mechanism exists for: an alternate name may
        // add a word, never substitute the product.
        for (String name : alternateNames(svc, "Throw Pillows")) {
            assertFalse(name.contains("throw") && !name.contains("pillow"),
                    "throw pillows must not become a throw: " + name);
            assertFalse(name.matches(".*\bthrows?\b.*") && !name.contains("pillow"),
                    "throw pillows must not be renamed to throws: " + name);
        }
        for (String name : alternateNames(svc, "Incense Holder")) {
            assertFalse(name.contains("candle") || name.contains("votive"),
                    "an incense holder must not become a candle holder: " + name);
        }
        for (String name : alternateNames(svc, "Resin Art")) {
            assertFalse(name.contains("wall art"),
                    "resin art must not be renamed to wall art: " + name);
        }
        for (String name : alternateNames(svc, "Reed Diffuser")) {
            assertTrue(name.contains("diffuser"),
                    "a reed diffuser must stay a diffuser: " + name);
        }
        for (String name : alternateNames(svc, "Cushion Covers")) {
            assertTrue(name.contains("cushion") || name.contains("cover"),
                    "cushion covers must stay cushion covers: " + name);
        }
    }

    @Test
    void everyAlternateNameNamesTheProductOrModifiesIt() throws Exception {
        Object svc = newService();
        // An alternate name must never be a DIFFERENT home-goods product.
        Set<String> asked = Set.of("vase", "rug", "mirror", "candle", "candles", "jar",
                "jars", "planter", "basket", "frame", "quilt", "linen", "diffuser",
                "holder", "art", "bowl", "tray", "pot");
        for (String product : new String[]{"Vase", "Area Rug", "Wall Mirror", "Candles",
                "Decorative Jars", "Planter", "Wicker Basket", "Picture Frame", "Quilt",
                "Bed Linen", "Reed Diffuser", "Incense Holder", "Brass Bowl", "Clay Pot",
                "Serving Tray", "Throw Pillows", "Cushion Covers", "Wall Art",
                "Macrame Wall Hanging", "Stained Glass", "Resin Art"}) {
            List<String> names = alternateNames(svc, product);
            assertTrue(names.size() <= 5,
                    product + " fired too many alternate names: " + names);
            for (String name : names) {
                assertFalse(name.isBlank(), product + " produced a blank alternate name");
                assertTrue(name.toLowerCase(Locale.ROOT).length() >= 3,
                        product + " produced a meaningless alternate name: " + name);
            }
        }
        assertFalse(asked.isEmpty());
    }

    // --- LOCATION PRIORITY: same-state widening -------------------------------

    @Test
    void citySearchWidensToOtherCitiesOfTheSameStateOnly() throws Exception {
        Object svc = newService();
        List<?> dallas = (List<?>) invoke(svc, "otherCitiesInState",
                new Class<?>[]{String.class, Set.class}, "TX", Set.of("dallas"));
        assertNotNull(dallas);
        assertFalse(dallas.isEmpty(), "Texas must expose other cities than the requested one");
        assertFalse(dallas.contains("Dallas"), "the requested city must never be re-searched");
        // Same result for the full state name.
        List<?> texas = (List<?>) invoke(svc, "otherCitiesInState",
                new Class<?>[]{String.class, Set.class}, "Texas", Set.of("dallas"));
        assertEquals(dallas, texas, "'TX' and 'Texas' must resolve to the same city pool");
        // Never leaves the requested state, and an unknown state yields nothing
        // (so the search can never jump to the whole country).
        List<?> unknown = (List<?>) invoke(svc, "otherCitiesInState",
                new Class<?>[]{String.class, Set.class}, "Freedonia", Set.of());
        assertTrue(unknown.isEmpty(),
                "an unknown state must not fall back to a nationwide city pool");
    }

    @Test
    void cityExpansionFollowsCorpusChristiThroughTheTexasCitiesInOrder() throws Exception {
        // Requirement 3: Corpus Christi -> Houston -> Austin -> San Antonio,
        // all still inside Texas, and the trace is logged as one arrow chain.
        Object svc = newService();
        List<?> others = (List<?>) invoke(svc, "otherCitiesInState",
                new Class<?>[]{String.class, Set.class}, "Texas", Set.of("corpus christi"));
        assertEquals(List.of("Houston", "Austin", "San Antonio"),
                others.subList(0, 3), "expansion must widen in importance order inside the state");
        assertFalse(others.contains("Corpus Christi"),
                "the requested city must never be re-searched as an expansion city");

        List<?> chain = (List<?>) invokeStatic("joinCityExpansion",
                new Class<?>[]{List.class, List.class},
                List.of("Corpus Christi"), List.of("Houston", "Austin", "San Antonio"));
        List<String> chainTexts = new ArrayList<>();
        for (Object city : chain) {
            chainTexts.add(String.valueOf(city));
        }
        assertEquals("Corpus Christi -> Houston -> Austin -> San Antonio",
                String.join(" -> ", chainTexts), "CITY_EXPANSION must be one readable arrow chain");
    }

    @Test
    void requestedCityGetsTheFirstIntentsAndExpansionGetsTheRest() throws Exception {
        // Requirement 1 + 2 + 3: the REQUESTED city is searched first with the
        // first buyer intents, and only then does the search widen to other
        // cities of the SAME state with the remaining intents.
        Object svc = newService();
        List<?> slots = productPlan(svc, "Singing Bowls", requestBudget());
        // The 10-request budget is split 4 requested-city / 3 other-city / 3
        // state slots, so the seven buyer intents land exactly like this.
        assertEquals(List.of("shop", "store", "retailer", "wholesaler"),
                List.of(slotWord(slots.get(0)), slotWord(slots.get(1)),
                        slotWord(slots.get(2)), slotWord(slots.get(3))),
                "the requested city must be searched first with shop/store/retailer/wholesaler");
        assertEquals(List.of("supplier", "distributor", "importer"),
                List.of(slotWord(slots.get(4)), slotWord(slots.get(5)), slotWord(slots.get(6))),
                "the same-state expansion must continue with supplier/distributor/importer");
    }

    // --- related-industry / use-case buyer queries ---------------------------

    /** The related-industry / use-case categories of a product, via the
     *  product-to-related-buyer-category mapping. */
    private static List<String> relatedCategories(String product) {
        return RelatedBuyerCategories.categoriesFor(product);
    }

    /** The StageEntries the search planner would add for these products/location. */
    private static List<?> relatedEntries(Object svc, List<String> products, List<String> cities,
                                          String state, String country) throws Exception {
        return (List<?>) invoke(svc, "relatedBuyerCategoryEntries",
                new Class<?>[]{List.class, List.class, String.class, String.class},
                products, cities, state, country);
    }

    private static Object entryCombo(Object entry) throws Exception {
        Field field = entry.getClass().getDeclaredField("combos");
        field.setAccessible(true);
        return ((List<?>) field.get(entry)).get(0);
    }

    private static String comboField(Object combo, String name) throws Exception {
        Field field = combo.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(combo);
    }

    /** The exact Firecrawl query string an added entry produces. */
    private static String entryQuery(Object svc, Object entry) throws Exception {
        return callString(svc, "buildDiversifiedQuery",
                new Class<?>[]{Class.forName(SVC_CLASS + "$Combo"),
                        Class.forName(SVC_CLASS + "$StageEntry")},
                entryCombo(entry), entry);
    }

    private static List<String> entryQueries(Object svc, List<?> entries) throws Exception {
        List<String> queries = new ArrayList<>();
        for (Object entry : entries) {
            queries.add(entryQuery(svc, entry));
        }
        return queries;
    }

    private static int relatedQueryCap() throws Exception {
        Field f = svc().getDeclaredField("MAX_RELATED_BUYER_CATEGORY_QUERIES");
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    void singingBowlsMapsToItsRelatedIndustryAndUseCaseCategories() {
        assertEquals(List.of("yoga studios", "meditation centers", "wellness centers",
                        "sound healing practitioners", "sound therapy centers", "spiritual shops",
                        "buddhist stores", "holistic wellness stores", "spas", "retreat centers",
                        "yoga teachers"),
                relatedCategories("singing bowls"),
                "singing bowls must expand to the wellness / therapy / retail buyer categories");
    }

    @Test
    void everyDocumentedProductMapsToItsOwnRelatedCategoryList() {
        // All six product groups the mapping has to cover, each resolved from the
        // normalized product name (no product name is hardcoded in the planner).
        assertEquals(List.of("yoga studios", "meditation centers", "wellness centers",
                        "sound healing practitioners", "sound therapy centers", "spiritual shops",
                        "buddhist stores", "holistic wellness stores", "spas", "retreat centers",
                        "yoga teachers"),
                relatedCategories("singing bowls"));
        assertTrue(relatedCategories("decorated jars with handle").contains("floral shops"),
                "decorated jars with handle must map to the jar buyer categories");
        assertTrue(relatedCategories("lanterns").contains("patio stores"),
                "lanterns must map to the outdoor / hospitality buyer categories");
        assertTrue(relatedCategories("candles").contains("candle shops"),
                "candles must map to the candle buyer categories");
        assertTrue(relatedCategories("candle holders").contains("interior designers"),
                "candle holders must map to the decor / specifier buyer categories");
        assertTrue(relatedCategories("votive holders").contains("religious spiritual stores"),
                "votive holders must map to the votive buyer categories");
    }

    @Test
    void productNameVariationsNormalizeToTheSameRelatedCategories() {
        // Requirement 5: "singing bowl" / "SINGING BOWLS" / "Tibetan Singing Bowls" /
        // "sound healing bowls" must all resolve to the same list.
        List<String> expected = relatedCategories("singing bowls");
        for (String variation : new String[]{"singing bowl", "SINGING BOWLS", "  Singing   Bowl  ",
                "Tibetan Singing Bowls", "Himalayan Singing Bowls", "Crystal Singing Bowls",
                "Meditation Singing Bowls", "Sound Healing Bowls", "singing-bowls",
                "singing bowls set"}) {
            assertEquals(expected, relatedCategories(variation),
                    "'" + variation + "' must normalize to the singing-bowl categories");
        }
        // Candle / votive family variations.
        assertEquals(relatedCategories("votive holders"), relatedCategories("Votive Holder"));
        assertEquals(relatedCategories("candle holders"), relatedCategories("Candle Holder"));
        assertEquals(relatedCategories("decorated jars with handle"),
                relatedCategories("Decorated Jars With Handles"));
    }

    @Test
    void narrowerProductFamiliesNeverInheritABroaderFamilysCategories() {
        // "votive holders" and "candle holders" both contain "candle", so the
        // detectors must be ordered most-specific-first.
        assertFalse(relatedCategories("votive holders").contains("candle retailers"),
                "votive holders must not resolve to the candle-HOLDER list");
        assertTrue(relatedCategories("votive holders").contains("religious spiritual stores"));
        assertFalse(relatedCategories("candle holders").contains("candle shops"),
                "candle holders must not resolve to the plain candle list");
        assertFalse(relatedCategories("candles").contains("interior designers"),
                "candles must not resolve to the candle-HOLDER list");
        // A plain kitchen bowl is a different product: no wellness categories.
        assertTrue(relatedCategories("glass bowl").isEmpty(),
                "a non-wellness bowl must not inherit the sound-therapy categories");
    }

    @Test
    void unmappedProductGeneratesNoRelatedQueries() {
        // A product with no mapping must leave the search exactly as it was.
        for (String product : new String[]{"vase", "area rug", "wall mirror", "macrame wall hanging",
                "", "   ", "custom plastic bottles"}) {
            assertTrue(relatedCategories(product).isEmpty(),
                    "'" + product + "' has no related-buyer-category mapping");
        }
    }

    @Test
    void relatedQueriesUseTheUsersProductAndTheSameRequestedLocation() throws Exception {
        // Product = singing bowls, Location = Texas, USA (no city requested).
        Object svc = newService();
        List<?> entries = relatedEntries(svc, List.of("singing bowls"), List.of(), "Texas", "USA");
        List<String> queries = entryQueries(svc, entries);

        assertEquals(11, entries.size(), "every singing-bowl related category must be searched");
        assertEquals(List.of("\"singing bowls\" \"yoga studios\" Texas",
                        "\"singing bowls\" \"meditation centers\" Texas",
                        "\"singing bowls\" \"wellness centers\" Texas",
                        "\"singing bowls\" \"sound healing practitioners\" Texas",
                        "\"singing bowls\" \"sound therapy centers\" Texas",
                        "\"singing bowls\" \"spiritual shops\" Texas",
                        "\"singing bowls\" \"buddhist stores\" Texas",
                        "\"singing bowls\" \"holistic wellness stores\" Texas",
                        "\"singing bowls\" \"spas\" Texas",
                        "\"singing bowls\" \"retreat centers\" Texas",
                        "\"singing bowls\" \"yoga teachers\" Texas"),
                queries,
                "each related query is the USER'S product plus one category plus the requested state");
    }

    @Test
    void relatedQueriesHonourTheRequestedCityAndNeverAnExpansionCity() throws Exception {
        // The extra queries must target the location the user actually asked for,
        // never a city from the other-cities-of-the-state expansion pool.
        Object svc = newService();
        List<?> entries = relatedEntries(svc, List.of("singing bowls"),
                List.of("Corpus Christi"), "Texas", "USA");
        for (Object entry : entries) {
            Object combo = entryCombo(entry);
            assertEquals("Corpus Christi", comboField(combo, "city"),
                    "an added query must use the REQUESTED city");
            assertEquals("Texas", comboField(combo, "state"));
            assertTrue(entryQuery(svc, entry).endsWith("\"Corpus Christi\" Texas"),
                    "the built query must end in the requested city + state");
        }
    }

    @Test
    void countryWideSearchStillScopesRelatedQueriesToTheRequestedCountry() throws Exception {
        Object svc = newService();
        List<?> entries = relatedEntries(svc, List.of("candles"), List.of(), null, "Canada");
        List<String> queries = entryQueries(svc, entries);
        assertFalse(queries.isEmpty(), "candles must still expand to their related categories");
        for (String query : queries) {
            assertTrue(query.endsWith(" Canada"),
                    "a country-wide related query must be scoped to the requested country: " + query);
        }
    }

    @Test
    void relatedQueriesAreDeduplicatedAndNeverRepeatAPlanQuery() throws Exception {
        Object svc = newService();
        // Several products of the SAME family (candles + candle holders) share
        // categories like "home decor stores" - the generated queries must not
        // repeat, and none may collide with a plan query.
        List<?> entries = relatedEntries(svc, List.of("candles", "candle holders"),
                List.of(), "Texas", "USA");
        List<String> queries = entryQueries(svc, entries);
        assertEquals(queries.size(), Set.copyOf(queries).size(),
                "generated related queries must be unique: " + queries);
        assertTrue(queries.size() <= relatedQueryCap(),
                "the added queries are capped at MAX_RELATED_BUYER_CATEGORY_QUERIES");

        // No added query may be identical to a query the existing plan already sends.
        List<?> slots = productPlan(svc, "candles", requestBudget());
        Set<String> planWords = new HashSet<>();
        for (Object slot : slots) {
            planWords.add(slotWord(slot));
        }
        for (Object entry : entries) {
            Field wordField = entry.getClass().getDeclaredField("word");
            wordField.setAccessible(true);
            String word = (String) ((String[]) wordField.get(entry))[0];
            assertFalse(planWords.contains(word),
                    "'" + word + "' already exists in the plan and must not be re-sent");
        }
    }

    @Test
    void addingRelatedQueriesLeavesTheExistingPlanUntouched() throws Exception {
        // The existing importer / distributor / wholesaler / buyer / retailer plan
        // must be byte-for-byte the same size, order and intents as before.
        Object svc = newService();
        List<?> slots = productPlan(svc, "Singing Bowls", requestBudget());
        assertEquals(requestBudget(), slots.size(),
                "the existing plan must still fill exactly the original request budget");
        assertEquals(List.of("shop", "store", "retailer", "wholesaler", "supplier", "distributor",
                        "importer", "exporter", "manufacturer", "trading", "dealer", "stockist",
                        "vendor", "b2b", "wholesale", "buyer", "bulk"),
                slots.subList(0, locationBuyerIntentCount()).stream()
                        .map(FirecrawlBuyerSearchServiceTest::safeWord).toList(),
                "every existing buyer intent must still be generated, in the same order");
        assertEquals("", safeWord(slots.get(locationBuyerIntentCount())),
                "the wordless product+location slot must still be generated");
    }

    private static String safeWord(Object slot) {
        try {
            String word = slotWord(slot);
            return word == null ? "" : word;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
