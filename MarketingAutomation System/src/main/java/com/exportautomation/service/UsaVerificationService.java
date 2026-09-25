package com.exportautomation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UsaVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(UsaVerificationService.class);

    public static final String STATUS_US = "US";
    public static final String STATUS_NON_US = "NON_US";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    private static final Set<String> US_STATES = Set.of(
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
        "washington state"
    );

    public static final Set<String> US_STATE_ABBREVS = Set.of(
        "al", "ak", "az", "ar", "ca", "co", "ct", "de", "fl", "ga",
        "hi", "id", "il", "in", "ia", "ks", "ky", "la", "me", "md",
        "ma", "mi", "mn", "ms", "mo", "mt", "ne", "nv", "nh", "nj",
        "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri", "sc",
        "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy", "dc"
    );

    private static final Set<String> US_CITIES = Set.of(
        "new york city", "los angeles", "chicago", "houston", "phoenix",
        "philadelphia", "san antonio", "san diego", "dallas", "san jose",
        "austin", "fort worth", "columbus", "charlotte",
        "san francisco", "indianapolis", "seattle", "denver", "boston",
        "nashville", "detroit", "portland", "las vegas", "memphis",
        "louisville", "baltimore", "milwaukee", "albuquerque", "tucson",
        "fresno", "sacramento", "mesa", "kansas city", "atlanta",
        "omaha", "miami", "minneapolis", "new orleans", "cleveland",
        "tampa", "honolulu", "pittsburgh", "cincinnati", "st louis",
        "raleigh", "buffalo", "madison", "salt lake city", "boise",
        "richmond", "spokane", "jackson", "little rock", "hartford",
        "providence", "chattanooga", "knoxville", "charleston", "savannah",
        "birmingham", "mobile", "shreveport", "baton rouge",
        "corpus christi", "lubbock", "amarillo", "el paso", "laredo",
        "mcallen", "plano", "irving", "garland", "arlington",
        "fort lauderdale", "st petersburg", "orlando", "tallahassee",
        "jacksonville"
    );

    private static final Pattern US_STATE_ZIP = Pattern.compile(
        ",\\s*(?:AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|DC)\\s+\\d{5}(?:-\\d{4})?\\b");

    private static final Pattern US_STATE_ABBREV_IN_TEXT = Pattern.compile(
        "\\b(?:AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|DC)\\b");

    // Locations that prove a business is outside the accepted USA + Canada area.
    // Canadian cities/provinces are deliberately NOT listed: buyers may be in
    // Canada, so a Canadian address is never treated as a foreign rejection.
    private static final Set<String> NON_US_LOCATIONS = Set.of(
        "mumbai", "new delhi", "delhi", "bangalore", "bengaluru", "chennai",
        "hyderabad", "pune", "ahmedabad", "kolkata", "surat", "jaipur", "lucknow",
        "kanpur", "nagpur", "indore", "bhopal", "coimbatore", "kochi", "cochin",
        "noida", "gurgaon", "gurugram", "vadodara", "rajkot", "patna",
        "chandigarh", "mysore", "madurai", "amritsar", "ludhiana", "faridabad",
        "ghaziabad", "visakhapatnam", "jodhpur", "udaipur", "varanasi", "siliguri",
        "vijayawada", "hubli", "tirupur", "thrissur",
        "karachi", "lahore", "islamabad", "dhaka", "colombo", "kathmandu",
        "sydney", "melbourne", "brisbane",
        "london", "manchester", "birmingham uk", "hong kong", "shanghai",
        "beijing", "shenzhen", "guangzhou", "seoul", "tokyo", "osaka",
        "bangkok", "kuala lumpur", "jakarta", "manila", "hanoi", "ho chi minh",
        "istanbul", "lagos", "accra", "nairobi", "sao paulo", "buenos aires"
    );

    private static final Set<String> NON_US_CORPORATE_SUFFIXES = Set.of(
        "pvt ltd", "pvt. ltd", "pvt.ltd", "private limited", "(p) ltd"
    );

    private static final Pattern NON_US_COUNTRY_TAIL = Pattern.compile(
        ",\\s*(india|australia|united kingdom|germany|france|spain|italy|netherlands|poland|turkey|mexico|brazil|argentina|china|japan|south korea|singapore|malaysia|indonesia|thailand|vietnam|philippines|pakistan|bangladesh|sri lanka|nepal|kenya|nigeria|south africa|egypt|morocco|uae|u\\.a\\.e\\.?|qatar|oman|kuwait|israel|hungary|romania)\\b");

    private static final Pattern NON_US_BASED_IN = Pattern.compile(
        "\\b(?:based|located|headquartered|situated)\\b[^.,;!?]{0,40}\\bin\\s+(india|australia|united kingdom|germany|china|france|italy|spain|singapore|malaysia|indonesia|thailand|vietnam|philippines|pakistan|bangladesh|sri lanka|nepal|dubai|uae|mexico|brazil|turkey|poland|netherlands|japan|south korea|kenya|nigeria|egypt)\\b");

    private static final Pattern FOREIGN_PHONE_PREFIX = Pattern.compile(
        "\\+(91|92|94|880|971|966|974|973|968|44|61|65|60|66|84|55|52)\\s?[\\d(]");

    private static final Pattern NON_US_POSTAL_CODE = Pattern.compile(
        "(?:\\bpin\\s*code\\b)|(?:,\\s*[1-9]\\d{5}\\b)");

    public Verdict verify(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new Verdict(STATUS_UNKNOWN, "no text to inspect");
        }

        String plainText = rawText.replaceAll("<[^>]*>", " ");
        String padded = (" " + plainText.replaceAll("\\p{Punct}", " ")
                .replaceAll("\\s+", " ").toLowerCase()).trim() + " ";
        String originalCase = plainText.replaceAll("\\s+", " ");

        Matcher zipMatcher = US_STATE_ZIP.matcher(originalCase);
        if (zipMatcher.find()) {
            return new Verdict(STATUS_US, "US state + ZIP code address pattern found");
        }

        for (String marker : new String[]{"united states", "usa", "u.s.a", "us-based", "u.s.-based", "united states of america"}) {
            if (padded.contains(marker)) {
                return new Verdict(STATUS_US, "US marker '" + marker + "' found");
            }
        }

        for (String abbrev : US_STATE_ABBREVS) {
            String target = " " + abbrev + " ";
            if (padded.contains(target)) {
                return new Verdict(STATUS_US, "US state abbreviation '" + abbrev.toUpperCase() + "' found");
            }
        }

        Matcher abbrevMatcher = US_STATE_ABBREV_IN_TEXT.matcher(originalCase);
        if (abbrevMatcher.find()) {
            String matched = abbrevMatcher.group();
            if (US_STATE_ABBREVS.contains(matched.toLowerCase())) {
                return new Verdict(STATUS_US, "US state abbreviation '" + matched + "' found");
            }
        }

        for (String state : US_STATES) {
            if (padded.contains(" " + state + " ")) {
                return new Verdict(STATUS_US, "US state '" + state + "' found");
            }
        }

        for (String city : US_CITIES) {
            if (padded.contains(" " + city + " ")) {
                return new Verdict(STATUS_US, "US city '" + city + "' found");
            }
        }

        Matcher phoneMatcher = FOREIGN_PHONE_PREFIX.matcher(originalCase);
        if (phoneMatcher.find()) {
            return new Verdict(STATUS_NON_US, "foreign phone prefix +" + phoneMatcher.group(1) + " found");
        }
        for (String suffix : NON_US_CORPORATE_SUFFIXES) {
            if (padded.contains(" " + suffix + " ") || padded.contains(suffix)) {
                return new Verdict(STATUS_NON_US, "non-US corporate suffix '" + suffix + "' found");
            }
        }
        for (String place : NON_US_LOCATIONS) {
            if (padded.contains(" " + place + " ")) {
                return new Verdict(STATUS_NON_US, "non-US city/location '" + place + "' found");
            }
        }
        Matcher tailMatcher = NON_US_COUNTRY_TAIL.matcher(padded);
        if (tailMatcher.find()) {
            return new Verdict(STATUS_NON_US, "address ends with country '" + tailMatcher.group(1) + "'");
        }
        Matcher basedMatcher = NON_US_BASED_IN.matcher(padded);
        if (basedMatcher.find()) {
            return new Verdict(STATUS_NON_US, "'based/located in' phrase points outside the USA");
        }
        if (NON_US_POSTAL_CODE.matcher(padded).find()) {
            return new Verdict(STATUS_NON_US, "6-digit postal code / PIN code found (not a US ZIP)");
        }

        return new Verdict(STATUS_UNKNOWN, "no definitive location signal");
    }

    public static class Verdict {
        private final String status;
        private final String reason;

        public Verdict(String status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        public String getStatus() { return status; }
        public String getReason() { return reason; }
    }
}
