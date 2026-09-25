package com.exportautomation.service;

import com.exportautomation.config.FirecrawlConfig;
import com.exportautomation.dto.BuyerSearchResult;
import com.exportautomation.repository.BuyerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import reactor.core.publisher.Mono;

@Service
public class FirecrawlBuyerSearchService {

    private static final Logger logger = LoggerFactory.getLogger(FirecrawlBuyerSearchService.class);

    // SOCIAL TARGETING: after the exact/web phases, a dedicated LinkedIn +
    // Facebook pass fires site:linkedin.com / site:facebook.com queries for the
    // SAME product + location the user selected. It SHARES the single
    // 10-request Firecrawl budget with the web passes, so web + social together
    // can never exceed 10 Firecrawl requests per search, and it only accepts
    // company/showcase and business-page URLs - individual person profiles are
    // still rejected.
    // PRODUCT BUYER INTENTS. Every search fires the USER'S OWN product once per
    // buyer-type word, so a search surfaces EVERY kind of buyer for that product
    // - shop, store, retailer, wholesaler, supplier, distributor, importer,
    // exporter, manufacturer, trading company, dealer, stockist, vendor, B2B,
    // wholesale, buyer and bulk - not just one role.
    //
    // The list is ordered by how reliably the word returns a real product
    // business: the strongest roles (shop / store / retailer / wholesaler /
    // supplier / distributor / importer / exporter) lead, so they land on the
    // REQUESTED city first. The remaining roles are consumed by the later phases
    // (other cities of the same state, then the whole state) and by the larger
    // request budget.
    private static final String[] LOCATION_BUYER_INTENTS = {
        "shop", "store", "retailer", "wholesaler", "supplier", "distributor",
        "importer", "exporter", "manufacturer", "trading", "dealer", "stockist",
        "vendor", "b2b", "wholesale", "buyer", "bulk"
    };

    // Slot index of the WORDLESS "product + city + state" query. The location
    // template is added by the phase planner, so this slot always fires as the
    // bare product/location form - the query a shop owner would recognise.
    private static final int LOCATION_ONLY_PLAN_SLOT =
            LOCATION_BUYER_INTENTS.length;

    // Rejection code for a candidate whose RESOLVED address sits outside the
    // requested state/country. An UNKNOWN or unextracted city is NEVER
    // rejected - only a positively resolved conflict is.
    private static final String CODE_LOCATION_CONFLICT =
            "REJECTED - LOCATION CONFLICT";

    // Countries that are NEVER the searched country for a USA / Canada buyer
    // search. Only used on social evidence (title/snippet) where a named
    // country is the one decisive location signal available; the web pipeline
    // compares the fully resolved address instead. Kept to unambiguous,
    // long-form country names so ordinary copy can never trip it.
    private static final List<String> OTHER_COUNTRY_NAMES = List.of(
            "united kingdom", "united states of america", "england", "scotland",
            "wales", "northern ireland", "ireland", "australia", "new zealand",
            "germany", "france", "spain", "italy", "netherlands", "belgium",
            "portugal", "poland", "sweden", "norway", "denmark", "finland",
            "switzerland", "austria", "india", "china", "japan", "korea",
            "singapore", "brazil", "mexico", "argentina", "south africa",
            "united arab emirates", "israel", "turkey"
    );

    // Country-code domains of countries that are NEVER the searched country for
    // a USA / Canada buyer search. The domain suffix is a decisive, low
    // false-positive signal of where a business actually trades: a USA search
    // must not return a business whose only presence is a .co.uk / .de / .com.au
    // domain, even when its address could not be extracted. Canada (.ca) and the
    // neutral gTLDs (.com/.net/.org/.co/...) are deliberately NOT listed.
    private static final Set<String> FOREIGN_COUNTRY_TLDS = Set.of(
            "co.uk", "uk", "com.au", "au", "co.nz", "nz", "de", "fr", "es",
            "it", "nl", "be", "pt", "pl", "se", "no", "dk", "fi", "ch", "at",
            "ie", "co.in", "in", "co.jp", "jp", "com.br", "br", "com.mx", "mx",
            "com.ar", "ar", "co.za", "za", "ae", "il", "com.tr", "tr"
    );

    // Major cities per US state and Canadian province, used to automatically
    // broaden the search when the SELECTED city does not provide enough NEW
    // buyers: selected city first, then other major cities of the same region,
    // then the region overall. A city already chosen by the user is skipped.
    // It never changes location verification: results are still required not to
    // conflict with the selected region, and unverifiable locations are saved
    // as Unknown rather than guessed.
    private static final Map<String, List<String>> REGION_MAJOR_CITIES = Map.ofEntries(
        Map.entry("alabama", List.of("Birmingham", "Mobile", "Montgomery")),
        Map.entry("alaska", List.of("Anchorage", "Fairbanks", "Juneau")),
        Map.entry("arizona", List.of("Phoenix", "Tucson", "Mesa")),
        Map.entry("arkansas", List.of("Little Rock", "Fort Smith", "Fayetteville")),
        Map.entry("california", List.of("Los Angeles", "San Diego", "San Jose", "San Francisco",
                "Sacramento", "Fresno", "Long Beach", "Oakland", "Anaheim", "Bakersfield", "Stockton", "Riverside")),
        Map.entry("colorado", List.of("Denver", "Colorado Springs", "Boulder")),
        Map.entry("connecticut", List.of("Hartford", "New Haven", "Stamford")),
        Map.entry("delaware", List.of("Wilmington", "Dover", "Newark")),
        Map.entry("florida", List.of("Miami", "Orlando", "Tampa", "Jacksonville", "St. Petersburg",
                "Hialeah", "Fort Lauderdale", "Tallahassee", "Gainesville", "Naples", "West Palm Beach")),
        Map.entry("georgia", List.of("Atlanta", "Savannah", "Augusta")),
        Map.entry("hawaii", List.of("Honolulu", "Hilo", "Kailua")),
        Map.entry("idaho", List.of("Boise", "Idaho Falls", "Meridian")),
        Map.entry("illinois", List.of("Chicago", "Springfield", "Rockford")),
        Map.entry("indiana", List.of("Indianapolis", "Fort Wayne", "Evansville")),
        Map.entry("iowa", List.of("Des Moines", "Cedar Rapids", "Davenport")),
        Map.entry("kansas", List.of("Wichita", "Kansas City", "Topeka")),
        Map.entry("kentucky", List.of("Louisville", "Lexington", "Bowling Green")),
        Map.entry("louisiana", List.of("New Orleans", "Baton Rouge", "Shreveport", "Lafayette")),
        Map.entry("maine", List.of("Portland", "Bangor", "Lewiston")),
        Map.entry("maryland", List.of("Baltimore", "Annapolis", "Rockville")),
        Map.entry("massachusetts", List.of("Boston", "Worcester", "Springfield")),
        Map.entry("michigan", List.of("Detroit", "Grand Rapids", "Ann Arbor")),
        Map.entry("minnesota", List.of("Minneapolis", "Saint Paul", "Duluth")),
        Map.entry("mississippi", List.of("Jackson", "Gulfport", "Biloxi")),
        Map.entry("missouri", List.of("St. Louis", "Kansas City", "Springfield")),
        Map.entry("montana", List.of("Billings", "Missoula", "Great Falls")),
        Map.entry("nebraska", List.of("Omaha", "Lincoln", "Grand Island")),
        Map.entry("nevada", List.of("Las Vegas", "Reno", "Henderson")),
        Map.entry("new hampshire", List.of("Manchester", "Nashua", "Concord")),
        Map.entry("new jersey", List.of("Newark", "Jersey City", "Paterson")),
        Map.entry("new mexico", List.of("Albuquerque", "Santa Fe", "Las Cruces")),
        Map.entry("new york", List.of("New York", "Buffalo", "Rochester", "Albany", "Yonkers",
                "Syracuse", "Utica", "White Plains", "Schenectady")),
        Map.entry("north carolina", List.of("Charlotte", "Raleigh", "Greensboro")),
        Map.entry("north dakota", List.of("Fargo", "Bismarck", "Grand Forks")),
        Map.entry("ohio", List.of("Columbus", "Cleveland", "Cincinnati", "Toledo")),
        Map.entry("oklahoma", List.of("Oklahoma City", "Tulsa", "Norman")),
        Map.entry("oregon", List.of("Portland", "Eugene", "Salem")),
        Map.entry("pennsylvania", List.of("Philadelphia", "Pittsburgh", "Allentown")),
        Map.entry("rhode island", List.of("Providence", "Warwick", "Cranston")),
        Map.entry("south carolina", List.of("Columbia", "Charleston", "Greenville")),
        Map.entry("south dakota", List.of("Sioux Falls", "Rapid City", "Aberdeen")),
        Map.entry("tennessee", List.of("Nashville", "Memphis", "Knoxville")),
        // Texas: ordered pool of IMPORTANT cities (never every Texas town).
        // The selected city is searched FIRST, then this list rotates in one
        // NEW city per remaining request (order = importance), and only LAST
        // does a query go state-wide. Buying requests are never spent on a
        // city already searched or on cities beyond the remaining budget.
        Map.entry("texas", List.of(
                "Houston", "Austin", "San Antonio", "Dallas", "Fort Worth",
                "El Paso", "Arlington", "Corpus Christi", "Plano", "Laredo",
                "Lubbock", "Garland", "Irving", "Amarillo", "Grand Prairie",
                "McKinney", "Frisco", "Brownsville", "Pasadena", "Killeen",
                "McAllen", "Waco", "Carrollton", "Denton", "Midland", "Abilene",
                "Beaumont", "Round Rock", "Richardson", "Conroe", "Tyler",
                "Lewisville", "League City", "Pearland", "College Station",
                "Sugar Land", "Edinburg", "Mission", "Longview", "Odessa",
                "Bryan", "San Angelo", "Victoria", "Temple", "Pharr",
                "New Braunfels")),
        Map.entry("utah", List.of("Salt Lake City", "Provo", "Ogden")),
        Map.entry("vermont", List.of("Burlington", "Montpelier", "South Burlington")),
        Map.entry("virginia", List.of("Virginia Beach", "Richmond", "Norfolk", "Arlington")),
        Map.entry("washington", List.of("Seattle", "Spokane", "Tacoma")),
        Map.entry("west virginia", List.of("Charleston", "Huntington", "Morgantown")),
        Map.entry("wisconsin", List.of("Milwaukee", "Madison", "Green Bay")),
        Map.entry("wyoming", List.of("Cheyenne", "Casper", "Laramie")),
        Map.entry("district of columbia", List.of("Washington")),
        Map.entry("alberta", List.of("Calgary", "Edmonton", "Red Deer", "Lethbridge",
                "St. Albert", "Medicine Hat")),
        Map.entry("british columbia", List.of("Vancouver", "Surrey", "Victoria", "Burnaby",
                "Richmond", "Kelowna", "Abbotsford", "Nanaimo")),
        Map.entry("manitoba", List.of("Winnipeg", "Brandon", "Steinbach")),
        Map.entry("new brunswick", List.of("Moncton", "Saint John", "Fredericton")),
        Map.entry("newfoundland and labrador", List.of("St. John's", "Corner Brook", "Mount Pearl")),
        Map.entry("nova scotia", List.of("Halifax", "Sydney", "Dartmouth")),
        Map.entry("ontario", List.of("Toronto", "Ottawa", "Mississauga", "Hamilton", "London",
                "Brampton", "Kitchener", "Windsor", "Oshawa", "Thunder Bay", "Guelph")),
        Map.entry("prince edward island", List.of("Charlottetown", "Summerside", "Stratford")),
        Map.entry("quebec", List.of("Montreal", "Quebec City", "Laval")),
        Map.entry("saskatchewan", List.of("Saskatoon", "Regina", "Prince Albert")),
        Map.entry("northwest territories", List.of("Yellowknife", "Inuvik", "Hay River")),
        Map.entry("nunavut", List.of("Iqaluit", "Rankin Inlet", "Arviat")),
        Map.entry("yukon", List.of("Whitehorse", "Dawson City"))
    );

    // Major USA + Canada cities used to broaden a city search when the user did
    // not select a state/province to scope the "other cities" phase to.
    private static final List<String> NATIONWIDE_MAJOR_CITIES = List.of(
        "Houston", "Chicago", "Los Angeles", "Miami", "Seattle", "Denver",
        "San Francisco", "Boston", "Phoenix", "Toronto", "Vancouver", "Montreal"
    );

    // ------------------------------------------------------------------
    // NEARBY CITIES. Two cities of the SAME state belong to the same regional
    // CLUSTER when they sit in one connected market - a shared metro area, a
    // single freight/highway corridor, or one coast - and are a genuinely
    // reachable local buyer. A buyer in a clustered city is ranked ahead of a
    // buyer at the far end of the same state, and the same-state widening phase
    // spends its requests on the clustered (nearby) cities BEFORE the rest of
    // the state, so a city search returns as many genuinely local buyers as
    // possible instead of generic product pages from far away.
    //
    // Deliberate properties:
    //   * every cluster lists only cities that are already in
    //     REGION_MAJOR_CITIES for that state - the map widens the ordering, it
    //     never introduces a city the search did not already know;
    //   * a state with no cluster simply never yields a NEARBY_CITY result and
    //     keeps its existing SAME_STATE ranking - the fallback is the old
    //     behaviour, never a rejection;
    //   * cities inside one cluster are each other's neighbours (membership is
    //     symmetric), so "nearby" needs no direction.
    // ------------------------------------------------------------------
    private static final Map<String, List<List<String>>> NEARBY_CITY_CLUSTERS =
        Map.ofEntries(
        // Alabama
        Map.entry("alabama", List.of(
                List.of("Birmingham", "Montgomery"),
                List.of("Mobile"))),
        // Alaska / Hawaii / the Dakotas have no second major city in range.
        Map.entry("arizona", List.of(
                List.of("Phoenix", "Mesa", "Tucson"))),
        Map.entry("arkansas", List.of(
                List.of("Little Rock", "Fort Smith", "Fayetteville"))),
        // California: the LA basin + San Diego, the Bay Area, the Central Valley.
        Map.entry("california", List.of(
                List.of("Los Angeles", "Riverside", "Long Beach", "Anaheim", "San Diego"),
                List.of("San Jose", "San Francisco", "Oakland"),
                List.of("Sacramento", "Stockton"),
                List.of("Fresno", "Bakersfield"))),
        Map.entry("colorado", List.of(
                List.of("Denver", "Boulder"),
                List.of("Colorado Springs"))),
        Map.entry("connecticut", List.of(
                List.of("Hartford", "New Haven", "Stamford"))),
        Map.entry("delaware", List.of(
                List.of("Wilmington", "Newark", "Dover"))),
        // Florida: South Florida, the I-4 Tampa/Orlando corridor, NE Florida.
        Map.entry("florida", List.of(
                List.of("Miami", "Hialeah", "Fort Lauderdale", "West Palm Beach"),
                List.of("Orlando", "Tampa", "St. Petersburg"),
                List.of("Jacksonville", "Gainesville"),
                List.of("Tallahassee"),
                List.of("Naples"))),
        Map.entry("georgia", List.of(
                List.of("Atlanta", "Augusta"),
                List.of("Savannah"))),
        Map.entry("hawaii", List.of(
                List.of("Hilo", "Kailua"),
                List.of("Honolulu"))),
        Map.entry("idaho", List.of(
                List.of("Boise", "Meridian"),
                List.of("Idaho Falls"))),
        Map.entry("illinois", List.of(
                List.of("Chicago", "Rockford"),
                List.of("Springfield"))),
        Map.entry("indiana", List.of(
                List.of("Indianapolis", "Fort Wayne"),
                List.of("Evansville"))),
        Map.entry("iowa", List.of(
                List.of("Des Moines", "Cedar Rapids"),
                List.of("Cedar Rapids", "Davenport"))),
        Map.entry("kansas", List.of(
                List.of("Wichita", "Topeka"),
                List.of("Kansas City"))),
        Map.entry("kentucky", List.of(
                List.of("Louisville", "Lexington"),
                List.of("Bowling Green"))),
        // Louisiana: the I-10 / Acadiana corridor.
        Map.entry("louisiana", List.of(
                List.of("New Orleans", "Baton Rouge", "Lafayette"),
                List.of("Shreveport"))),
        Map.entry("maine", List.of(
                List.of("Portland", "Bangor", "Lewiston"))),
        Map.entry("maryland", List.of(
                List.of("Baltimore", "Annapolis", "Rockville"))),
        Map.entry("massachusetts", List.of(
                List.of("Boston", "Worcester", "Springfield"))),
        Map.entry("michigan", List.of(
                List.of("Detroit", "Ann Arbor"),
                List.of("Grand Rapids"))),
        Map.entry("minnesota", List.of(
                List.of("Minneapolis", "Saint Paul"),
                List.of("Duluth"))),
        Map.entry("mississippi", List.of(
                List.of("Jackson", "Gulfport", "Biloxi"))),
        Map.entry("missouri", List.of(
                List.of("St. Louis", "Springfield"),
                List.of("Kansas City"))),
        Map.entry("nebraska", List.of(
                List.of("Omaha", "Lincoln"),
                List.of("Grand Island"))),
        Map.entry("nevada", List.of(
                List.of("Las Vegas", "Henderson"),
                List.of("Reno"))),
        Map.entry("new hampshire", List.of(
                List.of("Manchester", "Nashua", "Concord"))),
        Map.entry("new jersey", List.of(
                List.of("Newark", "Jersey City", "Paterson"))),
        Map.entry("new mexico", List.of(
                List.of("Albuquerque", "Santa Fe", "Las Cruces"))),
        // New York: the NYC metro, the I-90 upstate corridor, the capital region.
        Map.entry("new york", List.of(
                List.of("New York", "Yonkers", "White Plains", "Schenectady"),
                List.of("Buffalo", "Rochester", "Syracuse"),
                List.of("Albany"))),
        Map.entry("north carolina", List.of(
                List.of("Charlotte", "Raleigh", "Greensboro"))),
        Map.entry("north dakota", List.of(
                List.of("Fargo", "Grand Forks"),
                List.of("Bismarck"))),
        Map.entry("ohio", List.of(
                List.of("Columbus", "Toledo", "Cleveland"),
                List.of("Cincinnati"))),
        Map.entry("oklahoma", List.of(
                List.of("Oklahoma City", "Norman", "Tulsa"))),
        Map.entry("oregon", List.of(
                List.of("Portland", "Eugene", "Salem"))),
        Map.entry("pennsylvania", List.of(
                List.of("Philadelphia", "Allentown"),
                List.of("Pittsburgh"))),
        Map.entry("rhode island", List.of(
                List.of("Providence", "Warwick", "Cranston"))),
        Map.entry("south carolina", List.of(
                List.of("Columbia", "Charleston", "Greenville"))),
        Map.entry("south dakota", List.of(
                List.of("Sioux Falls", "Aberdeen"),
                List.of("Rapid City"))),
        Map.entry("tennessee", List.of(
                List.of("Nashville", "Knoxville", "Memphis"))),
        // Texas: the central/southeast corridor that links San Antonio, Austin,
        // the Gulf Coast and Houston into ONE distribution market (this is why
        // "Corpus Christi -> Houston -> Austin -> San Antonio" is a genuinely
        // local widening, not a jump across the state); then DFW, the
        // Panhandle, West Texas and the Rio Grande Valley.
        Map.entry("texas", List.of(
                List.of("Houston", "Corpus Christi", "Austin", "San Antonio",
                        "Beaumont", "Victoria", "New Braunfels", "Round Rock",
                        "Conroe", "Sugar Land", "Pearland", "League City",
                        "Pasadena", "Killeen", "Temple"),
                List.of("Dallas", "Fort Worth", "Arlington", "Plano", "Garland",
                        "Irving", "McKinney", "Frisco", "Denton", "Richardson",
                        "Carrollton", "Grand Prairie", "Lewisville", "Tyler",
                        "Longview"),
                List.of("Amarillo", "Lubbock"),
                List.of("El Paso", "Midland", "Odessa", "Abilene"),
                List.of("McAllen", "Edinburg", "Mission", "Pharr", "Brownsville",
                        "Laredo"))),
        Map.entry("utah", List.of(
                List.of("Salt Lake City", "Ogden", "Provo"))),
        Map.entry("vermont", List.of(
                List.of("Burlington", "South Burlington", "Montpelier"))),
        // Virginia: Hampton Roads and the Richmond/DC corridor.
        Map.entry("virginia", List.of(
                List.of("Virginia Beach", "Norfolk"),
                List.of("Richmond", "Arlington"))),
        Map.entry("washington", List.of(
                List.of("Seattle", "Tacoma"),
                List.of("Spokane"))),
        Map.entry("west virginia", List.of(
                List.of("Charleston", "Huntington"),
                List.of("Morgantown"))),
        Map.entry("wisconsin", List.of(
                List.of("Milwaukee", "Madison", "Green Bay"))),
        Map.entry("wyoming", List.of(
                List.of("Cheyenne", "Laramie"),
                List.of("Casper"))),
        Map.entry("district of columbia", List.of(
                List.of("Washington"))),
        // ---- Canada ----
        Map.entry("alberta", List.of(
                List.of("Edmonton", "St. Albert"),
                List.of("Calgary", "Lethbridge"),
                List.of("Red Deer", "Medicine Hat"))),
        Map.entry("british columbia", List.of(
                List.of("Vancouver", "Surrey", "Richmond", "Burnaby", "Nanaimo"),
                List.of("Victoria"))),
        Map.entry("manitoba", List.of(
                List.of("Winnipeg", "Steinbach", "Brandon"))),
        Map.entry("new brunswick", List.of(
                List.of("Moncton", "Saint John", "Fredericton"))),
        Map.entry("newfoundland and labrador", List.of(
                List.of("St. John's", "Mount Pearl"),
                List.of("Corner Brook"))),
        Map.entry("nova scotia", List.of(
                List.of("Halifax", "Dartmouth"),
                List.of("Sydney"))),
        Map.entry("ontario", List.of(
                List.of("Toronto", "Mississauga", "Brampton", "Oshawa", "Guelph"),
                List.of("Hamilton", "Kitchener", "London", "Windsor"),
                List.of("Ottawa"),
                List.of("Thunder Bay"))),
        Map.entry("prince edward island", List.of(
                List.of("Charlottetown", "Summerside", "Stratford"))),
        Map.entry("quebec", List.of(
                List.of("Montreal", "Laval"),
                List.of("Quebec City"))),
        Map.entry("saskatchewan", List.of(
                List.of("Saskatoon", "Prince Albert"),
                List.of("Regina"))),
        Map.entry("northwest territories", List.of(
                List.of("Yellowknife", "Hay River"),
                List.of("Inuvik"))),
        Map.entry("yukon", List.of(
                List.of("Whitehorse")))
    );

    // When this fraction of a strategy's raw results are already-known
    // businesses (existing DB rows / returned earlier in this search) and none
    // are new, the remaining same-location-form strategies are skipped: keep
    // discovering NEW businesses instead of re-running queries that only
    // produce duplicates.
    private static final double DUP_HEAVY_RATIO = 0.6;

    // Firecrawl search returns a single batch of results per call (no offset
    // pagination), so each round advances to the next query strategy. A search
    // NEVER fires more than 6 requests (first attempts + 429 retries all share
    // the same 6-request global budget). With a selected city the search uses
    // PROGRESSIVE GEOGRAPHIC BROADENING - the requested city first, then the
    // requested state, then the whole country - never rotating through other
    // cities. Every query pairs a DIFFERENT in-family product variant with a
    // DIFFERENT commercial buyer word and a narrower/later location:
    //   Phase "requested city"    : R1-R2 - "<Product>" <City> <State>
    //   Phase "state-wide"        : R3-R4 - "<Product>" <State>
    //   Phase "nationwide"        : R5-R6 - "<Product>" <USA>
    // Raw results requested PER Firecrawl query. This is the cheapest recall
    // lever there is: a bigger page size returns more candidate businesses for
    // the SAME single request, so the raw-result count grows without spending an
    // extra credit. 20 -> 50 more than doubles the raw candidates per query.
    private static final int RESULTS_PER_QUERY = 50;
    // Firecrawl searches are fired one at a time and globally paced so the
    // account's requests-per-minute budget is never exhausted. The plan allows
    // 20 requests/minute, so the pacing below keeps a safe margin.
    private static final long MIN_FIRECRAWL_REQUEST_INTERVAL_MS = 3500;
    // A rate-limited (429) query retries at most twice after the initial call
    // (3 attempts total). The FIRST retry waits for the server-provided
    // Retry-After when the 429 response includes one (never retried
    // immediately); without a header the fixed short backoff below is used.
    // After the retries the query is skipped - no long, repeated waits.
    private static final int MAX_429_RETRIES = 2;
    private static final long[] FIRE_429_RETRY_BASE_DELAY_MS = {2500, 6000};
    private static final long[] FIRE_429_RETRY_JITTER_MS = {500, 1000};
    // Hard cap on a server-provided Retry-After (429): the search waits at most
    // this long, never indefinitely, even if the server asks for more.
    private static final long MAX_SERVER_RETRY_AFTER_MS = 60_000;
    // Hard cap on actual Firecrawl API requests (first attempts + retries) for
    // a whole buyer search, so credits are never wasted on retries or on
    // searching after enough new buyers have been found. The cap is deliberately
    // generous so a single search can fan out across every buyer role and every
    // city of the requested state - it is the main driver of "more results".
    // Raised 10 -> 20, which doubles the number of distinct queries (and hence
    // the raw result pool) for one search.
    private static final int MAX_FIRECRAWL_REQUESTS_PER_SEARCH = 20;
    // RELATED-INDUSTRY / USE-CASE QUERIES. The deterministic product+intent plan
    // above is NEVER reduced: all MAX_FIRECRAWL_REQUESTS_PER_SEARCH of its
    // queries are still generated and still fire first. On top of that, the
    // planner can add up to this many EXTRA queries that search the SAME
    // requested location through the adjacent industries and use cases that
    // genuinely stock the product (a singing bowl through yoga studios and
    // meditation centers, a lantern through patio stores and event decor
    // companies, ...) - see RelatedBuyerCategories. They are added to the
    // per-search Firecrawl budget, never taken from it, and the categories come
    // from the product-to-related-buyer-category mapping, so no product name is
    // hardcoded in the search logic.
    private static final int MAX_RELATED_BUYER_CATEGORY_QUERIES = 11;
    // Business type recorded for a buyer found through a related-industry /
    // use-case query. Purely a label: the existing LIGHT business check only
    // records whatever role the page actually states.
    private static final String RELATED_BUYER_CATEGORY_TYPE = "Related Buyer";
    // Timestamp of the most recent Firecrawl request, shared across all searches
    // so two concurrent buyer-searches can never burst past the rate limit.
    private static final AtomicLong LAST_FIRECRAWL_REQUEST_NANOS = new AtomicLong(0);

    private static final int EMAIL_DISCOVERY_PARALLELISM = 8;

    // Hostnames that never represent an actual buyer business (social profiles,
    // business directories, review/content sites). Such hosts are excluded from
    // Firecrawl searches (excludeDomains) AND filtered again locally so a
    // profile/directory listing can never be returned as a buyer. Marketplace
    // hosts (amazon, ebay, etsy, ...) are NOT listed here: those pages are
    // evaluated by content - a generic product page is rejected, while a page
    // that clearly identifies a distinct relevant seller business may pass.
    // Event/ticket platforms are excluded too: an event listing (workshop,
    // sound bath, class) is not a business that provides/sells the product.
    private static final Set<String> NON_BUSINESS_HOSTS = Set.of(
        "linkedin.com", "facebook.com", "instagram.com", "twitter.com", "x.com",
        "youtube.com", "pinterest.com", "tiktok.com", "snapchat.com",
        "yelp.com", "yelp.ca", "glassdoor.com", "indeed.com",
        "foursquare.com", "angieslist.com", "mapquest.com", "yellowpages.com",
        "manta.com", "whitepages.com", "bbb.org", "trustpilot.com",
        "houzz.com", "behance.net", "dribbble.com", "medium.com",
        "crunchbase.com", "zoominfo.com", "wikipedia.org", "quora.com",
        "reddit.com", "tripadvisor.com", "city-data.com", "imdb.com",
        "eventbrite.com", "eventbrite.ca", "eventbrite.co.uk", "meetup.com",
        "poshmark.com", "mercari.com", "depop.com", "vinted.com",
        "thredup.com", "ebth.com", "chairish.com", "bonanza.com",
        "ebid.net", "worthpoint.com", "1stdibs.com",
        // Content/news sites and event-listings that surface in product queries
        // but are not buyer businesses: magazines, trade journals, event
        // planners, ticket marketplaces, podcast carriers and database portals.
        "menshealth.com", "avnetwork.com", "partyslate.com",
        "escholarship.org", "vividseats.com", "apple.com", "jogs.com", "etnow.com",
        // Marketplaces / online platforms sell many third-party products - a
        // listing there is never a specific business website with its own
        // product, so they are excluded from searches and rejected outright.
        "etsy.com", "ebay.com", "ebay.ca", "ebay.co.uk", "amazon.com", "amazon.ca",
        "amazon.co.uk", "alibaba.com", "aliexpress.com", "walmart.com", "target.com",
        "faire.com", "atly.com", "importyeti.com", "panjiva.com",
        "made-in-china.com", "dhgate.com", "zibbet.com", "wish.com",
        "wayfair.com", "overstock.com", "taobao.com", "shopee.com"
    );

    // Marketplace / aggregator hosts. Generic product pages on these sites are
    // rejected as "shopping/product page"; a page that clearly identifies a
    // distinct relevant seller business is still considered.
    private static final Set<String> SHOPPING_HOSTS = Set.of(
        "amazon.com", "amazon.ca", "amazon.co.uk", "amazon.de", "amazon.fr",
        "amazon.it", "amazon.es", "amazon.in", "amazon.ae", "amazon.com.au",
        "ebay.com", "ebay.co.uk", "ebay.de", "ebay.com.au", "ebay.in",
        "etsy.com", "etsy.co.uk", "etsy.com.au",
        "alibaba.com", "aliexpress.com", "1688.com", "taobao.com", "tmall.com",
        "walmart.com", "target.com", "bestbuy.com", "homedepot.com", "lowes.com",
        "costco.com", "wayfair.com", "overstock.com", "macys.com",
        "flipkart.com", "shopclues.com", "meesho.com", "shein.com", "temu.com",
        "noon.com", "jd.com", "wish.com", "dhgate.com", "zoro.com", "uline.com"
    );

    // Platform / site-builder hosts: the business has no independent domain, so
    // a title without a distinct brand (e.g. "Singing Bowls - myshopify.com")
    // is treated like a generic product/shopping page.
    private static final Set<String> PLATFORM_HOST_SUFFIXES = Set.of(
        ".myshopify.com", ".shopify.com", ".wixsite.com", ".squarespace.com",
        ".weebly.com", ".bigcartel.com", ".godaddysites.com", ".yolasite.com",
        ".webnode.", ".carrd.co", ".about.me", ".square.site"
    );

    // Brand words that alone never prove a distinct seller business (the
    // marketplace's own identity, or generic commerce words).
    private static final Set<String> AGGREGATOR_BRANDS = Set.of(
        "amazon", "ebay", "etsy", "alibaba", "ali express", "aliexpress",
        "ubuy", "desertcart", "walmart", "target", "shopee", "wish", "temu",
        "shein", "flipkart", "meesho", "noon", "zazzle", "dhgate", "taobao",
        "tmall", "1688", "gearbest", "banggood"
    );

    private static final Set<String> COMMERCE_NOISE_WORDS = Set.of(
        "online", "shop", "store", "stores", "shopping", "marketplace", "mall",
        "deals", "deal", "order", "orders", "cart", "price", "prices",
        "wholesale", "wholesaler", "wholesalers", "retail", "retailer",
        "retailers", "importer", "importers", "distributor", "distributors",
        "supplier", "suppliers", "vendor", "vendors", "trade", "trading",
        "buy", "buying", "buyers", "boutique", "ware", "wares", "goods",
        "outlet", "kiosk", "hub", "trends", "ltd", "llc", "inc", "company",
        "limited", "corp", "pvt", "usa", "com", "uk", "in", "ca", "au"
    );

    private static final Set<String> SHOPPING_PATH_MARKERS = Set.of(
        "/product/", "/product-", "/products/", "/products-", "/pdp",
        "/item/", "/dp/", "/gp/", "/catalog/", "/browse/", "/cart",
        "/checkout", "/offers/", "/compare", "/buy/", "/sku/", "/variant",
        "/collection", "/collections"
    );

    // Commerce (physical-product seller) sentences that, appearing NEAR a
    // genuine product phrase, prove the business actually sells the physical
    // product rather than merely writing about it. Deliberately excludes
    // service/ritual words (class, workshop, retreat, session, event, tickets,
    // book, register, enrol) so service-based pages never pass.
    private static final List<String> COMMERCE_SIGNAL_PHRASES = List.of(
        "we sell", "we carry", "we offer", "for sale", "sells", "selling",
        "buy", "buying", "purchase", "purchases", "purchasing", "shop now",
        "shop online", "shop our", "our shop", "online shop", "online store",
        "estore", "storefront", "add to cart", "add to basket", "cart",
        "checkout", "order now", "order online", "in stock", "on sale",
        "wholesale", "wholesaler", "wholesalers", "retail", "retailer",
        "retailers", "catalog", "catalogue", "catalogs", "collection",
        "collections", "product line", "product range", "product listing",
        "products", "price", "prices", "pricing", "discount", "supplier",
        "suppliers", "distributor", "distributors", "importer", "importers",
        "dealer", "dealers", "stockist", "stockists", "vendor", "vendors",
        "handmade", "handcrafted", "made to order", "custom order",
        "shipping", "free shipping", "delivery", "gift", "gifts", "boutique",
        "merchandise");

    // Business identity markers: standard pages every genuine business site
    // carries (About/Contact/address/phone/company/store phrases). They let the
    // DIRECT relevance gate accept a real business that names the product even
    // when no price/cart sentence sits right beside it - while articles, news,
    // event and directory pages (already filtered by classifyPage) stay out.
    private static final Set<String> BUSINESS_IDENTITY_MARKERS = Set.of(
        "about us", "about our store", "about our company", "our company",
        "our store", "our shop", "our business", "contact us", "contact our",
        "visit our store", "visit us", "call us", "email us", "send us a message",
        "address", "address:", "located at", "location", "phone", "telephone",
        "phone number", "fax", "hours", "store hours", "privacy policy",
        "terms of service", "terms & conditions", "terms and conditions",
        "our products", "our catalog", "our catalogue", "shop our",
        "view our", "best sellers", "our team", "our founder", "customer service",
        "faq", "shipping policy", "returns policy");

    private static final Set<String> SHOPPING_QUERY_KEYS = Set.of(
        "pid", "product", "products", "sku", "item", "asin", "variant",
        "offer", "ean", "upc", "prodid", "product_id"
    );

    private static final Set<String> SHOPPING_TITLE_MARKERS = Set.of(
        "add to cart", "buy now", "shop now", "free shipping",
        "online shopping", "compare prices", "best price", "lowest price",
        "checkout", "shopping cart", "in stock", "buy online", "order now",
        "save big", "% discount", "% off", "flash sale", "great deal"
    );

    private static final Set<String> ARTICLE_TITLE_MARKERS = Set.of(
        "how to", "top 10", "top ten", "10 best", "5 best", "best of",
        "guide to", "guide for", "review of", "reviews of", " review",
        "article", "what is a", "what are", "why you should",
        "everything you need", "tips for", "all about", "learn about",
        "a beginner's guide", "beginners guide", "the history of"
    );

    // Content/news hosts that routinely surface in product searches but are
    // never buyer businesses: online magazines, trade journals, event planners,
    // ticket marketplaces, research repositories and podcast carriers. Any page
    // on these hosts is classified as an article/news page, not a business.
    private static final Set<String> CONTENT_NEWS_HOST_PARTS = Set.of(
        "menshealth.com", "avnetwork.com", "partyslate.com",
        "escholarship.org", "vividseats.com", "jogs.com", "etnow.com",
        "maybevents", "millersoundstudio"
    );

    // Music / audio-streaming platforms whose pages are pure content, never
    // buyer businesses for a product. Matched on the exact host or any of its
    // subdomains (e.g. "open.spotify.com").
    private static final Set<String> MEDIA_CONTENT_HOSTS = Set.of(
        "spotify.com", "soundcloud.com", "bandcamp.com", "mixcloud.com",
        "pandora.com", "deezer.com", "tidal.com"
    );

    private static final Set<String> DIRECTORY_TITLE_MARKERS = Set.of(
        "listings", "directory", "business listing", "company profile",
        "member profile", "wholesale directory", "supplier directory",
        "members directory", " forum", "supplier guide"
    );

    private static final Set<String> LOW_SIGNAL_DOMAIN_PARTS = Set.of(
        "com", "org", "net", "edu", "gov", "mil", "co", "uk", "de", "fr",
        "it", "es", "nl", "be", "ca", "au", "in", "us", "jp", "cn", "hk",
        "sg", "biz", "info", "io", "me", "tv", "app", "shop", "store", "web"
    );

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:usd|inr|eur|gbp)\\s?\\d|[$\\u20AC\\u00A3\\u20B9]\\s?\\d|\\bprice\\s?[:$])");

    private static final String REJECT_NOT_BUYER = "REJECTED - not a buyer/business website";
    private static final String REJECT_SHOPPING = "REJECTED - shopping/product page";
    private static final String REJECT_ARTICLE = "REJECTED - article/blog";
    private static final String REJECT_GOV = "REJECTED - government site";
    private static final String REJECT_FILE = "REJECTED - file/document";
    private static final String REJECT_ACADEMIC = "REJECTED - academic/research page";

    // ------------------------------------------------------------------
    // LIGHT IRRELEVANT-CONTENT FILTER
    //
    // Product relevance alone can be satisfied INCIDENTALLY: a music/artist
    // site, a news or magazine page, a tourism board, an event/exhibitor
    // registration page, an academic or medical page and a fashion/tech/
    // finance site can all contain the searched words (a band or a track named
    // after the product) without ever dealing in the product. This filter
    // drops exactly those pages.
    //
    // It is deliberately LIGHT so the previous "No buyers found" problem can
    // not come back:
    //   * a page carrying real store/product evidence (shopping URL path,
    //     catalogue, cart, prices, store-selling copy) is NEVER dropped here,
    //     so a genuine Home Decor / Wellness / Spiritual / Meditation / Yoga /
    //     Gift store that merely writes about music, events or travel still
    //     passes;
    //   * it never asks for commercial evidence, a business type, a buyer
    //     role, an email address, a city or a state.
    //
    // It runs in two places with one shared marker set:
    //   * rawResultPreFilterReason - on the RAW search result (URL + title +
    //     snippet only), FIRST, before the product-relevance gate;
    //   * irrelevantContentReason - on the full fetched page during
    //     verification, where store evidence is visible.
    // ------------------------------------------------------------------
    private static final String REJECT_IRRELEVANT_CONTENT =
            "REJECTED - irrelevant non-product content";

    // Hosts that are never a product business for these searches: music/audio/
    // artist platforms, event & ticket platforms, academic databases and
    // news/magazine portals. Matched on any host substring.
    private static final Set<String> IRRELEVANT_CONTENT_HOST_PARTS = Set.of(
            // music / audio / artist platforms
            "spotify.com", "soundcloud.com", "bandcamp.com", "mixcloud.com",
            "pandora.com", "deezer.com", "tidal.com", "genius.com",
            "musixmatch.com", "discogs.com", "allmusic.com", "last.fm",
            "musicbrainz.org", "audiomack.com", "bandlab.com",
            "reverbnation.com", "iheart.com", "tunein.com", "audible.com",
            // event / ticket / exhibitor platforms
            "eventbrite", "ticketmaster", "seatgeek", "allevents.in",
            "raffle.com", "eventup", "brownpapertickets", "eventful.com",
            // academic / research databases
            "jstor.org", "pubmed", "ncbi.nlm.nih.gov", "researchgate.net",
            "academia.edu", "sciencedirect.com", "springer.com",
            "tandfonline.com", "arxiv.org", "doi.org", "wiley.com",
            "elsevier.com", "sagepub.com", "scholar.google",
            // news / magazine / content portals
            "newsweek.com", "usatoday.com", "cnn.com", "bbc.com", "npr.org",
            "forbes.com", "buzzfeed.com", "huffpost.com", "nytimes.com",
            "washingtonpost.com", "theguardian.com", "apnews.com",
            "reuters.com", "news.com", "newsweek", "timesofindia",
            "thetimes.co.uk", "telegraph.co.uk", "independent.co.uk"
    );

    // URL path segments that mark a music / news / event / registration
    // SECTION. Matched as a whole segment ("/music", "/tickets") so a
    // product-named path such as "/collections/singing-bowls" or
    // "/music-boxes" is never caught.
    private static final Set<String> IRRELEVANT_CONTENT_PATH_SEGMENTS = Set.of(
            "music", "albums", "songs", "tracks", "tracklist", "discography",
            "lyrics", "podcasts", "podcast", "radio", "listen", "now-playing",
            "mixtape", "news", "articles", "article", "blog", "press",
            "events", "event", "register", "registration", "tickets",
            "ticket", "exhibitors", "exhibitor", "trade-show", "tradeshow",
            "directory-of", "sponsors", "speakers", "lineup",
            // travel / accommodation / booking sections
            "booking", "bookings", "reservations", "reservation", "hotels",
            "flights", "vacation-rentals", "stays", "tours", "cruise",
            "itinerary", "check-in", "checkin",
            // hospital / healthcare sections
            "hospital", "hospitals", "emergency", "emergency-room",
            "emergency-department", "urgent-care", "er",
            "departments", "doctors", "physicians", "find-a-doctor",
            "find-a-hospital", "patient", "patients"
    );

    // Music / song / artist page language. Checked only when the page carries
    // NO store evidence, so a shop whose copy talks about music is kept.
    private static final String[] MUSIC_CONTENT_PHRASES = {
            "now playing", "tracklist", "track list", "discography", "mixtape",
            "album release", "new album", "our album", "our music",
            "music video", "tour dates", "band bio", "singer songwriter",
            "listen to our", "stream now", "lyrics to", "song lyrics",
            "vinyl record", "dj set", "concert tickets", "singles and eps",
            "cd and vinyl", "listen live", "soundtrack album"
    };

    // Government / tourism & visitor-board language (a .gov host is already
    // rejected by classifyPage; these are the commercial tourism boards).
    private static final String[] TOURISM_CONTENT_PHRASES = {
            "things to do", "travel guide", "tourist information",
            "tourist centre", "tourist center", "visitor centre",
            "visitor center", "visitor bureau", "sightseeing",
            "attractions", "accommodations", "book your stay", "plan your trip",
            "vacation package", "holiday package", "points of interest",
            "official tourism", "welcome centre", "welcome center",
            "travel itinerary", "day tours", "guided tours", "nightlife guide"
    };

    // Event / exhibitor / registration page language. Deliberately NOT the
    // plain service words (class, workshop, session, retreat, training): a
    // sound-healing or meditation business that ALSO sells the product stays
    // a valid buyer.
    private static final String[] EVENT_REGISTRATION_PHRASES = {
            "buy tickets", "get tickets", "ticket prices", "rsvp", "register now",
            "event registration", "register for this", "exhibitor list",
            "exhibitors", "call for vendors", "vendor application",
            "sponsorship packages", "save the date", "add to calendar",
            "trade show booth", "booth registration", "conference program",
            "conference programme", "speaker lineup", "festival lineup",
            "attend our event", "book your ticket", "on sale now tickets",
            "tickets available", "early bird tickets"
    };

    // Clinical / private-practice page language (an .edu host is already
    // rejected). Hospital FACILITY wording lives in HOSPITAL_CONTENT_PHRASES.
    private static final String[] MEDICAL_CONTENT_PHRASES = {
            "clinical trial", "medical centre", "medical center",
            "medical practice", "our patients", "patient portal",
            "symptoms and diagnosis", "treatment options", "prescription",
            "dermatology", "our clinic", "dental clinic", "family medicine",
            "internal medicine", "nurse practitioner", "medical records",
            "paediatrics", "pediatrics", "maternity care", "lab results",
            "immunization", "medical advice", "health advice",
            "symptom checker", "book an appointment",
            "request an appointment"
    };

    // Hospital / healthcare copy screened BEFORE the store-evidence bypass: a
    // hospital or clinic gift shop and a hospital-run wellness spa are still
    // healthcare pages and never buy a decor product.
    private static final String[] HOSPITAL_CONTENT_PHRASES = {
            "general hospital", "community hospital", "children's hospital",
            "regional medical center", "medical centre", "medical center",
            "hospital admission", "admitted patients", "inpatient care",
            "outpatient clinic", "outpatient services", "emergency room",
            "emergency department", "urgent care center", "urgent care clinic",
            "trauma center", "trauma centre", "cardiology department",
            "oncology department", "labor and delivery", "labor & delivery",
            "nurse practitioner", "physician directory", "find a doctor",
            "find a hospital", "insurance accepted", "insurance plans accepted",
            "referral required", "medical records release", "patient portal",
            "hospitalist", "icu", "intensive care unit", "operating room",
            "surgery center", "surgical center", "health information",
            "medical records", "clinical trial", "patient care team",
            "appointment scheduling", "same day appointments"
    };

    // Travel / accommodation / booking page language. A hotel, resort, tour
    // operator, cruise line or flight-booking page sells a stay or a seat, not
    // a physical product, even when its spa menu names the searched item.
    private static final String[] TRAVEL_BOOKING_PHRASES = {
            "book now", "book your stay", "book a room", "book a table",
            "check availability", "check in date", "check out date",
            "per night", "per person per day", "all inclusive", "all-inclusive",
            "reservations", "make a reservation", "reserve your stay",
            "vacation rental", "holiday package", "travel package",
            "flights and hotels", "flight deals", "airfare", "boarding pass",
            "check-in opens", "itinerary", "excursion", "day tour",
            "guided tour", "walking tour", "museum admission",
            "room rate", "guest rooms", "suites available", "crib available",
            "free cancellation", "cancel your booking", "trip planner"
    };

    // ------------------------------------------------------------------
    // CHEAP RAW PRE-FILTER. Runs on the RAW Firecrawl result using ONLY the
    // URL, the title and the snippet - no homepage fetch, no email scraping,
    // no Firecrawl credit. It runs BEFORE the product-relevance gate so an
    // obviously wrong kind of page is dropped while it is still free.
    //
    // It rejects only page KINDS that can never be a buyer of a physical
    // product (news/articles, conferences/events/registration, attendee and
    // exhibitor lists, medical/academic pages, government/tourism bodies,
    // music/song/artist pages and unrelated directories) and it can never
    // produce a "No buyers found" result: a legitimate product URL is always
    // kept, and no home-decor/wellness/spiritual/meditation/gift store copy is
    // rejected here.
    // ------------------------------------------------------------------

    // URL path segments that ARE legitimate product/commerce pages. A result on
    // one of these is KEEPed by the pre-filter and never dropped for its
    // surface wording - this is the escape hatch that protects a genuine shop.
    private static final Set<String> LEGITIMATE_PRODUCT_URL_SEGMENTS = Set.of(
            "product", "products", "shop", "shoppe", "store", "stores",
            "storefront", "collection", "collections", "catalog", "catalogs",
            "catalogue", "catalogues", "category", "categories", "browse",
            "boutique", "showroom", "e-store", "outlet", "warehouses",
            "item", "items", "p", "dp"
    );

    // Government / public-body host suffixes (classifyPage already rejects the
    // .gov/.mil hosts; this also covers the other country spellings).
    private static final Set<String> GOVERNMENT_HOST_SUFFIXES = Set.of(
            ".gov", ".mil", ".gov.uk", ".gouv.fr", ".gc.ca", ".gov.au",
            ".govt.nz", ".gov.in", ".gob.mx"
    );

    // Hospital / healthcare hosts. A hospital, medical centre, clinic network or
    // health-information site is never a buyer of a physical decor product, so it
    // is rejected by host identity whatever its path says. These are matched per
    // dot-delimited host LABEL, so a genuine medical-SUPPLY retailer
    // ("medicalsupplies.example") is not caught by the bare label "medical".
    private static final Set<String> HEALTHCARE_HOST_LABELS = Set.of(
            "hospital", "hospitals", "healthcare", "healthsystem",
            "healthsystems", "clinic", "clinics", "medical", "medicine",
            "dental", "dermatology", "oncology", "cardiology", "pharmacy",
            "emergency"
    );

    // Named hospital networks and health-information brands, matched as a
    // substring because they are brand tokens, not host labels.
    private static final Set<String> HEALTHCARE_HOST_PARTS = Set.of(
            "mayoclinic", "hopkinsmedicine", "clevelandclinic", "webmd",
            "healthgrades", "zocdoc", "goodrx", "healthline",
            "medicalnewstoday", "everydayhealth", "medscape", "drugs.com",
            "merckmanuals", "drugsandsurgery"
    );

    // Travel / accommodation / ticketing BOOKING platforms. A page that sells a
    // room, a flight or a holiday is not a product buyer; note that travel is
    // rejected even when the copy mentions the searched product (e.g. a hotel
    // spa selling "singing bowls" in its wellness page).
    private static final Set<String> TRAVEL_BOOKING_HOST_PARTS = Set.of(
            "expedia", "booking.com", "hotels.com", "airbnb", "vrbo",
            "tripadvisor", "tripadvisor.com", "kayak", "priceline", "agoda",
            "hostelworld", "skyscanner", "edgedelta", "ly.com", "cheapflights",
            "travelocity", "hertz.com", "avis.com", "enterprise.com",
            "amtrak.com", "sncf-connect", "eurail", "seatginder", "hotwire",
            "orbitz", "groupon", "livingsocial", "getyourguide", "viator.com",
            // hotel groups & airlines: a resort spa page listing "singing bowls"
            // is still a hospitality page, not a product buyer
            "hilton.com", "marriott.com", "hyatt.com", "ihg.com",
            "holidayinn.com", "bestwestern.com", "wyndhamhotels.com",
            "choicehotels.com", "accor.com", "radissonhotels.com",
            "fourseasons.com", "mandarinoriental.com", "shangri-la.com",
            "trumphotels.com", "kempinski.com", "delta.com",
            "united.com", "americanairlines.com", "southwest.com",
            "jetblue.com", "alaskaair.com", "spirit.com"
    );

    // News / editorial hosts beyond the big national outlets already listed, so
    // a local or regional paper cannot surface as a buyer of a physical product.
    private static final Set<String> NEWS_HOST_PARTS = Set.of(
            "nypost", "foxnews", "nbcnews", "cbsnews", "abcnews.go", "abcnews",
            "latimes", "sfgate", "chicagotribune", "bostonglobe", "dallasnews",
            "houstonchronicle", "denverpost", "seattletimes", "mercurynews",
            "nypost.com", "usnews", "newsweek", "politico", "axios",
            "thehill", "nationalreview", "newsmax", "benzinga", "seekingalpha",
            "businessinsider", "mashable", "gizmodo", "wired", "techcrunch",
            "engadget", "arstechnica", "theverge", "zdnet", "cnet",
            "pcmag", "digitaltrends", "tomsguide", "howstuffworks", "thespruce",
            "instructables", "hgtv", "housebeautiful", "architecturaldigest",
            "thespruce.com", "apartmenttherapy", "bhg", "householdtips"
    );

    // News / editorial / press page language.
    private static final String[] NEWS_ARTICLE_PHRASES = {
            "breaking news", "news update", "latest news", "press release",
            "press releases", "news article", "news coverage", "editorial",
            "opinion:", "interview with", "podcast episode", "news roundup",
            "headlines", "in the news", "media coverage", "obituary"
    };

    // Academic / research page language that is NOT already an .edu host.
    private static final String[] ACADEMIC_RESEARCH_PHRASES = {
            "research paper", "research article", "peer review", "peer reviewed",
            "et al", "abstract:", "doi:", "citation", "bibliography",
            "phd thesis", "university press", "journal article",
            "study published", "study finds", "systematic review",
            "meta-analysis", "conference paper"
    };

    // Attendee / exhibitor / sponsor LIST pages. A list of names of companies
    // or people is never the company itself and can never be emailed as a
    // buyer.
    private static final String[] ATTENDEE_EXHIBITOR_LIST_PHRASES = {
            "attendee list", "attendees list", "list of attendees",
            "exhibitor list", "list of exhibitors", "exhibitor directory",
            "our exhibitors", "who is exhibiting", "who's exhibiting",
            "sponsor list", "list of sponsors", "speaker list",
            "list of speakers", "programme schedule", "conference schedule",
            "exhibition schedule", "expo exhibitors", "trade show exhibitors",
            "past attendees", "previous attendees", "delegates list",
            "list of delegates", "conference agenda", "event agenda"
    };

    // Unrelated business/aggregator DIRECTORIES: pages that only list other
    // companies instead of being one. None of them can be a buyer.
    private static final String[] UNRELATED_DIRECTORY_PHRASES = {
            "business directory", "company directory", "supplier directory",
            "wholesale directory", "retail directory", "member directory",
            "yellow pages", "list of suppliers", "list of vendors",
            "list of companies", "list of stores", "list of shops",
            "top 10 stores", "top 20 stores", "best stores in",
            "stores near you", "shops near you", "near me",
            "reviews and ratings", "compare prices", "price comparison",
            "coupon codes", "promo codes", "coupons and discounts"
    };

    // Clearly unrelated industries: fashion/apparel, technology/software,
    // finance/insurance, real estate, food, automotive and sports venues.
    private static final String[] UNRELATED_INDUSTRY_PHRASES = {
            // fashion / apparel
            "plus size", "clothing", "apparel", "t shirts", "t-shirts",
            "hoodie", "sneakers", "jeans", "swimwear", "lingerie",
            "tailoring", "shoe store", "costume design", "sportswear",
            // technology / software
            "software as a service", "saas platform", "app download",
            "download the app", "web hosting", "domain registration",
            "api documentation", "developer portal", "open source",
            "cloud hosting", "managed hosting", "erp software",
            "crm software", "mobile app", "mobile application", "our sdk",
            "our plugin", "wordpress plugin", "chrome extension", "open api",
            // finance / insurance
            "bank account", "credit card", "personal loan", "mortgage",
            "insurance quote", "investment account", "stockbroker",
            "cryptocurrency", "bitcoin", "forex", "wire transfer",
            "loan application", "financial advisor", "tax return",
            "retirement planning",
            // real estate
            "homes for sale", "real estate listing", "apartments for rent",
            "property management", "mls listing", "realtor",
            "property valuation",
            // food
            "restaurant menu", "our bakery", "coffee beans", "wine cellar",
            "brewery", "catering menu", "grocery delivery", "farmers market",
            // automotive
            "auto parts", "car dealership", "used cars", "tire shop",
            "oil change",
            // sports / recreation venues
            "youth soccer", "golf course", "fishing charter", "bowling alley",
            "gym membership", "personal trainer"
    };

    // Rejection codes are reserved for wrong product, invalid content,
    // non-business pages and already-known results. Product relevance is the
    // only substantive web acceptance requirement.
    private static final String CODE_WRONG_PRODUCT = "REJECTED - WRONG PRODUCT";
    private static final String CODE_NO_BUYER_INTENT = "REJECTED - NO BUYER INTENT";
    private static final String CODE_INVALID_CONTENT = "REJECTED - INVALID CONTENT";
    private static final String CODE_NO_COMPANY_IDENTITY = "REJECTED - NO COMPANY IDENTITY";
    private static final String CODE_DUPLICATE = "DUPLICATE";
    private static final String CODE_ALREADY_IN_DATABASE = "ALREADY IN DATABASE";

    // Location-priority classes used for final selection/ranking. Location
    // metadata never controls whether a product-relevant candidate is accepted,
    // but it DOES control the ORDER: the requested city first, then a NEARBY
    // city of the requested city inside the SAME state, then the rest of that
    // state, then the same country.
    private static final String LOCATION_CLASS_EXACT_CITY = "EXACT_CITY";
    // A city in the SAME state that sits in the same regional cluster as the
    // requested city (NEARBY_CITY_CLUSTERS) - a genuinely reachable local
    // buyer, ranked ahead of the far end of the same state.
    private static final String LOCATION_CLASS_NEARBY_CITY = "NEARBY_CITY";
    private static final String LOCATION_CLASS_SAME_STATE = "SAME_STATE";
    private static final String LOCATION_CLASS_SAME_COUNTRY = "SAME_COUNTRY";
    private static final String LOCATION_CLASS_UNKNOWN = "UNKNOWN";
    private static final String PIPELINE_STATUS_POTENTIAL = "POTENTIAL";
    private static final String PIPELINE_STATUS_UNVERIFIED = "UNVERIFIED";

    // Classification used when a product-relevant business has no more specific
    // business-type evidence.
    private static final String PRODUCT_RELEVANT_CLASSIFICATION = "PRODUCT_RELEVANT_BUSINESS";

    // Default classification when neither the page NOR the website evidence
    // states an importer/distributor/wholesaler/retailer/supplier/dealer/buyer
    // role. A business is NEVER assigned a buyer role it does not evidence.
    private static final String CLASSIFICATION_OTHER = "OTHER";

    // Cheap-screen marker: the candidate passed the URL/title/snippet product
    // screen and may proceed to homepage verification.
    private static final String QUAL_PRODUCT_RELEVANT = "PRODUCT_RELEVANT";

    // The product phrase is anchored in the candidate's own title or URL while
    // the website text did not independently re-confirm it.
    private static final String QUAL_PRODUCT_LISTING = "PRODUCT_LISTING_RELEVANT";

    // Buyer-qualification tiers (full-page verify): what kind of product match
    // each accepted result is. These are prospect labels of the PRODUCT match,
    // never a business-type/classification claim:
    //  - DIRECT_PRODUCT_BUYER       the site demonstrably deals in the product
    //  - RELATED_CATEGORY_BUYER     a related-category store (meditation/spiritual/
    //                               wellness/metaphysical/gift/home-decor shop) that
    //                               plausibly carries the product, with store evidence
    //  - POTENTIAL_WHOLESALE_BUYER  accepted business carrying a wholesale signal
    //  - POTENTIAL_RETAIL_BUYER     accepted business carrying a retail/store signal
    //  - NOT_A_BUYER                rejected (never stored)
    private static final String QUAL_DIRECT_PRODUCT_BUYER = "DIRECT_PRODUCT_BUYER";
    private static final String QUAL_RELATED_CATEGORY_BUYER = "RELATED_CATEGORY_BUYER";
    private static final String QUAL_POTENTIAL_WHOLESALE_BUYER = "POTENTIAL_WHOLESALE_BUYER";
    private static final String QUAL_POTENTIAL_RETAIL_BUYER = "POTENTIAL_RETAIL_BUYER";
    private static final String QUAL_NOT_A_BUYER = "NOT_A_BUYER";

    // Valid business/buyer TYPE signals (requirement 4): words that express an
    // actual commercial business identity. They are honoured ONLY when they
    // appear in the CANDIDATE or WEBSITE evidence (title/snippet/URL/about/
    // contact/footer/homepage), NEVER in the fired search query. The cheap
    // screen uses phrase-level product relevance only; these words gate the
    // BUSINESS/BUYER TYPE on the full page.
    private static final String[] BUSINESS_TYPE_WORDS = {
        "retailer", "retailers", "wholesaler", "wholesalers", "distributor",
        "distributors", "importer", "importers", "dealer", "dealers",
        "supplier", "suppliers", "vendor", "vendors", "stockist", "stockists",
        "b2b", "procurement", "sourcing", "shop", "shops", "store", "stores",
        "storefront", "boutique", "estore", "booth", "marketplace", "wholesale",
        "retail", "bulk", "trading", "company", "companies", "enterprise",
        "enterprises", "corporation", "incorporated", "llc", "ltd", "inc"
    };
    // Multi-word business-type phrases checked with containsPhrase.
    private static final String[] BUSINESS_TYPE_PHRASES = {
        "retail store", "retail stores", "retail shop", "online store",
        "online shop", "gift shop", "trading company", "trading companies",
        "bulk supplier", "bulk buyer", "wholesale supplier", "wholesale store",
        "company store", "company shop"
    };

    // Words/markers of a SERVICE-only business: massage spas, therapy/
    // practitioner pages, classes, workshops, retreats, courses, training,
    // certification, booking/session/consultation pages and events. A page that
    // is only a service is rejected as a buyer EVEN when it names the product -
    // a therapy practice that USES singing bowls is not a business that buys
    // them. The same business that ALSO runs a store/shop/product catalogue IS
    // accepted (it buys stock), so this is only a reject when no commerce
    // evidence exists on the page.
    private static final String[] SERVICE_ONLY_MARKERS = {
            "massage", "spa", "therapy", "therapist", "therapists", "therapeutic",
            "practitioner", "practitioners", "healing session", "healing sessions",
            "sound bath", "sound baths", "sound healing session", "sound healing sessions",
            "meditation session", "meditation sessions", "meditation class", "yoga class",
            "yoga classes", "class", "classes", "workshop", "workshops", "retreat",
            "retreats", "course", "courses", "training", "certification", "certificate",
            "session", "sessions", "consultation", "appointment", "book a session",
            "book a class", "register for", "enroll", "enrol", "membership",
            "wellness studio", "meditation studio", "yoga studio", "healing studio",
            "ticket", "tickets", "register", "rsvp", "schedule a", "our hours"
    };

    // Words that show the page actually SELLS a physical product (a real store /
    // product catalogue / shop) rather than providing a service or being content.
    // Used to turn a product mention - DIRECT or a RELATED-CATEGORY store - into
    // a buyer lead, and to rescue a business that ALSO offers classes from the
    // service-only rejection.
    private static final String[] STORE_SELLING_MARKERS = {
            "our shop", "our store", "online store", "online shop", "shop now",
            "shop online", "shop our", "shop here", "our products", "our product",
            "our catalog", "our catalogue", "our collections", "our collection",
            "add to cart", "add to bag", "buy now", "for sale", "shopping cart",
            "checkout", "in stock", "back in stock", "product catalog",
            "product catalogue", "products", "collections", "catalog", "catalogue",
            "price", "prices", "order online", "wholesale", "wholesaler",
            "retailer", "retail", "shop", "store", "boutique", "estore",
            "storefront", "buy", "purchase", "ordering", "orders"
    };

    // Store-selling markers strong enough to count as commerce evidence on their
    // own (a single hit already proves selling intent).
    private static final String[] STRONG_STORE_SELLING_MARKERS = {
            "our shop", "our store", "online store", "online shop", "shop now",
            "shop online", "shop our", "our products", "our catalog",
            "our collections", "add to cart", "add to bag", "buy now",
            "shopping cart", "checkout", "in stock", "product catalog",
            "product catalogue", "estore", "storefront"
    };

    private static final Set<String> FILE_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "xls", "xlsx", "csv", "ppt", "pptx", "txt",
        "rtf", "zip", "rar", "7z", "gz", "tar", "jpg", "jpeg", "png", "gif",
        "webp", "tiff", "svg", "mp3", "mp4", "epub"
    );

    private static final Set<String> ACADEMIC_PATH_MARKERS = Set.of(
        "/research", "/papers", "/publications", "/journal", "/theses",
        "/dissertation", "/conference", "/proceedings"
    );

    private static final Set<String> ACADEMIC_TITLE_MARKERS = Set.of(
        "journal of", "research on", "research report", "working paper",
        "white paper", "whitepaper", "thesis", "dissertation", "proceedings",
        "academic", "scientific", "peer-reviewed", "conference paper",
        "preprint", "study of", "impact of"
    );

    // Location data supports extraction and ranking metadata. A location
    // conflict or missing address never rejects a product-relevant candidate.

    // Canadian provinces and territories (lowercased full name -> 2-letter
    // abbreviation). Together with STATE_ABBREVIATIONS these form the accepted
    // USA + Canada location set; "CA"/"ON" style abbreviations are kept out of
    // the US maps so "California" and "Ontario" never collide.
    private static final Map<String, String> CANADIAN_PROVINCE_ABBREVIATIONS = Map.ofEntries(
        Map.entry("alberta", "AB"),
        Map.entry("british columbia", "BC"),
        Map.entry("manitoba", "MB"),
        Map.entry("new brunswick", "NB"),
        Map.entry("newfoundland and labrador", "NL"),
        Map.entry("nova scotia", "NS"),
        Map.entry("ontario", "ON"),
        Map.entry("prince edward island", "PE"),
        Map.entry("quebec", "QC"),
        Map.entry("saskatchewan", "SK"),
        Map.entry("northwest territories", "NT"),
        Map.entry("nunavut", "NU"),
        Map.entry("yukon", "YT")
    );

    private static final String[] CANADIAN_PROVINCE_NAMES = {
        "alberta", "british columbia", "manitoba", "new brunswick",
        "newfoundland and labrador", "nova scotia", "ontario",
        "prince edward island", "quebec", "saskatchewan",
        "northwest territories", "nunavut", "yukon"
    };

    // Trade-data / import-export record & analytics sites (OEC, Panjiva,
    // Volza, Trademap, ImportGenius, customs/shipment data portals). They are
    // data providers, never buyers - rejected as NO BUYER INTENT.
    private static final Set<String> TRADE_DATA_HOST_PARTS = Set.of(
        "oec", "panjiva", "volza", "trademap", "importgenius", "exportgenius",
        "comtrade", "indexmundi", "tradingeconomics", "customsdata",
        "shipmentdata", "importdata", "exportdata", "tradeanalytics",
        "onlyimportdata", "importinfo", "seair", "cybex", "exim", "tradedata",
        "go4worldbusiness", "eximpedia", "globaltradeplaza", "tradeford",
        "customsmobile", "importyeti", "tradewheel", "exportpages",
        "tradeindia", "globalsources"
    );
    private static final Set<String> TRADE_DATA_TITLE_MARKERS = Set.of(
        "import data", "export data", "trade data", "customs data",
        "import records", "export records", "shipment records",
        "trade statistics", "country of import", "import statistics",
        "export statistics", "trade value", "trade volume", "customs records",
        "buyers & importers", "buyers and importers", "importers & buyers",
        "importers and buyers", "buyers list", "buyer list",
        "buying agents", "buyers directory", "importers directory",
        "shipment details", "importer names", "market insights",
        "importers leads", "buyers leads", "b2b buyers", "leads data",
        "customs ruling", "customs rulings", "tariff classification",
        "tradebase", "importers database", "buyers database",
        "supplier database", "exporter database", "importers usa",
        "business database", "companies database"
    );

    // Words that carry no product meaning; a product like "Basmati Rice" is
    // reduced to its meaningful terms ["basmati", "rice"] for relevance checks.
    // Trade qualifiers ("wholesale", "importer", "bulk", ...) are dropped so a
    // product such as "Wholesale Singing Bowls" is matched on "singing bowls".
    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "of", "for", "and", "or", "in", "on", "with",
        "by", "at", "from", "to", "usa", "us", "u", "s", "&", "-", "co",
        "ltd", "llc", "inc", "corp", "company", "products", "product",
        "wholesale", "wholesaler", "wholesalers", "retail", "retailer",
        "importer", "import", "importers", "exporter", "export", "exporters",
        "distributor", "distributors", "distribution", "supplier", "suppliers",
        "supply", "bulk", "buy", "buyer", "buyers", "trade", "trading",
        "vendor", "vendors", "dealer", "dealers", "stockist", "stockists",
        "buying", "house", "traders", "supplying"
    );

    // ------------------------------------------------------------------
    // Supported home-decor product category gate. A buyer search ONLY runs
    // for products in this category (singing/tibetan/crystal bowls, candles
    // & candle holders, lanterns & crystal lantern holders, decorative jars,
    // home decor). Anything else (cars, clothing, hotels, event supplies...)
    // is rejected BEFORE any Firecrawl request is fired, so credits are never
    // spent on an invalid product.
    // ------------------------------------------------------------------
    private static final String UNSUPPORTED_PRODUCT_MESSAGE =
            "Please search for a supported home decor product such as singing bowls, "
            + "candles, candle holders, lanterns, or decorative jars.";

    // Words that turn a plain "bowl(s)" into a supported singing/tibetan/
    // crystal bowl product; a bare "bowls" is ambiguous kitchen crockery and
    // is rejected so unrelated buyers are never returned to fill a quota.
    private static final Set<String> SUPPORTED_BOWL_QUALIFIERS = Set.of(
            "singing", "song", "tibetan", "crystal", "himalayan", "meditation",
            "sound", "buddhist", "zen", "chakra", "decorative", "decoration", "decor"
    );

    // Context words that make a "jar(s)" a DECORATIVE jar (home decor) rather
    // than a kitchen/canning storage jar. "storage"/"display" are accepted only
    // because kitchen words like "food" are rejected below by NON_HOME_DECOR_WORDS.
    private static final Set<String> DECORATIVE_JAR_CONTEXT = Set.of(
            "decorative", "decoration", "decor", "apothecary",
            "candy", "cookie", "ceramic", "mason", "amber", "vintage",
            "rustic", "farmhouse", "candle", "display", "storage"
    );

    // Query synonyms for the decorative-jar home-decor family. The user's own
    // phrasing is always tried first, then these related phrasings - all STILL
    // within the decorative/jar home-decor family, never unrelated products.
    // Each synonym is combined with the selected location and a business role
    // word (wholesaler, supplier, retailer, importer, distributor, ...) so the
    // 6-request budget explores diverse queries instead of repeating one
    // overly-specific phrase that returns zero results.
    private static final List<String> DECORATIVE_JAR_QUERY_SYNONYMS = List.of(
            "decorative jars",
            "glass jars",
            "apothecary jars",
            "decorative storage jars",
            "glass canisters",
            "decorative glass jars",
            "storage jars",
            "display jars",
            "jars with handles",
            "decorative jars with handles",
            "glass display jars",
            "home decor jars",
            "decorative canisters",
            "glass storage containers",
            "decorative storage containers",
            "decorative jar"
    );

    // ------------------------------------------------------------------
    // PRODUCT-FIRST query planning. Every buyer search builds its 6-request
    // window from the USER'S OWN product first, then MORE commercial synonym
    // phrases from the same home-decor family - each slot fires a DIFFERENT
    // product phrase so the budget never repeats one overly-specific exact
    // phrase that can return 0 results. Firecrawl queries always lead with a
    // product phrase from the user's own family and never cycle unrelated
    // catalogue products (lanterns/jars/... for a "Crystal Singing Bowls"
    // search).
    //   Slot 0 (selected city)   : the user's product + "for sale"
    //   Slot 1-4 (other cities)  : one DIFFERENT synonym per major city
    //   Slot 5 (state-wide)      : one more synonym for the fallback phase
    // The supported home-decor families, used to pick in-family synonym phrases
    // for the user's product. In priority order:
    //   1. Singing / wellness    4. Votive holders
    //   2. Lantern / lighting    5. Decorative jars
    //   3. Candle / candle holders 6. Handmade & home decor
    // ------------------------------------------------------------------
    // Product-family synonym phrases used for queries. Products are NEVER
    // broadened to generic wellness/meditation terms ("meditation bowls",
    // "healing sound therapy", "spiritual decor"...) because that surfaces
    // class, retreat and service pages, not buyers of the physical product.
    // Singing-bowl family terms stay STRONG singing-bowl phrases only.
    private static final List<List<String>> PRODUCT_FAMILY_TERMS = List.of(
        // Singing / wellness bowl family: commercially useful phrases only.
        // Overly narrow combinations ("buddhist singing bowls retailer",
        // "singing bowl wholesale supplier") are NOT used - they return poor
        // search-engine results and burn the whole 6-request budget.
        List.of("singing bowls", "tibetan singing bowls", "himalayan singing bowls",
                "crystal singing bowls", "meditation singing bowls", "sound healing bowls"),
        List.of("decorative lanterns", "metal lanterns", "moroccan lanterns",
                "glass lanterns", "hanging lanterns", "garden lanterns",
                "home decor lanterns"),
        List.of("candles", "candle holders", "candle stands",
                "crystal candle holders", "crystal votive holders",
                "crystal tealight holders", "tealight holders", "votive holders",
                "pillar candle holders", "decorative candle holders",
                "decorative candles", "handmade candles", "scented candles",
                "premium candles", "candle sets"),
        List.of("votive holders", "glass votive holders", "decorative votive holders"),
        List.of("decorative jars", "glass jars", "apothecary jars",
                "decorative storage jars", "glass canisters",
                "decorative glass jars", "storage jars", "display jars",
                "jars with handles", "decorative jars with handles",
                "glass display jars", "home decor jars", "decorative canisters",
                "glass storage containers", "decorative storage containers",
                "decorative jar"),
        List.of("handmade home decor", "handcrafted home decor", "home decor",
                "wall decor", "wall art", "interior decor", "spiritual decor",
                "wellness decor", "meditation decor", "yoga decor",
                "gift and home decor", "artisan home decor")
    );

    // Related-category buyer profiles used for the two category-profile search
    // queries (slot 4-5 state-wide). Each product family gets TWO adjacent store
    // categories through which the physical product is genuinely sold - e.g. for
    // singing bowls: meditation/spiritual/wellness stores and metaphysical/
    // crystal/gift shops. The engine generates the equivalent category-profile
    // query for the searched product's family; nothing is hardcoded per product.
    // Index order matches PRODUCT_FAMILY_TERMS (bowls, lanterns, candles &
    // holders, votive, jars, home decor). A slot's phrasing stays PRODUCT +
    // BUYER PROFILE: `"<cat>"... store "<product>" <state>`.
    private static final List<List<String>> RELATED_CATEGORY_PROFILES = List.of(
        // Singing / wellness bowls: meditation & spiritual & wellness stores;
        // metaphysical & crystal & gift shops.
        List.of("meditation", "spiritual", "wellness"),
        List.of("metaphysical", "crystal", "gift"),
        // Lanterns / lighting: home-decor, lighting, garden stores.
        List.of("home decor", "lighting", "garden"),
        List.of("outdoor", "furniture", "gift"),
        // Candles & candle holders: home-decor, home-fragrance, gift stores.
        List.of("home decor", "home fragrance", "gift"),
        List.of("spiritual", "wellness", "boutique"),
        // Votive holders: home-decor, glassware, gift stores.
        List.of("home decor", "glassware", "gift"),
        List.of("candle", "spiritual", "boutique"),
        // Decorative jars: home-decor, glassware, kitchen/gift stores.
        List.of("home decor", "glassware", "kitchen"),
        List.of("gift", "storage", "boutique"),
        // Handmade / home decor: artisan & home-goods stores.
        List.of("handmade", "artisan", "home goods"),
        List.of("gift", "boutique", "decor")
    );

    // Buyer-type words cycled onto the product-first slots (one unique buyer
    // type per slot so the 6 requests never repeat the same role). Commerce
    // (product) buyer roles lead - importers, wholesalers, distributors,
    // retailers, suppliers. Broad "store" words like "home decor store",
    // "wellness store" or "yoga store" are gone: they widened the search to
    // businesses that never carry the specific physical product.
    private static final List<String[]> BUYER_WORDS = List.of(
        new String[]{"retailer", "Retailer"},
        new String[]{"wholesale", "Wholesaler"},
        new String[]{"distributor", "Distributor"},
        new String[]{"shop", "Retailer"},
        new String[]{"store", "Retailer"},
        new String[]{"wholesale", "Wholesaler"},
        new String[]{"retailer", "Retailer"},
        new String[]{"online store", "Online Store"},
        new String[]{"supplier", "Supplier"},
        new String[]{"shop", "Retailer"}
    );

    // ------------------------------------------------------------------
    // DYNAMIC PRODUCT QUERY GENERATION. Every search slot = ONE generated
    // PRODUCT-ONLY phrase (never a location) + an optional buyer word; the
    // location is added LATER by the phase planner. The generator is
    // category-aware and product-family-CONSISTENT: every variation is derived
    // from the user's OWN product (same OM Enterprises family) - lanterns /
    // jars / candles are NEVER mixed into a singing-bowl search, products are
    // never broadened to flashlights / industrial wax / canning jars / crystal
    // shops, and the query list is never padded with unrelated catalogue items.
    // ------------------------------------------------------------------

    // Words that make a generated phrase commercially oriented. A slot whose
    // phrase already contains one of these is fired WORDLESS so the built query
    // never doubles the role ("singing bowl supplier supplier").
    private static final Pattern ROLE_WORD_PATTERN = Pattern.compile(
        "\\b(wholesale|wholesaler|wholesalers|supplier|suppliers|distributor|distributors|"
            + "importer|importers|retailer|retailers|buyer|buyers|bulk|bulk buyer)\\b");

    // Generalized craft/quality/venue modifiers safe to combine with ANY
    // supported home-decor product - the building blocks of the generator for
    // products without a dedicated reference dictionary (e.g. "Wooden Wall
    // Decor", "Metal Home Decor").
    private static final String[] GENERIC_DECOR_MODIFIERS = {
        "handmade", "handcrafted", "artisan", "artisanal", "decorative",
        "luxury", "premium", "rustic", "modern"
    };

    // ---- Singing / wellness bowl family vocabulary ----
    private static final String[] BOWL_STYLE_MODIFIERS = {
        "crystal", "tibetan", "himalayan", "buddhist", "meditation", "spiritual",
        "yoga", "healing", "sound healing", "sound therapy", "chakra", "antique",
        "traditional", "artisan", "handcrafted", "handmade", "metal", "bronze", "brass"
    };
    private static final String[] BOWL_CORE_SYNONYMS = {
        "singing bowl", "buddhist singing bowl",
        "tibetan meditation bowl", "himalayan meditation bowl",
        "meditation sound bowl", "chakra healing bowl",
        "handmade tibetan bowl", "handmade himalayan bowl"
    };

    // ---- Candle holder / candle / votive / tealight family vocabulary ----
    private static final String[] CANDLE_HOLDER_STYLE_MODIFIERS = {
        "decorative", "handmade", "handcrafted", "crystal", "glass", "metal",
        "brass", "iron", "wooden", "ceramic", "votive", "tealight", "pillar",
        "hanging", "wall", "table", "home decor", "luxury", "artisan"
    };
    private static final String[] CANDLE_HOLDER_CLOSE_SYNONYMS = {
        "candle stand", "decorative candle stand", "candle holder set"
    };
    private static final String[] CANDLE_STYLE_PHRASES = {
        "decorative candles", "scented candles", "handmade candles", "luxury candles",
        "pillar candles", "votive candles", "soy candles", "home decor candles",
        "artisan candles", "handcrafted candles", "natural candles", "premium candles",
        "organic candles", "beeswax candles", "aromatherapy candles", "therapeutic candles",
        "container candles", "jar candles", "tealight candles",
        "candle sets", "decorative candle sets"
    };
    private static final String[] VOTIVE_HOLDER_STYLE_MODIFIERS = {
            "glass", "crystal", "decorative", "handmade", "handcrafted", "metal",
            "brass", "ceramic", "wooden", "luxury", "artisan"
    };

    // ---- Lantern / lantern holder family vocabulary ----
    private static final String[] LANTERN_STYLE_MODIFIERS = {
        "decorative", "handmade", "handcrafted", "candle", "hanging", "metal",
        "brass", "iron", "glass", "moroccan", "indian", "artisan", "traditional",
        "garden", "table", "outdoor", "luxury"
    };
    private static final String[] LANTERN_HOLDER_STYLE_MODIFIERS = {
        "decorative", "candle", "hanging", "metal", "brass", "iron", "glass",
        "handmade", "handcrafted", "artisan", "wall", "garden", "outdoor"
    };

    // ---- Decorative jar family vocabulary (never kitchen/canning jars) ----
    private static final String[] JAR_STYLE_PHRASES = {
        "decorative home decor jars", "handmade decorative jars",
        "handcrafted decorative jars", "glass decorative jars",
        "decorative jars with handles", "decorative storage jars",
        "luxury decorative jars", "home decor accent jars",
        "decorative display jars", "glass display jars",
        "decorative glass jars", "artisan decorative jars",
        "decorative ceramic jars", "decorative metal jars",
        "vintage decorative jars", "rustic decorative jars",
        "farmhouse decorative jars", "decorative apothecary jars",
        "decorative jar sets", "decorative jars for home decor"
    };

    // Product category used to route dynamic query generation. Detection reuses
    // the exact helpers the home-decor category gate uses, so a query family is
    // always consistent with what the gate accepted.
    private enum QueryCategory { BOWL, CANDLE_HOLDER, CANDLE, VOTIVE_HOLDER, TEALIGHT, LANTERN, LANTERN_HOLDER, JAR, HOME_DECOR }

    // One PRODUCT-ONLY query candidate + the metadata the <=6-request planner
    // uses to keep the frozen Firecrawl budget diverse:
    //   band 0 - the user's exact phrasing (always first, never mixed products)
    //   band 1 - close synonyms + material/style/use-case/craft variations
    //   band 2 - wholesale / bulk commercial phrases
    //   band 3 - buyer/role phrases (supplier / wholesaler / distributor / importer)
    private static final class ProductQueryCandidate {
        final String query;
        final int band;
        final String product;
        final String queryKey;

        ProductQueryCandidate(String query, int band, String product, String queryKey) {
            this.query = query;
            this.band = band;
            this.product = product;
            this.queryKey = queryKey;
        }
    }

    /** Builds the <=6 PRODUCT-FOCUSED query slots for one search. Every slot's
     *  TERM is a PRODUCT-ONLY phrase produced by generateProductSearchQueries -
     *  location is never part of the product query; the phase planner adds the
     *  buyer word and the location. The dynamic generator keeps every variation
     *  inside the user's OWN product family (singing bowls for a "Singing Bowl"
     *  search, never lanterns/jars/candles) and the diversity planner picks the
     *  best slots so the frozen 6 requests are never burned on near-identical
     *  queries. Fewer than 6 slots is fine. */
    private List<String[]> buildProductFocusedSlots(List<String> products) {
        List<ProductQueryCandidate> generated = new ArrayList<>();
        Set<String> catalogueKeys = new HashSet<>();

        for (String product : products) {
            if (product == null || product.isBlank()) {
                continue;
            }
            List<ProductQueryCandidate> productQueries = generateProductSearchQueries(product);
            logger.info("PRODUCT QUERIES GENERATED for '{}' ({} candidate(s)):",
                    product, productQueries.size());
            for (int i = 0; i < productQueries.size(); i++) {
                ProductQueryCandidate cq = productQueries.get(i);
                logger.info("  Candidate {}. [band {}] '{}'",
                        i + 1, cq.band, cq.query);
                if (cq.queryKey != null && catalogueKeys.add(cq.queryKey)) {
                    generated.add(cq);
                }
            }
        }

        List<ProductQueryCandidate> selected = selectDiverseQueries(generated, MAX_FIRECRAWL_REQUESTS_PER_SEARCH);
        logger.info("SELECTED FIRECRAWL QUERY SLOTS (max {} per search):",
                MAX_FIRECRAWL_REQUESTS_PER_SEARCH);
        List<String[]> slots = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            ProductQueryCandidate cq = selected.get(i);
            logger.info("  Slot {}. '{}' (product variation only - buyer word & location are added by the phase planner)",
                    i + 1, cq.query);
            // A phrase that already carries a commercial role/wholesale word is
            // fired wordless so the full query never reads "... supplier supplier".
            String[] buyer = hasCommerceTerm(cq.query) ? null : buyerWordForTerm(cq.query, i);
            slots.add(new String[]{
                    cq.query,
                    buyer == null ? "" : buyer[0],
                    buyer == null ? "" : buyer[1]});
        }
        return slots;
    }

    /** Generates the PRODUCT-ONLY query candidates for ONE user product. The
     *  user's exact phrasing is always candidate 1 (band 0); every further
     *  candidate is derived from the same physical product family - synonyms,
     *  material/style/use-case/craft variations (band 1), wholesale/bulk
     *  commercial phrasings (band 2) and buyer/role phrasings (band 3).
     *  No candidate ever contains a location. Output is deduplicated on a
     *  singular key so "Singing Bowls" and "singing bowl" don't burn two
     *  slots in the 6-request budget. */
    private List<ProductQueryCandidate> generateProductSearchQueries(String product) {
        List<ProductQueryCandidate> generated = new ArrayList<>();
        Set<String> seenSingular = new HashSet<>();
        String given = collapseWhitespace(product);
        String normalized = normalizeProductForValidation(product);
        if (given == null || normalized.isEmpty()) {
            return generated;
        }
        addGeneratedKeyword(generated, seenSingular, given, 0, product);
        Set<String> words = new HashSet<>(tokenizeWords(normalized));
        QueryCategory category = detectQueryCategory(normalized, words);
        switch (category) {
            case BOWL:
                generateBowlQueries(generated, seenSingular, product, normalized);
                break;
            case CANDLE_HOLDER:
                generateCandleHolderQueries(generated, seenSingular, product, normalized);
                break;
            case CANDLE:
                generateCandleQueries(generated, seenSingular, product, normalized);
                break;
            case VOTIVE_HOLDER:
                generateVotiveHolderQueries(generated, seenSingular, product, normalized);
                break;
            case TEALIGHT:
                generateTealightQueries(generated, seenSingular, product, normalized);
                break;
            case LANTERN:
                generateLanternQueries(false, generated, seenSingular, product, normalized);
                break;
            case LANTERN_HOLDER:
                generateLanternQueries(true, generated, seenSingular, product, normalized);
                break;
            case JAR:
                generateJarQueries(generated, seenSingular, product, normalized);
                break;
            default:
                generateGenericDecorQueries(generated, seenSingular, product, normalized);
                break;
        }
        return generated;
    }

    /** Routes the user's product to the same family the home-decor category
     *  gate accepted, so the generated queries always match what was approved.
     *  Any product the gate lets through but the generator has no dictionary
     *  for falls to the generic home-decor generator. */
    private QueryCategory detectQueryCategory(String norm, Set<String> words) {
        if (isBowlFamilyProduct(norm, words) || isSoundHealingProduct(norm)) {
            return QueryCategory.BOWL;
        }
        if (isLanternProduct(norm)) {
            return norm.contains("holder") ? QueryCategory.LANTERN_HOLDER : QueryCategory.LANTERN;
        }
        boolean holderProduct = words.contains("holder") || words.contains("holders")
                || words.contains("stand") || words.contains("stands");
        if (holderProduct && (words.contains("votive") || words.contains("votives"))) {
            return QueryCategory.VOTIVE_HOLDER;
        }
        if (holderProduct && (words.contains("tealight") || words.contains("tealights")
                || norm.contains("tea light") || norm.contains("tea lights"))) {
            return QueryCategory.TEALIGHT;
        }
        if (isCandleProduct(norm, words)) {
            if (holderProduct) {
                return QueryCategory.CANDLE_HOLDER;
            }
            return QueryCategory.CANDLE;
        }
        if (isDecorativeJarProduct(words)) {
            return QueryCategory.JAR;
        }
        return QueryCategory.HOME_DECOR;
    }

    /** Adds one generated keyword unless a singular variant of it is already
     *  present. The key recorded on the candidate is the normalized full key
     *  (used for the cross-product catalog dedup); the seen set is keyed on
     *  the singular form so plural/singular spelling variants collapse. */
    private void addGeneratedKeyword(List<ProductQueryCandidate> out, Set<String> seenSingular,
                                     String query, int band, String product) {
        String q = collapseWhitespace(query);
        if (q == null || q.isEmpty()) {
            return;
        }
        String key = normalizeProductQueryKey(q);
        String singular = singularQueryKey(key);
        if (singular == null || !seenSingular.add(singular)) {
            return;
        }
        out.add(new ProductQueryCandidate(q, band, product, key));
    }

    private String normalizeProductQueryKey(String query) {
        String normalized = normalizeProductForValidation(query);
        return collapseWhitespace(normalized.replaceAll("[^a-z0-9]+", " "));
    }

    /** Collapses a normalized query key to its singular form for dedup:
     *  "holders" -> "holder", "jars" -> "jar", but "glass" and "vases" are
     *  left alone so unrelated words never collide. */
    private static String singularQueryKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        String k = key.trim();
        if (k.endsWith("ies")) {
            return k.substring(0, k.length() - 3) + "y";
        }
        if (k.endsWith("ss") || k.endsWith("ses")) {
            return k;
        }
        if (k.endsWith("s")) {
            return k.substring(0, k.length() - 1);
        }
        return k;
    }

    private boolean hasCommerceTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return ROLE_WORD_PATTERN.matcher(term.toLowerCase(Locale.ROOT)).find();
    }

    private boolean containsWholeWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        return (" " + text.trim().toLowerCase(Locale.ROOT) + " ")
                .contains(" " + word.trim().toLowerCase(Locale.ROOT) + " ");
    }

    /** The buyer word for one slot, cycling through BUYER_WORDS from that
     *  slot's index and skipping any keyword already inside the product phrase
     *  (a "crystal candle holder" slot never gets "buyer", "wholesaler",
     *  "importer", ... that would read "... supplier supplier"). */
    private String[] buyerWordForTerm(String term, int slotIndex) {
        int start = slotIndex % BUYER_WORDS.size();
        for (int i = 0; i < BUYER_WORDS.size(); i++) {
            String[] candidate = BUYER_WORDS.get((start + i) % BUYER_WORDS.size());
            if (!containsWholeWord(term, candidate[0])) {
                return candidate;
            }
        }
        return BUYER_WORDS.get(start);
    }

    /** Picks up to max diverse candidates from the generated pool within the
     *  frozen Firecrawl budget. Pass 1 always takes each product's EXACT
     *  phrasing (band 0, one per product, in input order). Pass 2 then walks
     *  the remaining candidates left-to-right alternating a close variant
     *  (band 1) with a commercial wholesale/role phrase (band 2/3) so the 6
     *  requests explore distinct commercial angles instead of 6 near-identical
     *  keywords. */
    private List<ProductQueryCandidate> selectDiverseQueries(
            List<ProductQueryCandidate> generated, int max) {
        List<ProductQueryCandidate> chosen = new ArrayList<>();
        if (generated.isEmpty()) {
            return chosen;
        }
        Set<String> presentedProducts = new HashSet<>();
        for (ProductQueryCandidate c : generated) {
            if (chosen.size() >= max) {
                break;
            }
            if (c.band == 0 && presentedProducts.add(c.product)) {
                chosen.add(c);
            }
        }
        boolean pickCommercial = false;
        for (ProductQueryCandidate c : generated) {
            if (chosen.size() >= max) {
                break;
            }
            if (chosen.contains(c)) {
                continue;
            }
            if (c.band == 1) {
                if (!pickCommercial) {
                    chosen.add(c);
                }
                pickCommercial = !pickCommercial;
            } else if (c.band == 2 || c.band == 3) {
                if (pickCommercial) {
                    chosen.add(c);
                }
                pickCommercial = !pickCommercial;
            }
        }
        return chosen;
    }

    /** Adds the commercial angle phrases (band 2) for a canonical base noun. */
    private void addBulkQueries(List<ProductQueryCandidate> out, Set<String> seenSingular,
                                String product, String baseNoun) {
        addGeneratedKeyword(out, seenSingular, baseNoun + " wholesale", 2, product);
        addGeneratedKeyword(out, seenSingular, baseNoun + " bulk", 2, product);
    }

    /** Adds the buyer/role phrases (band 3) for a canonical base noun. */
    private void addRoleQueries(List<ProductQueryCandidate> out, Set<String> seenSingular,
                                String product, String baseNoun) {
        for (String role : new String[]{"supplier", "wholesaler", "distributor", "importer"}) {
            addGeneratedKeyword(out, seenSingular, baseNoun + " " + role, 3, product);
        }
    }

    private void generateBowlQueries(List<ProductQueryCandidate> out, Set<String> seenSingular,
                                     String product, String normalized) {
        String core = "singing bowl";
        for (String mod : BOWL_STYLE_MODIFIERS) {
            if (containsWholeWord(normalized, mod)) {
                continue;
            }
            addGeneratedKeyword(out, seenSingular, mod + " " + core, 1, product);
        }
        for (String syn : BOWL_CORE_SYNONYMS) {
            if (containsPhrase(normalized, syn)) {
                continue;
            }
            addGeneratedKeyword(out, seenSingular, syn, 1, product);
        }
        addBulkQueries(out, seenSingular, product, core);
        addRoleQueries(out, seenSingular, product, core);
    }

    private void generateCandleHolderQueries(List<ProductQueryCandidate> out,
                                             Set<String> seenSingular,
                                             String product, String normalized) {
        boolean crystal = containsWholeWord(normalized, "crystal");
        String core = "candle holder";
        if (crystal) {
            addGeneratedKeyword(out, seenSingular, "crystal tealight holder", 1, product);
            addGeneratedKeyword(out, seenSingular, "crystal votive holder", 1, product);
            addGeneratedKeyword(out, seenSingular, "crystal candle stand", 1, product);
            addGeneratedKeyword(out, seenSingular, "crystal candle holder set", 1, product);
            addGeneratedKeyword(out, seenSingular, "natural crystal candle holder", 1, product);
        } else {
            for (String mod : CANDLE_HOLDER_STYLE_MODIFIERS) {
                if (containsWholeWord(normalized, mod)) {
                    continue;
                }
                addGeneratedKeyword(out, seenSingular, mod + " " + core, 1, product);
            }
            for (String syn : CANDLE_HOLDER_CLOSE_SYNONYMS) {
                if (containsPhrase(normalized, syn)) {
                    continue;
                }
                addGeneratedKeyword(out, seenSingular, syn, 1, product);
            }
        }
        String commercialCore = crystal ? "crystal candle holder" : core;
        addBulkQueries(out, seenSingular, product, commercialCore);
        addRoleQueries(out, seenSingular, product, commercialCore);
    }

    private void generateCandleQueries(List<ProductQueryCandidate> out,
                                       Set<String> seenSingular,
                                       String product, String normalized) {
        for (String phrase : CANDLE_STYLE_PHRASES) {
            if (containsPhrase(normalized, phrase)) {
                continue;
            }
            addGeneratedKeyword(out, seenSingular, phrase, 1, product);
        }
        addBulkQueries(out, seenSingular, product, "candles");
        addRoleQueries(out, seenSingular, product, "candle");
    }

    private void generateVotiveHolderQueries(List<ProductQueryCandidate> out,
                                             Set<String> seenSingular,
                                             String product, String normalized) {
        String core = "votive holder";
        boolean crystal = containsWholeWord(normalized, "crystal");
        if (crystal) {
            addGeneratedKeyword(out, seenSingular, "crystal " + core, 1, product);
        } else {
            for (String mod : VOTIVE_HOLDER_STYLE_MODIFIERS) {
                if (containsWholeWord(normalized, mod)) {
                    continue;
                }
                addGeneratedKeyword(out, seenSingular, mod + " " + core, 1, product);
            }
        }
        String commercialCore = crystal ? "crystal " + core : core;
        addBulkQueries(out, seenSingular, product, commercialCore);
        addRoleQueries(out, seenSingular, product, commercialCore);
    }

    private void generateTealightQueries(List<ProductQueryCandidate> out,
                                         Set<String> seenSingular,
                                         String product, String normalized) {
        boolean holderProduct = containsWholeWord(normalized, "holder")
                || containsWholeWord(normalized, "holders")
                || containsWholeWord(normalized, "stand")
                || containsWholeWord(normalized, "stands");
        String core = holderProduct ? "tealight holder" : "tealight candle";
        boolean crystal = containsWholeWord(normalized, "crystal");
        if (crystal) {
            addGeneratedKeyword(out, seenSingular, "crystal " + core, 1, product);
        } else {
            String[] mods = {"decorative", "glass", "crystal", "metal", "brass",
                    "handmade", "handcrafted", "ceramic", "wooden", "luxury"};
            for (String mod : mods) {
                if (containsWholeWord(normalized, mod)) {
                    continue;
                }
                addGeneratedKeyword(out, seenSingular, mod + " " + core, 1, product);
            }
        }
        if (!holderProduct) {
            addGeneratedKeyword(out, seenSingular, "tealight candles", 1, product);
            addGeneratedKeyword(out, seenSingular, "tea light candles", 1, product);
        }
        String commercialCore = crystal ? "crystal " + core : core;
        addBulkQueries(out, seenSingular, product, commercialCore);
        addRoleQueries(out, seenSingular, product, commercialCore);
    }

    private void generateLanternQueries(boolean holder, List<ProductQueryCandidate> out,
                                        Set<String> seenSingular,
                                        String product, String normalized) {
        String[] mods = holder ? LANTERN_HOLDER_STYLE_MODIFIERS : LANTERN_STYLE_MODIFIERS;
        String core = holder ? "lantern holder" : "lantern";
        boolean wallRequired = containsWholeWord(normalized, "wall")
                || containsWholeWord(normalized, "walls");
        String variantCore = wallRequired ? "wall " + core : core;
        for (String mod : mods) {
            if (containsWholeWord(normalized, mod)) {
                continue;
            }
            addGeneratedKeyword(out, seenSingular, mod + " " + variantCore, 1, product);
        }
        if (wallRequired) {
            addGeneratedKeyword(out, seenSingular, "wall mounted " + core, 1, product);
        }
        String commercialCore = wallRequired ? variantCore : core;
        addBulkQueries(out, seenSingular, product, commercialCore);
        addRoleQueries(out, seenSingular, product, commercialCore);
    }

    private void generateJarQueries(List<ProductQueryCandidate> out, Set<String> seenSingular,
                                    String product, String normalized) {
        boolean handleRequired = containsWholeWord(normalized, "handle")
                || containsWholeWord(normalized, "handles");
        if (handleRequired) {
            addGeneratedKeyword(out, seenSingular, "decorative jars with handles", 1, product);
            addBulkQueries(out, seenSingular, product, "decorative jars with handles");
            addRoleQueries(out, seenSingular, product, "decorative jars with handles");
            return;
        }
        for (String phrase : JAR_STYLE_PHRASES) {
            if (containsPhrase(normalized, phrase)) {
                continue;
            }
            addGeneratedKeyword(out, seenSingular, phrase, 1, product);
        }
        addBulkQueries(out, seenSingular, product, "decorative jar");
        addRoleQueries(out, seenSingular, product, "decorative jar");
    }

    /** Generic home-decor generator for products without a dedicated reference
     *  dictionary (e.g. "Wooden Wall Decor", "Metal Home Decor"): craft/quality
     *  modifiers on the user's own product, "for home decor" framing and the
     *  literal wall-decor -> wall-art synonym. Wholesale/role phrases are not
     *  bolted on here because the generic terms are already decorated, rarely
     *  unique, and commercial doubling adds noise. */
    private void generateGenericDecorQueries(List<ProductQueryCandidate> out,
                                             Set<String> seenSingular,
                                             String product, String normalized) {
        for (String mod : GENERIC_DECOR_MODIFIERS) {
            if (containsWholeWord(normalized, mod)) {
                continue;
            }
            addGeneratedKeyword(out, seenSingular, mod + " " + product, 1, product);
        }
        addGeneratedKeyword(out, seenSingular, product + " for home decor", 1, product);
        String swapped = normalized.replace(" wall decor", " wall art");
        if (!swapped.equals(normalized)) {
            addGeneratedKeyword(out, seenSingular, swapped, 1, product);
            addGeneratedKeyword(out, seenSingular, "handmade " + swapped, 1, product);
        }
        addGeneratedKeyword(out, seenSingular, normalized + " home decor", 1, product);
    }

    /** The in-family synonym phrases of the user's product: the family it
     *  belongs to (bowls / lanterns / candles / jars / home decor), chosen with
     *  the same detection used by the home-decor category gate. */
    private List<String> relatedFamilyTerms(String product) {
        String p = normalizeProductForValidation(product);
        Set<String> words = new HashSet<>(tokenizeWords(p));
        if (isBowlFamilyProduct(p, words) || isSoundHealingProduct(p)) {
            // Singing-bowl phrases are identity-preserving (tibetan / himalayan
            // / crystal / meditation / sound-healing singing bowls all stay
            // "singing bowls"), so the family terms are valid discovery variants.
            return PRODUCT_FAMILY_TERMS.get(0);
        }
        if (isLanternProduct(p)) {
            Set<String> required = requiredAttributesOf(p, words, 1);
            if (required.contains("wall")) {
                // A "wall lantern" search only fires attribute-preserving variants
                // ("outdoor wall lantern", "wall mounted lantern", ...) - never
                // the bare metal/glass/moroccan/hanging lantern category, which
                // is a DIFFERENT product (requirement 6).
                return attributePreservingVariants(p, 1, required);
            }
            return PRODUCT_FAMILY_TERMS.get(1);
        }
        if (isCandleProduct(p, words)) {
            boolean holderProduct = words.contains("holder") || words.contains("holders")
                    || words.contains("stand") || words.contains("stands");
            if (holderProduct && requiredAttributesOf(p, words, 2).contains("crystal")) {
                return STRONG_CRYSTAL_CANDLE_HOLDER_PHRASES;
            }
            boolean votiveProduct = words.contains("votive") || words.contains("votives");
            boolean tealightProduct = words.contains("tealight") || words.contains("tealights")
                    || p.contains("tea light");
            if (holderProduct && votiveProduct) {
                return PRODUCT_FAMILY_TERMS.get(3);
            }
            if (holderProduct && tealightProduct) {
                return PRODUCT_FAMILY_TERMS.get(2).stream()
                        .filter(phrase -> containsWholeWord(phrase, "tealight"))
                        .toList();
            }
            if (holderProduct) {
                return PRODUCT_FAMILY_TERMS.get(2).stream()
                        .filter(phrase -> containsWholeWord(phrase, "holder")
                                || containsWholeWord(phrase, "holders")
                                || containsWholeWord(phrase, "stand")
                                || containsWholeWord(phrase, "stands"))
                        .toList();
            }
            if (votiveProduct) {
                return PRODUCT_FAMILY_TERMS.get(2).stream()
                        .filter(phrase -> (containsWholeWord(phrase, "votive")
                                || containsWholeWord(phrase, "votives"))
                                && (containsWholeWord(phrase, "candle")
                                || containsWholeWord(phrase, "candles")))
                        .toList();
            }
            if (tealightProduct) {
                return PRODUCT_FAMILY_TERMS.get(2).stream()
                        .filter(phrase -> (containsWholeWord(phrase, "tealight")
                                || containsWholeWord(phrase, "tealights"))
                                && (containsWholeWord(phrase, "candle")
                                || containsWholeWord(phrase, "candles")))
                        .toList();
            }
            return PRODUCT_FAMILY_TERMS.get(2).stream()
                    .filter(phrase -> (containsWholeWord(phrase, "candle")
                            || containsWholeWord(phrase, "candles"))
                            && !containsWholeWord(phrase, "holder")
                            && !containsWholeWord(phrase, "holders")
                            && !containsWholeWord(phrase, "stand")
                            && !containsWholeWord(phrase, "stands")
                            && !containsWholeWord(phrase, "votive")
                            && !containsWholeWord(phrase, "votives")
                            && !containsWholeWord(phrase, "tealight")
                            && !containsWholeWord(phrase, "tealights"))
                    .toList();
        }
        if (isDecorativeJarProduct(words)) {
            if (requiredAttributesOf(p, words, 4).contains("handle")) {
                return PRODUCT_FAMILY_TERMS.get(4).stream()
                        .filter(phrase -> containsWholeWord(phrase, "handle")
                                || containsWholeWord(phrase, "handles"))
                        .toList();
            }
            return PRODUCT_FAMILY_TERMS.get(4);
        }
        if (isHomeDecorProduct(p, words)) {
            return homeDecorFamilyTerms(p, words);
        }
        // Any other recognised home-goods product: the alternate NAMES of the
        // user's own product ("Vase" -> "glass vases", "flower vases", ...), so
        // the trailing query slots search the same product under the words
        // retailers actually use without ever widening to a different product.
        if (isHomeGoodProduct(p, words)) {
            return homeGoodAlternateNames(product);
        }
        return List.of();
    }

    private List<String> homeDecorFamilyTerms(String normalized, Set<String> words) {
        boolean wallProduct = words.contains("wall") || words.contains("walls")
                || normalized.contains("wall decor") || normalized.contains("wall art");
        if (!wallProduct) {
            return PRODUCT_FAMILY_TERMS.get(5);
        }
        List<String> variants = new ArrayList<>();
        variants.add(normalized);
        if (normalized.contains("wall decor")) {
            variants.add(normalized.replace("wall decor", "wall art"));
        }
        if (normalized.contains("wall art")) {
            variants.add(normalized.replace("wall art", "wall decor"));
        }
        return variants.stream().filter(phrase -> phrase != null && !phrase.isBlank())
                .distinct().collect(Collectors.toList());
    }

    /** The home-decor family index (0=bowls, 1=lanterns, 2=candles/holders,
     *  4=jars, 5=home decor, 3=votive reserved) of the product, or -1 when it
     *  belongs to no supported family. Drives the per-family RELATED-CATEGORY
     *  buyer-profile queries (RELATED_CATEGORY_PROFILES). */
    private int familyIndexFor(String product) {
        String p = normalizeProductForValidation(product);
        Set<String> words = new HashSet<>(tokenizeWords(p));
        if (isBowlFamilyProduct(p, words) || isSoundHealingProduct(p)) {
            return 0;
        }
        if (isLanternProduct(p)) {
            return 1;
        }
        if ((words.contains("votive") || words.contains("votives"))
                && (words.contains("holder") || words.contains("holders"))) {
            return 3;
        }
        if (isCandleProduct(p, words)) {
            return 2;
        }
        if (isDecorativeJarProduct(words)) {
            return 4;
        }
        if (isHomeDecorProduct(p, words)) {
            return 5;
        }
        if (isHomeGoodProduct(p, words)) {
            // Generic home-goods family: the buyer categories of the home-decor
            // family, which is where a vase / rug / mirror retailer is found.
            return 5;
        }
        return -1;
    }

    /** The two RELATED-CATEGORY buyer profiles of the product's family (A, B);
     *  a generic gift/home-goods pair is returned when the product has no
     *  supported family so the slot still searches real retailers. */
    private List<List<String>> categoryProfilesFor(String product) {
        int familyIdx = familyIndexFor(product);
        if (familyIdx < 0) {
            return List.of(
                    List.of("gift", "boutique", "home goods"),
                    List.of("handmade", "artisan", "decor"));
        }
        return List.of(
                RELATED_CATEGORY_PROFILES.get(familyIdx * 2),
                RELATED_CATEGORY_PROFILES.get(familyIdx * 2 + 1));
    }

    /**
     * The RELATED-CATEGORY business types of the product's family - the
     * ProductSpec.relatedCategoryPhrases the validator accepts on WITHOUT any
     * product evidence. Scoped to the SAME family the product was detected with
     * (familyIndexFor), so a singing-bowl search accepts yoga / meditation /
     * wellness / spa / sound-healing businesses and never a lantern or
     * candle store. An unsupported product returns no related categories, so it
     * can only ever be proven by its own exact phrase.
     */
    private List<String> relatedCategoryBusinessPhrasesFor(String product) {
        int familyIdx = familyIndexFor(product);
        if (familyIdx < 0 || familyIdx >= RELATED_CATEGORY_BUSINESS_PHRASES.size()) {
            return List.of();
        }
        return withPlurals(RELATED_CATEGORY_BUSINESS_PHRASES.get(familyIdx));
    }

    /** The ordered, commercially useful product phrases fired for one user
     *  product: the USER'S OWN phrasing first, then the next in-family
     *  synonyms (e.g. for "Singing Bowls": "Tibetan Singing Bowls", "Himalayan
     *  Singing Bowls", "Crystal Singing Bowls", "Meditation Bowls" and "Sound
     *  Healing Bowls"). Only whole meaningful product phrases are used - never
     *  loose tokens ("singing", "bowl") and never generic home-decor
     *  replacements. The query planner (buildProductPlan) cycles its 6 slots
     *  through this list - the user's exact phrasing first, then the in-family
     *  synonyms - so every slot fires a DIFFERENT product variant, no slot
     *  repeats the user's overly-specific exact phrasing (which can return 0
     *  results) and no query stuffs all synonyms together. */
    private List<String> commercialProductPhrases(String product) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (product != null && !product.isBlank()) {
            String key = product.trim().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(product.trim());
            }
        }
        List<String> family = relatedFamilyTerms(product);
        for (String phrase : family) {
            String key = phrase.trim().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(phrase.trim());
            }
        }
        return out;
    }

    private List<String> productMatchPhrasesFor(String product, ProductSpec spec) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String normalized = normalizeProductForValidation(product);
        // The entered phrasing AND its plural defensive forms, so a "Vase"
        // search also matches a page that only ever writes "vases".
        for (String form : withPlurals(List.of(normalized == null ? "" : normalized))) {
            String key = normalizeProductForValidation(form);
            if (key != null && !key.isBlank() && seen.add(key)) {
                out.add(key);
            }
        }
        if (spec == null || spec.strongPhrases.isEmpty()) {
            return out;
        }
        for (String phrase : spec.strongPhrases) {
            String key = normalizeProductForValidation(phrase);
            if (key != null && !key.isBlank() && seen.add(key)) {
                out.add(key);
            }
        }
        for (String phrase : relatedFamilyTerms(normalized)) {
            String key = normalizeProductForValidation(phrase);
            if (key != null && !key.isBlank() && seen.add(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private List<Slot> buildProductPlan(String prod) {
        return buildProductPlan(prod, MAX_FIRECRAWL_REQUESTS_PER_SEARCH);
    }

    private List<Slot> buildProductPlan(String prod, int slotCount) {
        List<String> phrases = commercialProductPhrases(prod);
        if (phrases.isEmpty()) {
            phrases = new ArrayList<>(Collections.singletonList(prod));
        }

        List<String> distinctPhrases = new ArrayList<>();
        Set<String> stems = new HashSet<>();
        for (String phrase : phrases) {
            if (stems.add(singularStemOf(phrase))) {
                distinctPhrases.add(phrase);
            }
        }

        String exactPhrase = distinctPhrases.get(0);
        List<Slot> slots = new ArrayList<>(Math.max(0, slotCount));
        Set<String> usedPairs = new HashSet<>();

        for (int i = 0; i < slotCount; i++) {
            // The plan fires the USER'S OWN product once per buyer intent
            // (shop, store, retailer, wholesaler, supplier, distributor,
            // importer) in exactly that order, then the WORDLESS
            // "product + city + state" form, then product-family variants. The
            // location template is added later by the phase planner, so the
            // first intents land on the REQUESTED city and the following ones
            // on OTHER cities of the SAME state during the expansion.
            boolean locationOnly = i == LOCATION_ONLY_PLAN_SLOT;
            boolean intentSlot = i < LOCATION_BUYER_INTENTS.length;
            String phrase = intentSlot || locationOnly ? exactPhrase
                    : productVariantAt(distinctPhrases, i - LOCATION_ONLY_PLAN_SLOT - 1);

            String intent = "";
            if (locationOnly) {
                usedPairs.add(planPairKey(phrase, ""));
            } else {
                int intentIndex = i % LOCATION_BUYER_INTENTS.length;
                intent = LOCATION_BUYER_INTENTS[intentIndex];
                // Guarantee every slot is a UNIQUE (product phrase, intent)
                // pair even for a product family that only yields one distinct
                // phrase - the intent advances instead of repeating a request.
                for (int guard = 0;
                        !usedPairs.add(planPairKey(phrase, intent))
                                && guard < LOCATION_BUYER_INTENTS.length; guard++) {
                    intentIndex = (intentIndex + 1) % LOCATION_BUYER_INTENTS.length;
                    intent = LOCATION_BUYER_INTENTS[intentIndex];
                }
            }
            slots.add(new Slot(phrase, intent, businessTypeForIntent(intent), null));
        }
        return slots;
    }

    private static String planPairKey(String phrase, String intent) {
        return (phrase == null ? "" : phrase.toLowerCase(Locale.ROOT)) + "\u0000"
                + (intent == null ? "" : intent.toLowerCase(Locale.ROOT));
    }

    /**
     * The RELATED-INDUSTRY / USE-CASE buyer queries for the entered products:
     * the USER'S OWN product phrase paired with each adjacent buyer category of
     * its product family (RelatedBuyerCategories), so e.g. "singing bowls" also
     * searches "yoga studios", "meditation centers" and "sound healing
     * practitioners". Nothing is hardcoded per product - the categories come
     * from the product-to-related-buyer-category mapping, which normalizes
     * "Singing Bowls" / "singing bowl" / "Tibetan Singing Bowls" alike.
     *
     * <p>These are generated ALONGSIDE the plan of {@link #buildProductPlan},
     * which is left completely untouched: every existing importer /
     * distributor / wholesaler / buyer / retailer query is still generated and
     * still fires first.
     *
     * <p>Every entry targets the location the user ACTUALLY requested - the
     * requested city, else the requested state, else the requested country -
     * and never a city from the other-cities-of-the-state expansion pool, so an
     * added query can never silently search somewhere the user did not ask for.
     * Identical (product phrase, category) pairs are de-duplicated here, and a
     * category that merely repeats an existing {@link #LOCATION_BUYER_INTENTS}
     * word is skipped so the added phase can never re-send a plan query. The
     * shared firedQueries set in executeCombosSerially still guards the exact
     * final query string.
     */
    private List<StageEntry> relatedBuyerCategoryEntries(List<String> products, List<String> cities,
                                                         String stateValue, String countryWord) {
        List<StageEntry> entries = new ArrayList<>();
        boolean stateGiven = stateValue != null && !stateValue.isBlank();

        // The requested city/cities, in the order the user supplied them. Only
        // ever the REQUESTED city - the other-cities-of-the-state pool used by
        // the expansion phase is deliberately not consulted here.
        List<String> requestedCities = new ArrayList<>();
        Set<String> requestedCitySeen = new HashSet<>();
        for (String c : cities == null ? List.<String>of() : cities) {
            String collapsed = collapseWhitespace(c);
            if (collapsed != null && requestedCitySeen.add(collapsed.toLowerCase(Locale.ROOT))) {
                requestedCities.add(collapsed);
            }
        }

        Set<String> usedPairs = new LinkedHashSet<>();
        for (String prod : products) {
            if (entries.size() >= MAX_RELATED_BUYER_CATEGORY_QUERIES) {
                break;
            }
            List<String> categories = RelatedBuyerCategories.categoriesFor(prod);
            if (categories.isEmpty()) {
                continue;
            }
            String phrase = collapseWhitespace(prod);
            if (phrase == null || phrase.isBlank()) {
                continue;
            }
            for (String category : categories) {
                if (entries.size() >= MAX_RELATED_BUYER_CATEGORY_QUERIES) {
                    break;
                }
                if (category == null || category.isBlank()
                        || !usedPairs.add(planPairKey(phrase, category))
                        || repeatsBuyerIntent(category)) {
                    continue;
                }
                QueryFamily family;
                LocTemplate loc;
                String targetCity = null;
                if (!requestedCities.isEmpty()) {
                    family = QueryFamily.CITY;
                    targetCity = requestedCities.get(entries.size() % requestedCities.size());
                    loc = stateGiven ? LocTemplate.CITY_STATE_FULL : LocTemplate.CITY_ONLY;
                } else {
                    family = stateGiven ? QueryFamily.STATE : QueryFamily.NATIONWIDE;
                    loc = stateGiven ? LocTemplate.STATE_FULL : LocTemplate.NONE;
                }
                Combo combo = new Combo(phrase, phrase, stateValue, targetCity, countryWord);
                entries.add(new StageEntry(family, loc,
                        new String[]{category, RELATED_BUYER_CATEGORY_TYPE},
                        null, List.of(combo),
                        loc == LocTemplate.NONE ? countryWord : null, null, true));
                logger.info("RELATED_QUERY: {} | CATEGORY: {} | LOCATION: {}",
                        buildDiversifiedQuery(combo, entries.get(entries.size() - 1)),
                        category, targetCity != null
                                ? targetCity + (stateGiven ? ", " + stateValue : "")
                                : (stateGiven ? stateValue : countryWord));
            }
        }
        return entries;
    }

    /** True when a related category is really just one of the plan's existing
     *  buyer-intent words ("shop" / "store" / ...), compared on the same
     *  singular stem the plan uses so "shops" and "shop" count as one. */
    private boolean repeatsBuyerIntent(String category) {
        String stem = singularStemOf(category);
        for (String intent : LOCATION_BUYER_INTENTS) {
            if (singularStemOf(intent).equals(stem)) {
                return true;
            }
        }
        return false;
    }

    /** The business type a buyer-intent word represents. A wordless
     *  location-only slot is a plain local buyer, never an assumed role. */
    private static String businessTypeForIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return "Local Buyer";
        }
        return switch (intent) {
            case "importer" -> "Importer";
            case "exporter" -> "Exporter";
            case "distributor" -> "Distributor";
            case "wholesaler", "wholesale" -> "Wholesaler";
            case "bulk" -> "Bulk Supplier";
            case "supplier" -> "Supplier";
            case "manufacturer" -> "Manufacturer";
            case "trading" -> "Trading Company";
            case "dealer" -> "Dealer";
            case "stockist" -> "Stockist";
            case "vendor" -> "Vendor";
            case "b2b" -> "B2B Buyer";
            case "buyer" -> "Buyer";
            default -> "Retailer";
        };
    }

    private String productVariantAt(List<String> phrases, int offset) {
        if (phrases.size() == 1) {
            return phrases.get(0);
        }
        return phrases.get(1 + Math.floorMod(offset, phrases.size() - 1));
    }

    /**
     * Diagonal interleave of the per-product plans into a sequence capped at
     * {@code max}. Each output advances the selected product and the slot tier,
     * preserving per-product request fairness while exposing the complete mix
     * of exact-role, category and contact-intent strategies across products.
     */
    private static List<Slot> interleavePlans(List<List<Slot>> perProductSlots, int max) {
        List<Slot> interleaved = new ArrayList<>();
        if (perProductSlots.isEmpty() || max <= 0) {
            return interleaved;
        }
        int productCount = perProductSlots.size();
        for (int outputIndex = 0; outputIndex < max; outputIndex++) {
            List<Slot> slotPlan = perProductSlots.get(outputIndex % productCount);
            if (outputIndex < slotPlan.size()) {
                interleaved.add(slotPlan.get(outputIndex));
            }
        }
        return interleaved;
    }

    // Returns the singular stem of a phrase ("wall lanterns" -> "wall lantern")
    // so plural defensive forms never count as DISTINCT variants when the slot
    // plan is filled (buildProductPlan). Conservative: "glass", "is" and other
    // words genuinely ending in "ss"/short "s" are left untouched.
    private String singularStemOf(String phrase) {
        String trimmed = collapseWhitespace(phrase).trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > 4 && trimmed.endsWith("s") && !trimmed.endsWith("ss")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String keyOf(String phrase) {
        return phrase == null || phrase.isBlank()
                ? null : collapseWhitespace(phrase).toLowerCase(Locale.ROOT);
    }

    // Sound-healing / meditation-therapy phrases in the supported wellness &
    // home-decor category. Only these explicit phrases are accepted - a bare
    // "yoga", "meditation" or "healing" stays out so yoga classes/mats or
    // generic therapy clinics are never pulled in.
    private static final String[] SOUND_HEALING_TERMS = {
            "sound healing", "sound therapy", "healing sound",
            "yoga meditation", "sound healing therapy"
    };

    // Event / non-home venues that are never home decor even when a decor or
    // candle/lantern word appears in the product (party/wedding supplies).
    private static final Set<String> EVENT_NON_DECOR_WORDS = Set.of(
            "party", "event", "wedding", "banquet", "celebration", "occasion",
            "festival", "birthday"
    );

    // Product words that point outside the home-decor category even when a
    // decor word is present (car decor, cake decorations, clothing, hotels...).
    private static final Set<String> NON_HOME_DECOR_WORDS = Set.of(
            "car", "auto", "automobile", "vehicle", "jeep", "motorcycle",
            "truck", "boat", "rv",
            "cake", "cupcake", "baking", "bakery",
            "clothing", "clothes", "apparel", "garment", "garments", "jeans",
            "pants", "shirt", "dress", "shoe", "shoes",
            "hotel", "motel", "inn", "resort", "restaurant", "pizza", "food",
            "snack", "nail", "eyelash", "eyelashes", "lash", "lashes", "hair",
            "makeup", "cosmetic", "cosmetics", "beauty"
    );

    // ------------------------------------------------------------------
    // HOME-GOODS PRODUCT COVERAGE. The nine reference families above are the
    // hand-tuned dictionary; a real exporter also searches plain home-decor /
    // home-goods product nouns ("vase", "area rug", "wall mirror", "macrame
    // wall hanging", "reed diffuser", ...). Every noun listed here is a
    // PHYSICAL home-goods product a retailer/wholesaler/importer actually stocks,
    // so it passes the category gate and gets the same product+location search.
    // NON_HOME_DECOR_WORDS and EVENT_NON_DECOR_WORDS still veto the categories
    // that are not home goods (cars, clothing, hotels, party supplies), so
    // widening this list can never pull an unrelated product back in.
    // ------------------------------------------------------------------
    private static final Set<String> HOME_GOOD_PRODUCT_NOUNS = Set.of(
            // vessels & tabletop
            "vase", "vases", "urn", "pitcher", "pitchers", "carafe",
            "carafes", "teapot", "teapots", "tray", "trays", "platter",
            "platters", "bowl", "bowls", "basin", "dish", "dishes", "mug",
            "mugs", "cup", "cups", "coaster", "coasters", "napkin", "napkins",
            "placemat", "placemats", "tablecloth", "tablecloths", "runner",
            "runners", "cutlery", "crockery", "porcelain", "ceramic", "pottery",
            // storage & organisation
            "basket", "baskets", "planter", "planters", "pot", "pots",
            "cachepot", "cachepots", "organizer", "organizers", "organiser",
            "box", "boxes", "shelf", "shelves", "rack", "racks", "bin", "bins",
            "hamper", "crate", "crates", "trunk", "bucket", "buckets",
            "jar", "jars", "canister", "canisters", "holder", "holders",
            "stand", "stands", "storage", "glass",
            // wall & art
            "mirror", "mirrors", "frame", "frames", "art", "arts", "painting",
            "paintings", "print", "prints", "poster", "posters", "canvas",
            "canvases", "tapestry", "tapestries", "macrame", "hanging",
            "hangings", "sculpture", "sculptures", "figurine", "figurines",
            "ornament", "ornaments", "decor", "decoration", "decorations",
            "sign", "signs",
            // textiles & soft furnishing
            "rug", "rugs", "carpet", "carpets", "mat", "mats", "doormat",
            "pillow", "pillows", "cushion", "cushions", "throw", "throws",
            "quilt", "quilts", "blanket", "blankets", "bedsheet", "bedding",
            "linen", "linens", "towel", "towels", "curtain", "curtains",
            "cover", "covers", "slipcover", "slipcovers",
            "drape", "drapes", "valance", "chair", "furniture", "headboard",
            "bedside", "lamp", "lamps", "light", "lights", "sconce", "sconces",
            "string",
            // craft & wellness decor
            "diffuser", "diffusers", "incense", "burner", "burners", "candle",
            "candles", "wick", "wicks", "wax", "mushroom", "mushrooms",
            "bracelet", "necklace", "ring", "malachite", "chakra", "selenite",
            "amethyst", "quartz", "crystal", "crystals", "stone", "stones",
            "reed", "reeds", "aroma", "scent",
            // seasonal & garden
            "wreath", "wreaths", "garland", "garlands", "ornamental", "faux",
            "floral", "botanical", "succulent", "cactus", "terrarium",
            "birdhouse", "solar", "fairy", "garden", "outdoor", "mailbox",
            "clock", "clocks", "bookend", "bookends", "lantern", "lanterns"
    );

    // ALTERNATE PRODUCT NAMES used to find the SAME physical product under the
    // different words retailers actually use. Keyed by the singular product noun
    // of the entered product; the values are the commercially real phrasings that
    // surface real shops. These feed BOTH the fired search queries (the trailing
    // synonym slots of buildProductPlan) and the product-relevance phrase list,
    // so a "Vase" search also finds a site titled "Glass Vases Wholesale" and
    // vice versa. A product with no entry falls back to the generic
    // decorative/plural forms generated by homeGoodAlternateNames.
    private static final Map<String, List<String>> PRODUCT_ALTERNATE_NAMES = Map.ofEntries(
            Map.entry("vase", List.of("glass vases", "ceramic vases", "flower vases",
                    "decorative vases", "bud vases", "stone vases")),
            Map.entry("mirror", List.of("wall mirrors", "decorative mirrors",
                    "round mirrors", "mirror frames", "bathroom mirrors")),
            Map.entry("frame", List.of("picture frames", "photo frames", "wall frames",
                    "art frames", "gallery frame sets")),
            Map.entry("rug", List.of("area rugs", "decorative rugs", "modern rugs",
                    "wool rugs")),
            Map.entry("carpet", List.of("area rugs", "decorative rugs", "wool rugs")),
            Map.entry("pillow", List.of("throw pillows", "decorative pillows",
                    "accent pillows", "pillow covers")),
            Map.entry("cushion", List.of("decorative cushions", "throw pillows",
                    "seat cushions", "cushion pads")),
            Map.entry("planter", List.of("plant pots", "flower pots", "ceramic planters",
                    "indoor planters", "cachepots")),
            Map.entry("pot", List.of("plant pots", "flower pots", "ceramic pots",
                    "cachepots", "clay pots")),
            Map.entry("basket", List.of("wicker baskets", "storage baskets",
                    "decorative baskets", "seagrass baskets")),
            Map.entry("tray", List.of("decorative trays", "serving trays",
                    "wall trays", "catchall trays")),
            Map.entry("runner", List.of("table runners", "decorative table runners")),
            Map.entry("macrame", List.of("macrame wall hanging", "macrame hangers",
                    "woven wall hanging", "macrame plant hanger")),
            Map.entry("hanging", List.of("wall hangings", "macrame wall hanging",
                    "woven wall hanging", "hanging planters")),
            Map.entry("art", List.of("wall art", "wall art prints", "art prints",
                    "canvas prints", "decorative art")),
            Map.entry("print", List.of("art prints", "wall art prints",
                    "canvas prints", "decorative prints")),
            Map.entry("tapestry", List.of("wall tapestries", "decorative tapestries",
                    "woven wall hanging")),
            Map.entry("diffuser", List.of("reed diffusers", "home fragrance diffusers",
                    "aroma diffusers", "essential oil diffusers")),
            Map.entry("incense", List.of("incense holders", "incense burners",
                    "incense sticks")),
            Map.entry("burner", List.of("incense burners", "ceramic incense holders",
                    "oil burners")),
            Map.entry("quilt", List.of("decorative quilts", "patchwork quilts",
                    "quilted throws")),
            Map.entry("throw", List.of("decorative throws", "fleece throws",
                    "quilted throws")),
            Map.entry("blanket", List.of("decorative blankets", "fleece blankets",
                    "quilted blankets")),
            Map.entry("curtain", List.of("window curtains", "decorative curtains",
                    "curtain panels", "drapes")),
            Map.entry("drape", List.of("decorative drapes", "window drapes",
                    "curtain panels")),
            Map.entry("linen", List.of("bed linens", "table linens", "linen sets")),
            Map.entry("bedding", List.of("bed linens", "bedding sets", "duvet covers")),
            Map.entry("napkin", List.of("table napkins", "linen napkins",
                    "cloth napkins")),
            Map.entry("placemat", List.of("placemats", "table placemats",
                    "woven placemats")),
            Map.entry("wreath", List.of("decorative wreaths", "door wreaths",
                    "wall wreaths", "dried flower wreaths")),
            Map.entry("garland", List.of("decorative garlands", "string garlands",
                    "wall garlands")),
            Map.entry("terrarium", List.of("glass terrariums", "plant terrariums",
                    "geometric terrariums")),
            Map.entry("sculpture", List.of("decorative sculptures", "garden sculptures",
                    "tabletop sculptures")),
            Map.entry("figurine", List.of("decorative figurines", "collectible figurines",
                    "table figurines")),
            Map.entry("ornament", List.of("decorative ornaments", "wall ornaments",
                    "christmas ornaments")),
            Map.entry("crystal", List.of("crystal decor", "healing crystals",
                    "decorative crystals", "crystal ornaments")),
            Map.entry("stone", List.of("decorative stones", "marble decor",
                    "stoneware decor")),
            Map.entry("bookend", List.of("bookends", "decorative bookends")),
            Map.entry("bookends", List.of("decorative bookends", "wooden bookends")),
            Map.entry("clock", List.of("wall clocks", "decorative clocks")),
            Map.entry("ring", List.of("stone rings", "crystal rings", "signet rings")),
            Map.entry("necklace", List.of("stone necklaces", "crystal necklaces",
                    "layered necklaces")),
            Map.entry("bracelet", List.of("stone bracelets", "crystal bracelets",
                    "beaded bracelets"))
    );

    // Safe commercial qualifiers used to derive extra alternate names for a
    // recognised product noun that has no explicit PRODUCT_ALTERNATE_NAMES
    // entry. They describe the SAME product, never a different one, so they can
    // be combined with any home-goods noun.
    private static final String[] HOME_GOOD_NAME_QUALIFIERS = {
            "decorative", "home decor", "handmade", "artisan", "modern", "luxury"
    };

    // Modifier words that may sit IN FRONT of a product noun without being part
    // of it, so the alternate-name table can still be used for
    // "flower vase" / "area rug" / "clay pot". A word that is neither a modifier
    // nor a product noun (e.g. "resin", "reed", "stained", "covers") means the
    // entered phrase is not a bare "<modifier> <noun>" product, and the map is
    // not used for it.
    private static final Set<String> HOME_GOOD_PRODUCT_MODIFIERS = Set.of(
            "flower", "glass", "ceramic", "stone", "stoneware", "porcelain",
            "clay", "wooden", "wood", "metal", "brass", "iron", "copper",
            "marble", "crystal", "bamboo", "rattan", "wicker",
            "seagrass", "cotton", "wool", "linen", "velvet", "jute", "sisal",
            "leather", "area", "round", "rectangular", "square", "oval",
            "large", "small", "tall", "short", "mini", "jumbo", "oversized",
            "modern", "antique", "vintage", "rustic", "minimalist", "farmhouse",
            "bohemian", "luxury", "premium", "decorative", "handmade",
            "handcrafted", "artisan", "artisanal", "natural", "organic",
            "indoor", "outdoor", "table", "desk", "wall", "floor", "bedroom",
            "bathroom", "kitchen", "dining", "living", "office", "garden",
            "patio", "nursery", "baby", "kids", "set", "sets", "pack", "packs"
    );

    // Maximum alternate-name phrases kept for one product. Keeps the query plan
    // tight: the plan still fires every buyer intent on the user's own product
    // and only the trailing slots explore alternative product names.
    private static final int MAX_PRODUCT_ALTERNATE_NAMES = 4;

    // Home-decor family words that carry no "decor" substring; plain "decor",
    // "decorative", "decoration", "dÃ©cor" (normalized to "decor") and every
    // "home decor"/"wall decor" style phrase are matched by containment below.
    private static final String[] HOME_DECOR_PHRASES = {
            "home accessory", "home accessories", "home accent", "home accents",
            "home goods", "home furnishing", "home furnishings",
            "houseware", "housewares", "homeware", "homewares",
            "housewarming gift", "housewarming gifts",
            "festive lighting", "festive lights"
    };

    private static final Map<String, String> STATE_ABBREVIATIONS = buildStateAbbreviations();

    private static final String[] US_STATE_NAMES = {
        "alabama", "alaska", "arizona", "arkansas", "california", "colorado",
        "connecticut", "delaware", "florida", "georgia", "hawaii", "idaho",
        "illinois", "indiana", "iowa", "kansas", "kentucky", "louisiana",
        "maine", "maryland", "massachusetts", "michigan", "minnesota",
        "mississippi", "missouri", "montana", "nebraska", "nevada",
        "new hampshire", "new jersey", "new mexico", "new york",
        "north carolina", "north dakota", "ohio", "oklahoma", "oregon",
        "pennsylvania", "rhode island", "south carolina", "south dakota",
        "tennessee", "texas", "utah", "vermont", "virginia",
        "washington", "west virginia", "wisconsin", "wyoming",
        "district of columbia"
    };

    // ------------------------------------------------------------------
    // Individual business-location extraction. Used when the searched region is
    // too broad to be a useful per-buyer location (USA-wide / state-wide search)
    // or the searched city could not be confirmed: the buyer's REAL location is
    // pulled from the actual evidence, not guessed. Sources, best first:
    //   Google Business address in the search result -> search title/snippet ->
    //   schema.org structured data -> homepage footer -> homepage body ->
    //   contact page -> about page. Contact/about pages are fetched with plain
    //   HTTP (website scraping, NOT Firecrawl) so the 6-request search budget is
    //   never touched. A location is only saved when the website / search result
    //   actually contains it; otherwise it is recorded as Unknown (or the bare
    //   country when the country alone is positively known).
    // ------------------------------------------------------------------

    // Contact/About page paths tried (contact and about interleaved first)
    // only when the search result + homepage did not already reveal the buyer's
    // city/state. At most MAX_LOCATION_PAGE_FETCHES pages are fetched per buyer.
    private static final String[] LOCATION_PAGE_PATHS = {
        "/contact", "/about", "/contact-us", "/about-us", "/contactus",
        "/aboutus", "/pages/contact", "/pages/about", "/pages/contact-us",
        "/pages/about-us", "/get-in-touch", "/contact.html", "/about.html"
    };
    private static final int MAX_LOCATION_PAGE_FETCHES = 3;

    // "City ST ZIP" / "City, ST 12345" (comma optional) - the state MUST be a
    // real US state / Canadian province abbreviation, checked against the maps.
    private static final Pattern CITY_STATE_ZIP_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z .'\\-]{0,60}?)\\s*,?\\s+([A-Za-z]{2})\\s+\\d{5}(?:-\\d{4})?(?![A-Za-z0-9])");
    // "City, ST" without a ZIP - requires the comma so a bare
    // "Visit Dallas CA" / "Shipping Texas" cannot become a fake city match.
    private static final Pattern CITY_STATE_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z .'\\-]{0,60}?)\\s*,\\s*([A-Za-z]{2})(?![A-Za-z0-9])");
    // "City, FullStateName" (e.g. "Dallas, Texas").
    private static final Pattern CITY_FULL_STATE_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z .'\\-]{0,60}?)\\s*,\\s*("
                    + buildFullStateAlternation() + ")\\b", Pattern.CASE_INSENSITIVE);
    // "ST ZIP" (state + ZIP but no city) - establishes the state reliably.
    private static final Pattern STATE_ZIP_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z]{2})\\s+\\d{5}(?:-\\d{4})?(?![A-Za-z0-9])");

    // Every major US + Canadian city this app already knows (region/nationwide
    // maps), lowercased -> proper display name. Used to confirm a city candidate
    // so a random word is never saved as a buyer's location.
    private static final Map<String, String> KNOWN_US_CA_CITIES = buildKnownCityMap();

    // Reverse lookup: US state / Canadian province abbreviation -> full name.
    private static final Map<String, String> STATE_ABBR_TO_NAME = buildStateAbbrToNameMap();

    private static String buildFullStateAlternation() {
        List<String> names = new ArrayList<>();
        for (String s : US_STATE_NAMES) {
            names.add(s);
        }
        for (String s : CANADIAN_PROVINCE_NAMES) {
            names.add(s);
        }
        return names.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    }

    private static Map<String, String> buildKnownCityMap() {
        Map<String, String> map = new HashMap<>();
        for (List<String> list : REGION_MAJOR_CITIES.values()) {
            for (String city : list) {
                if (city != null && !city.isBlank()) {
                    map.put(city.toLowerCase(Locale.ROOT), city.trim());
                }
            }
        }
        for (String city : NATIONWIDE_MAJOR_CITIES) {
            if (city != null && !city.isBlank()) {
                map.put(city.toLowerCase(Locale.ROOT), city.trim());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> buildStateAbbrToNameMap() {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, String> e : STATE_ABBREVIATIONS.entrySet()) {
            map.put(e.getValue(), e.getKey());
        }
        for (Map.Entry<String, String> e : CANADIAN_PROVINCE_ABBREVIATIONS.entrySet()) {
            map.put(e.getValue(), e.getKey());
        }
        return Collections.unmodifiableMap(map);
    }

    @Autowired
    @Qualifier("firecrawlWebClient")
    private WebClient webClient;

    @Autowired
    private FirecrawlConfig firecrawlConfig;

    @Autowired
    private EmailDiscoveryService emailDiscoveryService;

    @Autowired
    private UsaVerificationService usaVerificationService;

    @Autowired
    private BuyerRepository buyerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<BuyerSearchResult> searchBuyers(String productName, int limit,
                                                 String state, String city, String country) {
        // HOME-DECOR CATEGORY GATE: every entered product must belong to one of
        // the supported home-decor families. An unrelated product (cars, clothing,
        // hotels, ...) stops immediately, BEFORE any Firecrawl request, so no
        // credits are ever spent on an invalid search.
        for (String entry : parseCommaSeparated(productName)) {
            if (!isSupportedHomeDecorProduct(entry)) {
                logger.warn("Unsupported product '{}' - stopping before any Firecrawl request", entry);
                throw new IllegalArgumentException(UNSUPPORTED_PRODUCT_MESSAGE);
            }
        }

        String apiKey = firecrawlConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Firecrawl API key is not configured. Set the FIRECRAWL_API_KEY environment variable.");
        }

        // Requested country (USA / Canada). Country-wide searches append the
        // country word to every query; per-buyer locations are then extracted
        // from each real website (never guessed).
        String countryValue = country == null || country.isBlank() ? "USA"
                : collapseWhitespace(country);
        if (!countryValue.equalsIgnoreCase("USA") && !countryValue.equalsIgnoreCase("Canada")) {
            throw new IllegalArgumentException(
                    "Unsupported country '" + countryValue + "' - supported values are USA and Canada.");
        }

        // A product made only of buyer-role words (wholesale/retailer/
        // distributor/supplier/...) has no meaningful product term, so it could
        // never satisfy the product gate - returning nothing avoids wasting
        // Firecrawl credits on a meaningless query.
        List<String> parsedProducts = parseCommaSeparated(productName);
        // All saved buyers of one search are associated with the product the
        // USER entered - not with the internal catalogue term each query used -
        // so "find buyers by product" and email discovery keep working.
        String userProductLabel = String.join(", ", parsedProducts);
        List<String> products = parsedProducts.stream()
                .filter(p -> !significantTerms(p).isEmpty())
                .collect(Collectors.toList());
        if (products.isEmpty()) {
            logger.warn("Product name '{}' has no meaningful product terms "
                    + "(only buyer-role words) - returning no results", productName);
            return List.of();
        }
        if (products.size() < parsedProducts.size()) {
            logger.warn("Dropping product entries with no meaningful product terms: {}",
                    parsedProducts.stream()
                            .filter(p -> significantTerms(p).isEmpty())
                            .collect(Collectors.toList()));
        }

        String stateValue = collapseWhitespace(state);
        List<String> cities = parseCommaSeparated(city);
        // Every CITY searched during THIS search (fire order, unique) - the
        // same city is never searched twice and the final summary logs the
        // total city coverage. Empty for state/country-wide searches.
        Set<String> searchedCities = new LinkedHashSet<>();
        Set<String> allQueries = new LinkedHashSet<>();

        // Deterministic 10-query plan: PRODUCT VARIANT + COMMERCIAL-INTENT BUYER
        // WORD + LOCATION. Every query is ONE product phrase (the user's exact
        // product or an in-family synonym) followed by an explicit commerce-
        // intent word and a location, so EVERY one of the 10 requests carries
        // stock-buying intent - a bare "<product> <city> <state>" query that
        // only surfaces accessory shops, classes or articles is never fired.
        // The ten slots pair a DIFFERENT in-family product variant with a
        // DIFFERENT commercial role word (store/shop/retailer/wholesale/
        // distributor/supplier/online store); weak or generic words that mostly
        // surface blogs, marketplaces and classifieds instead of stock-buying
        // businesses (buyer/sourcing/dealer/bulk/importer) are never fired.
        // For a requested city the plan splits into 4 requested-city slots
        // (the four selected-city rounds) followed by the 6 state-wide slots,
        // so ALL 10 web requests fire before any social search is even
        // considered (LinkedIn/Facebook share the same budget and only run
        // after the web plan is spent). Example for Product=Singing Bowls,
        // State=Texas, City=Dallas:
        //   R1: "singing bowls" retailer "Dallas" Texas
        //   R2: "tibetan singing bowls" wholesale "Dallas" Texas
        //   R3: "meditation singing bowls" store "Dallas" Texas
        //   R4: "crystal singing bowls" shop "Dallas" Texas
        //   R5: "himalayan singing bowls" wholesale Texas
        //   R6: "sound healing bowls" distributor Texas
        //   R7-R10: remaining in-family variants + supplier/online store/shop Texas
        // Only the selected city is ever searched (R1+R2); every remaining
        // request goes straight to the state/country-wide phase with a DIFFERENT
        // commercial intent word so wide-search recall is maximised - requests
        // are never spent rotating through OTHER cities.
        boolean canada = countryValue.equalsIgnoreCase("Canada");
        String countryWord = canada ? "Canada" : "USA";
        String countryNameFull = countryWord;

        String locationDesc = stateValue != null
                ? (cities.isEmpty() ? stateValue : cities + ", " + stateValue)
                : (cities.isEmpty() ? countryNameFull : String.join(", ", cities));

        // EVERY search - city, state or nationwide - uses the SAME hard budget
        // of exactly MAX_FIRECRAWL_REQUESTS_PER_SEARCH (10) Firecrawl requests
        // (first attempts AND 429 retries share the single 10-request budget).
        int requestBudget = MAX_FIRECRAWL_REQUESTS_PER_SEARCH;

        // Build the flat list of slots for all entered products from the
        // per-product deterministic plan (buildProductPlan): every slot pairs a
        // DIFFERENT in-family product variant with a DIFFERENT buyer-role word.
        // Each product fills up to the request budget (the global budget is
        // shared, so a multi-product search uses fewer slots per product). No
        // slot uses the old category-profile query form ("<cat> <cat> store
        // <product>") - every query stays natural: product variant + simple
        // buyer word + location.
// Round-robin INTERLEAVING across the entered products (requirement:
        // multi-product searches spread the whole budget across every product
        // instead of letting the first product consume all ten requests).
        List<List<Slot>> perProductSlots = new ArrayList<>();
        for (String prod : products) {
            List<Slot> plan = buildProductPlan(prod, requestBudget);
            if (!plan.isEmpty()) {
                perProductSlots.add(plan);
            }
        }
        List<Slot> allSlots = interleavePlans(perProductSlots, requestBudget);

        List<SearchPhase> phases = new ArrayList<>();

        if (cities.isEmpty()) {
            // No city selected: all queries are state-wide or country-wide.
            List<StageEntry> entries = new ArrayList<>();
            for (Slot slot : allSlots) {
                Combo combo = stateValue != null
                        ? new Combo(slot.phrase, slot.phrase, stateValue, null, countryNameFull)
                        : new Combo(slot.phrase, slot.phrase, null, null, countryNameFull);
                addSlotEntry(entries,
                        stateValue != null ? QueryFamily.STATE : QueryFamily.NATIONWIDE,
                        stateValue != null ? LocTemplate.STATE_FULL : LocTemplate.NONE,
                        new String[]{slot.phrase, slot.word, slot.type},
                        combo, stateValue != null ? null : countryWord, slot.profile);
                String builtQuery = buildDiversifiedQuery(combo, entries.get(entries.size() - 1));
                logger.info("QUERY: {} | PRODUCT: {} | BUYER TYPE: {} | LOCATION: {}",
                        builtQuery, slot.phrase, slot.word != null ? slot.word : "-", locationDesc);
            }
            phases.add(new SearchPhase(stateValue != null ? "state-wide"
                    : "nationwide", entries, MAX_FIRECRAWL_REQUESTS_PER_SEARCH));
        } else {
            // City selected: PROGRESSIVE GEOGRAPHIC BROADENING that stays INSIDE
            // the requested state. The single request budget is split into three
            // ordered phases, PROPORTIONALLY to the budget so that raising the
            // budget widens all three phases instead of leaving slots unused:
            //   PHASE 1 - the REQUESTED city/cities              (40% of slots)
            //   PHASE 2 - OTHER major cities of the SAME state  (30% of slots)
            //   PHASE 3 - the whole REQUESTED state              (30% of slots)
            // So the requested city+state is searched FIRST, and when it does
            // not yield enough relevant buyers the search widens to other
            // cities of the SAME state before it ever goes state-wide. A
            // requested city/state is NEVER broadened straight to the whole
            // USA: there is no nationwide phase for a city search. When no
            // state was given, every slot targets the requested city/cities
            // exclusively (exact-city results only).
            // Result priority equals the phases' order: verified EXACT_CITY
            // buyers first, then SAME_STATE - location metadata only RANKS
            int requestedCitySlots = Math.max(1, (int) Math.round(requestBudget * 0.40));
            int otherCitySlots = Math.max(1, (int) Math.round(requestBudget * 0.30));
            int stateStart = requestedCitySlots + otherCitySlots;
            boolean stateGiven = stateValue != null && !stateValue.isBlank();

            List<String> selectedCities = new ArrayList<>();
            Set<String> selectedCitySeen = new HashSet<>();
            for (String c : cities) {
                String cc = collapseWhitespace(c);
                if (cc != null && selectedCitySeen.add(cc.toLowerCase(Locale.ROOT))) {
                    selectedCities.add(cc);
                }
            }

            // PHASE 1 - the REQUESTED city/cities. Each slot keeps its distinct
            // (product phrase, buyer word); when several cities were given the
            // slots take them in order instead of repeating the first. When a
            // state was requested only the first requestedCitySlots slots are
            // city slots so the remaining requests can broaden inside the same
            // state; otherwise every slot stays on the requested city/cities.
            List<StageEntry> cityEntries = new ArrayList<>();
            int citySlots = stateGiven
                    ? Math.min(requestedCitySlots, allSlots.size()) : allSlots.size();
            for (int i = 0; i < citySlots; i++) {
                Slot slot = allSlots.get(i);
                String targetCity = selectedCities == null || selectedCities.isEmpty() ? null
                        : selectedCities.get(i % selectedCities.size());
                if (targetCity != null) {
                    searchedCities.add(targetCity);
                    logger.info("SEARCH_CITY={}{}", targetCity,
                            stateValue != null ? ", " + stateValue : "");
                }
                Combo combo = new Combo(slot.phrase, slot.phrase, stateValue, targetCity, countryNameFull);
                addSlotEntry(cityEntries, QueryFamily.CITY, LocTemplate.CITY_STATE_FULL,
                        new String[]{slot.phrase, slot.word, slot.type},
                        combo, null, slot.profile);
                String builtQuery = buildDiversifiedQuery(combo, cityEntries.get(cityEntries.size() - 1));
                logger.info("QUERY: {} | PRODUCT: {} | BUYER TYPE: {} | LOCATION: {} (requested city)",
                        builtQuery, slot.phrase, slot.word != null ? slot.word : "-",
                        targetCity != null
                                ? targetCity + (stateValue != null ? ", " + stateValue : "")
                                : countryNameFull);
            }
            if (!cityEntries.isEmpty()) {
                phases.add(new SearchPhase("requested city", cityEntries, cityEntries.size()));
            }

            // PHASE 2 - OTHER cities of the SAME state, reached only when the
            // requested-city rounds did not fill the limit. One new major city
            // per remaining request (importance order), never a city the user
            // already searched and never a city outside the requested state.
            if (stateGiven) {
                List<String> stateOtherCities = otherCitiesInState(stateValue, selectedCitySeen);
                int otherCityCount = Math.min(otherCitySlots,
                        Math.min(stateOtherCities.size(),
                                Math.max(0, allSlots.size() - requestedCitySlots)));
                if (otherCityCount > 0) {
                    List<StageEntry> otherCityEntries = new ArrayList<>();
                    List<String> expansionCities = new ArrayList<>();
                    for (int i = 0; i < otherCityCount; i++) {
                        String otherCity = stateOtherCities.get(i);
                        searchedCities.add(otherCity);
                        expansionCities.add(otherCity);
                        Slot slot = allSlots.get(requestedCitySlots + i);
                        Combo combo = new Combo(slot.phrase, slot.phrase, stateValue,
                                otherCity, countryNameFull);
                        addSlotEntry(otherCityEntries, QueryFamily.OTHER_CITY,
                                LocTemplate.CITY_STATE_FULL,
                                new String[]{slot.phrase, slot.word, slot.type},
                                combo, null, slot.profile);
                        String builtQuery = buildDiversifiedQuery(combo,
                                otherCityEntries.get(otherCityEntries.size() - 1));
                        logger.info("QUERY: {} | PRODUCT: {} | BUYER TYPE: {} | LOCATION: {} (other city in the requested state)",
                                builtQuery, slot.phrase, slot.word != null ? slot.word : "-",
                                otherCity + ", " + stateValue);
                    }
                    logger.info("CITY_EXPANSION={}", String.join(" -> ",
                            joinCityExpansion(selectedCities, expansionCities)));
                    phases.add(new SearchPhase("other cities in state: " + stateValue,
                            otherCityEntries, otherCityEntries.size()));
                }
            }

            // PHASE 3 - the REQUESTED state (the remaining slots). Only when a
            // state was requested and only reached when the requested-city and
            // same-state-city rounds did not fill the limit; the state is the
            // user's state, never the whole country. There is NO nationwide
            // phase for a city search, so when every phase is spent the search
            // ends.
            if (stateGiven) {
                List<StageEntry> stateEntries = new ArrayList<>();
                int stateSlots = Math.min(requestBudget - stateStart,
                        Math.max(0, allSlots.size() - stateStart));
                for (int i = 0; i < stateSlots; i++) {
                    Slot slot = allSlots.get(stateStart + i);
                    Combo combo = new Combo(slot.phrase, slot.phrase, stateValue, null, countryNameFull);
                    addSlotEntry(stateEntries, QueryFamily.STATE, LocTemplate.STATE_FULL,
                            new String[]{slot.phrase, slot.word, slot.type},
                            combo, null, slot.profile);
                    String builtQuery = buildDiversifiedQuery(combo,
                            stateEntries.get(stateEntries.size() - 1));
                    logger.info("QUERY: {} | PRODUCT: {} | BUYER TYPE: {} | LOCATION: {} (state-wide)",
                            builtQuery, slot.phrase, slot.word != null ? slot.word : "-",
                            stateValue);
                }
                if (!stateEntries.isEmpty()) {
                    phases.add(new SearchPhase("state-wide: " + stateValue,
                            stateEntries, stateEntries.size()));
                }
            }
        }

        // RELATED-INDUSTRY / USE-CASE PHASE. The three phases above are the
        // unchanged deterministic plan and keep their exact slots, order and
        // budget split, so every existing importer / distributor / wholesaler /
        // buyer / retailer query is still generated and still fires first. This
        // extra phase runs LAST (only when the plan did not fill the requested
        // limit) and adds the adjacent industries / use cases that genuinely
        // stock the product, all against the SAME requested city/state/country.
        List<StageEntry> relatedEntries = relatedBuyerCategoryEntries(
                products, cities, stateValue, countryWord);
        int relatedQueryCount = relatedEntries.size();
        if (relatedQueryCount > 0) {
            phases.add(new SearchPhase("related industries and use cases: " + locationDesc,
                    relatedEntries, relatedQueryCount));
        }

        // The RELATED-INDUSTRY / USE-CASE queries are ADDITIONAL Firecrawl
        // requests on top of the unchanged requestBudget plan, so widening the
        // product's buyer coverage never takes a slot away from the existing
        // importer / distributor / wholesaler / buyer / retailer queries. The
        // shared SearchBudget (first attempts AND 429 retries) covers both.
        int totalRequestBudget = requestBudget + relatedQueryCount;

        logger.info("========================================");
        logger.info("BUYER SEARCH STARTED");
        logger.info("  Queries   : {} deterministic product-buyer-type queries", allSlots.size());
        logger.info("  Products  : {}", products);
        logger.info("  Country   : {}", countryNameFull);
        logger.info("  Limit     : {}", limit);
        logger.info("  State     : {}", stateValue != null ? stateValue : "(any)");
        logger.info("  Cities    : {}", cities.isEmpty() ? "(any)" : cities);
        for (SearchPhase phaseEntry : phases) {
            String queries = phaseEntry.entries.stream()
                    .map(StageEntry::describe)
                    .collect(Collectors.joining(" | "));
            logger.info("  Phase '{}': {} queries, budget {} calls, plan: [{}]",
                    phaseEntry.label, phaseEntry.entries.size(), phaseEntry.maxCalls, queries);
        }
        logger.info("========================================");

        Set<String> knownDatabaseWebsites = loadNormalizedDatabaseWebsites();
        logger.info("Loaded {} existing website(s) from database to exclude", knownDatabaseWebsites.size());

        // Websites already returned for any product/location - never returned twice.
        Set<String> returnedWebsites = new HashSet<>();
        // Every DISTINCT domain already seen anywhere in this search (accepted,
        // rejected or filtered). A domain re-surfaced by a later query/phase is a
        // duplicate: it was already evaluated, so it is never re-verified - its
        // previous verdict stands (requirement 6: verify each domain once).
        Set<String> seenThisSearch = new HashSet<>();
        // Domains for which full homepage verification actually ran in this
        // search (safety net at the verify layer, keyed by normalized domain).
        Set<String> verifiedDomains = new HashSet<>();
        // Normalized business names already accepted - catches the same business
        // reached through different domains / country versions (big-bowl.com vs
        // big-bowl.co.uk) or different tracking URLs.
        Set<String> claimedBusinessNames = new HashSet<>();
        // Per-combo seen set so deeper rounds never re-consider earlier candidates,
        // while the same website stays eligible for a different product/city combo.
        Map<Combo, Set<String>> seenByCombo = new HashMap<>();
        List<BuyerSearchResult> verifiedBuyers = new ArrayList<>();
        Map<String, String> homepageCache = new ConcurrentHashMap<>();
        // The original Firecrawl RESULT page (sourceUrl) HTML, fetched and reused
        // during verification so every product-page URL is inspected once; the
        // result page is the PRIMARY commercial-evidence source, the homepage the
        // secondary business/location source (requirement 2/3).
        Map<String, String> resultPageCache = new ConcurrentHashMap<>();
        // Best (deepest/most product-specific) sourceUrl candidate per normalized
        // domain: when a domain surfaces once as its homepage and once as its
        // product page, the product page URL is kept for verification instead of
        // being discarded by the same-domain dedup (requirement 11).
        Map<String, BuyerSearchResult> domainBestResult = new HashMap<>();
        // Canonical lowercased queries already fired during THIS search, so a
        // repeated synonym / role-word / location combination is never fired
        // twice (Firecrawl has no offset pagination, so a re-run adds nothing).
        Set<String> firedQueries = new HashSet<>();

        int firecrawlCalls = 0;
        int totalRawResults = 0;
        int totalDupSkipped = 0;
        int totalDbSkipped = 0;
        int totalRejected = 0;
        int totalAccepted = 0;
        // Verified-and-passed WEB results (every candidate that clears the four
        // gates), independent of the return window - used so the final statistics
        // reconcile exactly: Raw = DB + Duplicate + Rejected + Accepted.
        int webAccepted = 0;
        SearchTally tally = new SearchTally();

        // Each round advances through the phase's ordered DIVERSIFIED query
        // strategies so the same strategy is never fired twice for the same
        // combo; Firecrawl has no offset pagination. Only ONE strategy is
        // attempted per combo per round, so the search is adaptive: it stops the
        // moment the limit or the per-search Firecrawl request budget is
        // reached. Once a phase's budget slice is spent without filling the
        // limit, the next phase (state-wide/nationwide fallback) starts a fresh
        // strategy list while credits remain. A strategy that came back
        // duplicate-heavy makes the remaining same-location-form strategies be
        // skipped, favouring NEW businesses over repeated hits.
        int phaseIdx = 0;
        int entryIdx = 0;
        int phaseCalls = 0;
        boolean skipSameFamily = false;
        QueryFamily firedFamily = null;
        String firedLocationKey = null;
        SearchPhase phase = phases.get(0);
        SearchBudget budget = new SearchBudget(totalRequestBudget);
        String stopReason = null;

        for (int round = 0; round < totalRequestBudget
                && verifiedBuyers.size() < limit && !budget.exhausted(); round++) {

            // Fallback gate: when the current phase's strategies are consumed
            // (or its call slice is spent) without filling the limit, move to
            // the next phase while credits remain. A state-wide phase only
            // exists when a specific state was requested (a requested city/state
            // is never broadened to the whole country - nationwide never fires
            // for a city search), so once every phase is spent, no strategy can
            // still yield results.
            if (entryIdx >= phase.entries.size() || phaseCalls >= phase.maxCalls) {
                if (phaseIdx + 1 < phases.size()) {
                    phaseIdx++;
                    phase = phases.get(phaseIdx);
                    entryIdx = 0;
                    phaseCalls = 0;
                    skipSameFamily = false;
                    logger.info("--- Broadening to phase '{}' ({} strategies) ---",
                            phase.label, phase.entries.size());
                    // Broadening without firing must not consume a round slot:
                    // only fired strategies count toward the 10-query budget.
                    round--;
                    continue;
                } else {
                    stopReason = "query bank yielded no more results";
                    break;
                }
            }

            // Duplicate-heavy strategy feedback marks ONLY the previous strategy
            // exhausted. A duplicate-heavy "Singing Bowls retailer Dallas" query
            // returning only already-known businesses must NOT suppress the other
            // Dallas commercial strategies ("shop", "store", "wholesale",
            // "distributor"): different product+intent searches surface
            // DIFFERENT businesses in the same city. Entry-by-entry progression
            // (entryIdx below) fires every city strategy before the phase can
            // broaden, and the firedQueries set still prevents any exact query
            // from repeating - so nothing is wasted and no useful city strategy
            // is ever skipped.
            if (skipSameFamily) {
                logger.info("Duplicate-heavy strategy '{}' marked exhausted - "
                                + "continuing with the remaining commercial strategies at '{}'",
                        firedFamily.name().toLowerCase(Locale.ROOT), firedLocationKey);
                skipSameFamily = false;
            }

            StageEntry entry = phase.entries.get(entryIdx);
            firedFamily = entry.family;
            firedLocationKey = locationKey(entry);

            // The selected city is fired MULTIPLE times on purpose - once per
            // distinct (product phrase, buyer word) slot - so the city-first
            // plan can surface several kinds of Arlington business. Exact
            // duplicate queries are still prevented by the firedQueries set
            // inside executeCombosSerially.

            int remaining = limit - verifiedBuyers.size();
            logger.info("--- Round {} [{}] : {} combo(s), query '{}', budget {}/{} ---",
                    round + 1, phase.label, entry.combos.size(), entry.describe(),
                    budget.used(), budget.max());

            // Search queries are fired one at a time, globally paced to respect
            // the Firecrawl rate limit, and stop immediately once the per-search
            // Firecrawl request budget is spent.
            CombosRound roundResponse =
                    executeCombosSerially(entry.combos, apiKey, entry, budget, firedQueries, round + 1);
            if (roundResponse.budgetExhausted) {
                stopReason = "Firecrawl request budget exhausted";
            }
            int roundCalls = roundResponse.grouped.values().stream().mapToInt(List::size).sum();
            firecrawlCalls += roundCalls;
            phaseCalls += roundCalls;

            List<Candidate> batchCandidates = new ArrayList<>();
            int entryRaw = 0;
            int entryDup = 0;
            int entryNew = 0;
            int roundDbSkipped = 0;

            // Every query fired in this round is fully processed for statistics
            // AND candidate collection - even when an earlier combo already fills
            // the requested window - so Raw results / DB skipped / duplicates
            // always reflect the COMPLETE search, never just the part that
            // eventually produced buyers.
            for (Combo combo : entry.combos) {
                Set<String> comboSeen = seenByCombo.computeIfAbsent(combo, k -> new HashSet<>());
                for (QueryTaskResult task : roundResponse.grouped.getOrDefault(combo, List.of())) {
                    totalRawResults += task.results.size();
                    entryRaw += task.results.size();
                    allQueries.add(task.query);
                    int newFromQuery = 0;
                    int dbSkippedFromQuery = 0;
                    int dupSkippedFromQuery = 0;
                    int filteredFromQuery = 0;

                    for (BuyerSearchResult result : task.results) {
                        String rawUrl = result.getWebsite();
                        String normalizedWebsite = normalizeWebsite(rawUrl);
                        if (normalizedWebsite == null || normalizedWebsite.isEmpty()) {
                            filteredFromQuery++;
                            totalRejected++;
                            tally.contentRejects.incrementAndGet();
                            logger.info("{} - no valid website URL in result (raw={})",
                                    CODE_INVALID_CONTENT, rawUrl);
                            continue;
                        }

                        if (isNonBusinessHost(normalizedWebsite)) {
                            filteredFromQuery++;
                            totalRejected++;
                            tally.contentRejects.incrementAndGet();
                            logger.info("{} '{}' ({}) - social/directory/review host",
                                    CODE_INVALID_CONTENT, result.getCompanyName(), normalizedWebsite);
                            continue;
                        }

                        if (knownDatabaseWebsites.contains(normalizedWebsite)) {
                            dbSkippedFromQuery++;
                            totalDbSkipped++;
                            roundDbSkipped++;
                            logger.info("{} '{}' ({}) - already in the buyer database",
                                    CODE_ALREADY_IN_DATABASE, result.getCompanyName(), normalizedWebsite);
                            continue;
                        }

                        if (returnedWebsites.contains(normalizedWebsite)) {
                            dupSkippedFromQuery++;
                            totalDupSkipped++;
                            logger.info("{} '{}' ({}) - already returned for another product/location",
                                    CODE_DUPLICATE, result.getCompanyName(), normalizedWebsite);
                            continue;
                        }

                        if (!comboSeen.add(normalizedWebsite)) {
                            dupSkippedFromQuery++;
                            totalDupSkipped++;
                            logger.info("{} '{}' ({}) - duplicate for combo '{}'",
                                    CODE_DUPLICATE, result.getCompanyName(), normalizedWebsite, combo.queryLabel());
                            continue;
                        }

                        // Search-wide dedup (requirement 6): a domain already seen
                        // for a DIFFERENT query/phase of this search is not
                        // re-processed - its previous screen/verdict stands. Only
                        // genuinely NEW domains reach the cheap screen, so late
                        // rounds surface different businesses instead of re-verifying
                        // the same ones for every query.
                        if (!seenThisSearch.add(normalizedWebsite)) {
                            // Requirement 11: a domain may surface once as its
                            // homepage and once as its product/collection page -
                            // never silently discard the deeper product URL. When
                            // the NEW original result URL is the stronger product
                            // page, upgrade the candidate's sourceUrl so verification
                            // inspects the actual product page (the homepage alone
                            // would lose its commercial evidence).
                            BuyerSearchResult earlier = domainBestResult.get(normalizedWebsite);
                            if (earlier != null && isBetterProductUrl(rawUrl, earlier.getSourceUrl())) {
                                earlier.setSourceUrl(rawUrl);
                                logger.info("UPGRADE sourceUrl for '{}' -> '{}' (deeper product page kept for verification)",
                                        normalizedWebsite, rawUrl);
                            }
                            dupSkippedFromQuery++;
                            totalDupSkipped++;
                            logger.info("{} '{}' ({}) - already processed earlier in this search",
                                    CODE_DUPLICATE, result.getCompanyName(), normalizedWebsite);
                            continue;
                        }

                        // ---- CHEAP RAW PRE-FILTER: URL + title + snippet only ----
                        // Runs FIRST, before the product-relevance gate and before
                        // any page fetch, so an obviously wrong kind of result
                        // (news/article, conference/event/registration, attendee
                        // or exhibitor LIST, medical/academic, government/tourism,
                        // music/song/artist, unrelated business directory) is
                        // dropped while it is still completely free - no homepage
                        // fetch, no email request, no Firecrawl credit spent on it.
                        // Deliberately LIGHT: a legitimate product URL
                        // (/product, /products, /shop, /store, /collection,
                        // /catalog, ...) is always KEPT and no home-decor /
                        // wellness / spiritual / meditation / yoga / gift store
                        // copy is rejected here.
                        String preFilterReason = rawResultPreFilterReason(rawUrl,
                                result.getRawTitle(), result.getSnippet());
                        if (preFilterReason != null) {
                            filteredFromQuery++;
                            totalRejected++;
                            tally.contentRejects.incrementAndGet();
                            logCandidateVerdict("REJECT", preFilterReason,
                                    result.getRawTitle(), result.getSnippet(), normalizedWebsite,
                                    combo.queryProduct, combo.state, combo.city);
                            logger.info("RAW_RESULT_PRE_FILTER=REJECT PRE_FILTER_REASON={} "
                                            + "'{}' ({})",
                                    preFilterReason, result.getRawTitle(), normalizedWebsite);
                            continue;
                        }
                        logger.info("RAW_RESULT_PRE_FILTER=KEEP '{}' ({})",
                                result.getRawTitle(), normalizedWebsite);

                        // ---- CHEAP SCREEN: URL/title/snippet only (no HTTP) ----
                        // Page-type classification (shopping/product page, file,
                        // article, government, directory, forum, academic) runs
                        // before any homepage fetch, so obviously bad results never
                        // cost processing.
                        String rejectReason = classifyPage(rawUrl, result.getRawTitle(), combo.queryProduct);
                        if (rejectReason != null) {
                            filteredFromQuery++;
                            totalRejected++;
                            tally.contentRejects.incrementAndGet();
                            logger.info("{} - {} '{}' ({})",
                                    CODE_INVALID_CONTENT, rejectReason,
                                    result.getRawTitle(), normalizedWebsite);
                            continue;
                        }

                        result.setWebsite(normalizedWebsite);
                        // SOURCE URL stays the EXACT Firecrawl result URL - the
                        // granular page Firecrawl returned (product/collection
                        // page, often with query params). website is only the
                        // normalized root domain. Verification (product evidence,
                        // commercial evidence) inspects the sourceUrl page FIRST;
                        // the homepage is used only when that exact page carries
                        // no evidence.
                        if (result.getSourceUrl() == null || result.getSourceUrl().isBlank()) {
                            result.setSourceUrl(rawUrl);
                        } else if (!normalizedWebsite.equals(normalizeWebsite(result.getSourceUrl()))) {
                            // Defensive: never let sourceUrl drift from the URL that
                            // produced this candidate's evidence.
                            result.setSourceUrl(rawUrl);
                        }
                        // The user's original product label is what gets saved and
                        // shown; the effective synonym is carried separately so the
                        // mandatory relevance gate verifies against what was actually
                        // searched.
                        result.setProductName(userProductLabel);
                        result.setSearchProduct(combo.queryProduct);

                        // Trade-data / import-export records & analytics portals
                        // (OEC, Panjiva, Volza, Trademap, customs data) provide
                        // statistics - they never buy the product.
                        if (isTradeDataPage(rawUrl, result.getRawTitle())) {
                            filteredFromQuery++;
                            totalRejected++;
                            tally.contentRejects.incrementAndGet();
                            logger.info("{} '{}' ({}) - trade-data/records site",
                                    CODE_NO_BUYER_INTENT, result.getRawTitle(), normalizedWebsite);
                            continue;
                        }

                        // CONDITION 1 - PRODUCT MATCH: every meaningful product
                        // term must be explicitly present in the title/snippet
                        // (known aliases count, e.g. meditation/sound bowl for
                        // singing bowls). Verified against the USER'S ENTERED
                        // products - the same gate the stage-2 full-page verify
                        // uses - so a result matching only the internal synonym
                        // ("meditation bowls") still fails when the user's own
                        // product family is absent. The EXACT Firecrawl result
                        // URL (sourceUrl/rawUrl) is what feeds the URL-path
                        // zone of the gate - never the normalized root, whose
                        // path is always empty and would wrongly discard a
                        // valid product/category page whose URL carries the
                        // product while its title does not.
                        if (!isProductRelevantForAny(result.getRawTitle(), result.getSnippet(),
                                rawUrl, null, products, task.query)) {
                            filteredFromQuery++;
                            totalRejected++;
                            tally.productRejects.incrementAndGet();
                            logCandidateVerdict("REJECT", "no product relevance evidence",
                                    result.getRawTitle(), result.getSnippet(), normalizedWebsite,
                                    combo.queryProduct, combo.state, combo.city);
                            logger.info("{} '{}' ({}) - evidence does not mention '{}'",
                                    CODE_WRONG_PRODUCT, result.getRawTitle(), normalizedWebsite, combo.queryProduct);
                            continue;
                        }
                        logger.info("PRODUCT_MATCH '{}' ({}) - product/product-family evidence "
                                        + "for '{}' present in the search listing",
                                result.getRawTitle(), normalizedWebsite, combo.queryProduct);

                        // CONDITION 2 - PRODUCT RELEVANCE has already been
                        // applied by the cheap raw pre-filter above and by the
                        // product gate below; the full-page irrelevant-content
                        // screen runs later, during verification, where the
                        // fetched homepage text and store evidence are visible.
                        logCandidateVerdict("PASSED-CHEAP-SCREEN",
                                "product relevance evidence in the search listing",
                                result.getRawTitle(), result.getSnippet(), normalizedWebsite,
                                combo.queryProduct, combo.state, combo.city);

                        String businessName = extractBusinessName(result, combo.queryProduct);
                        if (businessName == null || !isMeaningfulName(businessName, combo.queryProduct)
                                || !hasGenuineBrandWord(businessName)) {
                            String host = hostOf(normalizedWebsite);
                            businessName = host == null || host.isBlank() ? normalizedWebsite : host;
                        }

                        // Same business reached via another domain / country version
                        // / tracking URL is a duplicate, not a new buyer.
                        String businessKey = normalizeBusinessKey(businessName);
                        if (businessKey != null && !claimedBusinessNames.add(businessKey)) {
                            dupSkippedFromQuery++;
                            totalDupSkipped++;
                            logger.info("{} '{}' ({}) - same business as an already accepted result",
                                    CODE_DUPLICATE, businessName, normalizedWebsite);
                            continue;
                        }

                        result.setCompanyName(businessName);
                        result.setBuyerName(businessName);
                        result.setState(combo.state);
                        result.setCity(combo.city);
                        result.setCountry(combo.country);
                        result.setLocation(buildLocationDisplay(combo.state, combo.city, combo.country));
                        // Track the first candidate per domain; later same-domain
                        // results upgrade its sourceUrl when they carry the deeper
                        // product page (requirement 11, handled in the dup branch).
                        domainBestResult.putIfAbsent(normalizedWebsite, result);
                        batchCandidates.add(new Candidate(combo, result));
                        newFromQuery++;
                    }

                    // A query whose results are all DB rows / earlier hits must
                    // not end the search: log how many duplicates it returned so
                    // the credit use is visible, and keep trying the NEXT query
                    // strategy (requirement: continue until limit or budget ends).
                    if (newFromQuery == 0 && task.results.size() > 0) {
                        logger.info("Query '{}' yielded no new candidates ({} raw, {} already in DB, "
                                        + "{} duplicate in this search, {} filtered) - "
                                        + "continuing to the next query strategy",
                                task.query, task.results.size(), dbSkippedFromQuery,
                                dupSkippedFromQuery, filteredFromQuery);
                    }
                    entryDup += dbSkippedFromQuery + dupSkippedFromQuery;
                    entryNew += newFromQuery;
                }
            }

            if (!batchCandidates.isEmpty() && remaining > 0) {
                // Homepage verification is free (plain HTTP) and parallel, so
                // EVERY new candidate is verified - none are silently dropped.
                // The search still stops the moment the requested window fills.
                List<BuyerSearchResult> toVerify = new ArrayList<>(batchCandidates.size());
                for (Candidate candidate : batchCandidates) {
                    toVerify.add(candidate.result);
                }

                logger.info("Relevance/location-verifying {} candidate(s) to fill {} remaining slot(s)...",
                        toVerify.size(), remaining);

                // Requirement 17: every raw result is classified into EXACTLY one
                // bucket. Candidates skipped here because their domain was already
                // verified earlier in this search are DUPLICATES (not rejects), so
                // they are counted against totalDupSkipped and subtracted from the
                // reject count - otherwise Rejected would exceed sum of the gates.
                java.util.concurrent.atomic.AtomicLong dupSkippedInPhase =
                        new java.util.concurrent.atomic.AtomicLong();
                List<BuyerSearchResult> roundVerified =
                        verifyCandidatesInParallel(toVerify, homepageCache, resultPageCache,
                                verifiedDomains, tally, dupSkippedInPhase);

                int dupSkippedVerify = dupSkippedInPhase.intValue();
                int rejected = toVerify.size() - roundVerified.size() - dupSkippedVerify;
                totalRejected += rejected;
                totalDupSkipped += dupSkippedVerify;

                int slotsToAdd = Math.min(remaining, roundVerified.size());
                // Accepted = every product-relevant candidate returned by
                // verification (independent of the return window).
                webAccepted += roundVerified.size();
                totalAccepted += roundVerified.size();
                int roundAccepted = 0;
                for (int i = 0; i < slotsToAdd; i++) {
                    BuyerSearchResult toAdd = roundVerified.get(i);
                    verifiedBuyers.add(toAdd);
                    returnedWebsites.add(toAdd.getWebsite());
                    roundAccepted++;
                }

                logger.info("Verification: {} passed, {} rejected -> total {}/{}",
                        roundVerified.size(), rejected, verifiedBuyers.size(), limit);
                logger.info("Round {} | Searched City={} | Query=\"{}\" | Accepted={} | Rejected={} | Existing DB={} | Final Buyers={}",
                        round + 1,
                        !entry.combos.isEmpty() && entry.combos.get(0).city != null
                                ? entry.combos.get(0).city
                                : (stateValue != null ? "state-wide" : "nationwide"),
                        roundResponse.grouped.values().stream()
                                .flatMap(List::stream)
                                .map(t -> t.query)
                                .collect(Collectors.joining(" | ")),
                        roundAccepted, rejected, roundDbSkipped, verifiedBuyers.size());
            }

            // Stop immediately once the requested limit is reached. DB-skipped
            // and rejected results never count toward the limit.
            if (verifiedBuyers.size() >= limit) {
                stopReason = "requested limit reached";
                logger.info("Limit ({}) reached after round {} - {} Firecrawl call(s) used",
                        limit, round + 1, firecrawlCalls);
                break;
            }

            // Duplicate-heavy strategy feedback: when most raw results were already-known
            // businesses and nothing new came through, the remaining strategies
            // with the same location form are skipped so credit moves to a
            // different strategy that can actually surface NEW businesses.
            skipSameFamily = entryRaw > 0 && entryNew == 0
                    && entryDup > 0 && ((double) entryDup / entryRaw) >= DUP_HEAVY_RATIO;
            if (skipSameFamily) {
                logger.info("Strategy '{}' was duplicate-heavy ({}/{} = {}% dup/DB, 0 new) "
                                + "- pivoting to a different strategy",
                        entry.describe(), entryDup, entryRaw,
                        Math.round(100.0 * entryDup / entryRaw));
            }

            // Next round runs the next strategy for this phase's combos and can
            // only move forward - a strategy is never fired twice.
            entryIdx += 1;
        }

        if (stopReason == null) {
            stopReason = budget.exhausted()
                    ? "Firecrawl request budget exhausted"
                    : "query bank consumed";
        }
        logger.info("Search stopped: {}", stopReason);

        // SOCIAL TARGETING PASS - LinkedIn company/showcase pages and Facebook
        // business pages for the SAME product + location the user selected.
        // Runs ONLY while the requested limit is not yet filled and SHARES the
        // same 6-request Firecrawl budget as the exact/web search above, so the
        // whole search (web + social) never exceeds 10 Firecrawl requests. Social
        // pages block plain HTTP scraping and are not company websites, so they
        // are verified from the search result evidence itself (title/snippet)
        // and are automatically skipped by email discovery
        // (isRealBusinessWebsite=false) - they can never interrupt the
        // web-buyer email flow.
        int socialCalls = 0;
        if (verifiedBuyers.size() < limit && !products.isEmpty() && !budget.exhausted()) {
            Set<String> existingSocialWebsites = loadExistingSocialWebsites();
            Set<String> socialSeen = new HashSet<>();
            int callsBeforeSocial = budget.used();
            List<BuyerSearchResult> socialBuyers = runSocialSearch(
                    products.get(0), userProductLabel,
                    stateValue, cities.isEmpty() ? null : cities.get(0),
                    countryNameFull, countryWord, apiKey, budget,
                    limit - verifiedBuyers.size(), existingSocialWebsites, socialSeen);
            socialCalls = budget.used() - callsBeforeSocial;
            int socialAccepted = 0;
            for (BuyerSearchResult socialBuyer : socialBuyers) {
                if (verifiedBuyers.size() >= limit) {
                    break;
                }
                verifiedBuyers.add(socialBuyer);
                returnedWebsites.add(socialBuyer.getWebsite());
                socialAccepted++;
            }
            totalAccepted += socialAccepted;
            if (!socialBuyers.isEmpty()) {
                logger.info("SOCIAL SEARCH | LinkedIn/Facebook surfaced {} buyer(s) for '{}' in {}",
                        socialBuyers.size(), userProductLabel,
                        stateValue != null
                                ? (cities.isEmpty() ? stateValue : cities.get(0) + ", " + stateValue)
                                : countryNameFull);
            }
        }

        if (verifiedBuyers.size() > limit) {
            verifiedBuyers = new ArrayList<>(verifiedBuyers.subList(0, limit));
        }

        logger.info("========================================");
        logger.info("SEARCH + DEDUP + RELEVANCE-VERIFY COMPLETE");
        logger.info("  Firecrawl calls  : {}", firecrawlCalls);
        logger.info("  API requests     : {} ({} retried)", budget.used(), budget.retries());
        logger.info("  Social requests  : {} (LinkedIn/Facebook)", socialCalls);
        logger.info("  Web pipeline     : RAW={} -> DB_SKIPPED={} -> DUPLICATE={} -> "
                        + "HARD_REJECTED={} -> POTENTIAL={} -> UNVERIFIED={} -> VERIFIED={} -> ACCEPTED={}",
                totalRawResults, totalDbSkipped, totalDupSkipped, totalRejected,
                tally.potential.get(), tally.unverified.get(), tally.verified.get(), webAccepted);
        int sumBuckets = totalDbSkipped + totalDupSkipped + totalRejected + webAccepted;
        boolean reconciled = sumBuckets == totalRawResults
                && tally.potential.get() + tally.unverified.get() + tally.verified.get() == webAccepted;
        logger.info("  Reconciled       : {} raw = {} DB + {} duplicate + {} hard-rejected "
                        + "+ {} potential + {} unverified + {} verified = {} accepted {}",
                totalRawResults, totalDbSkipped, totalDupSkipped, totalRejected,
                tally.potential.get(), tally.unverified.get(), tally.verified.get(), webAccepted,
                reconciled ? "OK" : "MISMATCH");
        logger.info("  Final buyers     : {}", verifiedBuyers.size());
        logger.info("  Stopped because  : {}", stopReason);
        logger.info("  Cities searched  : {}",
                searchedCities.isEmpty() ? "(none - state/country-wide)" : String.join(", ", searchedCities));
        logger.info("========================================");
        logger.info("FINAL SEARCH | Product={} | State={} | RequestedCity={} | ActualSearchCity={} | "
                        + "Query={} | Accepted={} | Rejected={} | ExistingDB={} | FinalBuyers={}",
                products, stateValue != null ? stateValue : "(any)",
                cities.isEmpty() ? "(any)" : cities,
                searchedCities.isEmpty() ? "(none - state/country-wide)" : String.join(",", searchedCities),
                allQueries.isEmpty() ? "(none fired)" : String.join(" | ", allQueries),
                totalAccepted, totalRejected, totalDbSkipped, verifiedBuyers.size());

        long emailStart = System.currentTimeMillis();
        ExecutorService emailExecutor = Executors.newFixedThreadPool(EMAIL_DISCOVERY_PARALLELISM);
        try {
            logger.info("Starting email scraping for {} buyer(s)", verifiedBuyers.size());
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (BuyerSearchResult result : verifiedBuyers) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        // STRICT VALIDATION BEFORE EMAIL SCRAPING: a candidate is
                        // only sent to EmailDiscoveryService when its Relevant URL
                        // is the actual business/company website. Social profiles,
                        // marketplaces/platform pages, document/PDF platforms,
                        // newsletter platforms, directories/listing sites,
                        // article/blog/news pages, forum/event pages and generic
                        // platform domains are rejected here and never scraped.
                        if (!emailDiscoveryService.isRealBusinessWebsite(result.getWebsite())) {
                            logger.info("EMAIL NOT FOUND | buyer='{}' | website={} | reason=not the actual business/company website - email scraping skipped",
                                    result.getCompanyName(), result.getWebsite());
                            return;
                        }
                        // Reuse the homepage fetched during verification so the
                        // official website is requested only once when available.
                        // When the homepage was never reached, discovery is STILL
                        // run (passing null): EmailDiscoveryService then tries the
                        // homepage again plus the contact/about pages, because the
                        // email usually lives on a dedicated contact page.
                        String homepageHtml = homepageCache.get(result.getWebsite());
                        String email = emailDiscoveryService.discoverEmail(result.getWebsite(), homepageHtml);
                        result.setEmail(email);
                        if (email != null && !email.isBlank()) {
                            logger.info("EMAIL FOUND | buyer='{}' | website={} | email={}",
                                    result.getCompanyName(), result.getWebsite(), email);
                        } else {
                            logger.info("EMAIL NOT FOUND | buyer='{}' | website={}",
                                    result.getCompanyName(), result.getWebsite());
                        }
                    } catch (Exception e) {
                        logger.warn("EMAIL SCRAPING FAILED | buyer='{}' | website={} - buyer kept without email: {}",
                                result.getCompanyName(), result.getWebsite(), e.getMessage());
                    }
                }, emailExecutor));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } finally {
            emailExecutor.shutdown();
        }

        // Final ranking: location priority (EXACT_CITY > SAME_STATE > SAME_COUNTRY)
        // dominates (lower priority value = better), then evidence strength
        // (higher relevanceScore = better), then email availability (present
        // beats absent) - requirement 14 ordering.
        verifiedBuyers.sort(Comparator
                .comparingInt((BuyerSearchResult r) -> locationClassPriority(r.getLocationClass()))
                .thenComparingInt((BuyerSearchResult r) -> -r.getRelevanceScore())
                .thenComparingInt(r -> r.getEmail() != null && !r.getEmail().isBlank() ? 0 : 1));

        int exactCityCount = 0;
        int sameStateCount = 0;
        int sameCountryCount = 0;
        int unknownClassCount = 0;
        for (BuyerSearchResult r : verifiedBuyers) {
            String lc = r.getLocationClass();
            if (LOCATION_CLASS_EXACT_CITY.equals(lc)) {
                exactCityCount++;
            } else if (LOCATION_CLASS_SAME_STATE.equals(lc)) {
                sameStateCount++;
            } else if (LOCATION_CLASS_SAME_COUNTRY.equals(lc)) {
                sameCountryCount++;
            } else {
                unknownClassCount++;
            }
        }

        long totalDuration = System.currentTimeMillis() - emailStart;
        logger.info("========================================");
        logger.info("BUYER SEARCH COMPLETE");
        logger.info("  Products         : {}", products);
        logger.info("  Requested region : {}/{}", stateValue, cities.isEmpty() ? "(any)" : cities);
        logger.info("  Searched cities  : {}",
                searchedCities.isEmpty() ? "(none - state/country-wide)" : String.join(", ", searchedCities));
        logger.info("  Requested limit  : {}", limit);
        logger.info("  Unique returned  : {}", verifiedBuyers.size());
        logger.info("  Firecrawl calls  : {}", firecrawlCalls);
        logger.info("  Location class   : EXACT_CITY={} | SAME_STATE={} | SAME_COUNTRY={} | UNKNOWN={}",
                exactCityCount, sameStateCount, sameCountryCount, unknownClassCount);
        logger.info("  Flow totals      : REJECTED={} | ACCEPTED={}", totalRejected, totalAccepted);
        logger.info("  Gate breakdown   : {}", tally.breakdown());
        logger.info("  Pipeline         : DB_SKIPPED={} | DUPLICATE={} | CONTENT_FILTERED={} | "
                        + "PRODUCT_REJECTED={} | COMMERCIAL_REJECTED={} | BUSINESS_REJECTED={} | "
                        + "LOCATION_REJECTED={} | POTENTIAL={} | UNVERIFIED={} | VERIFIED={} | ACCEPTED={}",
                totalDbSkipped, totalDupSkipped,
                tally.contentRejects.get() + tally.identityRejects.get(),
                tally.productRejects.get(), tally.commercialRejects.get(),
                tally.businessTypeRejects.get(), tally.locationRejects.get(),
                tally.potential.get(), tally.unverified.get(), tally.verified.get(), webAccepted);
        logger.info("  Email scraping   : {} ms", totalDuration);
        logger.info("========================================");

        return verifiedBuyers;
    }

    /** Selection/ranking priority of a location class: lower = better
     *  (EXACT_CITY first, then SAME_STATE, then SAME_COUNTRY, UNKNOWN last). */
    private int locationClassPriority(String locationClass) {
        if (LOCATION_CLASS_EXACT_CITY.equals(locationClass)) {
            return 0;
        }
        if (LOCATION_CLASS_SAME_STATE.equals(locationClass)) {
            return 1;
        }
        if (LOCATION_CLASS_SAME_COUNTRY.equals(locationClass)) {
            return 2;
        }
        return 3;
    }

    // ------------------------------------------------------------------
    // Combo / query building
    // ------------------------------------------------------------------

    private static final class Combo {
        // The user's original product label - kept for display/classification and
        // stored as the buyer's productName (email discovery & campaigns depend on it).
        final String product;
        // The effective product phrasing actually sent to Firecrawl and used for
        // the mandatory relevance gate (e.g. a decorative-jar synonym).
        final String queryProduct;
        final String state;
        final String city;
        // Requested country ("USA"/"Canada") - drives the Firecrawl geo-location
        // and the country word appended to country-wide (nationwide) queries.
        final String country;

        Combo(String product, String state, String city) {
            this(product, product, state, city, "USA");
        }

        Combo(String product, String queryProduct, String state, String city) {
            this(product, queryProduct, state, city, "USA");
        }

        Combo(String product, String queryProduct, String state, String city, String country) {
            this.product = product;
            this.queryProduct = queryProduct != null && !queryProduct.isBlank() ? queryProduct : product;
            this.state = state;
            this.city = city;
            this.country = country != null && !country.isBlank() ? country : "USA";
        }

        String queryLabel() {
            StringBuilder sb = new StringBuilder();
            sb.append("\"").append(queryProduct).append("\"");
            if (city != null && !city.isBlank()) {
                sb.append(" \"").append(city).append("\"");
            }
            if (state != null && !state.isBlank()) {
                sb.append(" ").append(state);
            }
            if (country != null && !country.isBlank()) {
                sb.append(" ").append(country);
            }
            return sb.toString();
        }
    }

    // One planned query slot: the effective product phrase, the buyer-profile
    // word (e.g. "for sale", "store", "retailer", "wholesale") + its business
    // type, and an optional RELATED-CATEGORY profile (leading category words
    // for the category-profile queries, e.g. "meditation" "spiritual"
    // "wellness"); null for the standard product-first slots.
    private static final class Slot {
        final String phrase;
        final String word;
        final String type;
        final List<String> profile;

        Slot(String phrase, String word, String type, List<String> profile) {
            this.phrase = phrase;
            this.word = word;
            this.type = type;
            this.profile = profile;
        }
    }

    // Location/word query forms. Location-only forms (CITY_ABBR, HUB_ABBR) carry
    // no buyer-intent word: they surface businesses a role word would hide and
    // are still checked for product relevance before acceptance.
    private enum LocTemplate { CITY_ABBR, CITY_ONLY, HUB_ABBR, STATE_FULL, STATE_ABBR, CITY_STATE_FULL, NONE }

    // Strategy family used by duplicate-heavy feedback: "family" is the location
    // formula (city / metro / state / nationwide), so a city strategy that only
    // returns known businesses skips the OTHER city strategies and moves to a
    // different location formula.
    private enum QueryFamily { CITY, OTHER_CITY, STATE, NATIONWIDE }

    // One diversified query strategy: a single location template + optional
    // buyer-intent word, applied to each of its combos. Strategies are tried in
    // order and never fired twice for the same combo.
    private static final class StageEntry {
        final QueryFamily family;
        final LocTemplate loc;
        final String[] word;   // {word, businessType}; null for location-only
        final String hub;      // nearby metro city, only for HUB_ABBR
        final String countryWord; // "USA"/"Canada"; appended to country-wide queries
        // Leading category-profile words for the two RELATED-CATEGORY buyer
        // profile queries (e.g. "meditation" "spiritual" "wellness" store
        // "singing bowls" Texas). null for the standard product-first queries.
        final List<String> profile;
        // TRUE to always render word[0] as one exact quoted phrase. Set only by
        // the related-industry / use-case queries, whose categories are business
        // types ("spas", "yoga studios") rather than buyer roles. false for every
        // pre-existing query, whose rendering is unchanged.
        final boolean quoteWord;
        final List<Combo> combos;

        StageEntry(QueryFamily family, LocTemplate loc, String[] word, String hub, List<Combo> combos) {
            this(family, loc, word, hub, combos, null, null);
        }

        StageEntry(QueryFamily family, LocTemplate loc, String[] word, String hub,
                   List<Combo> combos, String countryWord) {
            this(family, loc, word, hub, combos, countryWord, null);
        }

        StageEntry(QueryFamily family, LocTemplate loc, String[] word, String hub,
                   List<Combo> combos, String countryWord, List<String> profile) {
            this(family, loc, word, hub, combos, countryWord, profile, false);
        }

        StageEntry(QueryFamily family, LocTemplate loc, String[] word, String hub,
                   List<Combo> combos, String countryWord, List<String> profile,
                   boolean quoteWord) {
            this.family = family;
            this.loc = loc;
            this.word = word;
            this.hub = hub;
            this.combos = combos;
            this.countryWord = countryWord;
            this.profile = profile;
            this.quoteWord = quoteWord;
        }

        boolean wordless() {
            return word == null || word[0] == null || word[0].isBlank();
        }

        String businessType() {
            return wordless() ? "Buyer" : word[1];
        }

        String describe() {
            StringBuilder s = new StringBuilder();
            if (profile != null && !profile.isEmpty()) {
                for (String pw : profile) {
                    s.append("\"").append(pw).append("\" ");
                }
                if (!wordless()) {
                    s.append(word[0]);
                }
                if (combos != null && combos.size() == 1 && combos.get(0).queryProduct != null) {
                    s.append(" \"").append(combos.get(0).queryProduct.trim()).append("\"");
                }
            } else if (combos != null && combos.size() == 1 && combos.get(0).queryProduct != null) {
                s.append("\"").append(combos.get(0).queryProduct.trim()).append("\"");
                if (!wordless()) {
                    s.append(" ").append(word[0]);
                }
            } else if (!wordless()) {
                s.append(word[0]);
            }
            switch (loc) {
                case CITY_ABBR: s.append(" \"<city>\" <ABBR>"); break;
                case CITY_ONLY: s.append(" \"<city>\""); break;
                case HUB_ABBR:  s.append(" \"").append(hub).append("\" <ABBR>"); break;
                case STATE_FULL: s.append(" <state>"); break;
                case STATE_ABBR: s.append(" <ABBR>"); break;
                case CITY_STATE_FULL: s.append(" \"<city>\" <state>"); break;
                default:         s.append(" nationwide");
            }
            if (countryWord != null) {
                s.append(" ").append(countryWord);
            }
            return s.toString();
        }
    }

    // One search stage (exact city -> state-wide/nationwide fallback). Each
    // phase owns its ordered diversified query strategies and a slice of the
    // per-search Firecrawl budget, so the fallback can never be starved by the
    // first phase.
    private static final class SearchPhase {
        final String label;
        final List<StageEntry> entries;
        final int maxCalls;

        SearchPhase(String label, List<StageEntry> entries, int maxCalls) {
            this.label = label;
            this.entries = entries;
            this.maxCalls = maxCalls;
        }
    }

// One stage entry holds exactly ONE combo, so a round always fires a single
    // diversified query (product synonym x location form x role word). This
    // spreads the per-search Firecrawl budget across distinct queries instead of
    // firing every synonym/role-word combo of an entry in the same round.
    private void addStateEntry(List<StageEntry> entries, QueryFamily family, LocTemplate loc,
                               String word, String type, List<Combo> combos) {
        for (Combo combo : combos) {
            entries.add(new StageEntry(family, loc,
                    word != null ? new String[]{word, type} : null, null, List.of(combo)));
        }
    }

    private void addStateEntry(List<StageEntry> entries, QueryFamily family, LocTemplate loc,
                               String word, String type, List<Combo> combos, String hub) {
        for (Combo combo : combos) {
            entries.add(new StageEntry(family, loc,
                    word != null ? new String[]{word, type} : null, hub, List.of(combo)));
        }
    }

    // One product slot (product term + buyer word + business type) becomes ONE
    // diversified strategy entry, so a round always fires the product query.
    // An optional category-profile leads the RELATED-CATEGORY buyer queries
    // (e.g. "meditation" "spiritual" "wellness" store "<product>" <state>).
    private void addSlotEntry(List<StageEntry> entries, QueryFamily family, LocTemplate loc,
                              String[] slot, Combo combo, String countryWord) {
        addSlotEntry(entries, family, loc, slot, combo, countryWord, null);
    }

    private void addSlotEntry(List<StageEntry> entries, QueryFamily family, LocTemplate loc,
                              String[] slot, Combo combo, String countryWord, List<String> profile) {
        entries.add(new StageEntry(family, loc,
                new String[]{slot[1], slot[2]}, null, List.of(combo), countryWord, profile));
    }

    // The location a strategy targets ("state|city"), used so a
    // duplicate-heavy query only skips further strategies for the SAME
    // location and never silently drops the other cities of the chain.
    private String locationKey(StageEntry entry) {
        Combo combo = entry.combos.get(0);
        String state = combo.state == null ? "" : combo.state.toLowerCase(Locale.ROOT);
        String city = combo.city == null ? "" : collapseWhitespace(combo.city).toLowerCase(Locale.ROOT);
        return state + "|" + city;
    }

    // Query-product synonyms for one product. The user's own phrasing is always
    // first; decorative-jar searches are widened with in-family synonyms (never
    // unrelated products) so each of the 10 Firecrawl requests can surface NEW
    // buyers instead of repeating an overly specific phrase that returns zero.
    // Duplicates are removed so no synonym is ever searched twice.
    private List<String> productQuerySynonyms(String product) {
        String norm = normalizeProductForValidation(product);
        Set<String> words = new HashSet<>(tokenizeWords(norm));
        List<String> synonymList = isDecorativeJarProduct(words)
                ? DECORATIVE_JAR_QUERY_SYNONYMS : List.of();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (product != null && !product.isBlank()) {
            String key = product.trim().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                result.add(product.trim());
            }
        }
        for (String synonym : synonymList) {
            String key = synonym.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                result.add(synonym);
            }
        }
        return result;
    }

    // Builds the combos for one product and location: one combo per query
    // synonym. The user's original product is kept as the label; the synonym is
    // the effective phrase fired at Firecrawl and used by the relevance gate.
    private List<Combo> combosFor(String product, String stateValue, String city) {
        List<Combo> combos = new ArrayList<>();
        for (String synonym : productQuerySynonyms(product)) {
            combos.add(new Combo(product, synonym, stateValue, city));
        }
        return combos;
    }

    private List<String> parseCommaSeparated(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(this::collapseWhitespace)
                .filter(s -> !s.isEmpty())
                .forEach(s -> unique.putIfAbsent(s.toLowerCase(Locale.ROOT), s));
        return new ArrayList<>(unique.values());
    }

    private String stateAbbreviation(String state) {
        if (state == null || state.isBlank()) {
            return "";
        }
        String abbr = STATE_ABBREVIATIONS.get(state.trim().toLowerCase(Locale.ROOT));
        return abbr != null ? abbr : "";
    }

    private String buildLocationDisplay(String state, String city) {
        return buildLocationDisplay(state, city, null);
    }

    /**
     * Builds the display location string. The SEARCHED country is honored when
     * the city/state alone cannot decide it (e.g. the user searched Canada and
     * a Canadian province is implied, or a city has no provable province), so a
     * Canada search never renders "Toronto, USA" / "Ontario, USA". When no
     * country is passed, the US/Canada decision is still made from a real state
     * or province name.
     */
    private String buildLocationDisplay(String state, String city, String country) {
        String countryLabel = country != null && !country.isBlank()
                ? country
                : (state != null && !state.isBlank()
                        ? countryNameForState(state)
                        : "USA");
        if (city != null && !city.isBlank() && state != null && !state.isBlank()) {
            return city + ", " + state + ", " + countryLabel;
        }
        if (city != null && !city.isBlank()) {
            return city + ", " + countryLabel;
        }
        if (state != null && !state.isBlank()) {
            return state + ", " + countryLabel;
        }
        // A location we cannot verify is never guessed - it is marked Unknown.
        return "Unknown";
    }

    private String countryNameForState(String state) {
        if (state == null) {
            return "USA";
        }
        String key = state.trim().toLowerCase(Locale.ROOT);
        return CANADIAN_PROVINCE_ABBREVIATIONS.containsKey(key) ? "Canada" : "USA";
    }

    // Builds one diversified query from a combo and its strategy:
    //   product-first for the standard slots
    //     "Singing Bowls" "for sale" "San Antonio", Texas   (profile word quoted)
    //     "Decorative Lanterns" wholesaler Texas
    //   category-profile first for the RELATED-CATEGORY buyer slots
    //     "meditation" "spiritual" "wellness" store "singing bowls" Texas
    // The buyer word is quoted when it is multi-word ("for sale"), so Firecrawl
    // treats it as one phrase instead of two random keywords.
    private String buildDiversifiedQuery(Combo combo, StageEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.profile != null && !entry.profile.isEmpty()) {
            for (String pw : entry.profile) {
                sb.append("\"").append(pw).append("\" ");
            }
            if (!entry.wordless()) {
                sb.append(entry.word[0]);
            }
            sb.append(" \"").append(combo.queryProduct.trim()).append("\"");
        } else {
            sb.append("\"").append(combo.queryProduct.trim()).append("\"");
            if (!entry.wordless()) {
                sb.append(" ");
                appendBuyerWord(sb, entry.word[0], entry.quoteWord);
            }
        }
        String abbr = stateAbbreviation(combo.state);
        switch (entry.loc) {
            case CITY_ABBR:
                if (combo.city != null && !combo.city.isBlank()) {
                    sb.append(" \"").append(combo.city.trim()).append("\"");
                    if (!abbr.isEmpty()) sb.append(" ").append(abbr);
                }
                break;
            case CITY_ONLY:
                if (combo.city != null && !combo.city.isBlank()) {
                    sb.append(" \"").append(combo.city.trim()).append("\"");
                }
                break;
            case HUB_ABBR:
                if (entry.hub != null && !entry.hub.isBlank()) {
                    sb.append(" \"").append(entry.hub).append("\"");
                    if (!abbr.isEmpty()) sb.append(" ").append(abbr);
                }
                break;
            case STATE_FULL:
                if (combo.state != null && !combo.state.isBlank()) {
                    sb.append(" ").append(combo.state.trim());
                }
                break;
            case STATE_ABBR:
                if (!abbr.isEmpty()) sb.append(" ").append(abbr);
                break;
            case CITY_STATE_FULL:
                if (combo.city != null && !combo.city.isBlank()) {
                    sb.append(" \"").append(combo.city.trim()).append("\"");
                    if (combo.state != null && !combo.state.isBlank()) {
                        sb.append(" ").append(combo.state.trim());
                    }
                }
                break;
            default:
                break; // NONE: product + word, then the country word
        }
        // Country-wide (nationwide) queries append the requested country so
        // Firecrawl scopes results to the USA / Canada.
        if (entry.loc == LocTemplate.NONE && entry.countryWord != null
                && !entry.countryWord.isBlank()) {
            sb.append(" ").append(entry.countryWord);
        }
        return sb.toString();
    }

    /** Appends the buyer-profile word; multi-word words are quoted ("for sale")
     *  so Firecrawl matches them as one exact phrase, single words stay bare. */
    /** Appends the buyer-profile word; a multi-word word is quoted ("for sale")
     *  so Firecrawl matches it as one exact phrase, a single word stays bare. A
     *  related-industry / use-case CATEGORY is always quoted, because it names a
     *  business type ("spas", "yoga studios") that must match as one phrase
     *  rather than as loose keywords. */
    private void appendBuyerWord(StringBuilder sb, String word, boolean alwaysQuote) {
        if (word == null || word.isBlank()) {
            return;
        }
        String w = word.trim();
        if (w.contains(" ") || alwaysQuote) {
            sb.append("\"").append(w).append("\"");
        } else {
            sb.append(w);
        }
    }

    /**
     * Geo-targeting value passed to Firecrawl's search `location` parameter
     * (e.g. "San Antonio, Texas, United States" or "Vancouver, British
     * Columbia, Canada"). Only the city/state/country the user actually asked
     * for is used - nothing is assumed.
     */
    private String buildFirecrawlLocation(Combo combo) {
        StringBuilder sb = new StringBuilder();
        if (combo.city != null && !combo.city.isBlank()) {
            sb.append(combo.city.trim());
        }
        if (combo.state != null && !combo.state.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(combo.state.trim());
        }
        if (sb.length() == 0) {
            return null;
        }
        sb.append(", ").append(combo.country != null && combo.country.equalsIgnoreCase("Canada")
                ? "Canada" : "United States");
        return sb.toString();
    }

    /**
     * Fires ONE strategy per combo per round, ONE AT A TIME and globally paced
     * by the Firecrawl rate limiter, so a parallel burst can never trip the
     * account's requests-per-minute limit. The search adapts to the remaining
     * per-search budget: the next strategy is tried for each active combo and
     * firing stops at once when the Firecrawl request budget is exhausted.
     */
    private CombosRound executeCombosSerially(List<Combo> combos, String apiKey,
                                              StageEntry entry, SearchBudget budget,
                                              Set<String> firedQueries, int round) {
        Map<Combo, List<QueryTaskResult>> grouped = new LinkedHashMap<>();
        int usedTypeCount = 0;
        boolean budgetExhausted = false;

        for (Combo combo : combos) {
            if (budget.exhausted()) {
                budgetExhausted = true;
                break;
            }
            List<QueryTaskResult> tasks = new ArrayList<>();
            usedTypeCount = 1;
            String query = buildDiversifiedQuery(combo, entry);
            String wordDisplay = entry.wordless() ? "-" : entry.word[0];
            // Required per-request log: round, product phrase, buyer term, city,
            // state and the FULL query as actually fired at Firecrawl.
            logger.info("SEARCH ROUND {} | Product phrase = \"{}\" | Buyer term = {} | "
                            + "City = {} | State = {} | Query = \"{}\"",
                    round, combo.queryProduct,
                    wordDisplay,
                    combo.city != null ? combo.city : "(any)",
                    combo.state != null ? combo.state : "(any)",
                    query);

            // Never fire a query that was already fired this search (identical
            // synonym + role word + location). A repeated query would spend a
            // budget slot for nothing, so it is skipped and the budget moves to
            // a different strategy.
            String queryKey = query.toLowerCase(Locale.ROOT);
            if (!firedQueries.add(queryKey)) {
                logger.info("Skipping already-fired duplicate query '{}' - moving to the next strategy",
                        query);
                grouped.put(combo, tasks);
                continue;
            }

            List<BuyerSearchResult> results;
            try {
                results = executeSearch(query, entry.businessType(), apiKey, RESULTS_PER_QUERY,
                        buildFirecrawlLocation(combo), budget);
            } catch (BudgetExhaustedException e) {
                budgetExhausted = true;
                results = List.of();
            } catch (Exception e) {
                logger.error("Failed to execute search for '{}' ({}): {}",
                        combo.queryLabel(), wordDisplay, e.getMessage());
                results = List.of();
            }
            tasks.add(new QueryTaskResult(query, entry.word, results));
            grouped.put(combo, tasks);
        }

        return new CombosRound(grouped, usedTypeCount, budgetExhausted);
    }

    private static final class CombosRound {
        final Map<Combo, List<QueryTaskResult>> grouped;
        final int usedTypeCount;
        final boolean budgetExhausted;

        CombosRound(Map<Combo, List<QueryTaskResult>> grouped, int usedTypeCount, boolean budgetExhausted) {
            this.grouped = grouped;
            this.usedTypeCount = usedTypeCount;
            this.budgetExhausted = budgetExhausted;
        }
    }

    private static final class QueryTaskResult {
        final String query;
        final String[] queryDef;
        final List<BuyerSearchResult> results;

        QueryTaskResult(String query, String[] queryDef, List<BuyerSearchResult> results) {
            this.query = query;
            this.queryDef = queryDef;
            this.results = results != null ? results : List.of();
        }
    }

    /**
     * Counts the actual Firecrawl API requests (first attempts AND 429 retries)
     * for a single buyer search. Every HTTP attempt goes through tryAcquire(),
     * so a search can never spend more than the capped budget and immediately
     * stops firing once the cap is reached.
     */
    private static final class SearchBudget {
        private final int maxRequests;
        private final AtomicInteger requests = new AtomicInteger(0);
        private final AtomicInteger retries = new AtomicInteger(0);

        SearchBudget(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        int max() {
            return maxRequests;
        }

        int used() {
            return requests.get();
        }

        int retries() {
            return retries.get();
        }

        boolean exhausted() {
            return requests.get() >= maxRequests;
        }

        boolean tryAcquire() {
            int current;
            do {
                current = requests.get();
                if (current >= maxRequests) {
                    return false;
                }
            } while (!requests.compareAndSet(current, current + 1));
            return true;
        }

        boolean tryAcquireRetry() {
            if (!tryAcquire()) {
                return false;
            }
            retries.incrementAndGet();
            return true;
        }
    }

    /** Thrown when the per-search Firecrawl request budget is spent. */
    private static final class BudgetExhaustedException extends RuntimeException {
        BudgetExhaustedException(String message) {
            super(message);
        }
    }

    private static final class Candidate {
        final Combo combo;
        final BuyerSearchResult result;

        Candidate(Combo combo, BuyerSearchResult result) {
            this.combo = combo;
            this.result = result;
        }
    }

    // Thread-safe per-search counters for the FINAL reject-breakdown log
    // (requirement: EXACT_CITY/SAME_STATE/SAME_COUNTRY/FINAL + reject buckets).
    private static final class SearchTally {
        final AtomicLong productRejects = new AtomicLong();
        final AtomicLong commercialRejects = new AtomicLong();
        final AtomicLong businessTypeRejects = new AtomicLong();
        final AtomicLong locationRejects = new AtomicLong();
        final AtomicLong contentRejects = new AtomicLong();
        final AtomicLong identityRejects = new AtomicLong();
        final AtomicLong potential = new AtomicLong();
        final AtomicLong unverified = new AtomicLong();
        final AtomicLong verified = new AtomicLong();

        String breakdown() {
            return "PRODUCT=" + productRejects.get()
                    + " | COMMERCIAL=" + commercialRejects.get()
                    + " | BUSINESS=" + businessTypeRejects.get()
                    + " | LOCATION=" + locationRejects.get()
                    + " | CONTENT=" + contentRejects.get()
                    + " | IDENTITY=" + identityRejects.get();
        }
    }

    private List<BuyerSearchResult> verifyCandidatesInParallel(List<BuyerSearchResult> batch,
                                                               Map<String, String> homepageCache,
                                                               Map<String, String> resultPageCache,
                                                               Set<String> verifiedDomains,
                                                               SearchTally tally,
                                                               java.util.concurrent.atomic.AtomicLong dupSkippedOut) {
        // Results are kept in Firecrawl ranking order so the highest-ranked
        // relevant buyers are returned first, not whichever check finishes first.
        BuyerSearchResult[] passed = new BuyerSearchResult[batch.size()];
        // Requirement 6: each normalized domain is verified at most once per
        // search. Domains verified in an earlier round keep their verdict - a
        // rejected domain is never re-verified for every later query.
        boolean[] schedule = new boolean[batch.size()];
        int dupSkipped = 0;
        for (int i = 0; i < batch.size(); i++) {
            String domain = normalizeWebsite(batch.get(i).getWebsite());
            if (domain != null && verifiedDomains.add(domain)) {
                schedule[i] = true;
            } else {
                dupSkipped++;
                logger.info("{} - '{}' already verified earlier in this search - keeping its verdict",
                        CODE_DUPLICATE, domain == null ? batch.get(i).getWebsite() : domain);
            }
        }
        if (dupSkippedOut != null) {
            dupSkippedOut.set(dupSkipped);
        }
        ExecutorService executor = Executors.newFixedThreadPool(EMAIL_DISCOVERY_PARALLELISM);
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                if (!schedule[i]) {
                    continue;
                }
                final int index = i;
                final BuyerSearchResult result = batch.get(i);
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (isRelevantBuyer(result, homepageCache, resultPageCache, tally)) {
                            passed[index] = result;
                        }
                    } catch (Exception e) {
                        if (hasSafeCheapProductAnchor(result)) {
                            markAcceptedCandidateAfterVerificationError(result,
                                    "verification error: " + e.getMessage(), tally);
                            passed[index] = result;
                        } else {
                            tally.productRejects.incrementAndGet();
                            logger.info("{} '{}' ({}) - verification error without a safe product anchor",
                                    CODE_WRONG_PRODUCT, result.getCompanyName(), result.getWebsite());
                        }
                    }
                }, executor));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
        List<BuyerSearchResult> verified = new ArrayList<>(batch.size());
        for (BuyerSearchResult result : passed) {
            if (result != null) {
                verified.add(result);
            }
        }
        return verified;
    }

    private void markAcceptedCandidateAfterVerificationError(BuyerSearchResult result, String reason,
                                                             SearchTally tally) {
        String classification = result.getClassification();
        if (classification == null || classification.isBlank()
                || PIPELINE_STATUS_POTENTIAL.equals(classification)
                || PIPELINE_STATUS_UNVERIFIED.equals(classification)) {
            classification = CLASSIFICATION_OTHER;
        }
        result.setClassification(classification);
        if (result.getBusinessType() == null || result.getBusinessType().isBlank()) {
            result.setBusinessType(CLASSIFICATION_OTHER);
        }
        result.setLocationClass(LOCATION_CLASS_UNKNOWN);
        result.setState(null);
        result.setCity(null);
        result.setLocation("Unknown");
        result.setLocationSource(null);
        tally.verified.incrementAndGet();
        logger.warn("ACCEPTED '{}' ({}) - product relevance passed before a verification error | reason={}",
                result.getCompanyName(), result.getWebsite(), reason);
    }

    /**
     * Proof the buyer's own website URL carries the product, used when the
     * homepage cannot be crawled at all: the search-listing title/snippet are
     * only the engine echoing the queried terms, so a website that is
     * unreachable is accepted ONLY when its own URL (shopping path, e.g.
     * /crystal-singing-bowls/, or an alphanumeric domain) holds a strong
     * product phrase for any of the user's entered products. The ORIGINAL
     * Firecrawl result URL (sourceUrl) is the primary source for this proof -
     * its product/collection path is exactly the commercial page (requirement 6).
     */
    private boolean hasWebsiteUrlProductEvidence(BuyerSearchResult result) {
        String url = result.getSourceUrl() != null && !result.getSourceUrl().isBlank()
                ? result.getSourceUrl() : result.getWebsite();
        String path = shopPathText(url);
        String domain = domainEvidence(result.getWebsite());
        for (String product : parseCommaSeparated(result.getProductName())) {
            String p = normalizeProductForValidation(product);
            if (p == null || p.isBlank()) {
                continue;
            }
            ProductSpec spec = buildProductSpec(p, new HashSet<>(tokenizeWords(p)));
            List<String> candidates = new ArrayList<>();
            candidates.add(p);
            for (String phrase : spec.strongPhrases) {
                if (!candidates.contains(phrase)) {
                    candidates.add(phrase);
                }
            }
            if (findFirstStrongPhrase(candidates, "", path, "", domain, "") != null) {
                return true;
            }
            // A product phrase in the EXACT sourceUrl path is product-page
            // evidence even without a shopping marker (/meditation-singing-bowls
            // names the product directly). The sourceUrl is the page Firecrawl
            // actually returned, so its path is the strongest URL-product
            // evidence - verification must never ignore it just because the
            // path is not a /product//collections/ marker path.
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank() && containsPhrase(path, candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fetches the ORIGINAL Firecrawl RESULT page (sourceUrl) when it is not
     * already the homepage - the actual product/collection/shop page the engine
     * found is the PRIMARY commercial-evidence source (requirement 2/3/6).
     * Plain HTTP (EmailDiscoveryService.fetchUrl), cached per sourceUrl; returns
     * null when the result URL is the homepage or unavailable, so verification
     * falls back to the homepage without duplicate work.
     */
    private String resultPageHtml(BuyerSearchResult result, Map<String, String> resultPageCache) {
        String sourceUrl = result.getSourceUrl();
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return null;
        }
        String path = pathOf(sourceUrl);
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;  // the result URL IS the homepage - homepage cache covers it
        }
        return resultPageCache.computeIfAbsent(sourceUrl,
                u -> emailDiscoveryService.fetchUrl(u));
    }

    /**
     * TRUE when {@code candidate} is a strictly better product-page URL than
     * {@code current}: a deep product/collection/shop path beats the bare
     * homepage, and a shopping path beats a non-shopping path (requirement 11:
     * keep the strongest product-page URL as sourceUrl).
     */
    private boolean isBetterProductUrl(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        String cPath = pathOf(current);
        String nPath = pathOf(candidate);
        if (cPath == null || cPath.isEmpty() || "/".equals(cPath)) {
            return true; // current is only the homepage
        }
        if (nPath == null || nPath.isEmpty()) {
            return false; // candidate is only the homepage
        }
        boolean nShops = isShoppingPath(nPath);
        boolean cShops = isShoppingPath(cPath);
        if (nShops != cShops) {
            return nShops; // prefer the product/shop page
        }
        int nSlash = nPath.length() - nPath.replace("/", "").length();
        int cSlash = cPath.length() - cPath.replace("/", "").length();
        return nSlash > cSlash; // deeper = more specific product page
    }

    private boolean isRelevantBuyer(BuyerSearchResult result, Map<String, String> homepageCache,
                                    Map<String, String> resultPageCache,
                                    SearchTally tally) {
        String evidence = buildEvidence(result);
        String homepage = homepageCache.computeIfAbsent(result.getWebsite(),
                w -> emailDiscoveryService.fetchHomepage(w));
        // PRIMARY verification target: the ORIGINAL Firecrawl result URL
        // (sourceUrl) - the actual product/collection/shop page the engine found.
        // The homepage stays SECONDARY (business/location evidence). When the
        // result URL is the homepage itself, only the homepage is fetched.
        String resultPage = resultPageHtml(result, resultPageCache);
        boolean websiteAvailable = !(homepage == null || homepage.isBlank())
                || !(resultPage == null || resultPage.isBlank());
        // Requirement 11 evidence attribution: the EXACT sourceUrl page - and its own
        // URL path - is the PRIMARY product/commercial evidence source; the
        // normalized homepage stays the secondary business/location source.
        // These flags drive the PRODUCT_EVIDENCE_SOURCE + COMMERCIAL_EVIDENCE_SOURCE
        // columns of every FINAL VERDICT line.
        String sourceUrl = result.getSourceUrl() != null && !result.getSourceUrl().isBlank()
                ? result.getSourceUrl() : result.getWebsite();
        boolean resultPageFetched = resultPage != null && !resultPage.isBlank();
        boolean homepageAvailable = homepage != null && !homepage.isBlank();
        boolean urlProductEvidence = hasWebsiteUrlProductEvidence(result);
        String productPageEvidenceLabel = urlProductEvidence ? "YES" : "NO";
        String evidenceSourceLabel = commercialEvidenceSourceLabel(
                urlProductEvidence, resultPageFetched, homepageAvailable);
        logger.info("VERIFY | SOURCE_URL: {} | NORMALIZED_WEBSITE: {} | "
                        + "VERIFY_PRODUCT_URL: {} | VERIFY_HOMEPAGE_URL: {}",
                sourceUrl, result.getWebsite(), sourceUrl, result.getWebsite());
        logger.info("EVIDENCE | SOURCE_URL: {} | NORMALIZED_WEBSITE: {} | "
                        + "PRODUCT_EVIDENCE_SOURCE: {} | COMMERCIAL_EVIDENCE_SOURCE: {}",
                sourceUrl, result.getWebsite(), evidenceSourceLabel, evidenceSourceLabel);

        // Gates may read the search-listing evidence (the candidate's OWN title/
        // snippet) and the buyer's actual pages - never the fired query text.
        String siteTextPrepared = prepareText(homepage, resultPage);
        String fullText = buildRawText(evidence, homepage, resultPage);
        String titleText = prepareText(result.getRawTitle());
        String snippetText = prepareText(result.getSnippet());
        String path = shopPathText(sourceUrl);
        String websitePath = shopPathText(result.getWebsite());
        String domain = domainEvidence(result.getWebsite());
        String productLabel = result.getProductName() != null && !result.getProductName().isBlank()
                ? result.getProductName() : effectiveSearchProduct(result);

        String matchType = "none";
        String commercialEvidence = "none";
        String businessEvidence = "none";
        String locationEvidence = "not used for acceptance";
        String rejectionReason = null;

        String qualification = isRelevantToEnteredProduct(result, siteTextPrepared);
        if (!websiteAvailable) {
            boolean listingAnchorProof = urlProductEvidence
                    || hasListingAnchorProductPhrase(result);
            logger.info("WEBSITE_UNAVAILABLE | buyer='{}' | website={} | "
                            + "URL/TITLE_PRODUCT_EVIDENCE: {}",
                    result.getCompanyName(), result.getWebsite(),
                    listingAnchorProof
                            ? "product phrase in the listing headline/URL (snippet-only evidence is not accepted)"
                            : "none (snippet keywords and URL-alone echoes are not accepted)");
            if (!listingAnchorProof) {
                logger.info("{} '{}' ({}) - website unavailable and no non-snippet product "
                                + "evidence (headline/URL product evidence absent)",
                        CODE_WRONG_PRODUCT, result.getCompanyName(), result.getWebsite());
                rejectionReason = "website unavailable and no non-snippet product evidence";
                tally.productRejects.incrementAndGet();
                logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                        businessEvidence, locationEvidence, rejectionReason,
                        productPageEvidenceLabel, evidenceSourceLabel);
                return false;
            }
            if (qualification == null) {
                qualification = QUAL_PRODUCT_RELEVANT;
            }
        } else if (qualification == null) {
            logger.info("{} '{}' ({}) - not relevant to any entered product family "
                            + "(no canonical, variant, or relevant Home Decor phrase in the candidate or website evidence)",
                    CODE_WRONG_PRODUCT, result.getCompanyName(), result.getWebsite());
            rejectionReason = "not relevant to any entered product family";
            tally.productRejects.incrementAndGet();
            logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                    businessEvidence, locationEvidence, rejectionReason,
                    productPageEvidenceLabel, evidenceSourceLabel);
            return false;
        }
        matchType = matchTypeLabelOf(qualification);
        logger.info("PRODUCT_RELEVANT '{}' ({}) - product evidence '{}' verified for '{}'",
                result.getCompanyName(), result.getWebsite(), qualification, productLabel);
        logger.info("PRODUCT_MATCH '{}' ({}) - product evidence '{}' verified for '{}'",
                result.getCompanyName(), result.getWebsite(), qualification, productLabel);

        commercialEvidence = commercialProductEvidenceOf(result, fullText, titleText, snippetText,
                siteTextPrepared, websitePath, path);
        String websiteHost = hostOf(result.getWebsite());
        String websiteHostL = websiteHost == null ? "" : websiteHost;
        boolean governmentSite = websiteHostL.matches("(?i).*\\.gov(\\.[a-z]{2})?$")
                || websiteHostL.endsWith(".mil") || path.contains("/gov")
                || titleText.contains("department of") || titleText.contains("ministry of")
                || titleText.contains("official website of");
        if (governmentSite) {
            logger.info("{} '{}' ({}) - government website is never a buyer",
                    CODE_NO_BUYER_INTENT, result.getCompanyName(), result.getWebsite());
            rejectionReason = "government website is not a buyer";
            tally.contentRejects.incrementAndGet();
            logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                    businessEvidence, locationEvidence, rejectionReason,
                    productPageEvidenceLabel, evidenceSourceLabel);
            return false;
        }
        boolean contentArticleSite = isArticleLike(websiteHostL, path, titleText);
        if (contentArticleSite) {
            logger.info("{} '{}' ({}) - article/blog/news/content page is never a buyer",
                    CODE_NO_BUYER_INTENT, result.getCompanyName(), result.getWebsite());
            rejectionReason = "article/blog/news/content page is not a buyer";
            tally.contentRejects.incrementAndGet();
            logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                    businessEvidence, locationEvidence, rejectionReason,
                    productPageEvidenceLabel, evidenceSourceLabel);
            return false;
        }

        // LIGHT IRRELEVANT-CONTENT CHECK (full page): the product words are
        // really there, but the PAGE is a music/artist site, a news or tourism
        // page, an event/exhibitor registration page, an academic/medical page
        // or an unrelated industry (fashion / technology / finance / ...).
        // Stays LIGHT by design: a page that demonstrates it sells physical
        // products (shopping URL path, product-named URL, catalogue, cart,
        // store-selling copy) is never dropped here, so a genuine Home Decor /
        // Wellness / Spiritual / Meditation / Yoga / Gift store that mentions
        // music, events or travel is still accepted. No commercial evidence,
        // business type, buyer role, email, city or state is required.
        String irrelevantReason = irrelevantContentReason(sourceUrl, titleText, snippetText,
                siteTextPrepared, hasProductSellingEvidence(titleText, snippetText,
                        siteTextPrepared, path, urlProductEvidence));
        if (irrelevantReason != null) {
            logger.info("IRRELEVANT_CONTENT '{}' ({}) - {}",
                    result.getCompanyName(), result.getWebsite(), irrelevantReason);
            rejectionReason = irrelevantReason;
            tally.contentRejects.incrementAndGet();
            logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                    businessEvidence, locationEvidence, rejectionReason,
                    productPageEvidenceLabel, evidenceSourceLabel);
            return false;
        }


        // CONDITION 2 - HOMEPAGE / BUSINESS VERIFICATION with LIGHT evidence.
        // The actual website (the exact sourceUrl page plus its homepage) has
        // been fetched above, and the content-kind screens above have already
        // removed news / event / attendee-list / government / medical /
        // academic / music / directory / unrelated-industry pages. What is left
        // only has to show that the SITE ITSELF is a real business / store /
        // supplier.
        //
        // This is deliberately LIGHT so "No buyers found" cannot come back:
        //   * ONE generic business word is enough (about us, contact us, a cart,
        //     a company suffix, any store/shop/supplier word);
        //   * a storefront URL (/shop, /collections/...) counts on its own;
        //   * NO specific business type is required, NO email is required, and
        //     NO commercial evidence next to the product phrase is required.
        // The old strict Commercial Evidence + Business Type + Location gates
        // are NOT restored.
        businessEvidence = businessTypeEvidenceOf(fullText, path, domain);
        if (!websiteAvailable) {
            // Nothing was fetched, so there is no homepage evidence to judge.
            // The listing headline/URL product anchor checked above already
            // stands as the proof, and the website stays unverifiable - it is
            // NOT treated as a failed business check.
            logger.info("BUSINESS_VERIFIED '{}' ({}) - website could not be fetched, so the "
                            + "listing headline/URL product anchor stands as the light evidence",
                    result.getCompanyName(), result.getWebsite());
        } else {
            String lightBusinessEvidence = lightBusinessEvidenceOf(result, siteTextPrepared,
                    path, websitePath, domain, businessEvidence);
            if (lightBusinessEvidence == null) {
                logger.info("BUSINESS_NOT_VERIFIED '{}' ({}) - the website shows no generic "
                                + "business / store / supplier evidence, so it is not a real "
                                + "business that could buy the product",
                        result.getCompanyName(), result.getWebsite());
                rejectionReason = "website shows no business / store / supplier evidence";
                tally.businessTypeRejects.incrementAndGet();
                logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                        businessEvidence, locationEvidence, rejectionReason,
                        productPageEvidenceLabel, evidenceSourceLabel);
                return false;
            }
            logger.info("BUSINESS_VERIFIED '{}' ({}) - {} (light check: no specific business "
                            + "type, email or exact city required)",
                    result.getCompanyName(), result.getWebsite(), lightBusinessEvidence);
        }

        String businessType = businessTypeFromEvidence(fullText, path, domain);
        logProductValidation(productLabel, result.getRawTitle(), result.getSnippet(),
                result.getWebsite(), effectiveSearchProduct(result), "ACCEPT",
                commercialEvidence == null ? productLabel : commercialEvidence,
                commercialEvidence == null ? "none" : commercialEvidence,
                businessEvidence == null ? "none" : businessEvidence,
                qualification, "product + light business evidence accepted without a "
                        + "commercial-evidence, business-type, email or exact-city gate");

        String requestedLocationKey = result.getState() != null && !result.getState().isBlank()
                || result.getCity() != null && !result.getCity().isBlank() ? "requested" : "";
        boolean cityRequested = result.getCity() != null && !result.getCity().isBlank();
        boolean stateRequested = result.getState() != null && !result.getState().isBlank();
        // Captured BEFORE the extracted address overwrites the result's
        // city/state, so every log and check below compares against what the
        // user actually requested.
        String requestedCity = result.getCity();
        String requestedState = result.getState();
        String requestedCountry = result.getCountry();
        ExtractedLocation resolvedLocation = resolveBusinessLocationFrom(result.getWebsite(),
                result.getSearchAddress(), homepage);
        boolean resolvedCity = resolvedLocation != null && resolvedLocation.city != null;
        boolean resolvedState = resolvedLocation != null && resolvedLocation.state != null;
        boolean cityMatches = cityRequested && resolvedCity
                && sameCity(resolvedLocation.city, requestedCity);
        boolean stateMatches = stateRequested && resolvedState
                && sameState(resolvedLocation.state, requestedState);

        // CONDITION 3a - STATE / COUNTRY RESPECTED. A POSITIVELY RESOLVED
        // address outside the requested state (or in another country) is a real
        // conflict and is rejected: a Texas search must not return a UK or a
        // California wholesaler. An UNEEXTRACTED city is never a conflict, and a
        // different city inside the requested state is never a conflict either -
        // both are handled below by ranking (SAME_STATE / UNKNOWN).
        String locationConflict = locationConflictReason(resolvedLocation, result.getWebsite(),
                requestedState, requestedCountry, stateMatches);
        if (locationConflict != null) {
            logger.info("{} '{}' ({}) - {} (requested: city='{}', state='{}', country='{}')",
                    CODE_LOCATION_CONFLICT, result.getCompanyName(), result.getWebsite(),
                    locationConflict,
                    cityRequested ? requestedCity : "-",
                    stateRequested ? requestedState : "-",
                    requestedCountry != null ? requestedCountry : "-");
            rejectionReason = locationConflict;
            tally.locationRejects.incrementAndGet();
            logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                    businessEvidence, "rejected: " + locationConflict, rejectionReason,
                    productPageEvidenceLabel, evidenceSourceLabel);
            return false;
        }

        if (resolvedLocation != null) {
            applyExtractedLocation(result, resolvedLocation);
        } else {
            resolveBusinessLocation(result, evidence, homepage);
        }

        String locationClass;
        if (cityMatches && (!stateRequested || stateMatches)) {
            locationClass = LOCATION_CLASS_EXACT_CITY;
        } else if (stateMatches) {
            locationClass = LOCATION_CLASS_SAME_STATE;
        } else if (requestedLocationKey.isBlank() && result.getLocation() != null
                && !result.getLocation().isBlank()
                && !"Unknown".equalsIgnoreCase(result.getLocation())) {
            locationClass = LOCATION_CLASS_SAME_COUNTRY;
        } else {
            locationClass = LOCATION_CLASS_UNKNOWN;
        }
        if (LOCATION_CLASS_UNKNOWN.equals(locationClass) && resolvedLocation == null) {
            result.setState(null);
            result.setCity(null);
            result.setLocation("Unknown");
            result.setLocationSource(null);
        }

        // CONDITION 3 - LOCATION PREFERENCE, graded. It is a PREFERENCE, not a
        // hard gate, except for the positively resolved state/country conflict
        // already rejected above:
        //   EXACT_CITY - preferred, always accepted
        //   SAME_STATE - acceptable, always accepted (a different Texas city for
        //                 a Texas search is never a rejection)
        //   UNKNOWN    - accepted ONLY when the product/business relevance is
        //                 strong (product in the URL / listing headline, an
        //                 exact product match, or store-selling evidence). A
        //                 loose category hit with no store evidence and an
        //                 unknown location is dropped.
        // No email and no exact city are ever required to accept a candidate.
        if (LOCATION_CLASS_UNKNOWN.equals(locationClass)
                && !hasStrongProductEvidence(result, matchType, siteTextPrepared, path)) {
            logger.info("LOCATION_UNKNOWN '{}' ({}) - no city/state could be extracted "
                            + "(requested: city='{}', state='{}') and the product/business "
                            + "relevance is not strong enough to accept an unknown location",
                    result.getCompanyName(), result.getWebsite(),
                    cityRequested ? requestedCity : "-",
                    stateRequested ? requestedState : "-");
            rejectionReason = "unknown location without strong product/business relevance";
            tally.locationRejects.incrementAndGet();
            logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                    businessEvidence, "rejected: unknown location, weak relevance",
                    rejectionReason, productPageEvidenceLabel, evidenceSourceLabel);
            return false;
        }
        String locationLogToken = locationLogTokenOf(locationClass);
        if (LOCATION_CLASS_UNKNOWN.equals(locationClass)) {
            logger.info("LOCATION_UNKNOWN '{}' ({}) - no city/state could be extracted "
                            + "(requested: city='{}', state='{}'); product/business relevance "
                            + "is strong, so the website is ACCEPTED and ranked last",
                    result.getCompanyName(), result.getWebsite(),
                    cityRequested ? requestedCity : "-",
                    stateRequested ? requestedState : "-");
        } else {
            logger.info("{} LOCATION_MATCH={} '{}' ({}) - {} (requested: city='{}', state='{}')",
                    locationLogToken, locationClass, result.getCompanyName(), result.getWebsite(),
                    result.getLocation() != null ? result.getLocation() : "unknown",
                    cityRequested ? requestedCity : "-",
                    stateRequested ? requestedState : "-");
        }

        String classificationSource = businessType;
        String currentClassification = result.getClassification();
        if (currentClassification != null && !currentClassification.isBlank()
                && !PIPELINE_STATUS_POTENTIAL.equals(currentClassification)
                && !PIPELINE_STATUS_UNVERIFIED.equals(currentClassification)
                && !CLASSIFICATION_OTHER.equals(currentClassification)) {
            classificationSource = currentClassification;
        }
        String finalClassification = classificationSource;
        result.setClassification(finalClassification);
        result.setBusinessType(businessType);
        result.setLocationClass(locationClass);
        result.setRelevanceScore(relevanceScoreOf(matchType, commercialEvidence, businessType));
        if (result.getBuyerName() == null) {
            String name = extractBusinessName(result, effectiveSearchProduct(result));
            if (name != null) {
                result.setCompanyName(name);
                result.setBuyerName(name);
            }
        }
        tally.verified.incrementAndGet();
        locationEvidence = locationClass + " - "
                + (result.getLocation() != null ? result.getLocation() : "unknown")
                + (resolvedLocation != null && resolvedLocation.source != null
                        ? " (source: " + resolvedLocation.source + ")" : "");
        logFinalVerdict(result, productLabel, matchType, commercialEvidence,
                businessEvidence, locationEvidence, null,
                productPageEvidenceLabel, evidenceSourceLabel);
        logger.info("ACCEPTED '{}' ({}) - product evidence verified for '{}' [{}={}]",
                result.getCompanyName(), result.getWebsite(), effectiveSearchProduct(result),
                locationClass, finalClassification);
        return true;
    }

    /** Evidence-strength score used to order results with the SAME location
     *  priority higher (product-match tier, real commerce, clear business
     *  type). Location priority always dominates; this only breaks ties. */
    private int relevanceScoreOf(String matchType, String commercialEvidence, String businessType) {
        int score = 0;
        if (matchType != null) {
            if (matchType.startsWith("EXACT")
                    || matchType.contains("VALID_VARIANT") || matchType.contains("ATTRIBUTE")) {
                score += 4;
            } else if (matchType.contains("STORE/SELLER")) {
                score += 3;
            } else if (matchType.contains("LISTING") || matchType.contains("SEARCH_RESULT")) {
                score += 2;
            } else if (matchType.contains("RELATED")) {
                score += 1;
            }
        }
        if (commercialEvidence != null && commercialEvidence.contains("website")) {
            score += 2;
        }
        if (businessType != null && !CLASSIFICATION_OTHER.equals(businessType)) {
            score += 1;
        }
        return score;
    }

    /** Maps the product evidence tier to a human description. */
    private String matchTypeLabelOf(String qualification) {
        if (qualification == null) {
            return "none";
        }
        switch (qualification) {
            case QUAL_PRODUCT_RELEVANT:
                return "SEARCH_RESULT_PRODUCT_EVIDENCE (strong product phrase in the candidate listing; accepted)";
            case QUAL_PRODUCT_LISTING:
                return "LISTING (product phrase in the candidate listing; accepted)";
            case QUAL_DIRECT_PRODUCT_BUYER:
                return "EXACT/VALID_VARIANT/ATTRIBUTE_MATCH (attribute-preserving product phrase on the website)";
            case QUAL_RELATED_CATEGORY_BUYER:
                return "RELATED CATEGORY (product not named; accepted)";
            case QUAL_POTENTIAL_WHOLESALE_BUYER:
                return "STORE/SELLER (wholesale-related or selling/store evidence)";
            case QUAL_POTENTIAL_RETAIL_BUYER:
                return "STORE/SELLER (selling/store evidence on the website)";
            default:
                return qualification;
        }
    }

    /**
     * Collects optional commercial evidence for scoring and logging. It does not
     * control acceptance.
     */
    private String commercialProductEvidenceOf(BuyerSearchResult result, String fullText,
                                              String titleText, String snippetText,
                                              String siteText,
                                              String websitePath, String resultPath) {
        List<String> parts = new ArrayList<>();
        boolean strongCommerce = isShoppingPath(resultPath) || isShoppingPath(websitePath)
                || hasWebsiteUrlProductEvidence(result);
        boolean shoppingPathEvidence = isShoppingPath(resultPath) || isShoppingPath(websitePath);
        if (shoppingPathEvidence) {
            parts.add("shopping URL path");
        }
        if (hasWebsiteUrlProductEvidence(result)) {
            parts.add("product named in the URL/domain");
        }
        boolean siteStoreSelling = hasStoreSellingEvidence("", "", siteText, resultPath)
                || hasStoreSellingEvidence("", "", siteText, websitePath);
        if (siteStoreSelling) {
            parts.add("store/selling evidence on the website");
        }
        if (parts.isEmpty() && !siteText.isBlank()
                && hasCommerceSignalText(prepareText(siteText, resultPath))) {
            parts.add("commerce sentence on the website");
        }
        // CONTENT-SITE GUARD (requirement 5): store/selling markers and commerce
        // sentences on an ARTICLE/BLOG/MEDIA page (a podcast with a merch shop,
        // a magazine with a shop) never prove the business SELLS the requested
        // product. When there is no shopping URL and no product-named URL/domain,
        // the commerce signal must be backed by the PRODUCT ITSELF - a commerce
        // signal must read next to the product phrase on the (result-page +
        // homepage) site text.
        if (!strongCommerce && !hasCommerceNearProductPhrase(result, siteText)) {
            return null;
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Optional scoring check for commerce evidence near a product phrase. */
    private boolean hasCommerceNearProductPhrase(BuyerSearchResult result, String siteText) {
        if (siteText == null || siteText.isBlank()) {
            return false;
        }
        List<String> phrases = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addProductPhraseIfNew(phrases, seen, effectiveSearchProduct(result));
        for (String product : parseCommaSeparated(result.getProductName())) {
            String normalized = normalizeProductForValidation(product);
            ProductSpec spec = buildProductSpec(normalized, new HashSet<>(tokenizeWords(normalized)));
            for (String phrase : productMatchPhrasesFor(normalized, spec)) {
                addProductPhraseIfNew(phrases, seen, phrase);
            }
        }
        String site = prepareText(siteText);
        int window = 260;
        for (String productPhrase : phrases) {
            String normalizedPhrase = normalizeProductForValidation(productPhrase);
            if (normalizedPhrase == null || normalizedPhrase.isBlank()) {
                continue;
            }
            String phrase = " " + normalizedPhrase + " ";
            int from = 0;
            while (from < site.length()) {
                int idx = site.indexOf(phrase, from);
                if (idx < 0) {
                    break;
                }
                int start = Math.max(0, idx - window);
                int end = Math.min(site.length(), idx + phrase.length() + window);
                String near = site.substring(start, end);
                for (String token : COMMERCE_SIGNAL_PHRASES) {
                    if (containsToken(near, token)) {
                        return true;
                    }
                }
                from = idx + phrase.length();
            }
        }
        return false;
    }

    private void addProductPhraseIfNew(List<String> phrases, Set<String> seen, String phrase) {
        String normalized = normalizeProductForValidation(phrase);
        if (normalized != null && !normalized.isBlank() && seen.add(normalized)) {
            phrases.add(normalized);
        }
    }

    /** TRUE when the prepared text contains any commerce signal phrase. */
    private boolean hasCommerceSignalText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String signal : COMMERCE_SIGNAL_PHRASES) {
            if (containsPhrase(text, signal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * LIGHT business-identity evidence from the website itself. Returns a short
     * description of what proves the site is a real business / store /
     * supplier, or null when nothing generic was found.
     *
     * It is intentionally WEAK so the previous "No buyers found" problem cannot
     * return:
     *   * it runs AFTER the content-kind screens (news / event / attendee list /
     *     government / medical / academic / music / directory / unrelated
     *     industry), which already removed every page kind that is not a
     *     business;
     *   * ONE generic business word is enough - "about us", "contact us",
     *     "add to cart", a company suffix, any store/shop/supplier word;
     *   * it does NOT require a SPECIFIC business type (importer, wholesaler,
     *     retailer, ...), it does NOT require commercial evidence next to the
     *     product phrase, and it does NOT require an email address;
     *   * a storefront URL (/shop, /collections/...) counts on its own, so a
     *     lean one-page store is never dropped.
     */
    private String lightBusinessEvidenceOf(BuyerSearchResult result, String siteText,
                                           String path, String websitePath,
                                           String domain, String businessEvidence) {
        List<String> parts = new ArrayList<>();
        String text = prepareText(siteText);
        // 1) A storefront URL (/shop, /collections/..., /product/...) is business
        //    evidence on its own - a lean one-page store is never dropped.
        if (isShoppingPath(path) || isShoppingPath(websitePath)) {
            parts.add("storefront URL path");
        }
        // 2) Store-selling copy (cart, catalogue, prices, in stock, ...).
        if (hasStoreSellingEvidence("", "", siteText, path)
                || hasStoreSellingEvidence("", "", siteText, websitePath)) {
            parts.add("store/selling evidence on the website");
        }
        // 3) Generic business-identity pages/phrases (about, contact, address,
        //    hours, policies) - the existing BUSINESS_IDENTITY_MARKERS set.
        for (String marker : BUSINESS_IDENTITY_MARKERS) {
            if (containsPhrase(text, marker)) {
                parts.add("business identity ('" + marker + "')");
                break;
            }
        }
        // 4) ANY business-type word - a company suffix or any role. No SPECIFIC
        //    role is required: importer, wholesaler, retailer, studio and
        //    boutique all satisfy this equally.
        String role = firstMatchedToken(BUSINESS_TYPE_WORDS, text);
        if (role == null) {
            role = firstMatchedPhrase(BUSINESS_TYPE_PHRASES, text);
        }
        if (role != null) {
            parts.add("business word ('" + role + "')");
        }
        // 5) A business word in the domain itself (e.g. *-store.com).
        if (domain != null && !domain.isBlank()) {
            String domainWord = firstMatchedToken(BUSINESS_TYPE_WORDS, domain);
            if (domainWord != null) {
                parts.add("business word in the domain ('" + domainWord + "')");
            }
        }
        // 6) Business-type evidence already read off the page.
        if (businessEvidence != null && !businessEvidence.isBlank()) {
            parts.add("business type words on the page");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** The first whitespace-delimited token of the list present in the text. */
    private String firstMatchedToken(String[] tokens, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String token : tokens) {
            if (containsToken(text, token)) {
                return token;
            }
        }
        return null;
    }

    /**
     * STRONG product/business relevance, used ONLY to decide whether an
     * UNKNOWN-location candidate may still be accepted. Strong means the WEBSITE
     * ITSELF (never the search snippet, which merely echoes the query) carries
     * the product evidence, or the product is named in the URL/listing headline:
     *   - the product is named in the candidate's own URL (a real product page);
     *   - the product phrase is in the listing headline (not the snippet);
     *   - the product match tier is an exact / attribute-preserving match;
     *   - the fetched page shows store-selling evidence (cart, catalogue, prices)
     *     or a product-family storefront page.
     * A hit that exists ONLY in the search listing, with nothing on the website
     * to back it up, is NOT strong and an unknown location is then rejected.
     */
    private boolean hasStrongProductEvidence(BuyerSearchResult result, String matchType,
                                             String siteText, String path) {
        if (result == null) {
            return false;
        }
        if (hasWebsiteUrlProductEvidence(result) || hasListingAnchorProductPhrase(result)) {
            return true;
        }
        if (matchType != null && (matchType.startsWith("EXACT")
                || matchType.contains("VALID_VARIANT") || matchType.contains("ATTRIBUTE"))) {
            return true;
        }
        if (siteText == null || siteText.isBlank()) {
            return false;
        }
        if (hasStoreSellingEvidence("", "", siteText, path)) {
            return true;
        }
        // The product family is named on the candidate's OWN page, so a lean
        // one-page store (e.g. a home-decor boutique with no cart) still counts.
        if (homeDecorWebsiteEvidenceOf(prepareText(result.getRawTitle()), siteText,
                path, domainEvidence(result.getWebsite()), null) != null) {
            return true;
        }
        // An allowed RELATED-CATEGORY business (a yoga studio / meditation or
        // wellness center / spa / sound-healing practice for a singing-bowl
        // search) is relevant by its category alone, so it keeps an unknown
        // location and is simply ranked last.
        return relatedCategoryEvidenceForAnyProduct(result, siteText, path) != null;
    }
    /** The stable log token for a location class: LOCATION_EXACT_CITY,
     *  LOCATION_SAME_STATE or LOCATION_UNKNOWN. */
    private static String locationLogTokenOf(String locationClass) {
        if (LOCATION_CLASS_EXACT_CITY.equals(locationClass)) {
            return "LOCATION_EXACT_CITY";
        }
        if (LOCATION_CLASS_SAME_STATE.equals(locationClass)) {
            return "LOCATION_SAME_STATE";
        }
        return "LOCATION_UNKNOWN";
    }

    /** Business-type evidence: the words actually expressed by the page. */
    private String businessTypeEvidenceOf(String fullText, String path, String domain) {
        String text = prepareText(fullText);
        List<String> hits = new ArrayList<>();
        for (String word : BUSINESS_TYPE_WORDS) {
            if (containsToken(text, word) && !hits.contains(word)) {
                hits.add(word);
            }
        }
        for (String phrase : BUSINESS_TYPE_PHRASES) {
            if (containsPhrase(text, phrase) && !hits.contains(phrase)) {
                hits.add(phrase);
            }
        }
        // URL/path can carry a business-type word too (e.g. /shop, a *-store.com
        // domain such as soundjourneystore.com) - evidence, never the query.
        if ((path != null && !path.isBlank()) || (domain != null && !domain.isBlank())) {
            String urlText = (path == null ? "" : path) + " " + (domain == null ? "" : domain);
            for (String word : BUSINESS_TYPE_WORDS) {
                if (containsPhrase(urlText, word) && !hits.contains(word)) {
                    hits.add(word);
                }
            }
        }
        return hits.isEmpty() ? null : String.join(", ", hits);
    }

    /** Business-type classification: a role the page states, else a business-type
     *  signal actually expressed, else OTHER - never an invented role. */
    private String businessTypeFromEvidence(String fullText, String path, String domain) {
        String role = classifyBusinessTypeFromEvidence(fullText);
        if (role != null) {
            return role;
        }
        String text = prepareText(fullText) + " "
                + (path == null ? "" : path) + " " + (domain == null ? "" : domain);
        if (containsPhrase(text, "wholesale")
                || containsToken(text, "wholesaler") || containsToken(text, "wholesalers")) {
            return "Wholesaler";
        }
        if (containsToken(text, "distributor") || containsToken(text, "distributors")
                || containsPhrase(text, "distribution")) {
            return "Distributor";
        }
        if (containsToken(text, "importer") || containsToken(text, "importers")
                || containsPhrase(text, "importing")) {
            return "Importer";
        }
        if (containsToken(text, "supplier") || containsToken(text, "suppliers")
                || containsToken(text, "vendor") || containsToken(text, "vendors")
                || containsPhrase(text, "supplying") || containsPhrase(text, "trading company")
                || containsPhrase(text, "sourcing") || containsPhrase(text, "procurement")
                || containsPhrase(text, "b2b")) {
            return "Supplier";
        }
        if (containsToken(text, "dealer") || containsToken(text, "dealers")
                || containsPhrase(text, "dealership")) {
            return "Buyer";
        }
        if (containsToken(text, "retailer") || containsToken(text, "retailers")
                || containsToken(text, "retail") || containsToken(text, "store")
                || containsToken(text, "stores") || containsToken(text, "shop")
                || containsPhrase(text, "boutique") || containsPhrase(text, "storefront")) {
            return "Retailer";
        }
        return CLASSIFICATION_OTHER;
    }

    /** Logs the final product, evidence, location and acceptance decision. */
    private void logFinalVerdict(BuyerSearchResult result, String product, String matchType,
                                 String commercialEvidence, String businessEvidence,
                                 String locationEvidence, String rejectionReason,
                                 String productPageEvidenceLabel, String evidenceSourceLabel) {
        String sourceUrl = result.getSourceUrl() != null && !result.getSourceUrl().isBlank()
                ? result.getSourceUrl() : result.getWebsite();
        String finalDecision = rejectionReason != null ? "REJECT"
                : PIPELINE_STATUS_POTENTIAL.equals(result.getClassification())
                || PIPELINE_STATUS_UNVERIFIED.equals(result.getClassification())
                ? result.getClassification() : "ACCEPT";
        String finalToken = rejectionReason != null || "REJECT".equals(finalDecision)
                ? "FINAL_REJECT" : "FINAL_ACCEPT";
        logger.info("{} | SOURCE_URL: {} | NORMALIZED_WEBSITE: {} | PRODUCT: '{}' | "
                        + "MATCH TYPE: {} | PRODUCT_PAGE_EVIDENCE: {} | "
                        + "PRODUCT_EVIDENCE_SOURCE: {} | COMMERCIAL_EVIDENCE_SOURCE: {} | "
                        + "COMMERCIAL EVIDENCE: {} | BUSINESS EVIDENCE: {} | LOCATION EVIDENCE: {} | "
                        + "FINAL DECISION: {} | REJECTION REASON: {}",
                finalToken,
                sourceUrl, result.getWebsite(), product,
                matchType == null ? "none" : matchType,
                productPageEvidenceLabel == null ? "NO" : productPageEvidenceLabel,
                evidenceSourceLabel == null ? "NONE" : evidenceSourceLabel,
                evidenceSourceLabel == null ? "NONE" : evidenceSourceLabel,
                commercialEvidence == null ? "none" : commercialEvidence,
                businessEvidence == null ? "none" : businessEvidence,
                locationEvidence == null ? "none" : locationEvidence,
                finalDecision,
                rejectionReason == null ? "(none)" : rejectionReason);
    }

    // Which page supplied the verdict's evidence (requirement 11): the EXACT
    // Firecrawl result URL (sourceUrl) is the PRIMARY evidence source, the
    // normalized homepage the secondary source. Returns SOURCE_URL when the
    // exact Firecrawl page was fetched or its URL/product path carried the
    // evidence, HOMEPAGE when only the root page was available, NONE when no
    // page could be inspected at all (so the verdict can never misattribute
    // evidence to a page that was never fetched).
    private String commercialEvidenceSourceLabel(boolean urlProductEvidence, boolean resultPageFetched,
                                                 boolean homepageAvailable) {
        boolean sourceUrlEvidence = urlProductEvidence || resultPageFetched;
        if (sourceUrlEvidence) {
            return "SOURCE_URL";
        }
        if (homepageAvailable) {
            return "HOMEPAGE";
        }
        return "NONE";
    }

    // The effective product phrasing this result must be verified against: the
    // actual searched synonym when one was used, otherwise the original label.
    private String effectiveSearchProduct(BuyerSearchResult result) {
        String searchProduct = result.getSearchProduct();
        return searchProduct != null && !searchProduct.isBlank()
                ? searchProduct : result.getProductName();
    }

    private String buildEvidence(BuyerSearchResult result) {
        // companyName holds the cleaned business name; the original Firecrawl
        // title (which carries the product terms) is kept in rawTitle so product
        // relevance checks still cover what the search engine actually returned.
        String title = result.getRawTitle() != null ? result.getRawTitle() : result.getCompanyName();
        return (title == null ? "" : title)
                + " | " + (result.getSnippet() == null ? "" : result.getSnippet());
    }

    // Role evidence ranks from the clearest B2B statements down to retail /
    // dealer / buyer wording. The FIRST role the page actually expresses
    // decides its business type; the role word in the fired query is never
    // evidence by itself. Row 0 holds the business-type label, the rest are the
    // page-text signals that express it.
    private static final String[][] ROLE_SIGNALS = {
        new String[]{"Importer", "importer", "importers", "importing",
                "imported", "we import"},
        new String[]{"Distributor", "distributor", "distributors",
                "distributing", "distribution", "authorized distributor"},
        new String[]{"Wholesaler", "wholesaler", "wholesalers", "wholesale",
                "wholesaling"},
        new String[]{"Supplier", "supplier", "suppliers", "supplying",
                "supplies"},
        new String[]{"Dealer", "dealer", "dealers", "dealership"},
        new String[]{"Retailer", "retailer", "retailers", "retailing",
                "retail", "retail store", "gift shop", "boutique"},
        new String[]{"Bulk Buyer", "bulk", "bulk orders", "bulk pricing"},
        new String[]{"Buyer", "buyer", "buyers", "buying", "we buy",
                "purchasing"}
    };

    /**
     * Classifies the buyer from the page's OWN role statements (importer >
     * distributor > wholesaler > supplier > dealer > retailer > bulk > buyer):
     * a gift shop found through a "distributor" query is only classified
     * Distributor if its page says so. Returns null when the page states no
     * role at all - callers then fall back to a neutral label instead of
     * assigning one from the query.
     */
    private String classifyBusinessTypeFromEvidence(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return null;
        }
        String text = prepareText(fullText);
        for (String[] signal : ROLE_SIGNALS) {
            String label = signal[0];
            for (int i = 1; i < signal.length; i++) {
                if (containsPhrase(text, signal[i])) {
                    return label;
                }
            }
        }
        return null;
    }

    /**
     * Trade-data / import-export record & analytics portals (OEC, Panjiva,
     * Volza, Trademap, ImportGenius, customs/shipment data...) publish statistics
     * about shipments - they do not buy the product, so they are not buyers.
     */
    private boolean isTradeDataPage(String url, String title) {
        String host = hostOf(url);
        if (host != null) {
            for (String part : TRADE_DATA_HOST_PARTS) {
                if (host.contains(part)) {
                    return true;
                }
            }
        }
        if (title != null) {
            String t = title.toLowerCase();
            for (String marker : TRADE_DATA_TITLE_MARKERS) {
                if (t.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a product qualification when the candidate matches an entered
     * product or a relevant Home Decor website, otherwise null.
     */
    private String isRelevantToEnteredProduct(BuyerSearchResult result, String siteText) {
        List<String> enteredProducts = parseCommaSeparated(result.getProductName());
        if (enteredProducts.isEmpty()) {
            // No product to verify against - nothing can be proven relevant.
            return null;
        }
        // Product-page verification targets the ORIGINAL result URL (sourceUrl),
        // whose product path feeds the DIRECT/shopping-path evidence; the page
        // text passed in is the result page first, homepage secondary.
        String siteEvidenceUrl = result.getSourceUrl() != null && !result.getSourceUrl().isBlank()
                ? result.getSourceUrl() : result.getWebsite();
        for (String product : enteredProducts) {
            String qualification = isProductRelevant(result.getRawTitle(), result.getSnippet(),
                    siteEvidenceUrl, siteText, product,
                    result.getSearchQuery() != null && !result.getSearchQuery().isBlank()
                            ? result.getSearchQuery()
                            : result.getSearchProduct());
            if (qualification != null) {
                return qualification;
            }
        }
        return null;
    }

    private boolean hasSafeCheapProductAnchor(BuyerSearchResult result) {
        if (result == null) {
            return false;
        }
        String url = result.getSourceUrl() != null && !result.getSourceUrl().isBlank()
                ? result.getSourceUrl() : result.getWebsite();
        String title = prepareText(result.getRawTitle());
        String path = shopPathText(url);
        String domain = domainEvidence(result.getWebsite());
        if (isNonBusinessHost(normalizeWebsite(url)) || isTradeDataPage(url, title)
                || classifyPage(url, title, effectiveSearchProduct(result)) != null) {
            return false;
        }
        if (isRelevantToEnteredProduct(result, null) == null) {
            return false;
        }
        return hasWebsiteUrlProductEvidence(result)
                || hasListingAnchorProductPhrase(result)
                || homeDecorWebsiteEvidenceOf(title, "", path, domain, null) != null;
    }

    /**
     * Non-snippet listing product evidence for a website-unavailable candidate:
     * a canonical or approved variant phrase of one of the USER'S ENTERED
     * products in the listing HEADLINE (title) or in the business URL path/
     * domain - the SNIPPET is deliberately excluded, since it merely echoes the
     * queried terms and must never stand as product evidence on its own
     * (requirement: a buyer is never verified on a snippet keyword).
     */
    private boolean hasListingAnchorProductPhrase(BuyerSearchResult result) {
        List<String> enteredProducts = parseCommaSeparated(result.getProductName());
        if (enteredProducts.isEmpty()) {
            return false;
        }
        String titleText = prepareText(result.getRawTitle());
        String url = result.getSourceUrl() != null && !result.getSourceUrl().isBlank()
                ? result.getSourceUrl() : result.getWebsite();
        String path = shopPathText(url);
        String domain = domainEvidence(result.getWebsite());
        for (String product : enteredProducts) {
            String p = normalizeProductForValidation(product);
            ProductSpec spec = buildProductSpec(p, new HashSet<>(tokenizeWords(p)));
            if (spec.strongPhrases.isEmpty()) {
                continue;
            }
            List<String> candidates = productMatchPhrasesFor(p, spec);
            if (findFirstStrongPhrase(candidates, titleText, path, "", domain, "") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cheap-screen multi-product gate: TRUE when the search evidence (title +
     * snippet + URL, no homepage yet) is relevant to at least one of the
     * USER'S ENTERED products. Every product goes through the same
     * phrase-based gate as the stage-2 full-page verify - a result matching
     * only an internal synonym phrase is never accepted on its own.
     */
    private boolean isProductRelevantForAny(String title, String snippet, String website,
                                            String homepageHtml, List<String> products,
                                            String searchQuery) {
        if (products == null || products.isEmpty()) {
            return false;
        }
        for (String product : products) {
            if (isProductRelevant(title, snippet, website, homepageHtml, product, searchQuery) != null) {
                return true;
            }
        }
        for (String product : products) {
            String normalized = normalizeProductForValidation(product);
            ProductSpec spec = buildProductSpec(normalized, new HashSet<>(tokenizeWords(normalized)));
            if (!spec.strongPhrases.isEmpty()
                    && classifyPage(website, title, product) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Product-family-aware relevance used by both the cheap screen and full
     * verification. It accepts direct product evidence, approved product-family
     * evidence and relevant Home Decor website evidence, while rejecting
     * accessory, wrong-family and clearly non-product contexts.
     */
    private String isProductRelevant(String title, String snippet, String website,
                                     String homepageHtml, String product, String searchQuery) {
        if (product == null || product.isBlank()) {
            return QUAL_DIRECT_PRODUCT_BUYER;
        }
        if (significantTerms(product).isEmpty()) {
            // A "product" made only of role words (wholesale / retailer /
            // distributor / importer) can never be verified on any page;
            // letting it pass would admit irrelevant businesses.
            logger.debug("PRODUCT RELEVANCE | REJECT '{}' - role-only product", product);
            return null;
        }
        String p = normalizeProductForValidation(product);
        ProductSpec spec = buildProductSpec(p, new HashSet<>(tokenizeWords(p)));
        if (spec.strongPhrases.isEmpty()) {
            // Unsupported product with no acceptance phrases: nothing can prove
            // it, so it is rejected here (the category gate already blocked it
            // before any Firecrawl request).
            return null;
        }

        // All zones are normalized the same way (lowercase, punctuation
        // collapsed, single-spaced) so hyphens/commas in the evidence never
        // break phrase matching.
        String titleText = prepareText(title);
        String snippetText = prepareText(snippet);
        String homeText = prepareText(homepageHtml);
        String path = shopPathText(website);
        String domain = domainEvidence(website);

        List<String> candidates = productMatchPhrasesFor(p, spec);

        // Related-industry contexts come from the SEARCH evidence
        // (title/snippet) only: a genuine candle company whose footer happens
        // to mention candle-making kits must not be rejected, but a
        // candle-making-supplies PAGE is.
        String reject = findRejectContext(spec.rejectContexts, prepareText(title, snippet));
        boolean searchScreenOnly = homepageHtml == null;

        // Jar/canister combination proof: a decorative-jar seller whose page is
        // worded differently (glass canisters, storage jars, apothecary jars,
        // kitchen canisters...) is relevant when a jar-family noun appears with
        // a product modifier AND business/local evidence. It is applied for any
        // jar-family synonym actually searched.
        boolean jarComboProof = isJarFamilyQuery(product)
                && reject == null
                && jarComboRelevant(titleText, snippetText, path, domain, homeText);
        // The same jar-family relaxation scoped to the website ITSELF (shopping
        // path / domain / homepage body) - used to exempt a genuine
        // differently-worded jar business from the query-echo rejection and to
        // demand store/selling evidence on the site for the full-page accept.
        boolean jarComboSiteProof = isJarFamilyQuery(product)
                && reject == null
                && jarComboRelevant("", "", path, domain, homeText == null ? "" : homeText);

        // The Level-1 phrase is computed up-front so the accessory/handle reject
        // logs below can already name it.
        String matched = findFirstStrongPhrase(candidates, titleText, path, snippetText, domain, homeText);

        // ---- ACCESSORY / CONSUMABLE GATE (cheap screen AND full page): a page
        // for the product's ACCESSORIES ("Singing Bowl Cushion", "Bowl Mallet",
        // "Box for Singing Bowls") sells the accessory, never the product, so it
        // is not a buyer of the product itself. Bowl-family heads run the
        // adjacency rule; every family additionally rejects the composed form
        // "<strong product phrase> <accessory>" ("candle holder bag"). A legit
        // "Candle Stand" product is never caught because only bowl heads trigger
        // the adjacency rule.
        String accessoryEvidence = findAccessoryEvidence(spec, p, titleText, snippetText,
                path, domain, homeText == null ? "" : homeText);
        if (accessoryEvidence != null) {
            logProductValidation(product, title, snippet, website, searchQuery,
                    "ACCESSORY-REJECT", matched, "none", "none", QUAL_NOT_A_BUYER,
                    "product accessory/consumable evidence '" + accessoryEvidence + "'");
            return null;
        }

        // ---- CUSTOMISED-JAR HANDLE GATE: a "Jars With Handle" product demands
        // handle evidence. When the user product carries "handle", a page that
        // clearly claims the jar/canister family but never shows a handle is
        // generic stock - not the searched product - and is rejected. A page
        // that does not claim the jar family at all is left to the (stricter)
        // strong-phrase gate below.
        boolean handleRequired = "jar".equals(spec.coreNoun) && p.contains("handle");
        if (handleRequired && jarFamilyMentioned(titleText, snippetText, path, domain)) {
            String handleEvidence = findHandleEvidence(titleText, snippetText, path, domain, homeText);
            if (handleEvidence == null) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "HANDLE-REJECT", matched, "none", "none", QUAL_NOT_A_BUYER,
                        "jar-family page with no 'handle' evidence for a 'jars with handle' product");
                return null;
            }
        }

        // Level-1 check: a strong product phrase (computed earlier so the
        // accessory/handle reject logs can name it).
        boolean matchedMultiWord = matched != null && matched.trim().contains(" ");

        // The strong product phrase re-scoped to the website ITSELF (shopping
        // URL path, domain, homepage body) - NEVER the search-listing title or
        // snippet, which merely echo the terms that were searched. A phrase
        // found only in the listing is retained only when its title or URL is
        // a meaningful product anchor.
        String sitePhrase = findFirstStrongPhrase(candidates, "", path, "",
                domain, homeText == null ? "" : homeText);

        // Base-category-only terms (e.g. a bare "lantern" / "glass lantern" page
        // for a "wall lantern" search): when the product has REQUIRED attributes,
        // category-only evidence labels the result GENERIC_CATEGORY_ONLY and is
        // rejected before any store/related fallthrough.
        String genericCategory = findFirstStrongPhrase(spec.genericCategoryTerms,
                titleText, path, snippetText, domain, homeText == null ? "" : homeText);
        String homeDecorListing = homeDecorWebsiteEvidenceOf(
                titleText, snippetText, path, domain, null);
        // Allowed RELATED-CATEGORY business type seen in the listing: a yoga /
        // meditation / wellness / spa / sound-healing business for a singing-bowl
        // search. These pages often never name the product, so the listing alone
        // is enough to send them on to homepage verification.
        String relatedListing = relatedCategoryEvidenceOf(spec, titleText, snippetText,
                path, domain, homeText == null ? "" : homeText);

        // ---- CHEAP SCREEN (no homepage yet): a promising candidate is sent to
        // homepage verification before the final product decision.
        if (searchScreenOnly) {
            // The cheap screen decides only product relevance. A candidate is
            // retained for verification when the listing has a strong product
            // phrase or relevant Home Decor evidence.
            boolean screenPassed = false;
            if (matched != null && (matchedMultiWord || reject == null)) {
                // Any strong product phrase ANYWHERE in the listing (title, URL,
                // snippet or domain) sends the candidate to free homepage
                // verification. The final product decision is made on the fetched
                // website - a snippet phrase alone is never enough to accept a
                // candidate whose website is clearly unrelated.
                screenPassed = true;
            } else if (matched != null) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "LEVEL1-REJECT", matched, "none", "none", QUAL_NOT_A_BUYER,
                        "related-industry context '" + reject + "' overrides solo product word");
                return null;
            }
            if (matched == null && reject == null && homeDecorListing == null
                    && genericCategory != null && !spec.requiredAttributes.isEmpty()) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "GENERIC-CATEGORY-REJECT", genericCategory, "none", "none", QUAL_NOT_A_BUYER,
                        "MATCH TYPE: GENERIC_CATEGORY_ONLY | BASE CATEGORY '" + spec.coreNoun
                                + "' | REQUIRED ATTRIBUTES " + spec.requiredAttributes
                                + " - result carries the base category but not the required attribute(s)");
                return null;
            }
            if (!screenPassed && reject == null && homeDecorListing != null) {
                screenPassed = true;
            }
            if (!screenPassed && reject == null && relatedListing != null) {
                // An allowed related-category business reaches homepage
                // verification even when the listing never names the product.
                screenPassed = true;
            }
            if (!screenPassed && jarComboProof) {
                // Related jar/canister language with business/local evidence:
                // NOT auto-rejected - sent to homepage verification.
                logger.info("JARCOMBO PASS-TO-VERIFY '{}' - jar-family noun + modifier + business evidence",
                        website);
                screenPassed = true;
            }
            if (screenPassed) {
                String evidenceLabel = jarComboProof
                        ? "jar/canister + business/local evidence"
                        : (matched != null ? matched
                                : (relatedListing != null ? relatedListing : homeDecorListing));
                logProductValidation(product, title, snippet, website, searchQuery,
                        "PASS-TO-VERIFY", matched != null ? matched
                                : (relatedListing != null ? relatedListing : homeDecorListing),
                        evidenceLabel, "none",
                        QUAL_PRODUCT_RELEVANT,
                        "candidate passes product relevance and proceeds to direct acceptance");
                return QUAL_PRODUCT_RELEVANT;
            }
            logProductValidation(product, title, snippet, website, searchQuery,
                    "REJECT", null, "none", "none", QUAL_NOT_A_BUYER,
                    "no product phrase and no related-category seller/store signal");
            return null;
        }

        // ---- FULL PAGE (homepage already crawled): apply product-family rules.
        // The canonical/variant product phrase is accepted when the website
        // itself evidences it. When it appears only in the candidate's own
        // listing, a title or URL anchor is sufficient; a snippet-only phrase
        // must be confirmed by the website itself.
        if (matched != null && sitePhrase == null && !jarComboSiteProof) {
            boolean multiWord = matched.trim().contains(" ");
            boolean exactCanonical = p.equalsIgnoreCase(matched.trim());
            if (reject != null && !multiWord && !exactCanonical) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "LEVEL1-REJECT", matched, "none", "none", QUAL_NOT_A_BUYER,
                        "solo listing word '" + matched + "' overridden by related-industry context '" + reject + "'");
                return null;
            }
            // A phrase on the listing title, URL path or domain is meaningful
            // lead evidence. A snippet-only phrase is not website evidence and
            // must be confirmed by the fetched page below.
            boolean listingAnchor = findFirstStrongPhrase(candidates,
                    titleText, path, "", domain, "") != null;
            if (listingAnchor) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "LEVEL1-ACCEPT", matched, "none", "none", QUAL_PRODUCT_LISTING,
                        "canonical/variant product phrase in the listing headline/URL; website text "
                                + "did not independently re-confirm it (VERIFICATION_UNAVAILABLE)");
                return QUAL_PRODUCT_LISTING;
            }
            // Snippet-only phrasing: keep evaluating the website below - the
            // snippet alone is never an accept.
        }
        if (jarComboSiteProof && sitePhrase == null) {
            logProductValidation(product, title, snippet, website, searchQuery,
                    "DEFER", matched, "none", "none", QUAL_DIRECT_PRODUCT_BUYER,
                    "product phrase is only a listing echo but the jar/canister "
                            + "family is worded on the website - routed to the jar-combo gate");
        }
        if (sitePhrase != null) {
            boolean siteMatchedMultiWord = sitePhrase.trim().contains(" ");
            if (siteMatchedMultiWord || reject == null || p.equalsIgnoreCase(sitePhrase.trim())) {
                boolean productEvidence = hasPhysicalProductEvidence(sitePhrase,
                        titleText, snippetText, homeText, path, domain);
                String tier = (productEvidence && !jarComboProof)
                        ? QUAL_DIRECT_PRODUCT_BUYER : QUAL_POTENTIAL_RETAIL_BUYER;
                logProductValidation(product, title, snippet, website, searchQuery,
                        "LEVEL1-ACCEPT", sitePhrase,
                        productEvidence ? "physical-product/seller evidence on the actual website" : "none",
                        "none", tier,
                        "canonical/variant product phrase confirmed on the actual website");
                return tier;
            }
            // A solo noun ("candle", "lantern") is too weak when a related-
            // industry context (candle-making supplies, camping gear...) is
            // present.
            logProductValidation(product, title, snippet, website, searchQuery,
                    "LEVEL1-REJECT", sitePhrase, "none", "none", QUAL_NOT_A_BUYER,
                    "solo noun '" + sitePhrase + "' overridden by related-industry context '" + reject + "'");
            return null;
        }

        // RELATED-INDUSTRY: with no DIRECT phrase at all, a kitchen-bowl /
        // camping-lantern / canning-jar / candle-making page that mentions the
        // family loosely must not accept.
        if (reject != null) {
            logProductValidation(product, title, snippet, website, searchQuery,
                    "REJECT", null, "none", "none", QUAL_NOT_A_BUYER,
                    "related-industry context '" + reject + "' with no product phrase");
            return null;
        }

        String homeDecorSite = homeDecorWebsiteEvidenceOf(
                "", "", path, domain, homeText);
        if (homeDecorSite != null) {
            logProductValidation(product, title, snippet, website, searchQuery,
                    "LEVEL2-ACCEPT", homeDecorSite,
                    "relevant Home Decor evidence on the actual website", "none",
                    QUAL_RELATED_CATEGORY_BUYER,
                    "relevant Home Decor website accepted without the exact product phrase");
            return QUAL_RELATED_CATEGORY_BUYER;
        }

        // GENERIC_CATEGORY_ONLY gate: when the product has REQUIRED attributes
        // (e.g. the "wall" of a "wall lantern"), a page that sits in the base
        // category but never evidences the attribute - any glass/moroccan/
        // hanging lantern, a bare candle-holder shop for a crystal-holder
        // search - is NOT a variant of the entered product and is rejected
        // HERE, before the RELATED store channel or MEDIUM signals could
        // accept it. A store that never names the category at all is still
        // handled by the RELATED gate below.
        if (matched == null && sitePhrase == null && reject == null
                && genericCategory != null && !spec.requiredAttributes.isEmpty()) {
            logProductValidation(product, title, snippet, website, searchQuery,
                    "GENERIC-CATEGORY-REJECT", genericCategory, "none", "none", QUAL_NOT_A_BUYER,
                    "MATCH TYPE: GENERIC_CATEGORY_ONLY | BASE CATEGORY '" + spec.coreNoun
                            + "' | REQUIRED ATTRIBUTES " + spec.requiredAttributes
                            + " - result carries the base category but not the required attribute(s)");
            return null;
        }

        // JAR/CANISTER COMBO PROOF (full page): with no strong phrase on the
        // page, a genuine decorative-jar business that words the product
        // differently (glass canisters, storage jars, apothecary jars, kitchen
        // canisters...) is accepted WHEN the jar-family noun appears with a
        // product modifier AND website evidence - never a naked "jar" mention
        // and never a canning/lab/pickle page (rejected above).
        if (jarComboProof && jarComboSiteProof) {
            boolean serviceOnly = hasServiceOnlyActivity(titleText, snippetText, homeText);
            if (!serviceOnly) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "JARCOMBO-ACCEPT", null,
                        "jar-family noun + product modifier + business/local evidence on the website",
                        "none", QUAL_POTENTIAL_RETAIL_BUYER,
                        "jar/canister language on the website for a decorative-jar product");
                return QUAL_POTENTIAL_RETAIL_BUYER;
            }
            logProductValidation(product, title, snippet, website, searchQuery,
                    "JARCOMBO-REJECT", null,
                    "jar-family combo but service-only page",
                    "service-only page (spa/class/workshop/retreat)",
                    QUAL_NOT_A_BUYER,
                    "jar/canister words on a service-only page");
            return null;
        }

        // LEVEL 2b - RELATED-CATEGORY ACCEPT, run only after every DIRECT product
        // gate above has had its chance (so a real product match always wins and
        // is reported as the stronger DIRECT/POTENTIAL_RETAIL tier), after the
        // reject-context screen (a kitchen-bowl / canning-jar / camping-lantern
        // page never reaches here) and after the GENERIC_CATEGORY_ONLY gate (a
        // "wall lantern" search still rejects a bare lighting store that never
        // evidences the required attribute).
        //
        // A business that IS one of the product family's allowed related
        // categories is accepted with NO product evidence at all: a yoga studio,
        // meditation or wellness center, holistic center, spa or sound-healing
        // practice is a genuine buyer of singing bowls even when its page never
        // names them. This is the RELATED -> ACCEPT rule and the main source of
        // buyers a strict product-phrase match would miss.
        String relatedCategory = relatedCategoryEvidenceOf(spec, titleText, snippetText,
                path, domain, homeText);
        if (relatedCategory != null) {
            logProductValidation(product, title, snippet, website, searchQuery,
                    "LEVEL2-RELATED-ACCEPT", null,
                    "allowed related business category '" + relatedCategory
                            + "' (no product evidence required)",
                    "none", QUAL_RELATED_CATEGORY_BUYER,
                    "business matches the allowed related category for '"
                            + spec.coreNoun + "' family - accepted without product evidence");
            return QUAL_RELATED_CATEGORY_BUYER;
        }

        // LEVEL 3 - MEDIUM / GENERIC: looser family-context words accept when
        // enough distinct signals appear; a bare unrelated phrase never does.
        if (!spec.specific) {
            int signals = countMediumSignals(spec.mediumSignals,
                    prepareText(title, snippet, homepageHtml), spec.coreNoun, path);
            if (signals >= RELEVANCE_MEDIUM_COUNT_FOR_ACCEPT) {
                logProductValidation(product, title, snippet, website, searchQuery,
                        "LEVEL3-ACCEPT", null,
                        signals + " medium family signals", "none",
                        QUAL_POTENTIAL_RETAIL_BUYER,
                        "family-context signals for a loose family product");
                return QUAL_POTENTIAL_RETAIL_BUYER;
            }
            if (signals > 0) {
                logger.debug("PRODUCT RELEVANCE | '{}' has only {}/{} medium signals - rejected",
                        product, signals, RELEVANCE_MEDIUM_COUNT_FOR_ACCEPT);
            }
        }

        logProductValidation(product, title, snippet, website, searchQuery,
                "REJECT", null, "none", "none", QUAL_NOT_A_BUYER,
                "no canonical/variant product phrase, no related-category store and no family-context signals");
        return null;
    }

    // The number of looser family-context signals (or one core-noun-on-a-shop-
    // path) needed to accept when no STRONG product phrase was found.
    private static final int RELEVANCE_MEDIUM_COUNT_FOR_ACCEPT = 2;

    // Strong phrases per product family: a genuine multi-word product phrase
    // (or the family's own core noun) that proves the candidate deals in it.
    // withPlurals() adds the missing singular/plural form so "holder" and
    // "holders" both match.
    private static final List<String> STRONG_BOWL_PHRASES = withPlurals(List.of(
            "singing bowl", "tibetan singing bowl", "tibetan bowl",
            "himalayan singing bowl", "himalayan bowl", "meditation bowl",
            "crystal singing bowl", "sound healing bowl", "healing bowl",
            "sound bowl", "chakra bowl", "zen bowl", "buddhist bowl",
            "sing bowl", "song bowl"));

    private static final List<String> STRONG_CRYSTAL_CANDLE_HOLDER_PHRASES = withPlurals(List.of(
            "crystal candle holder", "crystal tealight holder", "crystal votive holder",
            "crystal candlestick", "crystal candle stand"));

    private static final List<String> STRONG_CANDLE_HOLDER_PHRASES = withPlurals(List.of(
            "candle holder", "candle stand", "candle pedestal", "candle sconce",
            "tealight holder", "tea light holder", "taper holder", "taper stand",
            "pillar candle holder", "candle lantern", "hurricane candle holder"));

    private static final List<String> STRONG_VOTIVE_HOLDER_PHRASES = withPlurals(List.of(
            "votive holder", "glass votive holder", "decorative votive holder",
            "votive candle holder", "votive cup", "votive glass"));

    private static final List<String> STRONG_CANDLE_PHRASES = withPlurals(List.of(
            "pillar candle", "scented candle", "aromatherapy candle",
            "soy candle", "beeswax candle", "votive candle", "taper candle",
            "dinner candle", "jar candle", "container candle", "candle set",
            "gift candle", "tealight", "tea light", "led candle", "rustic candle",
            "dripless candle", "luxury candle"));

    private static final List<String> STRONG_LANTERN_PHRASES = withPlurals(List.of(
            "decorative lantern", "moroccan lantern", "metal lantern",
            "glass lantern", "hanging lantern", "garden lantern",
            "home decor lantern", "table lantern", "candle lantern",
            "vintage lantern", "paper lantern", "wire lantern",
            "outdoor lantern", "patio lantern", "rustic lantern"));

    private static final List<String> STRONG_JAR_PHRASES = List.of(
            "decorative jar", "decorative jars", "decorative storage jar", "decorative storage jars",
            "glass decorative jar", "glass decorative jars", "glass display jar", "glass display jars",
            "glass storage jar", "glass storage jars", "glass jar", "glass jars",
            "storage jar", "storage jars", "canister", "canisters",
            "glass canister", "glass canisters", "decorative canister", "decorative canisters",
            "decorative jar with handle", "decorative jars with handle", "decorative jars with handles",
            "home decor jar", "home decor jars", "apothecary jar", "apothecary jars",
            "glass apothecary jar", "glass apothecary jars", "candy jar", "candy jars",
            "cookie jar", "cookie jars", "ceramic jar", "ceramic jars", "amber jar", "amber jars",
            "vintage jar", "vintage jars", "rustic jar", "rustic jars", "farmhouse jar", "farmhouse jars",
            "candle jar", "candle jars", "display jar", "display jars",
            "decorative glass jar", "decorative glass jars",
            "glass storage container", "glass storage containers",
            "decorative storage container", "decorative storage containers");

    // RELATED category phrases: the home-decor sales channel. A home-decor
    // wholesale/boutique is a plausible buyer for any non-crystal product even
    // when it never names the product - this is the "Home Decor Wholesale â†’
    // RELATED â†’ ACCEPT" rule. Crystal products are exact: their channel alone
    // proves nothing, so these phrases are never attached to crystal specs.
    private static final List<String> RELATED_HOME_DECOR_PHRASES = withPlurals(List.of(
            "home decor", "handmade home decor", "handcrafted home decor",
            "spiritual decor", "wellness decor", "meditation decor", "yoga decor",
            "gift and home decor", "artisan home decor", "home goods",
            "home accessory", "home accent", "houseware", "homeware", "decor"));
    private static final List<String> RELEVANT_HOME_DECOR_WEBSITE_PHRASES = withPlurals(List.of(
            "home decor", "handmade home decor", "handcrafted home decor",
            "artisan home decor", "interior decor", "wall decor",
            "home goods", "homeware", "houseware"));

    // RELATED-CATEGORY ACCEPT: the business types that are a plausible buyer of a
    // product family WITHOUT the page ever naming the product. A singing-bowl
    // search therefore also returns the yoga studios, meditation centers,
    // wellness centers, spas and sound-healing practices that genuinely carry the
    // product - the "RELATED -> ACCEPT" rule, and the single largest source of
    // buyers a strict product-phrase match misses.
    //
    // Index order matches PRODUCT_FAMILY_TERMS (0=bowls, 1=lanterns, 2=candles &
    // holders, 3=votive, 4=jars, 5=home decor). Two rules keep this list safe:
    //   1. ONLY real BUSINESS TYPES - every entry names the trade (studio, center,
    //      spa, shop, store, boutique, designer). A bare theme word ("yoga",
    //      "meditation", "crystal", "gift", "sound") is deliberately NOT here, so
    //      a page that merely mentions the theme can never qualify on it.
    //   2. News articles, travel/booking platforms, generic blogs, directories and
    //      marketplaces never reach this gate - they are already removed by
    //      rawResultPreFilterReason / classifyPage / isArticleLike, and
    //      lightBusinessEvidenceOf still has to confirm a real business.
    private static final List<List<String>> RELATED_CATEGORY_BUSINESS_PHRASES = List.of(
            // Singing / wellness bowls: the practices and shops that use and
            // retail them (yoga, meditation, mindfulness, wellness, holistic and
            // healing centres, spas, sound healing, spiritual / metaphysical /
            // crystal / new-age retail).
            List.of("yoga studio", "yoga center", "yoga centre", "yoga school",
                    "meditation center", "meditation centre", "meditation studio",
                    "meditation group", "mindfulness center", "mindfulness centre",
                    "wellness center", "wellness centre", "wellness studio",
                    "holistic center", "holistic centre", "holistic wellness",
                    "healing center", "healing centre",
                    "spa", "spas", "day spa", "wellness spa", "health spa",
                    "sound healing", "sound healer", "sound therapy",
                    "sound bath", "sound baths",
                    "spiritual shop", "spiritual store",
                    "metaphysical shop", "metaphysical store",
                    "crystal shop", "crystal store",
                    "new age shop", "new age store",
                    "yoga shop", "yoga store",
                    "meditation supply", "meditation supplies"),
            // Lanterns / lighting: home-decor, lighting and garden retail plus the
            // interior-design trade that specifies the fixtures.
            List.of("home decor store", "home decor shop", "home decor boutique",
                    "interior decor store", "lighting store", "lighting shop",
                    "lighting showroom", "lighting retailer", "garden center",
                    "garden centre", "garden store", "garden shop",
                    "outdoor lighting store", "outdoor living store", "patio store",
                    "home goods store", "home goods shop", "interior design",
                    "interior designer", "interior design studio",
                    "home furnishings", "home furnishing store"),
            // Candles & candle holders: home-decor, candle and home-fragrance
            // retail, gift retail, and the wellness/spiritual practices that buy
            // candles and holders in volume.
            List.of("home decor store", "home decor shop", "home decor boutique",
                    "candle shop", "candle store", "candle boutique",
                    "home fragrance", "home fragrance store",
                    "gift shop", "gift store", "boutique", "home goods store",
                    "spiritual shop", "wellness center", "yoga studio"),
            // Votive holders: home-decor, glassware and gift retail.
            List.of("home decor store", "glassware store", "glassware shop",
                    "gift shop", "gift store", "boutique", "candle shop",
                    "home goods store"),
            // Decorative jars: home-decor, glassware, kitchenware and gift retail
            // (the canning / lab / pickle trade stays excluded by
            // JAR_REJECT_CONTEXTS, which is checked before this list).
            List.of("home decor store", "home decor shop", "glassware store",
                    "glassware shop", "kitchen store", "kitchen shop",
                    "kitchenware store", "gift shop", "gift store", "boutique",
                    "home goods store", "apothecary shop", "apothecary store"),
            // Handmade / home decor: the artisan, interior-design and home-goods
            // trade.
            List.of("home decor store", "home decor shop", "home decor boutique",
                    "interior decor store", "interior design studio",
                    "interior designer", "home goods store", "home goods shop",
                    "home furnishings", "home accessory store", "artisan market",
                    "artisan shop", "artisan studio", "gift shop", "gift store",
                    "boutique"));

    // Looser family-context words: 2+ of them (or the core noun on a shopping
    // path) can accept when no STRONG phrase matched. Candles and lanterns get
    // this tier; exact families (crystal/votive/bowl/jar) never rely on it.
    private static final List<String> MEDIUM_CANDLE_SIGNALS = List.of(
            "candlelight", "hand poured", "home fragrance", "ambiance", "warm glow");

    private static final List<String> MEDIUM_LANTERN_SIGNALS = List.of(
            "garden lights", "string lights", "festive lighting", "outdoor lights",
            "hanging lights", "patio lights", "garden lighting", "outdoor lighting");

    // Words pointing at an OTHER-industry page (kitchen suppliers, camping gear,
    // canning/lab containers, candle-making/wax suppliers) so a loose family
    // word on such a page never accepts. Material words (soy wax, beeswax,
    // paraffin...) are deliberately NOT here: genuine finished-candle pages
    // constantly mention them ("soy wax candles", "made with beeswax") and
    // must not be rejected - an industry page instead says it MAKES SUPPLIES.
    private static final List<String> BOWL_REJECT_CONTEXTS = List.of(
            "kitchen bowl", "kitchen bowls", "mixing bowl", "mixing bowls",
            "serving bowl", "serving bowls", "salad bowl", "salad bowls",
            "cereal bowl", "soup bowl", "pasta bowl", "food storage",
            "dinnerware", "dishwasher", "microwave safe", "bakeware");

    private static final List<String> LANTERN_REJECT_CONTEXTS = List.of(
            "camping lantern", "camping gear", "flashlight", "headlamp",
            "hiking lantern", "backpacking", "emergency lantern", "automotive",
            "car accessory", "traffic", "storm lantern", "patrol lantern");

    private static final List<String> CANDLE_REJECT_CONTEXTS = List.of(
            "candle making", "candle supply", "candle supplies",
            "wick supplier", "candle mold", "candle molds",
            "candle kit", "candle kits", "restaurant", "diner");

    private static final List<String> CRYSTAL_CANDLE_REJECT_CONTEXTS = List.of(
            "gemstone", "healing crystal", "raw crystal", "crystal therapy",
            "chakra", "mineral specimen", "crystal collection");

    private static final List<String> JAR_REJECT_CONTEXTS = List.of(
            "canning jar", "canning jars", "canning supplies", "pickle jar",
            "pickle jars", "baby food", "laboratory", "pharma", "pharmaceutical",
            "medical");

    // Related jar/canister product evidence (requirement: decorative jars are
    // sold under many names). A candidate is accepted as a potential decorative-
    // jar buyer when the site says a JAR-FAMILY NOUN together with a PRODUCT
    // MODIFIER (decorative, glass, storage, display, apothecary, handle,
    // kitchen...) AND carries business/local evidence - see jarComboRelevant().
    // A page merely saying "jar" once, or a canning/lab/pickle page (JAR_REJECT_
    // CONTEXTS), never passes.
    private static final List<String> JAR_FAMILY_NOUNS = List.of(
            "jar", "jars", "canister", "canisters", "container", "containers");
    private static final List<String> JAR_COMBO_MODIFIERS = List.of(
            "decorative", "decoration", "decor", "glass", "storage", "display",
            "apothecary", "handle", "handles", "kitchen", "canister", "canisters");
    private static final List<String> JAR_COMBO_BUSINESS_EVIDENCE = List.of(
            "store", "shop", "retailer", "retail", "wholesale", "wholesaler",
            "distributor", "supplier", "buyer", "importer", "boutique",
            "products", "collection", "catalog", "decor");
    private static final java.util.regex.Pattern JAR_FAMILY_QUERY_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\b(jar|jars|canister|canisters|container|containers)\\b");
    private static final java.util.regex.Pattern US_CITY_STATE_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\b[A-Z][a-zA-Z]{2,}(?:\\s+[A-Z][a-zA-Z]{2,})?,\\s*[A-Z]{2}\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    // Per-family REQUIRED attributes: when the user's canonical product carries
    // the attribute word, the CANONICAL is what the user narrowed the search to
    // (e.g. "wall lantern") and a candidate must evidence that attribute to be
    // a VALID_VARIANT / ATTRIBUTE_MATCH. A result that only reaches the BASE
    // CATEGORY (e.g. any glass/moroccan "lantern" for a "wall lantern" search)
    // is GENERIC_CATEGORY_ONLY and is never a relevant buyer - the search
    // planner fires attribute-preserving phrases and the validator rejects the
    // generic category before any store/related fallthrough. Index matches
    // familyIndexFor / PRODUCT_FAMILY_TERMS (0=bowls, 1=lanterns, 2=candles &
    // holders, 3=votive reserved, 4=jars, 5=home decor). Bowl products keep
    // identity-preserving variants ("Singing Bowls" -> tibetan/himalayan/crystal
    // singing bowls all preserve the singing-bowl identity) so no extra
    // attribute gate applies.
    private static final List<Set<String>> FAMILY_REQUIRED_ATTRIBUTES = List.of(
            Set.of(),
            Set.of("wall"),
            Set.of("crystal"),
            Set.of(),
            Set.of("handle"),
            Set.of());

    // Alternative form of a required-attribute token (the canonical token is
    // replaced 1:1 so the resulting phrase still carries the attribute), e.g.
    // "wall lantern" -> "wall mounted lantern".
    private static final Map<String, List<String>> ATTRIBUTE_ALT_FORMS = Map.of(
            "wall", List.of("wall mounted"),
            "crystal", List.of("crystal"));

    // Secondary modifiers that keep the canonical's required attribute in a
    // NEW variant: "<modifier> <canonical>" (e.g. "outdoor wall lantern"). The
    // canonical phrase (which already carries the attribute) is never dropped
    // to a bare base-category term, so no modifier puts the product's required
    // attribute at risk. Ordered so the first variants are the commercially
    // strongest attribute-preserving phrases.
    private static final List<String> LANTERN_ATTRIBUTE_MODIFIERS = List.of(
            "outdoor", "exterior", "decorative", "led", "interior", "metal",
            "glass", "modern", "farmhouse", "rustic", "hanging", "garden",
            "table", "moroccan", "vintage", "solar", "black", "bronze",
            "iron", "antique");

    private Set<String> requiredAttributesOf(String p, Set<String> words, int familyIndex) {
        Set<String> required = new LinkedHashSet<>();
        if (familyIndex < 0 || familyIndex >= FAMILY_REQUIRED_ATTRIBUTES.size()) {
            return required;
        }
        for (String attr : FAMILY_REQUIRED_ATTRIBUTES.get(familyIndex)) {
            if (words.contains(attr) || words.contains(attr + "s")) {
                required.add(attr);
            }
        }
        return required;
    }

    private static List<String> modifiersForFamily(int familyIndex) {
        if (familyIndex == 1) {
            return LANTERN_ATTRIBUTE_MODIFIERS;
        }
        return List.of();
    }

    // Attribute-preserving candidate phrases for a product with REQUIRED
    // attributes: the canonical itself, its attribute-synonym alt form and the
    // secondary-modifier forms, all of which keep the attribute. Used by BOTH
    // the query planner (discovery) and the product validator, so a "wall
    // lantern" search fires and accepts "outdoor wall lantern", "wall mounted
    // lantern", "exterior wall lantern", "decorative wall lantern", "LED wall
    // lantern", ... but never "glass lantern" / "moroccan lantern" (no wall
    // attribute). An unsupported family returns the canonical alone.
    private List<String> attributePreservingVariants(String p, int familyIndex, Set<String> required) {
        List<String> out = new ArrayList<>();
        out.add(p);
        for (String attr : required) {
            for (String alt : ATTRIBUTE_ALT_FORMS.getOrDefault(attr, List.of(attr))) {
                String form = p.replaceFirst("\\b" + Pattern.quote(attr) + "\\b", alt);
                if (!form.equals(p) && !out.contains(form)) {
                    out.add(form);
                }
            }
        }
        for (String modifier : modifiersForFamily(familyIndex)) {
            String form = modifier + " " + p;
            if (!out.contains(form)) {
                out.add(form);
            }
        }
        return withPlurals(out);
    }

    // Base-category terms of the lantern family: words that prove a result is
    // IN the lantern category without the required attribute (bare "lantern" or
    // any generic lantern phrase). Only used to LABEL the category-only case as
    // GENERIC_CATEGORY_ONLY and reject it - never as an accept phrase.
    private List<String> lanternCategoryTerms() {
        List<String> terms = new ArrayList<>(withPlurals(List.of("lantern")));
        for (String t : STRONG_LANTERN_PHRASES) {
            if (!terms.contains(t)) {
                terms.add(t);
            }
        }
        return terms;
    }

    /** The acceptance profile of one product family: the phrases that prove it
     *  DIRECTLY, the RELATED-CATEGORY store words that make it a plausible
     *  buyer (audio for exact/crystal products), the looser language that may
     *  hint at it, the related-industry contexts that exclude it, whether it is
     *  exact/crystal (never accepts on RELATED or MEDIUM signals), its core
     *  noun used for shop-path evidence, the base-category terms that make a
     *  product with REQUIRED attributes GENERIC_CATEGORY_ONLY (never accepted),
     *  and the required attributes the product's variant phrases must preserve. */
    private static final class ProductSpec {
        final List<String> strongPhrases;
        final List<String> relatedCategoryPhrases;
        final List<String> mediumSignals;
        final List<String> rejectContexts;
        final boolean specific;
        final String coreNoun;
        final List<String> genericCategoryTerms;
        final Set<String> requiredAttributes;

        ProductSpec(List<String> strongPhrases, List<String> relatedCategoryPhrases,
                    List<String> mediumSignals, List<String> rejectContexts,
                    boolean specific, String coreNoun,
                    List<String> genericCategoryTerms, Set<String> requiredAttributes) {
            this.strongPhrases = strongPhrases;
            this.relatedCategoryPhrases = relatedCategoryPhrases;
            this.mediumSignals = mediumSignals;
            this.rejectContexts = rejectContexts;
            this.specific = specific;
            this.coreNoun = coreNoun;
            this.genericCategoryTerms = genericCategoryTerms;
            this.requiredAttributes = requiredAttributes;
        }
    }

    /** Picks the ProductSpec for the normalized entered product, using the same
     *  family detection as the query planner: candle family first (crystal â†’
     *  exact, votive+holder, holder, candles), then bowls, lanterns, jars and
     *  generic home decor. Anything else can only match its own exact phrase.
     *  CRYSTAL-qualified products are always exact/specific: they never accept
     *  via the home-decor RELATED channel or loose MEDIUM signals. */
    private ProductSpec buildProductSpec(String p, Set<String> words) {
        boolean crystal = words.contains("crystal");
        boolean candle = words.contains("candle") || words.contains("candles")
                || p.contains("candle") || p.contains("candela")
                || words.contains("votive") || words.contains("votives")
                || words.contains("taper") || words.contains("tapers")
                || words.contains("tealight") || words.contains("tealights")
                || p.contains("tea light") || p.contains("tea lights");
        boolean holder = words.contains("holder") || words.contains("holders")
                || words.contains("stand") || words.contains("stands")
                || words.contains("sconce") || words.contains("pedestal");
        boolean votive = words.contains("votive") || words.contains("votives")
                || words.contains("tealight") || words.contains("tealights")
                || p.contains("tea light") || p.contains("tea lights");

        int familyIndex = familyIndexFor(p);
        Set<String> requiredAttrs = requiredAttributesOf(p, words, familyIndex);

        // LEVEL 2 - RELATED CATEGORY: the product family's allowed BUYER
        // CATEGORIES (yoga studios / meditation & wellness centers / spas / sound
        // healing for bowls, home-decor & lighting & garden retail for lanterns,
        // candle & gift & boutique for candles, glassware & kitchen for jars,
        // artisan & interior-design for home decor). A business matching one of
        // these is accepted WITHOUT product evidence - see
        // relatedCategoryEvidenceOf() - which is what turns a product search into
        // buyer discovery. The entries are business TYPES, so the category match
        // is itself the evidence that the page is a real business in that trade.
        List<String> related = relatedCategoryBusinessPhrasesFor(p);
        boolean specific = crystal;

        if (candle && crystal && holder) {
            // Crystal candle holders are the exact/specific case: only a true
            // "crystal ... holder" phrase can ever accept these.
            return new ProductSpec(STRONG_CRYSTAL_CANDLE_HOLDER_PHRASES, List.of(),
                    List.of(), CRYSTAL_CANDLE_REJECT_CONTEXTS, true, "candle",
                    STRONG_CANDLE_HOLDER_PHRASES, requiredAttrs);
        }
        if (candle && votive && holder) {
            return new ProductSpec(STRONG_VOTIVE_HOLDER_PHRASES, related, List.of(),
                    CANDLE_REJECT_CONTEXTS, specific, "votive", List.of(), requiredAttrs);
        }
        if (candle && holder) {
            return new ProductSpec(STRONG_CANDLE_HOLDER_PHRASES, related, List.of(),
                    CANDLE_REJECT_CONTEXTS, specific, "candle", List.of(), requiredAttrs);
        }
        if (candle) {
            return new ProductSpec(STRONG_CANDLE_PHRASES, related, MEDIUM_CANDLE_SIGNALS,
                    CANDLE_REJECT_CONTEXTS, specific, "candle", List.of(), requiredAttrs);
        }
        if (words.contains("bowl") || words.contains("bowls")) {
            // Singing bowls: the approved relevance space is the exact singing-
            // bowl phrases PLUS the related business categories that carry them
            // (yoga / meditation / wellness / holistic / healing centres, spas
            // and sound-healing practices, spiritual / metaphysical / crystal
            // shops). Article/blog, kitchen-bowl and other clearly non-product
            // contexts remain rejected.
            return new ProductSpec(STRONG_BOWL_PHRASES, related, List.of(),
                    BOWL_REJECT_CONTEXTS, true, "bowl", List.of(), requiredAttrs);
        }
        if (p.contains("lantern")) {
            if (requiredAttrs.contains("wall")) {
                return new ProductSpec(attributePreservingVariants(p, 1, requiredAttrs),
                        related, List.of(), LANTERN_REJECT_CONTEXTS, true, "lantern",
                        lanternCategoryTerms(), requiredAttrs);
            }
            return new ProductSpec(STRONG_LANTERN_PHRASES, related, MEDIUM_LANTERN_SIGNALS,
                    LANTERN_REJECT_CONTEXTS, specific, "lantern", List.of(), requiredAttrs);
        }
        if (words.contains("jar") || words.contains("jars")) {
            return new ProductSpec(STRONG_JAR_PHRASES, related, List.of(),
                    JAR_REJECT_CONTEXTS, specific, "jar", List.of(), requiredAttrs);
        }
        if (isHomeDecorProduct(p, words)) {
            return new ProductSpec(homeDecorFamilyTerms(p, words), related, List.of(),
                    List.of(), specific, "decor", List.of(), requiredAttrs);
        }
        if (isHomeGoodProduct(p, words)) {
            // The alternate NAMES of this product (with their plural forms) are
            // the strong phrases, so the user's "Vase" search also matches a
            // "Glass Vases Wholesale" page and a "Glass Vases" search also
            // matches a plain "vase" store.
            return new ProductSpec(withPlurals(homeGoodAlternateNames(p)), related,
                    List.of(), List.of(), specific, "decor", List.of(), requiredAttrs);
        }
        // Unsupported combination: the exact entered phrasing is the only
        // phrase that can ever match (never loose tokens).
        return new ProductSpec(List.of(), List.of(), List.of(), List.of(), false, null,
                List.of(), requiredAttrs);
    }

    // ----- product-phrase helpers -----------------------------------------

    /** Adds the plural (or missing singular) form of every phrase, e.g. adds
     *  "candle holders" for "candle holder" and "lantern" for "lanterns" (the
     *  singular is never re-added when the phrase already ends in "s"). */
    private static List<String> withPlurals(List<String> phrases) {
        List<String> out = new ArrayList<>(phrases);
        for (String phrase : phrases) {
            String trimmed = phrase.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] words = trimmed.split("\\s+");
            String last = words[words.length - 1].toLowerCase(Locale.ROOT);
            if (last.length() < 3 || last.endsWith("s") || last.endsWith("or")
                    || last.endsWith("ing") || last.endsWith("ware")) {
                continue;
            }
            words[words.length - 1] = last + "s";
            out.add(String.join(" ", words));
        }
        return out.stream().distinct().collect(Collectors.toList());
    }

    /** TRUE when the phrase occurs as a whole phrase in the text: padded with
     *  spaces both sides so "candle holder" never matches inside "candle
     *  holders" (the plural form is matched by its own entry). */
    private boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) {
            return false;
        }
        String t = " " + normalizeProductForValidation(text) + " ";
        String p = " " + normalizeProductForValidation(phrase) + " ";
        return t.contains(p);
    }

    /** Scans the evidence zones in priority order (title â†’ URL path â†’ snippet â†’
     *  domain â†’ homepage) and returns the first STRONG phrase found, or null.
     *  The URL path zone carries the EXACT Firecrawl result URL (sourceUrl):
     *  a product phrase in it - whether the path is a /product//collections/
     *  marker path OR a plain product-named path like /meditation-singing-bowls
     *  - is real product-page evidence, so the path is never gated behind a
     *  shopping marker. */
    private String findFirstStrongPhrase(List<String> phrases, String title, String path,
                                         String snippet, String domain, String homepageText) {
        for (String phrase : phrases) {
            if (containsPhrase(title, phrase)) {
                return phrase;
            }
        }
        for (String phrase : phrases) {
            if (containsPhrase(path, phrase)) {
                return phrase;
            }
        }
        for (String phrase : phrases) {
            if (containsPhrase(snippet, phrase)) {
                return phrase;
            }
        }
        for (String phrase : phrases) {
            if (domainAlnumEvidence(domain, phrase)) {
                return phrase;
            }
        }
        for (String phrase : phrases) {
            if (containsPhrase(homepageText, phrase)) {
                return phrase;
            }
        }
        return null;
    }

    /** Names the zone where a matched phrase was found (for the diagnostic log). */
    private String strongPhraseZone(String phrase, String title, String path,
                                    String snippet, String domain, String homepageText) {
        if (containsPhrase(title, phrase)) {
            return "title";
        }
        if (containsPhrase(path, phrase)) {
            return "exact result URL path";
        }
        if (containsPhrase(snippet, phrase)) {
            return "snippet";
        }
        if (domainAlnumEvidence(domain, phrase)) {
            return "domain";
        }
        return "homepage";
    }

    // ------------------------------------------------------------------
    // ACCESSORY / CONSUMABLE GATE - words that are parts sold FOR the product
    // ("Singing Bowl Cushion", "Bowl Mallet", "Box for Singing Bowls",
    // "candle holder bag") rather than the product itself. A page whose product
    // is an accessory sells the accessory, never the product, so it is not a
    // buyer of the product and is rejected before any email scraping or save.
    // Bowl-family heads run the ADJACENCY rule (an accessory within a few tokens
    // of a strong bowl phrase, before or after); every family additionally
    // rejects the COMPOSED form of a multi-word strong phrase immediately
    // followed by an accessory word. A legit "Candle Stand" product is never
    // caught: "candle" is a single-word phrase that the composed form skips
    // (the strong lists carry no loose bare noun), and the adjacency rule only
    // runs for bowl heads.
    // ------------------------------------------------------------------
    private static final Set<String> ACCESSORY_WORDS = Set.of(
            "cushion", "mallet", "striker", "beater", "box", "bag", "pouch",
            "case", "cover", "sleeve", "stand", "ring", "carrying", "travel",
            "storage", "organizer", "organiser"
    );

    // Max tokens separating an accessory word from a strong bowl phrase (either
    // side) for the bowl-head adjacency rule. 3 tokens covers "Box for Singing
    // Bowls" (accessory 2 tokens before the phrase) while keeping genuine
    // bowl sellers safe.
    private static final int ACCESSORY_ADJACENCY_TOKENS = 3;

    /** The strong-product phrase candidates for the accessory gate: the user's
     *  own product phrase first, then every strong phrase of its family. */
    private List<String> accessoryPhraseCandidates(ProductSpec spec, String product) {
        List<String> candidates = new ArrayList<>();
        if (product != null && !product.isBlank()) {
            candidates.add(product.trim());
        }
        for (String phrase : spec.strongPhrases) {
            if (!candidates.contains(phrase)) {
                candidates.add(phrase);
            }
        }
        return candidates;
    }

    private List<String> phraseTokens(String phrase) {
        return Arrays.stream(phrase.split("\\s+"))
                .filter(w -> !w.isEmpty())
                .collect(Collectors.toList());
    }

    private boolean phraseMatchesAt(List<String> words, int at, List<String> phraseWords) {
        for (int k = 0; k < phraseWords.size(); k++) {
            if (!phraseWords.get(k).equals(words.get(at + k))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the accessory word found in the evidence when the site sells an
     * accessory of the product rather than the product itself, or null.
     *  - bowl-family heads: an ACCESSORY_WORDS token within
     *    ACCESSORY_ADJACENCY_TOKENS of any strong bowl phrase (either side);
     *  - every family: a multi-word strong phrase immediately followed by an
     *    ACCESSORY_WORDS token ("candle holder bag").
     */
    private String findAccessoryEvidence(ProductSpec spec, String product, String titleText,
                                         String snippetText, String path, String domain,
                                         String homeText) {
        String text = prepareText(titleText, snippetText, path, domain, homeText);
        List<String> words = tokenizeWords(text);
        if (words.isEmpty()) {
            return null;
        }
        boolean bowlHead = "bowl".equals(spec.coreNoun);
        for (String phrase : accessoryPhraseCandidates(spec, product)) {
            List<String> phraseWords = phraseTokens(phrase);
            int len = phraseWords.size();
            if (len == 0) {
                continue;
            }
            for (int i = 0; i + len <= words.size(); i++) {
                if (!phraseMatchesAt(words, i, phraseWords)) {
                    continue;
                }
                if (bowlHead) {
                    // Adjacency rule: an accessory within a small radius on either
                    // side of the strong bowl phrase.
                    int start = Math.max(0, i - ACCESSORY_ADJACENCY_TOKENS);
                    int end = Math.min(words.size(), i + len + ACCESSORY_ADJACENCY_TOKENS);
                    for (int j = start; j < end; j++) {
                        String w = words.get(j);
                        if (ACCESSORY_WORDS.contains(w)) {
                            return w;
                        }
                    }
                } else if (len >= 2) {
                    // Composed form: a multi-word strong phrase immediately
                    // followed by an accessory word (e.g. "candle holder bag").
                    int next = i + len;
                    if (next < words.size() && ACCESSORY_WORDS.contains(words.get(next))) {
                        return words.get(next);
                    }
                }
            }
        }
        return null;
    }

    /** The meaningful single label of a website host, e.g. "crystalcandleholder"
     *  for "www.crystalcandleholder.com" - used for domain-level phrase proof. */
    private String domainEvidence(String website) {
        if (website == null || website.isBlank()) {
            return "";
        }
        String host = website.replaceFirst("(?i)^https?://", "")
                .replaceFirst("(?i)^www\\.", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        String best = "";
        for (String label : host.split("\\.")) {
            if (label.length() > best.length()) {
                best = label;
            }
        }
        return best.toLowerCase(Locale.ROOT);
    }

    /** A phrase without any non-alphanumeric characters, e.g. "candle holder"
     *  â†’ "candleholder", so a domain can prove it. */
    private String stripNonAlnum(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9]", "");
    }

    /** A domain proves a phrase when the phrase's letter-form appears inside
     *  the domain's letter-form (minimum 6 letters to avoid noise). */
    private boolean domainAlnumEvidence(String domain, String phrase) {
        String phraseAlnum = stripNonAlnum(phrase);
        if (phraseAlnum.length() < 6) {
            return false;
        }
        return stripNonAlnum(domain).contains(phraseAlnum);
    }

    /** The URL path of the website (hyphens/underscores â†’ spaces), used to
     *  detect "the shop really stocks the product" from /shop, /collections,
     *  /category, /products or /catalog links. */
    private String shopPathText(String website) {
        if (website == null || website.isBlank()) {
            return "";
        }
        String w = website.toLowerCase(Locale.ROOT);
        int q = w.indexOf('?');
        if (q >= 0) {
            w = w.substring(0, q);
        }
        int scheme = w.indexOf("://");
        String rest = scheme >= 0 ? w.substring(scheme + 3) : w;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "";
        }
        return rest.substring(slash).replace('-', ' ').replace('_', ' ');
    }

    /** TRUE for the URL paths that act as a shop's product sections. */
    private boolean isShoppingPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("/shop") || p.contains("/collections") || p.contains("/category")
                || p.contains("/catalog") || p.contains("/product") || p.contains("/browse")
                || p.contains("/store");
    }

    /** TRUE when a DIRECT product phrase is backed by physical-product (seller)
     *  evidence: a shop/collection/catalog URL, a product-named domain, or a
     *  COMMERCE_SIGNAL sentence within a short window of the phrase in the
     *  combined title/snippet/homepage text. Requiring the commerce token NEAR
     *  the phrase means an article/service page that merely mentions the
     *  product cannot pass just because "buy"/"wholesale" appears somewhere in
     *  its footer. */
    private boolean hasPhysicalProductEvidence(String phrase, String title,
            String snippet, String home, String path, String domain) {
        if (isShoppingPath(path)) {
            return true;
        }
        if (domainAlnumEvidence(domain, phrase)) {
            return true;
        }
        String t = " " + (title + " " + snippet + " " + home).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ") + " ";
        String p = " " + phrase.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        if (p.trim().isEmpty()) {
            return false;
        }
        int from = 0;
        int window = 140;
        while (true) {
            int idx = t.indexOf(p, from);
            if (idx < 0) {
                return false;
            }
            int start = Math.max(0, idx - window);
            int end = Math.min(t.length(), idx + p.length() + window);
            String near = t.substring(start, end);
            for (String token : COMMERCE_SIGNAL_PHRASES) {
                if (near.contains(" " + token + " ")) {
                    return true;
                }
            }
            from = idx + p.length();
        }
    }

    private String homeDecorWebsiteEvidenceOf(String titleText, String snippetText,
                                               String path, String domain, String homeText) {
        return findFirstStrongPhrase(RELEVANT_HOME_DECOR_WEBSITE_PHRASES,
                titleText, snippetText, path, domain, homeText == null ? "" : homeText);
    }

    /**
     * The first allowed RELATED-CATEGORY business type of the product's family
     * present in the evidence zones, or null.
     *
     * This is the evidence lookup for the RELATED-CATEGORY accept: it needs NO
     * product phrase and NO physical-product/selling evidence, because a
     * business that IS one of the allowed categories (a yoga studio, a
     * meditation or wellness center, a spa, a sound-healing practice for a
     * singing-bowl search) is a buyer whether or not its page ever names the
     * product. The zones are read title -> URL path -> snippet -> domain ->
     * homepage, and every entry in the list is a real business type, so the
     * match itself is the proof that the page is a business in that trade.
     */
    private String relatedCategoryEvidenceOf(ProductSpec spec, String titleText,
                                            String snippetText, String path,
                                            String domain, String homeText) {
        if (spec == null || spec.relatedCategoryPhrases.isEmpty()) {
            return null;
        }
        return findFirstStrongPhrase(spec.relatedCategoryPhrases, titleText, path,
                snippetText, domain, homeText == null ? "" : homeText);
    }

    /** The related-category evidence for ANY of the user's entered products -
     *  used where only the result object is available (hasStrongProductEvidence),
     *  so an allowed related-category business keeps its unknown location. */
    private String relatedCategoryEvidenceForAnyProduct(BuyerSearchResult result,
                                                        String siteText, String path) {
        if (result == null) {
            return null;
        }
        String titleText = prepareText(result.getRawTitle());
        String snippetText = prepareText(result.getSnippet());
        String domain = domainEvidence(result.getWebsite());
        String homeText = siteText == null ? "" : siteText;
        for (String product : parseCommaSeparated(result.getProductName())) {
            String p = normalizeProductForValidation(product);
            if (p == null || p.isBlank()) {
                continue;
            }
            ProductSpec spec = buildProductSpec(p, new HashSet<>(tokenizeWords(p)));
            String hit = relatedCategoryEvidenceOf(spec, titleText, snippetText, path,
                    domain, homeText);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** TRUE when the page actually SELLS a physical product: a shopping URL,
     *  a single STRONG store marker (our shop / add to cart / buy now /
     *  checkout / shopping cart...) anywhere on the page, or at least two
     *  general store-selling markers (shop, store, boutique, retail, ...).
     *  This backs the LEVEL-2 related-category gate and rescues a business
     *  that ALSO offers classes from the service-only rejection. */
    private boolean hasStoreSellingEvidence(String titleText, String snippetText,
                                            String homeText, String path) {
        if (isShoppingPath(path)) {
            return true;
        }
        String text = titleText + " " + snippetText + " " + homeText;
        for (String marker : STRONG_STORE_SELLING_MARKERS) {
            if (containsPhrase(text, marker)) {
                return true;
            }
        }
        int count = 0;
        for (String marker : STORE_SELLING_MARKERS) {
            if (containsPhrase(text, marker)) {
                count++;
            }
        }
        return count >= 2;
    }

    /** TRUE when the page looks like a SERVICE-only business (spa, therapist,
     *  yoga class, sound-healing sessions, workshops, retreats, courses,
     *  training, certification, bookings, events). Used ONLY against a page
     *  that has no store/catalogue: a service business that names the product
     *  is not a buyer unless it also has shop/cart evidence. */
    private boolean hasServiceOnlyActivity(String titleText, String snippetText,
                                           String homeText) {
        String text = titleText + " " + snippetText + " " + homeText;
        for (String marker : SERVICE_ONLY_MARKERS) {
            if (containsPhrase(text, marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Single structured decision log for the product validation of one result.
     * Prints the fired query, the requested product, the matched phrase, the
     * commercial/business evidence, the decision, the buyer-qualification tier
     * and the final reason, so every result has a traceable, human-readable
     * audit line (SEARCH QUERY | PRODUCT | URL | TITLE | MATCHED | COMMERCIAL
     * EVIDENCE | BUSINESS TYPE | DECISION | QUALIFICATION | REASON).
     */
    private void logProductValidation(String product, String title, String snippet,
            String website, String searchQuery, String decision, String matchedPhrase,
            String commercialEvidence, String businessType, String qualification,
            String reason) {
        logger.info("PRODUCT VALIDATION |"
                + " Query: {} | Product: '{}' | Matched phrase: {}"
                + " | Commercial evidence: {} | Business type: {} | Decision: {}"
                + " | Qualification: {} | Reason: {} | Site: {} | Title: {} | Snippet: {}",
                searchQuery == null || searchQuery.isBlank() ? "n/a" : "'" + searchQuery + "'",
                product, matchedPhrase == null ? "none" : "'" + matchedPhrase + "'",
                commercialEvidence, businessType == null ? "none" : businessType,
                decision, qualification, reason,
                website == null || website.isBlank() ? "unknown" : website,
                cleanForLog(title), cleanForLog(snippet));
    }

    /** Requirement: for EVERY accepted/rejected candidate log the PRODUCT /
     *  BUSINESS / LOCATION evidence buckets plus the DECISION and REASON in one
     *  line, so failures stack up (why a decorative-jar seller was dropped) and
     *  gate-relaxation effects are visible in the logs. Evidence is read from
     *  the raw title/snippet/URL surface only - never invented. */
    private void logCandidateVerdict(String decision, String reason, String title,
                                     String snippet, String website, String queryProduct,
                                     String state, String city) {
        try {
            String surface = prepareText(title) + " " + prepareText(snippet);
            String path = shopPathText(website);
            String domain = domainEvidence(website);

            List<String> productHits = new ArrayList<>();
            if (queryProduct != null && !queryProduct.isBlank()) {
                for (String term : significantTerms(queryProduct)) {
                    if (surface.contains(term)) {
                        productHits.add(term);
                    }
                }
            }
            List<String> businessHits = new ArrayList<>();
            for (String m : STORE_SELLING_MARKERS) {
                if (containsPhrase(surface, m)
                        || (path != null && containsPhrase(path, m))) {
                    businessHits.add(m);
                }
            }
            List<String> locHits = new ArrayList<>();
            if (US_CITY_STATE_PATTERN.matcher(surface).find()) {
                locHits.add("address(US city,ST)");
            }
            if (city != null && !city.isBlank()
                    && surface.contains(city.toLowerCase(Locale.ROOT))) {
                locHits.add("city '" + city + "'");
            }
            if (state != null && !state.isBlank()) {
                String abbr = stateAbbreviation(state);
                if (abbr != null && !abbr.isEmpty()
                        && containsToken(surface, abbr.toLowerCase(Locale.ROOT))) {
                    locHits.add("state '" + abbr + "'");
                }
            }
            logger.info("CANDIDATE VERDICT | Decision: {} | Reason: {}"
                            + " | PRODUCT EVIDENCE: {} | BUSINESS EVIDENCE: {}"
                            + " | LOCATION EVIDENCE: {}"
                            + " | Product: '{}' | Site: {} | Title: {}",
                    decision, reason,
                    productHits.isEmpty() ? "none" : String.join(",", productHits),
                    businessHits.isEmpty() ? "none" : String.join(",", businessHits),
                    locHits.isEmpty() ? "none" : String.join(",", locHits),
                    queryProduct,
                    website == null || website.isBlank() ? "unknown" : website,
                    cleanForLog(title));
        } catch (Exception e) {
            logger.debug("CANDIDATE VERDICT logging skipped for '{}': {}",
                    website, e.getMessage());
        }
    }

    private String cleanForLog(String text) {
        if (text == null || text.isBlank()) {
            return "none";
        }
        String t = text.trim().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    /** TRUE when the path mentions the family's core noun (with its common
     *  singular/plural forms), e.g. "holder"/"holders" in /collections/holders. */
    private boolean mentionsCoreNoun(String path, String coreNoun) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String form : new String[]{coreNoun, coreNoun + "s", coreNoun + "es"}) {
            if (containsToken(p, form)) {
                return true;
            }
        }
        return false;
    }

    /** Counts the loose family-context signals found in the evidence; a core
     *  noun on the shopping path counts as one extra signal. */
    private int countMediumSignals(List<String> signals, String fullText,
                                   String coreNoun, String path) {
        int count = 0;
        for (String signal : signals) {
            if (containsPhrase(fullText, signal)) {
                count++;
            }
        }
        if (coreNoun != null && isShoppingPath(path) && mentionsCoreNoun(path, coreNoun)) {
            count++;
        }
        return count;
    }

    /** Returns the first reject context found in the evidence, or null. */
    private String findRejectContext(List<String> contexts, String text) {
        for (String context : contexts) {
            if (containsPhrase(text, context)) {
                return context;
            }
        }
        return null;
    }

    private List<String> significantTerms(String product) {
        String[] words = product.toLowerCase().split("[^a-z0-9]+");
        return Arrays.stream(words)
                .filter(w -> w.length() >= 2)
                .filter(w -> !STOPWORDS.contains(w))
                .collect(Collectors.toList());
    }

    /** True when the extracted business state matches the requested state, by
     *  full name or abbreviation ("Texas" vs "TX"). */
    private boolean sameState(String extracted, String requested) {
        String e = stateKey(extracted);
        String r = stateKey(requested);
        return e != null && r != null && e.equals(r);
    }

    private String stateKey(String state) {
        if (state == null) {
            return null;
        }
        String s = collapseWhitespace(state);
        if (s == null) {
            return null;
        }
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.length() == 2) {
            return s; // already an abbreviation ("tx")
        }
        String abbr = STATE_ABBREVIATIONS.get(s);
        return abbr != null ? abbr.toLowerCase(Locale.ROOT) : s;
    }

    /**
     * The major cities of the REQUESTED state that the user did NOT already
     * search, in importance order. Used by the location-priority widening: when
     * the requested city yields too few relevant buyers the search continues in
     * OTHER cities of the SAME state before it goes state-wide, so a city+state
     * search is never broadened straight to the whole country. Returns an empty
     * list when the state has no known city pool.
     *
     * <p>NEARBY-FIRST: the cities that share a regional cluster with one of the
     * cities the user requested come first, then the rest of the state. Within
     * each group the state's own importance order is preserved, so a Corpus
     * Christi search widens Houston -&gt; Austin -&gt; San Antonio (all in the
     * same central/southeast Texas market) before it ever considers Dallas or
     * El Paso. Every existing city is still returned in the same relative
     * order - only the NEARBY ones are promoted.
     */
    private List<String> otherCitiesInState(String state, Set<String> excludeCities) {
        List<String> out = new ArrayList<>();
        if (state == null || state.isBlank()) {
            return out;
        }
        String abbr = stateKey(state);
        String fullName = abbr == null ? null
                : STATE_ABBR_TO_NAME.get(abbr.toUpperCase(Locale.ROOT));
        if (fullName == null) {
            fullName = state.trim().toLowerCase(Locale.ROOT);
        }
        List<String> pool = REGION_MAJOR_CITIES.get(fullName);
        if (pool == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String city : pool) {
            if (city == null || city.isBlank()) {
                continue;
            }
            String key = city.trim().toLowerCase(Locale.ROOT);
            if (excludeCities != null && excludeCities.contains(key)) {
                continue;
            }
            if (seen.add(key)) {
                out.add(city.trim());
            }
        }
        return out;
    }

    /**
     * The CITY_EXPANSION trace: the requested city/cities followed by the other
     * SAME-STATE cities the search actually went on to, in the order they were
     * used - e.g. "Corpus Christi -> Houston -> Austin -> San Antonio".
     */
    private static List<String> joinCityExpansion(List<String> requestedCities,
                                                  List<String> expansionCities) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (requestedCities != null) {
            for (String city : requestedCities) {
                if (city != null && !city.isBlank()
                        && seen.add(city.trim().toLowerCase(Locale.ROOT))) {
                    out.add(city.trim());
                }
            }
        }
        if (expansionCities != null) {
            for (String city : expansionCities) {
                if (city != null && !city.isBlank()
                        && seen.add(city.trim().toLowerCase(Locale.ROOT))) {
                    out.add(city.trim());
                }
            }
        }
        return out;
    }

    /** True when the extracted business city matches the requested city,
     * tolerating a trailing state on either value ("Dallas, TX" == "Dallas"). */
    private boolean sameCity(String extracted, String requested) {
        String e = cityKey(extracted);
        String r = cityKey(requested);
        return e != null && r != null && e.equals(r);
    }

    private String cityKey(String city) {
        if (city == null) {
            return null;
        }
        String c = collapseWhitespace(city);
        if (c == null) {
            return null;
        }
        c = c.replaceAll("[.;]+", " ").trim().toLowerCase(Locale.ROOT);
        int comma = c.indexOf(',');
        if (comma >= 0) {
            c = c.substring(0, comma).trim();
        }
        String[] toks = c.split("\\s+");
        if (toks.length >= 2) {
            String last = toks[toks.length - 1];
            if (last.length() == 2
                    && STATE_ABBR_TO_NAME.containsKey(last.toUpperCase(Locale.ROOT))) {
                // "arlington tx" -> "arlington"
                c = String.join(" ", Arrays.copyOf(toks, toks.length - 1));
            }
        }
        if (c.isBlank()) {
            return null;
        }
        return c;
    }

    // Canadian postal code "A1A 1A1" - a decisive Canadian-address proof when no
    // province name is written out.
    private static final Pattern CANADA_POSTAL_PATTERN = Pattern.compile(
            "\\b[a-z]\\d[a-z]\\s\\d[a-z]\\d\\b");

    private static final class ExtractedLocation {
        final String city;
        final String state;
        final String country;
        final String source;

        ExtractedLocation(String city, String state, String country, String source) {
            this.city = city;
            this.state = state;
            this.country = country;
            this.source = source;
        }
    }

    /**
     * Extracts the buyer's REAL business location whenever the search did not
     * pin a verified city (USA-wide / state-wide searches and searched-city
     * unavailable). Sources in priority order: Google Business address from the
     * search result, search title/snippet, homepage schema.org data, homepage
     * footer, homepage body, then (only when still unresolved) the contact page
     * and the about page fetched with plain HTTP - never a Firecrawl credit.
     * Nothing is ever invented: when no reliable city/state exists the location
     * is marked Unknown, or the bare country alone when only the country is
     * positively known.
     */
    private void resolveBusinessLocation(BuyerSearchResult result, String evidence, String homepageHtml) {
        String website = result.getWebsite();
        ExtractedLocation el = resolveBusinessLocationFrom(website, result.getSearchAddress(),
                homepageHtml);
        if (el != null) {
            applyExtractedLocation(result, el);
            return;
        }

        // No reliable city/state anywhere - fall back without inventing one.
        String state = result.getState();
        if (state != null && !state.isBlank()) {
// A searched state (state-wide search) that verified still stands.
                result.setLocation(buildLocationDisplay(state, null, result.getCountry()));
            if (result.getLocationSource() == null) {
                result.setLocationSource("searched state");
            }
            logger.info("LOCATION EXTRACT | website={} | source={} | extracted='{}'",
                    website, result.getLocationSource(), result.getLocation());
            return;
        }
        String combined = buildRawText(evidence, homepageHtml);
        if (hasCanadaBusinessSignal(combined)) {
            result.setCountry("Canada");
            result.setLocation("Canada");
            result.setLocationSource("canada-only signal");
            logger.info("LOCATION EXTRACT | website={} | source={} | extracted='Canada'",
                    website, result.getLocationSource());
            return;
        }
        if (hasUsBusinessSignal(combined)) {
            result.setCountry("USA");
            result.setLocation(buildLocationDisplay(null, null, "USA"));
            result.setLocationSource("usa-only signal");
            logger.info("LOCATION EXTRACT | website={} | source={} | extracted='USA'",
                    website, result.getLocationSource());
            return;
        }
        result.setLocation("Unknown");
        result.setLocationSource(null);
        logger.info("LOCATION UNKNOWN | website={} | reason=no address/schema/location signal found | sources_tried={}",
                website, el == null ? "google business, search result, homepage schema, homepage footer, homepage, contact page, about page" : el.source);
    }

    /**
     * The requested state/country RESPECTED without hard-rejecting an unknown
     * city. Returns a rejection reason ONLY when a location was positively
     * resolved and it demonstrably sits outside what the user asked for:
     *   - a resolved state that is not the requested state (e.g. a Texas search
     *     resolving to California or to Ontario, Canada);
     *   - a resolved country that is not the searched country (e.g. a Texas
     *     search resolving to the UK).
     * Returns null - i.e. the candidate is KEPT - when the location could not
     * be resolved at all (an unknown city is never a conflict) and when the
     * resolved state/country agrees with the request, including a different
     * city inside the same state.
     */
    private String locationConflictReason(ExtractedLocation resolved, String requestedState,
                                         String requestedCountry, boolean stateMatches) {
        return locationConflictReason(resolved, null, requestedState, requestedCountry,
                stateMatches);
    }

    private String locationConflictReason(ExtractedLocation resolved, String website,
                                         String requestedState, String requestedCountry,
                                         boolean stateMatches) {
        if (resolved == null && website == null) {
            return null;
        }
        // Country conflict, form 1: the business exists only under a foreign
        // country domain. This is decisive even when the address itself could
        // not be extracted, so a UK / German / Australian wholesaler can never
        // be saved for a USA or Canada search.
        String tld = foreignCountryTldOf(website);
        if (tld != null && isUsaOrCanadaCountry(requestedCountry)) {
            return "website is registered under the foreign country domain '."
                    + tld + "', not '" + requestedCountry + "'";
        }
        if (resolved == null) {
            return null;
        }
        // Country conflict, form 2: the resolved address belongs to another
        // country than the user searched.
        if (resolved.country != null && !resolved.country.isBlank()
                && requestedCountry != null && !requestedCountry.isBlank()
                && !resolved.country.equalsIgnoreCase(requestedCountry)) {
            return "resolved country '" + resolved.country
                    + "' does not match the searched country '" + requestedCountry + "'";
        }
        // State conflict: an address in a different US state / Canadian province.
        if (requestedState != null && !requestedState.isBlank()
                && resolved.state != null && !resolved.state.isBlank()
                && !stateMatches) {
            return "resolved state '" + resolved.state
                    + "' does not match the requested state '" + requestedState + "'";
        }
        return null;
    }

    /** The foreign country-code domain of a website ("co.uk", "de", ...), or
     *  null for a USA / Canada / neutral domain. */
    private String foreignCountryTldOf(String website) {
        String host = hostOf(website);
        if (host == null || !host.contains(".")) {
            return null;
        }
        String[] labels = host.split("\\.");
        if (labels.length < 2) {
            return null;
        }
        String tld = labels[labels.length - 1];
        if (FOREIGN_COUNTRY_TLDS.contains(tld)) {
            return tld;
        }
        // Two-label country domains such as "co.uk" / "com.au".
        if (labels.length >= 3) {
            String pair = labels[labels.length - 2] + "." + tld;
            if (FOREIGN_COUNTRY_TLDS.contains(pair)) {
                return pair;
            }
        }
        return null;
    }

    private static boolean isUsaOrCanadaCountry(String country) {
        if (country == null || country.isBlank()) {
            return false;
        }
        String c = country.trim().toLowerCase(Locale.ROOT);
        return "usa".equals(c) || "us".equals(c) || "united states".equals(c)
                || "united states of america".equals(c)
                || "canada".equals(c) || "ca".equals(c);
    }

    /**
     * Multi-source business-location extraction used by the pre-save location
     * metadata resolver. Sources in priority
     * order: Google/Firecrawl business-metadata address, homepage schema.org
     * data, homepage footer, homepage body, then (only when nothing resolved yet)
     * the contact page and the about page fetched with plain HTTP - never a
     * Firecrawl credit. The search-listing title/snippet is NEVER a source: a
     * city word echoed by the engine is not location evidence (requirement 4).
     * Returns null when no reliable city/state exists anywhere. Nothing is ever
     * invented: a bare word is never accepted as a city (only real "City, ST" /
     * ZIP / state patterns count).
     */
    private ExtractedLocation resolveBusinessLocationFrom(String website, String searchAddress,
                                                          String homepageHtml) {
        // Source 1 - the structured Google Business / Firecrawl business address
        // (the business's own listing, never free-text search snippets).
        if (searchAddress != null && !searchAddress.isBlank()) {
            ExtractedLocation el = extractLocationFromText(searchAddress, "google business");
            if (el != null) {
                return el;
            }
        }

        if (homepageHtml != null && !homepageHtml.isBlank()) {
            // Source 3 - schema.org structured data on the homepage.
            String schema = extractSchemaAddress(homepageHtml);
            if (schema != null && !schema.isBlank()) {
                ExtractedLocation el = extractLocationFromText(schema, "homepage schema");
                if (el != null) {
                    return el;
                }
            }
            // Source 4 - homepage footer (most business addresses live there).
            String footer = extractFooterText(homepageHtml);
            if (footer != null && !footer.isBlank()) {
                ExtractedLocation el = extractLocationFromText(footer, "homepage footer");
                if (el != null) {
                    return el;
                }
            }
            // Source 5 - homepage body text (decoded, tag-stripped).
            String body = extractBodyText(homepageHtml);
            if (body != null && !body.isBlank()) {
                ExtractedLocation el = extractLocationFromText(body, "homepage");
                if (el != null) {
                    return el;
                }
            }
        }

        // Sources 6-7 - contact page then about page. Fetched only when the
        // buyer's city/state is still unknown (plain HTTP, no Firecrawl credit),
        // and bounded so an unreachable site cannot trigger a long request burst.
        int fetches = 0;
        for (String path : LOCATION_PAGE_PATHS) {
            if (fetches >= MAX_LOCATION_PAGE_FETCHES) {
                break;
            }
            fetches++;
            boolean contact = path.contains("contact") || path.contains("touch")
                    || path.contains("reach");
            String source = contact ? "contact page" : "about page";
            ExtractedLocation el = extractLocationFromPage(website, path, source);
            if (el != null) {
                return el;
            }
        }
        return null;
    }

    /**
     * Applies an extracted location to the result and logs the website / source
     * / extracted value (requirement: keep every location extract traceable).
     */
    private void applyExtractedLocation(BuyerSearchResult result, ExtractedLocation el) {
        result.setCity(el.city);
        result.setState(el.state);
        // The extracted country is only applied when it agrees with (or the
        // search was for) the searched country - a Canada search can NEVER be
        // re-labelled "USA" because its homepage happens to show a US-looking
        // address. The extracted country is retained only when it agrees with
        // the searched country.
        String searchedCountry = result.getCountry();
        if (el.country != null && (searchedCountry == null
                || el.country.equalsIgnoreCase(searchedCountry))) {
            result.setCountry(el.country);
        }
        result.setLocation(buildLocationDisplay(el.state, el.city, result.getCountry()));
        result.setLocationSource(el.source);
        logger.info("LOCATION EXTRACT | website={} | source={} | extracted='{}' | city={} | state={} | country={}",
                result.getWebsite(), el.source, result.getLocation(),
                el.city, el.state, el.country);
    }

    private ExtractedLocation extractLocationFromPage(String website, String path, String source) {
        String html = emailDiscoveryService.fetchWebsitePage(website, path);
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            String schema = extractSchemaAddress(html);
            if (schema != null && !schema.isBlank()) {
                ExtractedLocation el = extractLocationFromText(schema, source + " (schema)");
                if (el != null) {
                    return el;
                }
            }
            String body = extractBodyText(html);
            if (body != null && !body.isBlank()) {
                ExtractedLocation el = extractLocationFromText(body, source);
                if (el != null) {
                    return el;
                }
            }
        } catch (Exception e) {
            logger.debug("Location extraction failed on {} for '{}': {}", path, website, e.getMessage());
        }
        return null;
    }

    /**
     * Extracts a reliable city/state from a plain text block. "Reliable" means
     * a real US state / Canadian province (by abbreviation or full name) came
     * with it - a bare word is never accepted as a city. Returns null when no
     * such location is present.
     */
    private ExtractedLocation extractLocationFromText(String rawText, String source) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = buildRawText(rawText);

        // 1. "City ST ZIP" / "City, ST ZIP" (comma optional) - most reliable.
        Matcher m = CITY_STATE_ZIP_PATTERN.matcher(text);
        while (m.find()) {
            String city = cleanCityCandidate(m.group(1));
            String state = stateNameForAbbr(m.group(2));
            if (state != null) {
                return new ExtractedLocation(city, state, countryNameForState(state), source);
            }
        }
        // 2. "City, ST" (no ZIP).
        m = CITY_STATE_PATTERN.matcher(text);
        while (m.find()) {
            String city = cleanCityCandidate(m.group(1));
            String state = stateNameForAbbr(m.group(2));
            if (state != null) {
                return new ExtractedLocation(city, state, countryNameForState(state), source);
            }
        }
        // 3. "City, FullState" (e.g. "Dallas, Texas").
        m = CITY_FULL_STATE_PATTERN.matcher(text);
        while (m.find()) {
            String city = cleanCityCandidate(m.group(1));
            String state = stateFullName(m.group(2));
            if (state != null) {
                return new ExtractedLocation(city, state, countryNameForState(state), source);
            }
        }
        // 4. "ST ZIP" - a state plus a ZIP is still a reliable state.
        m = STATE_ZIP_PATTERN.matcher(text);
        while (m.find()) {
            String state = stateNameForAbbr(m.group(1));
            if (state != null) {
                return new ExtractedLocation(null, state, countryNameForState(state), source);
            }
        }
        // 5. A standalone full state/province name ("We ship from Texas").
        String state = standaloneStateName(text);
        if (state != null) {
            return new ExtractedLocation(null, state, countryNameForState(state), source);
        }
        return null;
    }

    private String stateNameForAbbr(String abbr) {
        if (abbr == null) {
            return null;
        }
        String name = STATE_ABBR_TO_NAME.get(abbr.toUpperCase(Locale.ROOT));
        return name != null ? titleCaseState(name) : null;
    }

    private String stateFullName(String matched) {
        if (matched == null) {
            return null;
        }
        String key = matched.toLowerCase(Locale.ROOT);
        for (String name : US_STATE_NAMES) {
            if (name.equals(key)) {
                return titleCaseState(name);
            }
        }
        for (String name : CANADIAN_PROVINCE_NAMES) {
            if (name.equals(key)) {
                return titleCaseState(name);
            }
        }
        return null;
    }

    private String standaloneStateName(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : US_STATE_NAMES) {
            if (containsToken(lower, name)) {
                return titleCaseState(name);
            }
        }
        for (String name : CANADIAN_PROVINCE_NAMES) {
            if (containsToken(lower, name)) {
                return titleCaseState(name);
            }
        }
        return null;
    }

    // Turns the text before ", ST" / "ST ZIP" into a clean, reliable city name.
    // Confirmed known cities win; leading house numbers and street names are
    // stripped; short residues are kept whole and title-cased. A pure street
    // address ("123 Main St") has NO city - null is returned so the buyer keeps
    // only their verified state instead of an invented city.
    private String cleanCityCandidate(String chunk) {
        if (chunk == null) {
            return null;
        }
        String c = chunk.trim();
        if (c.isEmpty()) {
            return null;
        }
        // 1) A known city suffix proves the exact city without guessing.
        String known = knownCitySuffix(c);
        if (known != null) {
            return known;
        }
        // 2) Strip leading house-number tokens ("123 Main St Dallas" -> "Main St Dallas").
        if (c.matches(".*\\d.*")) {
            String[] toks = c.split("\\s+");
            int i = 0;
            while (i < toks.length && toks[i].matches(".*\\d.*")) {
                i++;
            }
            c = joinWhitespace(toks, i, toks.length);
        }
        String[] toks = c.split("\\s+");
        if (toks.length == 0 || (toks.length == 1 && toks[0].isEmpty())) {
            return null;
        }
        // 3) Strip "<street-name> <street-suffix>" pairs when they only preface
        //    a real city ("Main St Dallas" -> "Dallas"). When they ARE the whole
        //    chunk ("123 Main St", "221B Baker Street") no city exists at all.
        int keepFrom = 0;
        while (toks.length - keepFrom >= 2 && isStreetSuffix(toks[keepFrom + 1])) {
            keepFrom += 2;
        }
        int remaining = toks.length - keepFrom;
        if (remaining == 0) {
            return null;
        }
        // 4) Multiword residue: prefer the last two tokens ("Avery Creek").
        if (remaining >= 2) {
            String pair = toks[toks.length - 2] + " " + toks[toks.length - 1];
            // Guard: a trailing single-word street pair ("Oak St") is still a
            // street, not a city - no city can be claimed.
            if (remaining == 2 && (isStreetSuffix(toks[toks.length - 1])
                    || isStreetSuffix(toks[toks.length - 2]))) {
                return null;
            }
            return titleCaseCity(pair);
        }
        return titleCaseCity(joinWhitespace(toks, keepFrom, toks.length));
    }

    private boolean isStreetSuffix(String token) {
        if (token == null) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        switch (t) {
            case "st": case "st.": case "street": case "ave": case "ave.":
            case "avenue": case "rd": case "road": case "dr": case "dr.":
            case "drive": case "blvd": case "blvd.": case "boulevard":
            case "ln": case "lane": case "ct": case "court": case "pl":
            case "place": case "way": case "pkwy": case "parkway": case "hwy":
            case "highway": case "sq": case "square": case "trl": case "trail":
            case "cir": case "circle": case "ter": case "terrace": case "ste":
            case "suite": case "unit": case "bldg": case "boulee":
                return true;
            default:
                return false;
        }
    }

    private String knownCitySuffix(String chunk) {
        if (chunk == null) {
            return null;
        }
        String[] toks = chunk.trim().split("\\s+");
        for (int i = 0; i < toks.length; i++) {
            StringBuilder phrase = new StringBuilder();
            for (int j = i; j < toks.length; j++) {
                if (phrase.length() > 0) {
                    phrase.append(' ');
                }
                phrase.append(toks[j]);
            }
            String display = KNOWN_US_CA_CITIES.get(phrase.toString().toLowerCase(Locale.ROOT));
            if (display != null) {
                return display;
            }
        }
        return null;
    }

    private String titleCaseCity(String city) {
        if (city == null) {
            return null;
        }
        String clean = collapseWhitespace(city);
        if (clean == null) {
            return null;
        }
        String[] toks = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String tok : toks) {
            if (tok.isEmpty()) {
                continue;
            }
            String lower = tok.toLowerCase(Locale.ROOT);
            if (lower.equals("st") || lower.equals("st.") || lower.equals("saint")) {
                sb.append("St.");
            } else if (lower.equals("mt") || lower.equals("mt.")) {
                sb.append("Mt.");
            } else if (lower.startsWith("mc") && lower.length() > 2) {
                sb.append("Mc").append(Character.toUpperCase(lower.charAt(2)))
                        .append(lower.substring(3));
            } else {
                sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private String titleCaseState(String name) {
        if (name == null) {
            return null;
        }
        String clean = collapseWhitespace(name);
        if (clean == null) {
            return null;
        }
        String[] toks = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String tok : toks) {
            if (tok.isEmpty()) {
                continue;
            }
            String lower = tok.toLowerCase(Locale.ROOT);
            if (lower.equals("of") || lower.equals("and") || lower.equals("the")) {
                sb.append(lower);
            } else {
                sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private String joinWhitespace(String[] parts, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private String extractFooterText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Elements footers = doc.select("footer, [class*=footer], [id*=footer], "
                    + "[class*=Footer], [id*=Footer]");
            StringBuilder sb = new StringBuilder();
            for (Element f : footers) {
                String t = f.text();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append(' ');
                }
            }
            return sb.length() == 0 ? null : collapseWhitespace(sb.toString());
        } catch (Exception e) {
            logger.debug("Failed to extract footer text: {}", e.getMessage());
            return null;
        }
    }

    private String extractBodyText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            String text = doc.body() != null ? doc.body().text() : doc.text();
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            logger.debug("Failed to extract page text: {}", e.getMessage());
            return null;
        }
    }

    /** Structured address from a page: schema.org JSON-LD first, then HTML
     *  microdata (itemprop=addressLocality / addressRegion). */
    private String extractSchemaAddress(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Elements scripts = doc.select("script[type='application/ld+json']");
            for (Element script : scripts) {
                String json = script.data();
                if (json == null || json.isBlank()) {
                    continue;
                }
                try {
                    JsonNode root = objectMapper.readTree(json);
                    String address = extractSchemaAddressNode(root);
                    if (address != null && !address.isBlank()) {
                        return address;
                    }
                } catch (JsonProcessingException e) {
                    // malformed JSON-LD block - try the next one
                }
            }
            Element locEl = doc.selectFirst("[itemprop=addressLocality]");
            Element regionEl = doc.selectFirst("[itemprop=addressRegion]");
            if (locEl != null || regionEl != null) {
                StringBuilder sb = new StringBuilder();
                if (locEl != null && !locEl.text().isBlank()) {
                    sb.append(locEl.text().trim());
                }
                if (regionEl != null && !regionEl.text().isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(regionEl.text().trim());
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse schema data: {}", e.getMessage());
        }
        return null;
    }

    private String extractSchemaAddressNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String found = extractSchemaAddressNode(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        String locality = textFieldOrNull(node, "addressLocality");
        String region = textFieldOrNull(node, "addressRegion");
        String postal = textFieldOrNull(node, "postalCode");
        if (locality != null || region != null) {
            StringBuilder sb = new StringBuilder();
            if (locality != null) {
                sb.append(locality);
            }
            if (region != null) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(region);
            }
            if (postal != null) {
                sb.append(' ').append(postal);
            }
            return sb.toString();
        }
        JsonNode address = node.get("address");
        if (address != null && address.isObject()) {
            String found = extractSchemaAddressNode(address);
            if (found != null) {
                return found;
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            String found = extractSchemaAddressNode(fields.next().getValue());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String textFieldOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isTextual()) {
            String s = v.asText();
            return s.isBlank() ? null : s.trim();
        }
        return null;
    }

    private boolean hasUsBusinessSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            return UsaVerificationService.STATUS_US.equals(
                    usaVerificationService.verify(text).getStatus());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasCanadaBusinessSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : CANADIAN_PROVINCE_NAMES) {
            if (containsToken(lower, name)) {
                return true;
            }
        }
        return CANADA_POSTAL_PATTERN.matcher(lower).find();
    }

    // ------------------------------------------------------------------
    // Home-decor product category gate helpers (used BEFORE any Firecrawl
    // request so invalid products never spend credits).
    // ------------------------------------------------------------------

    private boolean isSupportedHomeDecorProduct(String productEntry) {
        String p = normalizeProductForValidation(productEntry);
        if (p.isEmpty()) {
            return false;
        }
        Set<String> words = new HashSet<>(tokenizeWords(p));
        return isBowlFamilyProduct(p, words)
                || isCandleProduct(p, words)
                || isLanternProduct(p)
                || isDecorativeJarProduct(words)
                || isHomeDecorProduct(p, words)
                || isHomeGoodProduct(p, words)
                || isSoundHealingProduct(p);
    }

    private boolean isSoundHealingProduct(String p) {
        for (String phrase : SOUND_HEALING_TERMS) {
            if (p.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBowlFamilyProduct(String p, Set<String> words) {
        if (!words.contains("bowl") && !words.contains("bowls")) {
            return false;
        }
        for (String qualifier : SUPPORTED_BOWL_QUALIFIERS) {
            if (words.contains(qualifier)) {
                return true;
            }
        }
        // Alternate phrasings: "sing bowls", "song bowls", "singing bowls".
        return p.contains("sing bowl") || p.contains("song bowl");
    }

    private boolean isCandleProduct(String p, Set<String> words) {
        if (p.contains("candle") || p.contains("candela")) {
            return true;
        }
        return words.contains("votive") || words.contains("votives")
                || words.contains("taper") || words.contains("tapers")
                || words.contains("tealight") || words.contains("tealights")
                || p.contains("tea light") || p.contains("tea lights");
    }

    private boolean isLanternProduct(String p) {
        return p.contains("lantern");
    }

    /** TRUE when the product is a recognised PHYSICAL home-goods noun that is
     *  not one of the nine reference families (e.g. "vase", "area rug", "wall
     *  mirror", "macrame wall hanging", "reed diffuser"). The event/non-decor
     *  word vetoes still apply, so "party decorations" and "car decor" stay
     *  rejected. Called LAST - the reference families always win. */
    private boolean isHomeGoodProduct(String p, Set<String> words) {
        if (isNonHomeDecorOrEventProduct(p, words)) {
            return false;
        }
        for (String word : words) {
            if (HOME_GOOD_PRODUCT_NOUNS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** The category vetoes shared by isHomeDecorProduct and isHomeGoodProduct:
     *  event/party supplies and clearly non-home products are never home decor,
     *  whatever decor or home-goods noun the product string contains. */
    private boolean isNonHomeDecorOrEventProduct(String p, Set<String> words) {
        for (String eventWord : EVENT_NON_DECOR_WORDS) {
            if (words.contains(eventWord)) {
                return true;
            }
        }
        for (String nonHomeWord : NON_HOME_DECOR_WORDS) {
            if (words.contains(nonHomeWord)) {
                return true;
            }
        }
        return false;
    }

    /** The product's own NOUN PHRASE: the maximal TRAILING run of the entered
     *  words that are recognised home-goods nouns. English compounds put the
     *  head noun last, so "wall mirror" -> "mirror", "flower vase" -> "vase",
     *  "area rug" -> "rug" and "macrame wall hanging" -> "hanging", while a
     *  compound product keeps every noun it was entered with: "throw pillows" ->
     *  "throw pillows", "incense holder" -> "incense holder". Returns null when
     *  the product carries no recognised noun. */
    private String homeGoodNounPhrase(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        String[] words = p.split(" ");
        int start = words.length;
        while (start > 0 && isProductNounWord(words[start - 1])) {
            start--;
        }
        if (start == words.length) {
            return null;
        }
        return String.join(" ", List.of(words).subList(start, words.length));
    }

    /** TRUE when the word names the item being sold rather than its material,
     *  craft or setting, so "glass vases" is the product noun phrase "vases". */
    private static boolean isProductNounWord(String word) {
        return HOME_GOOD_PRODUCT_NOUNS.contains(word)
                && !NON_PRODUCT_DESCRIPTOR_WORDS.contains(word);
    }

    /** The CANONICAL (singular) form of a recognised home-goods noun, decided by
     *  set membership rather than string surgery so short plurals are handled:
     *  "rugs" -> "rug", "pots" -> "pot", "cachepots" -> "cachepot",
     *  "candies" -> "candy". A word that is not a recognised noun, or whose
     *  singular form is not itself a recognised noun, is returned unchanged. */
    /** Words that are recognised home-goods nouns but describe the MATERIAL, the
     *  craft or the setting rather than the item being sold. They must never
     *  count as a product change, so "glass vases" is a valid name for a "vase"
     *  and "canvas prints" for a "print". */
    private static final Set<String> NON_PRODUCT_DESCRIPTOR_WORDS = Set.of(
            "glass", "stone", "ceramic", "pottery", "porcelain", "crystal",
            "quartz", "amethyst", "selenite", "malachite", "canvas", "string",
            "light", "storage", "plant", "garden", "outdoor", "faux", "floral",
            "botanical", "ornamental", "solar", "fairy", "wax", "aroma", "scent",
            "reed", "wick"
    );

    private static String canonicalNoun(String word) {
        if (word == null || word.isEmpty() || !HOME_GOOD_PRODUCT_NOUNS.contains(word)
                || !word.endsWith("s")) {
            return word;
        }
        if (word.endsWith("ies") && word.length() > 4) {
            String yForm = word.substring(0, word.length() - 3) + "y";
            if (HOME_GOOD_PRODUCT_NOUNS.contains(yForm)) {
                return yForm;
            }
        }
        String singular = word.substring(0, word.length() - 1);
        return HOME_GOOD_PRODUCT_NOUNS.contains(singular) ? singular : word;
    }

    /** The ALTERNATE NAMES of the entered product: the phrasings retailers,
     *  wholesalers and importers actually use for the same physical product.
     *  These are fired as the trailing synonym slots of the query plan and are
     *  also used as product-relevance phrases, so a "Vase" search also finds
     *  "Glass Vases Wholesale" and a "Glass Vases" search also finds a plain
     *  "vase" store.
     *
     *  An alternate name NEVER changes the product: a single-noun product uses
     *  the PRODUCT_ALTERNATE_NAMES entry for that noun, and every candidate is
     *  then re-checked so that each product noun it names was also named by the
     *  user. That guard is what keeps "incense holder" from picking up "candle
     *  holders" and "throw pillows" from becoming "throws". A multi-noun product
     *  gets the safe qualifiers on the user's own phrasing instead. */
    private List<String> homeGoodAlternateNames(String product) {
        String p = normalizeProductForValidation(product);
        if (p == null || p.isBlank()) {
            return List.of();
        }
        Set<String> words = new HashSet<>(tokenizeWords(p));
        if (!isHomeGoodProduct(p, words)) {
            return List.of();
        }
        String nounPhrase = homeGoodNounPhrase(p);
        if (nounPhrase == null) {
            // A product whose only recognised word is a material ("stained
            // glass", "crystal glass") has no product noun of its own, so the
            // qualifiers are applied to the whole entered phrase.
            nounPhrase = p;
        }
        Set<String> productNouns = new HashSet<>();
        for (String word : nounPhrase.split(" ")) {
            productNouns.add(canonicalNoun(word));
        }

        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(p);

        // Alternate names taken from the table are re-worded products, so each one
        // is re-checked against the searched product: it may only ADD a word, never
        // name a product the user did not ask for.
        String[] nounWords = nounPhrase.split(" ");
        if (nounWords.length == 1 && isModifierQualifiedNoun(p, nounPhrase)) {
            for (String candidate : PRODUCT_ALTERNATE_NAMES
                    .getOrDefault(canonicalNoun(nounWords[0]), List.of())) {
                if (namesOnlyProductNouns(candidate, productNouns)) {
                    addAlternateName(out, seen, candidate);
                }
            }
        }

        // Qualifiers are prefixed to the user's OWN phrasing, so they already name
        // the searched product and are safe for every other product shape.
        for (String qualifier : HOME_GOOD_NAME_QUALIFIERS) {
            if (out.size() >= MAX_PRODUCT_ALTERNATE_NAMES) {
                break;
            }
            if (containsWholeWord(p, qualifier)) {
                continue;
            }
            addAlternateName(out, seen, qualifier + " " + p);
        }

        return out.size() > MAX_PRODUCT_ALTERNATE_NAMES
                ? new ArrayList<>(out.subList(0, MAX_PRODUCT_ALTERNATE_NAMES))
                : out;
    }

    /** TRUE when every recognised PRODUCT noun named by the candidate phrase
     *  was also named by the searched product (compared in singular form) - the
     *  invariant that stops an alternate name from silently turning into a
     *  different product. Material and setting words ("glass", "canvas",
     *  "storage") describe the same item and are not a product change. */
    private boolean namesOnlyProductNouns(String candidate, Set<String> productNouns) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        for (String word : tokenizeWords(candidate)) {
            if (HOME_GOOD_PRODUCT_NOUNS.contains(word)
                    && !NON_PRODUCT_DESCRIPTOR_WORDS.contains(word)
                    && !productNouns.contains(canonicalNoun(word))) {
                return false;
            }
        }
        return true;
    }

    /** TRUE when the words in front of the product noun are all safe MODIFIERS,
     *  so the entered phrase really is "<modifier(s)> <noun>" and the
     *  alternate-name table applies. "Flower vase" and "area rug" qualify;
     *  "resin art", "reed diffuser" and "stained glass" do not. */
    private boolean isModifierQualifiedNoun(String product, String nounPhrase) {
        List<String> words = new ArrayList<>(Arrays.asList(product.split(" ")));
        words.removeAll(Arrays.asList(nounPhrase.split(" ")));
        for (String word : words) {
            if (!HOME_GOOD_PRODUCT_MODIFIERS.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private void addAlternateName(List<String> out, Set<String> seen, String name) {
        if (name == null) {
            return;
        }
        String trimmed = collapseWhitespace(name);
        if (trimmed == null || trimmed.isBlank()) {
            return;
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (seen.add(key)) {
            out.add(trimmed);
        }
    }

    private boolean isDecorativeJarProduct(Set<String> words) {
        if (!words.contains("jar") && !words.contains("jars")) {
            return false;
        }
        for (String context : DECORATIVE_JAR_CONTEXT) {
            if (words.contains(context)) {
                return true;
            }
        }
        return false;
    }

    /** TRUE when the SEARCHED product is a jar/canister family phrase - the
     *  trigger for the looser jar/canister combo relevance rule. Covering the
     *  related commercial names (glass jars, canisters, containers) so results
     *  found for ANY decorative-jar synonym get the same relaxed processing. */
    private boolean isJarFamilyQuery(String product) {
        return product != null
                && JAR_FAMILY_QUERY_PATTERN.matcher(product.toLowerCase(Locale.ROOT)).find();
    }

    /** Jar/canister combination proof (requirement 1): the site states a
     *  jar-family noun (jar/canister/container) together with a PRODUCT modifier
     *  (decorative, glass, storage, display, apothecary, handle, kitchen...) AND
     *  carries business or local evidence (store, shop, retailer, wholesale,
     *  supplier, boutique, catalog, ..., or a "City, ST" address). This is the
     *  relaxation that lets a real decorative-jar seller that worded its page
     *  differently (e.g. "Set of Blue & White Ribbed Kitchen Canisters", with a
     *  local address) reach homepage verification instead of being auto-
     *  rejected. It still NEVER accepts a naked "jar" mention, and a canning /
     *  lab / pickle page is blocked by JAR_REJECT_CONTEXTS before this runs. */
    private boolean jarComboRelevant(String titleText, String snippetText, String path,
                                     String domain, String homeText) {
        List<String> zones = new ArrayList<>();
        if (titleText != null && !titleText.isBlank()) {
            zones.add(titleText);
        }
        if (snippetText != null && !snippetText.isBlank()) {
            zones.add(snippetText);
        }
        if (path != null && !path.isBlank()) {
            zones.add(path);
        }
        if (domain != null && !domain.isBlank()) {
            zones.add(domain);
        }
        if (homeText != null && !homeText.isBlank()) {
            zones.add(homeText);
        }
        String text = String.join(" ", zones).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        Set<String> words = new HashSet<>();
        for (String w : tokenizeWords(text)) {
            if (w.length() >= 2) {
                words.add(w);
            }
        }
        boolean jarOrCanister = words.contains("jar") || words.contains("jars")
                || words.contains("canister") || words.contains("canisters");
        boolean containerWord = words.contains("container") || words.contains("containers");
        boolean decoOrGlass = words.contains("decorative") || words.contains("decoration")
                || words.contains("decor") || words.contains("glass");
        // "container" alone is too generic (shipping containers); it only counts
        // with an explicit decorative/glass qualifier.
        if (!jarOrCanister && !(containerWord && decoOrGlass)) {
            return false;
        }
        boolean modifier = false;
        for (String m : JAR_COMBO_MODIFIERS) {
            if (words.contains(m)) {
                modifier = true;
                break;
            }
        }
        if (!modifier) {
            return false;
        }
        for (String b : JAR_COMBO_BUSINESS_EVIDENCE) {
            if (words.contains(b)) {
                return true;
            }
        }
        // "Houston, TX" style local-business addresses count as business evidence.
        return US_CITY_STATE_PATTERN.matcher(text).find();
    }

    /** TRUE when the search evidence (title/snippet/shopping path/domain)
     *  actually declares the jar/canister family - a jar-family noun appears -
     *  as opposed to merely being the searched product label. The handle gate
     *  uses this to distinguish "the page claims the family but never shows a
     *  handle" (rejected for a "jars with handle" product) from "the page does
     *  not claim the jar family at all" (left to the stricter strong-phrase
     *  gate below). */
    private boolean jarFamilyMentioned(String titleText, String snippetText, String path,
                                       String domain) {
        String text = prepareText(titleText, snippetText, path, domain);
        for (String noun : JAR_FAMILY_NOUNS) {
            if (containsPhrase(text, noun)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the handle evidence when the page shows a handle for the
     *  jar/canister family ("with handle", "handles", "handle", "handled"), or
     *  null. A "Jars With Handle" product demands this on any page that claims
     *  the jar family: without it the mention is generic stock, not the
     *  searched product. */
    private String findHandleEvidence(String titleText, String snippetText, String path,
                                      String domain, String homeText) {
        String text = prepareText(titleText, snippetText, path, domain, homeText);
        for (String form : new String[]{"with handle", "handles", "handle", "handled"}) {
            if (containsPhrase(text, form)) {
                return form;
            }
        }
        return null;
    }

    private boolean isHomeDecorProduct(String p, Set<String> words) {
        // Event/party supplies and other non-home venues are never home decor.
        for (String eventWord : EVENT_NON_DECOR_WORDS) {
            if (words.contains(eventWord)) {
                return false;
            }
        }
        // Car decor, cake decorations, clothing, hotels, ... are not home decor.
        for (String nonHomeWord : NON_HOME_DECOR_WORDS) {
            if (words.contains(nonHomeWord)) {
                return false;
            }
        }
        // "decor"/"decorative"/"decoration"/"dÃ©cor" and every home-* decor
        // phrase (home decor, wall decor, table decor, ...) matches here.
        if (p.contains("decor")) {
            return true;
        }
        for (String phrase : HOME_DECOR_PHRASES) {
            if (p.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeProductForValidation(String product) {
        if (product == null) {
            return "";
        }
        String normalized = product.toLowerCase(Locale.ROOT)
                .replace('\u00e9', 'e').replace('\u00e8', 'e').replace('\u00ea', 'e')
                .replace('\u00e2', 'a').replace('\u00e0', 'a')
                .replace('\u00f4', 'o')
                .replace('\u00fb', 'u').replace('\u00f9', 'u')
                .replace('\u00ee', 'i').replace('\u00ef', 'i')
                .replace('\u00e7', 'c');
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private List<String> tokenizeWords(String text) {
        return Arrays.stream(text.split("[^a-z0-9]+"))
                .filter(w -> w.length() >= 2)
                .collect(Collectors.toList());
    }

    /**
     * Builds raw (original-case, punctuation-preserved) concatenated text from
     * the given parts, with HTML tags removed. Used for address-pattern checks
     * such as "city, ST" where comma placement carries meaning.
     */
    private String buildRawText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            sb.append(part).append(' ');
        }
        return sb.toString().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private String prepareText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            sb.append(part).append(' ');
        }
        String cleaned = sb.toString()
                .replaceAll("<[^>]*>", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&#\\d+;", " ")
                .replaceAll("\\p{Punct}", " ");
        return " " + cleaned.replaceAll("\\s+", " ").toLowerCase() + " ";
    }

    private String normalizeForComparison(String s) {
        if (s == null) {
            return null;
        }
        return s.toLowerCase().replaceAll("\\p{Punct}", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean containsToken(String text, String token) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(token) + "(?![a-z0-9])")
                .matcher(text).find();
    }

    // ------------------------------------------------------------------
    // Cheap page-type & business-identity screening - URL + title only, no
    // homepage fetch. Runs before verification/email scraping so rejected,
    // duplicate, irrelevant and non-buyer results never cost processing.
    // ------------------------------------------------------------------

    /**
     * Returns a "REJECTED - ..." reason when the page must be dropped, or null
     * when it may represent a real buyer business and deserves deeper
     * (homepage-based) verification. Marketplaces are NOT rejected by domain:
     * a page that clearly identifies a distinct relevant seller business
     * passes, a generic product/listing page is rejected.
     */
    private String classifyPage(String url, String title, String product) {
        String hostL = hostOf(url) == null ? "" : hostOf(url);
        String path = pathOf(url) == null ? "" : pathOf(url);
        String query = queryOf(url) == null ? "" : queryOf(url);
        String t = title == null ? "" : title;
        String tL = t.toLowerCase();

        // Files / documents (PDFs, office docs, archives, images...) are never
        // buyer business pages.
        if (isFileUrl(path)) {
            return REJECT_FILE;
        }

        // Academic / research pages (universities, journals, working papers).
        if (isAcademicLike(hostL, path, tL)) {
            return REJECT_ACADEMIC;
        }

        // Government websites are never buyers.
        if (hostL.matches("(?i).*\\.gov(\\.[a-z]{2})?$") || hostL.endsWith(".mil")
                || path.contains("/gov")
                || tL.contains("department of") || tL.contains("ministry of")
                || tL.contains("official website of")) {
            return REJECT_GOV;
        }

        // Blogs / articles / news / pure content pages are not buyer businesses.
        if (isArticleLike(hostL, path, tL)) {
            return REJECT_ARTICLE;
        }

        // Directories / forums / listing aggregators are not buyer businesses.
        if (isDirectoryLike(hostL, path, tL)) {
            return REJECT_NOT_BUYER;
        }

        boolean marketplace = isShoppingHost(hostL);
        boolean platform = isPlatformHost(hostL);
        boolean productPath = matchesAny(path, SHOPPING_PATH_MARKERS);
        boolean shoppingQuery = containsAnyKey(query, SHOPPING_QUERY_KEYS);
        boolean shoppingTitle = matchesAny(tL, SHOPPING_TITLE_MARKERS)
                || PRICE_PATTERN.matcher(t).find();
        boolean productDetail = productPath || shoppingQuery;

        if (marketplace) {
            // ANY page on a marketplace is rejected outright. A listing may
            // identify a plausible seller brand, but the "domain" is the
            // marketplace itself (etsy.com, ebay.com, faire.com, atly.com,
            // importyeti.com...) - it is not the seller's own business website
            // and never has its own product catalog/contact ownership. Only a
            // business with its own website is a genuine buyer.
            logger.info("BUSINESS EVIDENCE | MARKETPLACE '{}' - rejecting listing on marketplace host", url);
            return REJECT_SHOPPING;
        }

        if (productDetail || shoppingTitle || platform) {
            // Generic shopping pages (search/product listing pages, unbranded
            // platform storefronts) are rejected; a page that identifies a
            // distinct seller business may still be a genuine buyer.
            String brand = bestBrandSegment(tL, product);
            boolean distinctSeller = brand != null && isMeaningfulName(brand, product)
                    && !mentionsAggregator(brand) && hasGenuineBrandWord(brand);
            if (distinctSeller) {
                return null;
            }
            return REJECT_SHOPPING;
        }

        return null;
    }

    /**
     * CHEAP RAW PRE-FILTER. Returns a REJECT reason when the RAW Firecrawl
     * result is obviously a page KIND that can never buy a physical product, or
     * null when the result may still be a product business.
     *
     * It reads ONLY the URL, the title and the snippet - never a fetched page -
     * so it costs no Firecrawl credit and no email request, and it runs BEFORE
     * the product-relevance gate. It rejects exactly:
     *   - news / article / press pages
     *   - conference / event / registration pages
     *   - attendee / exhibitor / sponsor LIST pages
     *   - medical / academic / research pages
     *   - government / tourism & visitor-board pages
     *   - music / song / artist pages
     *   - unrelated business directories that only list other companies
     *
     * It is deliberately LIGHT so the "No buyers found" problem cannot return:
     *   * a LEGITIMATE PRODUCT URL (/product, /products, /shop, /store,
     *     /collection, /catalog, ...) is always KEPT;
     *   * home-decor / wellness / spiritual / meditation / yoga / gift store
     *     copy is never rejected here - none of the marker lists below
     *     describes a shop;
     *   * no commercial evidence, business type, buyer role, email, city or
     *     state is required to pass.
     *
     * @return the rejection reason, or null to keep the result
     */
    /** TRUE when a dot-delimited host LABEL of the host is the given word, or
     *  ends with it after a hyphen, so "hospital", "stmarys-hospital" and
     *  "stmarys.hospital" all match while the genuine retailer
     *  "medicalsupplies.example" does not. */
    private static boolean hasHostLabel(String hostL, String label) {
        for (String part : hostL.split("\\.")) {
            if (part.equals(label) || part.endsWith("-" + label)) {
                return true;
            }
        }
        return false;
    }

    /** Host-identity rejection shared by the raw pre-filter and the full-page
     *  verification screen: music / event / research / news / hospital /
     *  travel-booking platforms and government bodies are never product
     *  businesses, whatever their URL path or page copy says. */
    private String irrelevantHostReason(String host) {
        String hostL = host == null ? "" : host;
        for (String label : HEALTHCARE_HOST_LABELS) {
            if (hasHostLabel(hostL, label)) {
                return REJECT_IRRELEVANT_CONTENT + ": host '" + hostL
                        + "' is a hospital / healthcare site, not a product business";
            }
        }
        for (String part : HEALTHCARE_HOST_PARTS) {
            if (hostL.contains(part)) {
                return REJECT_IRRELEVANT_CONTENT + ": host '" + hostL
                        + "' is a hospital / healthcare site, not a product business";
            }
        }
        for (String part : TRAVEL_BOOKING_HOST_PARTS) {
            if (hostL.contains(part)) {
                return REJECT_IRRELEVANT_CONTENT + ": host '" + hostL
                        + "' is a travel / booking platform, not a product business";
            }
        }
        for (String part : NEWS_HOST_PARTS) {
            if (hostL.contains(part)) {
                return REJECT_IRRELEVANT_CONTENT + ": host '" + hostL
                        + "' is a news / editorial site, not a product business";
            }
        }
        for (String part : IRRELEVANT_CONTENT_HOST_PARTS) {
            if (hostL.contains(part)) {
                return REJECT_IRRELEVANT_CONTENT + ": host '" + hostL
                        + "' is a music / event / research / news platform, not a product business";
            }
        }
        for (String suffix : GOVERNMENT_HOST_SUFFIXES) {
            if (hostL.endsWith(suffix)) {
                return REJECT_IRRELEVANT_CONTENT + ": host '" + hostL
                        + "' is a government / public body, not a product business";
            }
        }
        return null;
    }

    private String rawResultPreFilterReason(String url, String titleText, String snippetText) {
        String host = hostOf(url);
        String hostL = host == null ? "" : host;
        String path = pathOf(url);

        // 1) Hard host identity: a music / event / research / news / hospital /
        //    travel platform or a government body is never a product business.
        String hostReject = irrelevantHostReason(hostL);
        if (hostReject != null) {
            return hostReject;
        }
        // 2) The URL itself is a music / news / event / registration / booking /
        //    hospital SECTION.
        if (hasIrrelevantPathSegment(path)) {
            return REJECT_IRRELEVANT_CONTENT + ": URL path '" + cleanForLog(path)
                    + "' is a music / news / event / registration / booking section";
        }
        // 3) A legitimate product / collection / catalogue URL is KEPT. This is
        //    the escape hatch: a shop never loses a result over surface wording.
        if (hasLegitimateProductUrl(path)) {
            return null;
        }
        String text = prepareText(titleText, snippetText);
        // 4) Surface wording of a page kind that can never be a buyer.
        String music = firstMatchedPhrase(MUSIC_CONTENT_PHRASES, text);
        if (music != null) {
            return REJECT_IRRELEVANT_CONTENT + ": music/song/artist content ('" + music + "')";
        }
        String news = firstMatchedPhrase(NEWS_ARTICLE_PHRASES, text);
        if (news != null) {
            return REJECT_IRRELEVANT_CONTENT + ": news/article content ('" + news + "')";
        }
        String event = firstMatchedPhrase(EVENT_REGISTRATION_PHRASES, text);
        if (event != null) {
            return REJECT_IRRELEVANT_CONTENT + ": event/registration page ('" + event + "')";
        }
        String attendeeList = firstMatchedPhrase(ATTENDEE_EXHIBITOR_LIST_PHRASES, text);
        if (attendeeList != null) {
            return REJECT_IRRELEVANT_CONTENT + ": attendee/exhibitor list page ('" + attendeeList + "')";
        }
        String medical = firstMatchedPhrase(MEDICAL_CONTENT_PHRASES, text);
        if (medical != null) {
            return REJECT_IRRELEVANT_CONTENT + ": medical page ('" + medical + "')";
        }
        String academic = firstMatchedPhrase(ACADEMIC_RESEARCH_PHRASES, text);
        if (academic != null) {
            return REJECT_IRRELEVANT_CONTENT + ": academic/research page ('" + academic + "')";
        }
        String tourism = firstMatchedPhrase(TOURISM_CONTENT_PHRASES, text);
        if (tourism != null) {
            return REJECT_IRRELEVANT_CONTENT + ": tourism/visitor-board content ('" + tourism + "')";
        }
        String travel = firstMatchedPhrase(TRAVEL_BOOKING_PHRASES, text);
        if (travel != null) {
            return REJECT_IRRELEVANT_CONTENT + ": travel/booking page ('" + travel + "')";
        }
        String directory = firstMatchedPhrase(UNRELATED_DIRECTORY_PHRASES, text);
        if (directory != null) {
            return REJECT_IRRELEVANT_CONTENT + ": unrelated business directory ('" + directory + "')";
        }
        String industry = firstMatchedPhrase(UNRELATED_INDUSTRY_PHRASES, text);
        if (industry != null) {
            return REJECT_IRRELEVANT_CONTENT + ": unrelated industry ('" + industry + "')";
        }
        return null;
    }

    /** TRUE when the URL path is a legitimate product / collection / catalogue
     *  page. Matched as a WHOLE segment so a product-named path such as
     *  "/collections/singing-bowls", "/catalogue/music-boxes" or
     *  "/store/bath-candles" is recognised while "/news/singing-bowls" is not. */
    private boolean hasLegitimateProductUrl(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT).replaceAll("/{2,}", "/");
        for (String segment : LEGITIMATE_PRODUCT_URL_SEGMENTS) {
            String needle = "/" + segment;
            int from = 0;
            while (true) {
                int idx = p.indexOf(needle, from);
                if (idx < 0) {
                    break;
                }
                int after = idx + needle.length();
                if (after >= p.length() || p.charAt(after) == '/') {
                    return true;
                }
                from = idx + 1;
            }
        }
        return false;
    }

    /**
     * LIGHT irrelevant-content check. Returns a REJECT reason when the page is
     * an obvious NON-product result that merely mentions the product, or null
     * when it may still be a product-relevant business.
     *
     * This is the FULL-PAGE (verification stage) screen: it runs while verifying
     * an already product-relevant candidate and therefore still sees the fetched
     * homepage text and its store evidence. The raw-search stage uses the
     * cheaper, earlier rawResultPreFilterReason instead.
     *
     * @param storeEvidence TRUE when the page/site already shows it sells
     *                      physical products - it then bypasses the surface
     *                      language checks (host and path checks still apply).
     */
    private String irrelevantContentReason(String url, String titleText, String snippetText,
                                           String homeText, boolean storeEvidence) {
        String host = hostOf(url);
        String hostL = host == null ? "" : host;
        String path = pathOf(url);

        String hostReject = irrelevantHostReason(hostL);
        if (hostReject != null) {
            return hostReject;
        }
        if (hasIrrelevantPathSegment(path)) {
            return REJECT_IRRELEVANT_CONTENT + ": URL path '" + cleanForLog(path)
                    + "' is a music / news / event / registration / booking section";
        }
        String text = titleText + " " + snippetText + " " + (homeText == null ? "" : homeText);
        // A legitimate product / collection / catalogue URL is KEPT here too, so
        // the two screens share exactly one escape hatch.
        if (hasLegitimateProductUrl(path)) {
            return null;
        }
        // Music / artist content is the clearest non-product case, so it is the
        // only language that still applies when the page shows store evidence -
        // and then only on TWO independent music markers, so a shop whose copy
        // merely mentions one song/album is still kept.
        String music = firstMatchedPhrase(MUSIC_CONTENT_PHRASES, text);
        if (music != null && (!storeEvidence
                || countMatchedPhrases(MUSIC_CONTENT_PHRASES, text) >= 2)) {
            return REJECT_IRRELEVANT_CONTENT + ": music/song/artist content ('" + music + "')";
        }
        // Travel / booking copy is screened even on a page that shows store
        // evidence, because a hotel or tour operator with a small gift shop is
        // still a hospitality business. Two independent markers are required
        // when store evidence is present, so a shop that merely says "book now"
        // for a design consultation is kept.
        String travel = firstMatchedPhrase(TRAVEL_BOOKING_PHRASES, text);
        if (travel != null && (!storeEvidence
                || countMatchedPhrases(TRAVEL_BOOKING_PHRASES, text) >= 2)) {
            return REJECT_IRRELEVANT_CONTENT + ": travel/booking page ('" + travel + "')";
        }
        // Hospital / healthcare copy is screened the same way: a hospital spa or
        // a wellness clinic in a hospital is not a buyer of a decor product.
        String hospital = firstMatchedPhrase(HOSPITAL_CONTENT_PHRASES, text);
        if (hospital != null && (!storeEvidence
                || countMatchedPhrases(HOSPITAL_CONTENT_PHRASES, text) >= 2)) {
            return REJECT_IRRELEVANT_CONTENT + ": hospital/healthcare page ('" + hospital + "')";
        }
        // A page that demonstrably sells physical products is never dropped by
        // the remaining surface-language checks below.
        if (storeEvidence) {
            return null;
        }
        String news = firstMatchedPhrase(NEWS_ARTICLE_PHRASES, text);
        if (news != null) {
            return REJECT_IRRELEVANT_CONTENT + ": news/article content ('" + news + "')";
        }
        String tourism = firstMatchedPhrase(TOURISM_CONTENT_PHRASES, text);
        if (tourism != null) {
            return REJECT_IRRELEVANT_CONTENT + ": tourism/visitor-board content ('" + tourism + "')";
        }
        String event = firstMatchedPhrase(EVENT_REGISTRATION_PHRASES, text);
        if (event != null) {
            return REJECT_IRRELEVANT_CONTENT + ": event/exhibitor/registration page ('" + event + "')";
        }
        String attendeeList = firstMatchedPhrase(ATTENDEE_EXHIBITOR_LIST_PHRASES, text);
        if (attendeeList != null) {
            return REJECT_IRRELEVANT_CONTENT + ": attendee/exhibitor list page ('" + attendeeList + "')";
        }
        String medical = firstMatchedPhrase(MEDICAL_CONTENT_PHRASES, text);
        if (medical != null) {
            return REJECT_IRRELEVANT_CONTENT + ": medical page ('" + medical + "')";
        }
        String academic = firstMatchedPhrase(ACADEMIC_RESEARCH_PHRASES, text);
        if (academic != null) {
            return REJECT_IRRELEVANT_CONTENT + ": academic/research page ('" + academic + "')";
        }
        String directory = firstMatchedPhrase(UNRELATED_DIRECTORY_PHRASES, text);
        if (directory != null) {
            return REJECT_IRRELEVANT_CONTENT + ": unrelated business directory ('" + directory + "')";
        }
        String industry = firstMatchedPhrase(UNRELATED_INDUSTRY_PHRASES, text);
        if (industry != null) {
            return REJECT_IRRELEVANT_CONTENT + ": unrelated industry ('" + industry + "')";
        }
        return null;
    }

    /** TRUE when the URL path contains one of the irrelevant-content sections
     *  as a WHOLE segment, so "/collections/singing-bowls" and "/music-boxes"
     *  are never mistaken for a "/music" section. */
    private boolean hasIrrelevantPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT).replaceAll("/{2,}", "/");
        for (String segment : IRRELEVANT_CONTENT_PATH_SEGMENTS) {
            String needle = "/" + segment;
            int from = 0;
            while (true) {
                int idx = p.indexOf(needle, from);
                if (idx < 0) {
                    break;
                }
                int after = idx + needle.length();
                if (after >= p.length() || p.charAt(after) == '/') {
                    return true;
                }
                from = idx + 1;
            }
        }
        return false;
    }

    /** The first phrase of the list found in the text, or null. */
    private String firstMatchedPhrase(String[] phrases, String text) {
        for (String phrase : phrases) {
            if (containsPhrase(text, phrase)) {
                return phrase;
            }
        }
        return null;
    }

    /** How many DISTINCT phrases of the list are found in the text. */
    private int countMatchedPhrases(String[] phrases, String text) {
        int count = 0;
        for (String phrase : phrases) {
            if (containsPhrase(text, phrase)) {
                count++;
            }
        }
        return count;
    }

    /** TRUE when the page shows it sells physical products: a shopping URL
     *  path, a product-named URL, or store-selling copy. Used as the safety
     *  valve of the light irrelevant-content filter. */
    private boolean hasProductSellingEvidence(String titleText, String snippetText,
                                             String homeText, String path, boolean urlProductEvidence) {
        return urlProductEvidence
                || isShoppingPath(path)
                || hasStoreSellingEvidence(titleText, snippetText, homeText, path);
    }

    /** First title segment (before any separator) - the place a company name
     *  is expected on marketplace storefronts and branded catalogs. */
    private String leadingBrandSegment(String title, String product) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String cleaned = cleanTitle(title);
        List<String> segments = splitSegments(cleaned);
        if (segments.isEmpty()) {
            return null;
        }
        return segments.get(0);
    }

    private boolean isFileUrl(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String p = path.toLowerCase();
        int lastSlash = p.lastIndexOf('/');
        String tail = lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
        if (tail.isEmpty() || !tail.contains(".")) {
            return false;
        }
        String ext = tail.substring(tail.lastIndexOf('.') + 1).toLowerCase();
        return FILE_EXTENSIONS.contains(ext);
    }

    private boolean isAcademicLike(String host, String path, String titleLower) {
        if (host.endsWith(".edu") || host.contains(".edu.")
                || host.endsWith(".ac.uk") || host.endsWith(".ac.in")
                || host.endsWith(".ac.jp") || host.endsWith(".ac.id")) {
            return true;
        }
        if (path != null) {
            for (String marker : ACADEMIC_PATH_MARKERS) {
                if (path.contains(marker)) {
                    return true;
                }
            }
        }
        if (titleLower != null) {
            for (String marker : ACADEMIC_TITLE_MARKERS) {
                if (titleLower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isArticleLike(String host, String path, String titleLower) {
        if (host.contains("wordpress.com") || host.contains("blog.")
                || host.contains("medium.com") || host.contains("substack.com")
                || host.contains("tumblr.com")) {
            return true;
        }
        // Podcast episodes are content, never buyer businesses. This covers
        // podcast.substack / apple podcasts / spotify / podbean / buzzsprout
        // and any host, path or TITLE carrying the word "podcast" - a podcast
        // page is content even when the host domain does not say "podcast"
        // (e.g. disruptors.com "Disruptors Podcast").
        if (host.contains("podcast") || host.contains("podbean")
                || host.contains("buzzsprout") || host.contains("anchor.fm")
                || path.contains("/podcast") || path.contains("/episode")
                || path.contains("/episodes")
                || (titleLower != null && titleLower.contains("podcast"))) {
            return true;
        }
        // Music / audio-streaming platforms are content distributors, never
        // buyer businesses for a product.
        for (String mediaHost : MEDIA_CONTENT_HOSTS) {
            if (host.equals(mediaHost) || host.endsWith("." + mediaHost)) {
                return true;
            }
        }
        for (String part : CONTENT_NEWS_HOST_PARTS) {
            if (host.contains(part)) {
                return true;
            }
        }
        if (path.contains("/blog") || path.contains("/news")
                || path.contains("/article") || path.contains("/press")
                || path.contains("/insights") || path.contains("/resources")
                || path.matches(".*/20\\d{2}/\\d{2}/.*")) {
            return true;
        }
        if (titleLower != null) {
            for (String marker : ARTICLE_TITLE_MARKERS) {
                if (titleLower.contains(marker)) {
                    return true;
                }
            }
            // Event / ticket / workshop pages (sound baths, classes, retreats,
            // concerts) describe a happening, not a business that sells the
            // product - and title links like "Buy Tickets" are explicit event
            // commerce, not product commerce.
            if (titleLower.contains("tickets") || titleLower.contains("ticket")
                    || titleLower.contains("eventbrite") || titleLower.contains("event")
                    || titleLower.contains("workshop") || titleLower.contains("workshops")
                    || titleLower.contains("retreat") || titleLower.contains("retreats")
                    || titleLower.contains("sound bath") || titleLower.contains("sound baths")
                    || titleLower.contains("sound healing session")
                    || titleLower.contains("meditation session")) {
                return true;
            }
            if (titleLower.endsWith("- blog") || titleLower.endsWith("| blog")
                    || titleLower.endsWith("- news") || titleLower.endsWith(" news")
                    || titleLower.endsWith("| news")
                    || titleLower.contains(" - wikipedia") || titleLower.contains(" | wikipedia")) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectoryLike(String host, String path, String titleLower) {
        if (host.contains("directory") || host.contains("yellowpages")
                || host.contains("classified") || host.contains("listings")
                || host.contains("cybo") || host.contains("thomsonlocal")
                || host.contains("forum") || host.contains("discussion")
                || host.contains("bulletinboard")) {
            return true;
        }
        if (path.contains("/directory") || path.contains("/classified")
                || path.contains("/forum") || path.contains("/forums")
                || path.contains("/discussion") || path.contains("/thread/")
                || path.contains("/threads")) {
            return true;
        }
        if (titleLower != null) {
            for (String marker : DIRECTORY_TITLE_MARKERS) {
                if (titleLower.contains(marker)) {
                    return true;
                }
            }
            if (titleLower.contains("forum") || titleLower.contains("discussion")) {
                return true;
            }
        }
        return false;
    }

    private boolean isShoppingHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        return SHOPPING_HOSTS.contains(host)
                || host.contains("ubuy") || host.contains("desertcart");
    }

    private boolean isPlatformHost(String host) {
        if (host == null) {
            return false;
        }
        for (String suffix : PLATFORM_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAny(String text, Set<String> markers) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyKey(String query, Set<String> keys) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        String q = query.toLowerCase();
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String key = eq >= 0 ? part.substring(0, eq) : part;
            if (keys.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the meaningful business/company name from the result: the best
     * title segment, falling back to a name derived from the site's own domain
     * so a "-" or bare product-title result is never used as a buyer name.
     */
    private String extractBusinessName(BuyerSearchResult result, String product) {
        String title = result.getRawTitle() != null ? result.getRawTitle() : result.getCompanyName();
        String name = bestBrandSegment(title, product);
        if (name != null && isMeaningfulName(name, product)) {
            return name;
        }
        String hostName = businessNameFromHost(result.getWebsite());
        if (hostName != null && isMeaningfulName(hostName, product)) {
            return hostName;
        }
        return null;
    }

    /**
     * Picks the title segment that most looks like a company name: most words
     * that are neither stopwords nor product terms, preferring non-aggregator
     * segments ("Blue Gem Trading" over "Ubuy India").
     */
    private String bestBrandSegment(String title, String product) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String cleaned = cleanTitle(title);
        String best = null;
        int bestMeaningful = 0;
        boolean bestAggregator = false;
        for (String seg : splitSegments(cleaned)) {
            int meaningful = meaningfulNameWords(seg, product).size();
            if (meaningful == 0) {
                continue;
            }
            boolean aggregator = mentionsAggregator(seg);
            if (best == null || meaningful > bestMeaningful
                    || (meaningful == bestMeaningful && !aggregator && bestAggregator)) {
                best = seg;
                bestMeaningful = meaningful;
                bestAggregator = aggregator;
            }
        }
        return best;
    }

    private boolean isMeaningfulName(String name, String product) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty() || "-".equals(n) || "\u2014".equals(n) || "\u2013".equals(n)) {
            return false;
        }
        if (n.length() < 2) {
            return false;
        }
        String productTrim = product == null ? "" : product.trim();
        if (!productTrim.isEmpty() && n.equalsIgnoreCase(productTrim)) {
            return false;
        }
        // Entirely composed of stopwords / product terms (e.g. a bare "Singing
        // Bowls" title) is a generic product page, not a business identity.
        return !meaningfulNameWords(n, product).isEmpty();
    }

    /** Words that are neither stopwords nor product terms, i.e. brand words. */
    private Set<String> meaningfulNameWords(String text, String product) {
        Set<String> productTerms = new HashSet<>(significantTerms(product));
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() < 2 || STOPWORDS.contains(word) || productTerms.contains(word)) {
                continue;
            }
            result.add(word);
        }
        return result;
    }

    /** True when the text contains a word that is genuinely a brand word (not a
     *  marketplace identity, commerce stopword or aggregator name). */
    private boolean hasGenuineBrandWord(String name) {
        if (name == null) {
            return false;
        }
        for (String word : name.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() < 2) {
                continue;
            }
            if (COMMERCE_NOISE_WORDS.contains(word) || AGGREGATOR_BRANDS.contains(word)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean mentionsAggregator(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        for (String brand : AGGREGATOR_BRANDS) {
            if (containsToken(n, brand)) {
                return true;
            }
        }
        return false;
    }

    private String businessNameFromHost(String website) {
        String host = hostOf(website);
        if (host == null || host.isEmpty()) {
            return null;
        }
        String h = host;
        for (String suffix : PLATFORM_HOST_SUFFIXES) {
            if (h.endsWith(suffix)) {
                h = h.substring(0, h.length() - suffix.length());
            }
        }
        if (h.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : h.split("[.]")) {
            if (part.length() < 2 || LOW_SIGNAL_DOMAIN_PARTS.contains(part)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.length() == 0 ? null : out.toString();
    }

    private String normalizeBusinessKey(String name) {
        if (name == null) {
            return null;
        }
        String k = name.toLowerCase().replaceAll("\\s+", " ").trim();
        k = k.replaceAll("^the\\s+", "");
        k = k.replaceAll("\\b(?:llc|inc|incorporated|corp|corporation|ltd|limited|co|company|pvt|private limited|gmbh|pty|plc)\\b\\.?", " ");
        k = k.replaceAll("\\s+", " ").trim();
        return k.isEmpty() ? null : k;
    }

    private String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = title
                .replaceAll("(?i)\\b(add to cart|buy now|shop now|free shipping|order now|best price|low price|compare prices|online shopping|in stock|limited time offer|checkout)\\b", " ")
                .replaceAll("[$\\u20AC\\u00A3\\u20B9]\\s?\\d+(?:[.,]\\d+)*", " ")
                .replaceAll("\\b(?:u\\$d|usd|inr|eur|gbp|rs\\.?)\\s?\\d+(?:[.,]\\d+)*", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.isEmpty() ? title.trim() : t;
    }

    private List<String> splitSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        for (String part : text.split("\\s*\\R\\s*|\\s*[\\||:;]\\s*|\\s+(?:-|\u2014|\u2013)\\s+|\\s*[()]\\s*")) {
            String seg = part.trim();
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        return segments;
    }

    private String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    private String pathOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            return (path == null ? "" : path).toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private String queryOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String query = uri.getQuery();
            return query == null ? "" : query.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private String collapseWhitespace(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------------
    // LinkedIn & Facebook targeting (site:linkedin.com / site:facebook.com)
    // ------------------------------------------------------------------

    /**
     * Fires the dedicated LinkedIn/Facebook search pass for the user's product
     * + location, drawing from the SAME shared per-search Firecrawl budget as
     * the exact/web passes (so web + social together stay within the 6-request
     * cap). Only company / showcase pages (LinkedIn) and business-style page
     * slugs (Facebook) are accepted; individual person profiles and search/home
     * pages are skipped. Verification uses the search-result evidence only
     * (LinkedIn/Facebook block plain HTTP scraping) - product relevance and
     * location are checked exactly like the cheap-screen checks of the web
     * pipeline.
     */
    private List<BuyerSearchResult> runSocialSearch(String queryProduct, String userProductLabel,
                                                     String stateValue, String selectedCity,
                                                     String countryNameFull, String countryWord,
                                                     String apiKey, SearchBudget budget,
                                                     int maxResults,
                                                     Set<String> existingSocialWebsites,
                                                     Set<String> socialSeen) {
        List<BuyerSearchResult> social = new ArrayList<>();
        if (queryProduct == null || queryProduct.isBlank() || maxResults <= 0) {
            return social;
        }
        if (budget.exhausted()) {
            return social;
        }

        // The SAME location the user selected: "City" State / State / country word.
        String locPart;
        if (selectedCity != null && !selectedCity.isBlank()) {
            locPart = "\"" + selectedCity.trim() + "\""
                    + (stateValue != null && !stateValue.isBlank() ? " " + stateValue.trim() : "");
        } else if (stateValue != null && !stateValue.isBlank()) {
            locPart = stateValue.trim();
        } else {
            locPart = countryWord != null ? countryWord : countryNameFull;
        }
        Combo combo = new Combo(queryProduct, queryProduct, stateValue, selectedCity, countryNameFull);
        String firecrawlLocation = buildFirecrawlLocation(combo);

        for (String siteOp : new String[]{"site:linkedin.com", "site:facebook.com"}) {
            if (budget.exhausted() || social.size() >= maxResults) {
                break;
            }
            String platform = "site:linkedin.com".equals(siteOp) ? "LinkedIn" : "Facebook";
            String query = "\"" + queryProduct.trim() + "\" " + locPart + " " + siteOp;

            List<BuyerSearchResult> results;
            try {
                results = executeSearch(query, "Buyer", apiKey, RESULTS_PER_QUERY,
                        firecrawlLocation, budget, true);
            } catch (BudgetExhaustedException e) {
                logger.warn("SOCIAL SEARCH stopped - {}", e.getMessage());
                break;
            } catch (Exception e) {
                logger.error("SOCIAL SEARCH failed for '{}': {}", query, e.getMessage());
                continue;
            }

            int accepted = 0;
            int skipped = 0;
            for (BuyerSearchResult result : results) {
                if (social.size() >= maxResults) {
                    break;
                }
                String url = result.getWebsite();

                // Only company/showcase (LinkedIn) and business-page style
                // (Facebook) URLs. Individual people profiles (/in/, /profile/,
                // numeric page ids) never become buyers.
                if (!isSocialBusinessPage(url)) {
                    skipped++;
                    logger.info("{} | '{}' ({}) - not a LinkedIn company / Facebook business page",
                            CODE_INVALID_CONTENT, result.getRawTitle(), url);
                    continue;
                }

                // Dedup against already-saved social pages and this search.
                String socialKey = normalizeSocialUrl(url);
                if (socialKey == null) {
                    skipped++;
                    continue;
                }
                if (existingSocialWebsites.contains(socialKey)) {
                    skipped++;
                    logger.info("{} '{}' ({}) - social page already in the buyer database",
                            CODE_ALREADY_IN_DATABASE, result.getRawTitle(), url);
                    continue;
                }
                if (!socialSeen.add(socialKey)) {
                    skipped++;
                    logger.info("{} '{}' ({}) - social page already returned this search",
                            CODE_DUPLICATE, result.getRawTitle(), url);
                    continue;
                }

                // Business identity from the profile slug (most reliable), with
                // the cleaned page title as fallback.
                String name = extractSocialBusinessName(url, result.getRawTitle());
                if (name == null) {
                    skipped++;
                    logger.info("{} '{}' ({}) - no business identity in social page",
                            CODE_NO_COMPANY_IDENTITY, result.getRawTitle(), url);
                    continue;
                }

                // Product relevance is verified against the search evidence
                // (title + snippet) with the same gate as the web pipeline.
                String evidence = buildEvidence(result);
                if (isProductRelevant(result.getRawTitle(), result.getSnippet(), url, null,
                            queryProduct, result.getSearchQuery() != null
                                    ? result.getSearchQuery() : queryProduct) == null) {
                    skipped++;
                    logger.info("{} '{}' ({}) - evidence does not mention '{}'",
                            CODE_WRONG_PRODUCT, result.getRawTitle(), url, queryProduct);
                    continue;
                }

                // STATE / COUNTRY RESPECTED (social evidence is title + snippet
                // only, so this is the WEAK form of the same rule as the web
                // pipeline): a social page that positively names a country or a
                // state other than the searched one is a real conflict and is
                // skipped, while a page that simply names no location is NOT a
                // conflict and is still kept and ranked last.
                String socialConflict = socialLocationConflict(evidence, countryNameFull,
                        stateValue);
                if (socialConflict != null) {
                    skipped++;
                    logger.info("{} '{}' ({}) - {} (searched: state='{}', country='{}')",
                            CODE_LOCATION_CONFLICT, name, url, socialConflict,
                            stateValue != null ? stateValue : "-",
                            countryNameFull != null ? countryNameFull : "-");
                    continue;
                }

                String socialClass = socialLocationClass(evidence, countryNameFull,
                        stateValue, selectedCity);
                if (socialClass == null) {
                    socialClass = LOCATION_CLASS_UNKNOWN;
                }

                result.setWebsite(url);
                result.setCompanyName(name);
                result.setBuyerName(name);
                result.setProductName(userProductLabel);
                result.setSearchProduct(queryProduct);
                result.setBusinessType("Buyer");
                result.setClassification(PRODUCT_RELEVANT_CLASSIFICATION);
                result.setSourceUrl(url);
                result.setLocationClass(socialClass);
                result.setRelevanceScore(relevanceScoreOf(
                        "LISTING (social page evidence)", null, "Buyer"));
                applySocialLocation(result, evidence, countryNameFull, stateValue, selectedCity);
                logger.info("ACCEPTED [{}] '{}' ({}) - social page relevant to '{}' in {}/{} [{}={}]",
                        platform, name, url, queryProduct,
                        result.getState() != null ? result.getState() : "any",
                        result.getCity() != null ? result.getCity() : "any",
                        result.getLocationClass() != null ? result.getLocationClass() : "UNKNOWN",
                        result.getLocation() != null ? result.getLocation() : "unknown");
                social.add(result);
                accepted++;
            }
            logger.info("SOCIAL SEARCH | platform={} | query='{}' | raw={} | accepted={} | skipped={}",
                    platform, query, results.size(), accepted, skipped);
        }
        return social;
    }

    private void applySocialLocation(BuyerSearchResult result, String evidence,
                                     String countryNameFull, String stateValue, String selectedCity) {
        String evidenceText = prepareText(evidence);
        String countryValue = countryNameFull != null ? countryNameFull : "USA";
        result.setCountry(countryValue);
        boolean cityPresent = selectedCity != null && !selectedCity.isBlank()
                && containsToken(evidenceText, selectedCity.toLowerCase(Locale.ROOT));
        boolean statePresent = stateValue != null && !stateValue.isBlank()
                && containsToken(evidenceText, stateValue.toLowerCase(Locale.ROOT));
        if (selectedCity != null && !selectedCity.isBlank() && cityPresent) {
            result.setState(collapseWhitespace(stateValue));
            result.setCity(selectedCity.trim());
            result.setLocation(buildLocationDisplay(stateValue, selectedCity.trim(), countryValue));
            result.setLocationSource("social page (searched city)");
        } else if (selectedCity == null && stateValue != null && !stateValue.isBlank()
                && statePresent) {
            // A state-level fallback is allowed ONLY when no specific city was
            // requested - a searched city is never silently broadened.
            result.setState(collapseWhitespace(stateValue));
            result.setCity(null);
            result.setLocation(buildLocationDisplay(stateValue, null, countryValue));
            result.setLocationSource("social page (searched state)");
        } else {
            result.setState(null);
            result.setCity(null);
            result.setLocation(countryValue);
            result.setLocationSource("social page (country)");
        }
    }

    /** Social-page location conflict: a title/snippet that positively names a
     *  country OTHER than the searched one (e.g. a UK or Canadian page in a
     *  Texas search). Only POSITIVE country evidence counts - a social page that
     *  names no country at all is never a conflict, so the result is kept and
     *  simply ranked last as UNKNOWN. A state name is deliberately NOT treated
     *  as a conflict here: a Texas business legitimately advertises customers in
     *  other states, and the web pipeline (which resolves a real address) is
     *  where a state conflict is decided. */
    private String socialLocationConflict(String evidence, String countryNameFull,
                                          String stateValue) {
        String text = prepareText(evidence);
        if (countryNameFull != null && !countryNameFull.isBlank()
                && containsToken(text, countryNameFull.toLowerCase(Locale.ROOT))) {
            return null;
        }
        for (String country : OTHER_COUNTRY_NAMES) {
            if (containsToken(text, country)) {
                return "social evidence names '" + country
                        + "' instead of the searched country '" + countryNameFull + "'";
            }
        }
        return null;
    }

    /** Location-priority class for a social page (title/snippet only): EXACT_CITY
     *  when the requested city appears, SAME_STATE when the requested state does.
     *  A missing constrained location is represented as UNKNOWN by the caller
     *  and does not reject the social result. */
    private String socialLocationClass(String evidence, String countryNameFull,
                                       String stateValue, String selectedCity) {
        String evidenceText = prepareText(evidence);
        boolean constrained = (selectedCity != null && !selectedCity.isBlank())
                || (stateValue != null && !stateValue.isBlank());
        boolean cityPresent = selectedCity != null && !selectedCity.isBlank()
                && containsToken(evidenceText, selectedCity.toLowerCase(Locale.ROOT));
        boolean statePresent = stateValue != null && !stateValue.isBlank()
                && containsToken(evidenceText, stateValue.toLowerCase(Locale.ROOT));
        if (cityPresent) {
            return LOCATION_CLASS_EXACT_CITY;
        }
        if (statePresent) {
            return LOCATION_CLASS_SAME_STATE;
        }
        return constrained ? null : LOCATION_CLASS_SAME_COUNTRY;
    }

    /** True for LinkedIn company/showcase pages and Facebook business-page URLs. */
    private boolean isSocialBusinessPage(String url) {
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        if (host.equals("linkedin.com") || host.endsWith(".linkedin.com")) {
            return socialSlugOfLinkedIn(url) != null;
        }
        if (host.equals("facebook.com") || host.endsWith(".facebook.com")) {
            return socialSlugOfFacebook(url) != null;
        }
        return false;
    }

    private String socialSlugOfLinkedIn(String url) {
        String path = pathOf(url);
        String[] segs = path.split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if ("company".equals(segs[i]) || "showcase".equals(segs[i])) {
                String slug = segs[i + 1];
                if (slug.isEmpty() || "pages".equals(slug)) {
                    return null;
                }
                String trimmed = slug.replaceAll("[.?].*$", "").trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
        }
        return null;
    }

    private String socialSlugOfFacebook(String url) {
        String path = pathOf(url);
        String lastAlpha = null;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            String s = seg.toLowerCase(Locale.ROOT);
            if ("pages".equals(s) || "category".equals(s) || "profile.php".equals(s)
                    || "groups".equals(s) || "events".equals(s) || "watch".equals(s)
                    || "login".equals(s) || "messages".equals(s) || "share".equals(s)) {
                continue;
            }
            // A numeric page id cannot provide a business name.
            if (s.matches("\\d+")) {
                return null;
            }
            if (s.chars().anyMatch(Character::isLetter)) {
                lastAlpha = seg;
            }
        }
        return lastAlpha;
    }

    /** Friendly company name from the social handle (e.g. "blue-gem-trading" ->
     *  "Blue Gem Trading"). Returns null when the handle carries no brand. */
    private String slugToCompanyName(String slug) {
        if (slug == null) {
            return null;
        }
        String cleaned = slug.replaceAll("[_-]+", " ")
                .replaceAll("[^a-zA-Z0-9 ]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String tok : cleaned.split(" ")) {
            if (tok.length() < 2) {
                continue;
            }
            String lower = tok.toLowerCase(Locale.ROOT);
            if (COMMERCE_NOISE_WORDS.contains(lower)) {
                continue;
            }
            sb.append(Character.toUpperCase(tok.charAt(0))).append(tok.substring(1)).append(' ');
            kept++;
        }
        return kept == 0 ? null : sb.toString().trim();
    }

    /** Business identity for a social page: the profile slug first, then the
     *  cleaned page title (platform suffix stripped) as fallback. */
    private String extractSocialBusinessName(String url, String title) {
        String slug = null;
        String host = hostOf(url);
        if (host != null && (host.equals("linkedin.com") || host.endsWith(".linkedin.com"))) {
            slug = socialSlugOfLinkedIn(url);
        } else if (host != null && (host.equals("facebook.com") || host.endsWith(".facebook.com"))) {
            slug = socialSlugOfFacebook(url);
        }
        if (slug != null) {
            String fromSlug = slugToCompanyName(slug);
            if (fromSlug != null) {
                return fromSlug;
            }
        }
        if (title != null && !title.isBlank()) {
            String cleaned = title
                    .replaceAll("(?i)\\s+\\|\\s*(?:linkedin|facebook).*$", "")
                    .replaceAll("(?i)\\s+-\\s*(?:home|official|facebook|linkedin).*$", "")
                    .replaceAll("(?i)\\s+\\|\\s+.*$", "")
                    .trim();
            if (cleaned.length() >= 2 && cleaned.length() <= 120) {
                return cleaned;
            }
        }
        return null;
    }

    /** Registry key for a social profile URL (host + lowercase path, no
     *  trailing slash) used to dedupe already-saved social pages. */
    private String normalizeSocialUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String key = (uri.getHost() == null ? "" : uri.getHost().toLowerCase())
                    + (uri.getPath() == null ? "" : uri.getPath().toLowerCase());
            while (key.endsWith("/")) {
                key = key.substring(0, key.length() - 1);
            }
            return key.isEmpty() ? null : key;
        } catch (Exception e) {
            String key = url.trim().toLowerCase(Locale.ROOT);
            while (key.endsWith("/")) {
                key = key.substring(0, key.length() - 1);
            }
            return key.isEmpty() ? null : key;
        }
    }

    private Set<String> loadExistingSocialWebsites() {
        Set<String> normalized = new HashSet<>();
        try {
            Set<String> stored = buyerRepository.findSocialWebsiteValues();
            if (stored != null) {
                for (String website : stored) {
                    String n = normalizeSocialUrl(website);
                    if (n != null && !n.isEmpty()) {
                        normalized.add(n);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load existing social websites from DB: {}", e.getMessage());
        }
        return normalized;
    }

    // ------------------------------------------------------------------
    // Firecrawl execution
    // ------------------------------------------------------------------

    private Set<String> loadNormalizedDatabaseWebsites() {
        Set<String> normalized = new HashSet<>();
        try {
            Set<String> stored = buyerRepository.findAllWebsiteValues();
            if (stored != null) {
                for (String website : stored) {
                    String n = normalizeWebsite(website);
                    if (n != null && !n.isEmpty()) {
                        normalized.add(n);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load existing websites from DB: {}", e.getMessage());
        }
        return normalized;
    }

    private List<BuyerSearchResult> executeSearch(String query, String businessType,
                                                   String apiKey, int limit,
                                                   String location, SearchBudget budget)
            throws JsonProcessingException {
        return executeSearch(query, businessType, apiKey, limit, location, budget, false);
    }

    private List<BuyerSearchResult> executeSearch(String query, String businessType,
                                                   String apiKey, int limit,
                                                   String location, SearchBudget budget,
                                                   boolean allowSocial)
            throws JsonProcessingException {
        List<BuyerSearchResult> results = new ArrayList<>();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", query);
        requestBody.put("limit", limit);
        requestBody.put("country", "US");
        if (location != null && !location.isBlank()) {
            requestBody.put("location", location);
        }
        // Never surface social profiles, business directories, review sites or
        // marketplaces - only real business domains can be relevant buyers.
        // EXCEPTION (allowSocial): the LinkedIn/Facebook targeting pass keeps
        // linkedin.com and facebook.com available so company/showcase/business
        // pages can be found, while every other non-business host stays blocked.
        List<String> excludeDomains = new ArrayList<>(NON_BUSINESS_HOSTS);
        if (allowSocial) {
            excludeDomains.remove("linkedin.com");
            excludeDomains.remove("facebook.com");
        }
        requestBody.put("excludeDomains", excludeDomains);
        requestBody.put("ignoreInvalidURLs", true);

        String responseJson = null;
        HttpStatusCode lastStatus = null;
        int attempt = 0;
        if (!budget.tryAcquire()) {
            throw new BudgetExhaustedException(
                    "Firecrawl request budget exhausted before query '" + query + "'");
        }
        while (responseJson == null) {
            attempt++;
            // Log every request that actually hits the Firecrawl API (retries
            // included) so credit usage is always accountable.
            logger.info("Firecrawl request {}/{} (attempt {}/{}) for '{}'",
                    budget.used(), budget.max(), attempt, MAX_429_RETRIES + 1, query);

            // Respect the account-wide requests-per-minute budget before every
            // Firecrawl call, retries included. Runs before each attempt so a
            // backed-off retry also waits its turn behind other searches.
            throttleFirecrawlRequest();

            // exchangeToMono gives direct access to the response status/headers
            // (the config's default status handler is not applied here), so a
            // 429 can be detected and retried with a short backoff. The status
            // AND body must be captured inside a single reactive chain: reading
            // response.bodyToMono(...) after the exchange has already been
            // block()-ed returns an EMPTY body, because the completed exchange
            // discards the unread response body.
            Object[] statusAndBody = webClient.post()
                    .uri("/v2/search")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .exchangeToMono(resp -> {
                        // Read the optional Retry-After BEFORE consuming the body:
                        // headers are always available on the ClientResponse, and
                        // this is the only per-attempt chance to capture them.
                        String retryAfter = resp.headers().header("Retry-After").stream()
                                .findFirst().orElse(null);
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new Object[]{resp.statusCode(), retryAfter, body});
                    })
                    .onErrorResume(e -> {
                        logger.error("Firecrawl request failed for query '{}': {}",
                                query, e.getMessage());
                        return Mono.just(new Object[]{HttpStatus.BAD_GATEWAY, null, ""});
                    })
                    .block();

            if (statusAndBody == null) {
                logger.warn("Firecrawl returned no response for query '{}'", query);
                return results;
            }

            HttpStatusCode responseStatus = (HttpStatusCode) statusAndBody[0];
            Object retryAfterHeader = statusAndBody[1];
            responseJson = (String) statusAndBody[2];
            lastStatus = responseStatus;

            if (responseStatus.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                if (attempt - 1 >= MAX_429_RETRIES) {
                    // After two backoff retries the query is skipped entirely -
                    // no long, repeated waiting that burns the request budget.
                    logger.error("Firecrawl rate-limited (429) for '{}' - skipping after {} attempts",
                            query, attempt);
                    return results;
                }
                if (!budget.tryAcquireRetry()) {
                    throw new BudgetExhaustedException(
                            "Firecrawl request budget exhausted before retry for query '" + query + "'");
                }
                // Wait for the server-provided Retry-After when the 429 carried one
                // (never retry immediately); fall back to the short fixed backoff
                // when the server sent no usable header.
                long serverWaitMs = serverRetryWaitMillis(retryAfterHeader);
                long backoffMs = serverWaitMs >= 0 ? serverWaitMs : retryBackoffMillis(attempt);
                logger.warn("Firecrawl rate-limited (429) for '{}' - retrying after {} ms (attempt {}/{}){}",
                        query, backoffMs, attempt, MAX_429_RETRIES,
                        serverWaitMs >= 0 ? " (server-provided Retry-After)" : "");
                sleepQuietly(backoffMs);
                continue;
            }

            if (responseStatus.isError()) {
                logger.error("Firecrawl HTTP {} for query '{}': {}",
                        responseStatus, query, safePreview(responseJson, 300));
                return results;
            }

            if (responseJson == null || responseJson.isBlank()) {
                // A 2xx response with an empty body means this query/engine call
                // produced nothing. Rewinding and retrying the SAME query is a
                // guaranteed credit burn, so return now and let the round use the
                // next role-word (distributor, wholesaler, ...) for the budget.
                logger.warn("Firecrawl HTTP {} for query '{}': API genuinely returned "
                                + "an empty body - 0 raw results, not a parsing failure",
                        responseStatus.value(), query);
                return results;
            }
        }

        // Parse outside the retry loop: the body was not consumed, so no retry
        // would change the content. A failure here is REAL and different from
        // the API returning zero results - it must never be logged as "0 valid".
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (JsonProcessingException e) {
            logger.error("Firecrawl response PARSE FAILED for query '{}' (HTTP {}) - "
                            + "NOT counted as zero results. parseError='{}', preview={}",
                    query, lastStatus, e.getOriginalMessage(),
                    safePreview(responseJson, 300));
            return results;
        }

        if (root.hasNonNull("error")) {
            String errorMessage = root.get("error").asText("Unknown Firecrawl error");
            logger.warn("Firecrawl HTTP {} reported an error for query '{}': {}",
                    lastStatus, query, safePreview(errorMessage, 300));
            throw new RuntimeException("Firecrawl returned error: " + errorMessage);
        }

        if (!root.path("success").asBoolean(true)) {
            logger.warn("Firecrawl reported failure for query '{}', no results returned", query);
            return results;
        }

        JsonNode data = root.path("data");
        JsonNode resultsArray = data;
        // Some Firecrawl revisions return per-source arrays under data.web.
        if (!data.isArray() && data.isObject() && data.path("web").isArray()) {
            resultsArray = data.path("web");
        }
        if (!resultsArray.isArray()) {
            logger.warn("No results array for query '{}'. Keys: {} (HTTP {})",
                    query, root.fieldNames(), lastStatus);
            return results;
        }

        int noLink = 0;
        int noTitle = 0;
        for (JsonNode item : resultsArray) {
            String title = extractText(item, "title");
            String link = extractText(item, "url");
            if (link.isEmpty()) {
                link = extractText(item, "link");
            }
            if (link.isEmpty()) {
                link = extractText(item, "pageUrl");
            }
            String snippet = extractText(item, "description");
            if (snippet.isEmpty()) {
                snippet = extractText(item, "snippet");
            }
            // Google Business / local-result data and generic meta fields often
            // carry the real business address - the most reliable structured
            // location source of all. It is kept on the result so per-buyer
            // location extraction can use it before touching any website.
            String searchAddress = extractGoogleBusinessAddress(item);
            if (searchAddress.isEmpty()) {
                // Generic meta fields (Firecrawl may name it "meta" or
                // "metadata") sometimes carry an address too.
                JsonNode meta = item.path("meta").isObject() ? item.path("meta")
                        : item.path("metadata");
                if (meta.isObject() && meta.path("address").isTextual()) {
                    searchAddress = meta.path("address").asText("").trim();
                }
            }

            if (title.isEmpty()) {
                noTitle++;
            }
            if (link.isEmpty()) {
                noLink++;
            }
            if (!title.isEmpty() && !link.isEmpty()) {
                BuyerSearchResult parsed = new BuyerSearchResult(
                        title, link, "USA", businessType, link, query, snippet);
                if (!searchAddress.isEmpty()) {
                    parsed.setSearchAddress(searchAddress);
                }
                results.add(parsed);
            }
        }

        int rawCount = resultsArray.size();
        int parsedCount = results.size();
        int rejectedCount = rawCount - parsedCount;
        if (rawCount == 0) {
            // The API genuinely returned zero results for this query - a parse
            // never happened, so this is NOT treated as a parsing failure.
            logger.info("Firecrawl HTTP {} [query '{}']: API returned 0 raw results, "
                            + "0 parsed, 0 rejected - genuine zero, not a parse failure. preview={}",
                    lastStatus, query, safePreview(responseJson, 200));
        } else if (parsedCount == 0) {
            // Raw results existed but none had both a title and URL - flagged so
            // it can never be mistaken for "the API returned nothing".
            logger.warn("Firecrawl HTTP {} [query '{}']: {} raw results -> 0 parsed "
                            + "({} rejected: title missing={}, link missing={}) - "
                            + "results HAVE a body but could not be extracted; preview={}",
                    lastStatus, query, rawCount, rejectedCount,
                    noTitle, noLink, safePreview(responseJson, 300));
        } else {
            logger.info("Firecrawl HTTP {} [query '{}']: {} raw results -> {} parsed "
                            + "({} rejected: title missing={}, link missing={})",
                    lastStatus, query, rawCount, parsedCount,
                    rejectedCount, noTitle, noLink);
        }

        return results;
    }

    // Short, safe single-line preview of a Firecrawl response body for logs.
    private String safePreview(String body, int maxLen) {
        if (body == null) {
            return "null";
        }
        String flat = body.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\t ]+", " ").trim();
        if (flat.length() <= maxLen) {
            return flat;
        }
        return flat.substring(0, maxLen) + "...(" + flat.length() + " total chars)";
    }

    /**
     * Waits so Firecrawl requests are fired at most every
     * MIN_FIRECRAWL_REQUEST_INTERVAL_MS, using a static timestamp shared by all
     * searches (even concurrent ones) so the account's requests-per-minute
     * budget is honoured. Failed compare-and-set means another request just
     * claimed the slot, so the loop simply waits for the next one.
     */
    private void throttleFirecrawlRequest() {
        while (true) {
            long now = System.nanoTime();
            long nextAllowed = LAST_FIRECRAWL_REQUEST_NANOS.get()
                    + TimeUnit.MILLISECONDS.toNanos(MIN_FIRECRAWL_REQUEST_INTERVAL_MS);
            long waitMs = TimeUnit.NANOSECONDS.toMillis(nextAllowed - now);
            if (waitMs <= 0) {
                if (LAST_FIRECRAWL_REQUEST_NANOS.compareAndSet(
                        LAST_FIRECRAWL_REQUEST_NANOS.get(), now)) {
                    return;
                }
                continue;
            }
            sleepQuietly(Math.min(waitMs, MIN_FIRECRAWL_REQUEST_INTERVAL_MS));
        }
    }

    /**
     * Short fixed backoff for a rate-limited query: ~2.5s before the first
     * retry and ~6s before the second (2-3s and 5-7s with jitter). Never a long
     * wait - after MAX_429_RETRIES retries the query is skipped entirely.
     */
    private long retryBackoffMillis(int attempt) {
        int idx = Math.min(attempt - 1, FIRE_429_RETRY_BASE_DELAY_MS.length - 1);
        long jitter = ThreadLocalRandom.current().nextLong(FIRE_429_RETRY_JITTER_MS[idx] + 1);
        return FIRE_429_RETRY_BASE_DELAY_MS[idx] + jitter;
    }

    /**
     * Server-provided Retry-After (seconds) when the 429 response carried one.
     * The search NEVER retries immediately after a 429: it waits the full
     * server-requested delay, hard-capped at MAX_SERVER_RETRY_AFTER_MS so a
     * hostile/huge value can never stall the search indefinitely. Returns -1
     * (caller falls back to the fixed short backoff) when the server sent no
     * usable Retry-After header.
     */
    private long serverRetryWaitMillis(Object retryAfterHeader) {
        if (retryAfterHeader == null) {
            return -1;
        }
        try {
            String raw = String.valueOf(retryAfterHeader).trim();
            if (raw.isEmpty()) {
                return -1;
            }
            long seconds = Long.parseLong(raw);
            if (seconds <= 0) {
                return -1;
            }
            return Math.min(seconds * 1000L, MAX_SERVER_RETRY_AFTER_MS);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads a field from a Firecrawl search result, falling back to the nested
     * metadata object. The field can be a plain string or an array (Firecrawl
     * may return either), in which case the first element is used.
     */
    private String extractText(JsonNode item, String field) {
        JsonNode node = item.path(field);
        String value = node.isArray() && node.size() > 0 ? node.get(0).asText("").trim()
                : node.asText("").trim();
        if (value.isEmpty()) {
            JsonNode meta = item.path("metadata");
            JsonNode metaNode = meta.path(field);
            value = metaNode.isArray() && metaNode.size() > 0 ? metaNode.get(0).asText("").trim()
                    : metaNode.asText("").trim();
        }
        return value;
    }

    /**
     * Extracts the structured business address carried by a Firecrawl Google
     * Search result item. Google local results put the address in
     * google.business_data.address (a formatted string) and often also expose
     * the separate locality/region parts; older revisions may nest it under a
     * "googleData" key. Returns "" when no address is present.
     */
    private String extractGoogleBusinessAddress(JsonNode item) {
        if (item == null) {
            return "";
        }
        JsonNode google = item.path("google");
        if (!google.isObject()) {
            google = item.path("googleData");
        }
        if (!google.isObject()) {
            return "";
        }
        JsonNode business = google.path("business_data");
        if (business.isObject()) {
            JsonNode address = business.path("address");
            if (address.isTextual() && !address.asText().trim().isEmpty()) {
                return address.asText().trim();
            }
            // Address parts (Google may return them separately).
            JsonNode locality = business.path("locality");
            JsonNode region = business.path("region");
            String loc = locality.isTextual() ? locality.asText().trim() : "";
            String reg = region.isTextual() ? region.asText().trim() : "";
            if (!loc.isEmpty() && !reg.isEmpty()) {
                return loc + ", " + reg;
            }
            String street = business.path("streetAddress").asText("").trim();
            String postal = business.path("postalCode").asText("").trim();
            if (!street.isEmpty() && !loc.isEmpty()) {
                String base = street + ", " + loc;
                return (postal.isEmpty() ? base : base + " " + postal).trim();
            }
        }
        JsonNode address = google.path("address");
        if (address.isTextual() && !address.asText().trim().isEmpty()) {
            return address.asText().trim();
        }
        return "";
    }

    private String normalizeWebsite(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return "https://" + host;
        } catch (Exception e) {
            return url.toLowerCase();
        }
    }

    /**
     * Returns true when the (normalized) website belongs to a social network,
     * directory, review or marketplace host that can never be a real buyer
     * business, or is a subdomain / sub-path of one. Subdomains are checked so
     * e.g. "elpaso.yellowpages.com" is still filtered even though the host is
     * not literally in the blocked set.
     */
    private boolean isNonBusinessHost(String normalizedWebsite) {
        if (normalizedWebsite == null) {
            return false;
        }
        try {
            URI uri = URI.create(normalizedWebsite);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            if (NON_BUSINESS_HOSTS.contains(host)) {
                return true;
            }
            int dot = host.indexOf('.');
            while (dot > 0 && dot < host.length() - 1) {
                String base = host.substring(dot + 1);
                if (NON_BUSINESS_HOSTS.contains(base)) {
                    return true;
                }
                dot = host.indexOf('.', dot + 1);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> buildStateAbbreviations() {
        Map<String, String> map = new HashMap<>();
        map.put("alabama", "AL");
        map.put("alaska", "AK");
        map.put("arizona", "AZ");
        map.put("arkansas", "AR");
        map.put("california", "CA");
        map.put("colorado", "CO");
        map.put("connecticut", "CT");
        map.put("delaware", "DE");
        map.put("florida", "FL");
        map.put("georgia", "GA");
        map.put("hawaii", "HI");
        map.put("idaho", "ID");
        map.put("illinois", "IL");
        map.put("indiana", "IN");
        map.put("iowa", "IA");
        map.put("kansas", "KS");
        map.put("kentucky", "KY");
        map.put("louisiana", "LA");
        map.put("maine", "ME");
        map.put("maryland", "MD");
        map.put("massachusetts", "MA");
        map.put("michigan", "MI");
        map.put("minnesota", "MN");
        map.put("mississippi", "MS");
        map.put("missouri", "MO");
        map.put("montana", "MT");
        map.put("nebraska", "NE");
        map.put("nevada", "NV");
        map.put("new hampshire", "NH");
        map.put("new jersey", "NJ");
        map.put("new mexico", "NM");
        map.put("new york", "NY");
        map.put("north carolina", "NC");
        map.put("north dakota", "ND");
        map.put("ohio", "OH");
        map.put("oklahoma", "OK");
        map.put("oregon", "OR");
        map.put("pennsylvania", "PA");
        map.put("rhode island", "RI");
        map.put("south carolina", "SC");
        map.put("south dakota", "SD");
        map.put("tennessee", "TN");
        map.put("texas", "TX");
        map.put("utah", "UT");
        map.put("vermont", "VT");
        map.put("virginia", "VA");
        map.put("washington", "WA");
        map.put("washington state", "WA");
        map.put("west virginia", "WV");
        map.put("wisconsin", "WI");
        map.put("wyoming", "WY");
        map.put("district of columbia", "DC");
        return Collections.unmodifiableMap(map);
    }
}
