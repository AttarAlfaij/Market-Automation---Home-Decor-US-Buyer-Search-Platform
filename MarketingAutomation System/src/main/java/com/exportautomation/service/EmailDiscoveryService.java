package com.exportautomation.service;

import com.exportautomation.dto.EmailDiscoveryResult;
import com.exportautomation.entity.Buyer;
import com.exportautomation.exception.ResourceNotFoundException;
import com.exportautomation.repository.BuyerRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EmailDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(EmailDiscoveryService.class);

    private static final String[] PAGE_PATHS = {
        "/",
        "/contact",
        "/contact-us",
        "/contactus",
        "/contact-page",
        "/pages/contact",
        "/pages/contact-us",
        "/pages/contactus",
        "/about",
        "/about-us",
        "/aboutus",
        "/pages/about",
        "/pages/about-us",
        "/wholesale",
        "/wholesale-contact",
        "/wholesale-enquiry",
        "/get-in-touch",
        "/getintouch",
        "/reach-us",
        "/reachus",
        "/contact.html",
        "/about.html",
        "/support",
        "/support/contact",
        "/enquiry",
        "/enquiries",
        "/inquiry",
        "/inquire",
        "/faq"
    };

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"
    );

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico",
        ".css", ".js", ".woff", ".woff2", ".ttf", ".eot",
        ".pdf", ".doc", ".docx"
    );

    private static final Set<String> EXCLUDED_DOMAINS = Set.of(
        "example.com", "sentry.io", "wixpress.com", "w3.org",
        "schema.org", "googleapis.com", "gstatic.com",
        "facebook.com", "twitter.com", "instagram.com",
        "youtube.com", "linkedin.com", "pinterest.com",
        "cloudflare.com", "jquery.com", "wordpress.org",
        "gravatar.com", "w.org",
        "paypal.com", "stripe.com", "squareup.com", "godaddy.com",
        "shopify.com", "wix.com", "squarespace.com",
        "mailchimp.com", "brevo.com", "sendinblue.com", "hubspot.com",
        "zendesk.com", "intercom.io", "activecampaign.com",
        "convertkit.com", "mailerlite.com",
        "yoursite.com", "yourdomain.com", "yourcompany.com",
        "yourbusiness.com", "yourname.com", "yourstore.com",
        "youremail.com", "yourwebsite.com", "sitename.com",
        "example.org", "example.net", "example.edu",
        // Template / placeholder domains embedded by site-builder themes and
        // contact-form placeholders - never a real business mailbox.
        "email.com", "email.net", "email.org", "myemail.com",
        "test.com", "test.net", "test.org", "mailtest.com",
        "emailtest.com", "mydomain.com", "mydomain.net",
        "template.com", "templates.com", "website.com", "webaddress.com"
    );

    // Local parts that only ever occur in template placeholder addresses
    // ("your@email.com", "test@test.com", "name@example.com", "demo@..."),
    // never in a real business mailbox. A theme/template placeholder is
    // rejected regardless of its domain so it is never saved as a buyer email.
    private static final Set<String> PLACEHOLDER_LOCAL_PARTS = Set.of(
        "your", "yourname", "youremail", "your email", "your-email",
        "your_email", "yoursite", "yourdomain", "yourcompany",
        "yourbusiness", "yourstore", "yourwebsite", "yoursitename",
        "sitename", "site name", "sample", "samples", "example",
        "examples", "username", "usernamehere", "user", "user123",
        "demo", "test", "testing", "test123", "testmail", "name",
        "firstname", "lastname", "emailaddress", "email",
        "placeholder", "dummy", "foo", "bar", "abc", "xyz",
        "somebody", "someone", "anybody", "anyone"
    );

    // Disposable / throwaway mailbox domains - never a real business email.
    private static final Set<String> DISPOSABLE_EMAIL_DOMAINS = Set.of(
        "mailinator.com", "guerrillamail.com", "yopmail.com",
        "tempmail.com", "temp-mail.org", "temp-mail.com",
        "maildrop.cc", "getnada.com", "10minutemail.com",
        "10minutemail.net", "throwawaymail.com", "sharklasers.com",
        "mailsac.com", "mailnesia.com", "spamgourmet.com",
        "discard.email", "trashmail.com", "dropmail.me",
        "dispostable.com", "mailexpire.com", "mailmetrash.com",
        "nada.email", "spambox.us", "tmail.ws"
    );

    // Business-role prefixes used ONLY for ranking the best email among the valid
    // ones already extracted - they never limit which addresses are accepted.
    // Every valid address on the website (john@, abc123@, orders@, ...) is kept,
    // so a non-role email is still returned when it is the only one present.
    private static final String[] ROLE_PREFIXES = {
        "contact", "sales", "info", "hello", "support", "service", "care",
        "export", "import", "trade", "wholesale", "enquiry", "enquiries",
        "inquiry", "inquiries", "orders", "order", "accounts", "purchasing",
        "purchase", "office", "marketing", "team"
    };

    // Hosts that never represent the actual business/company website. A Relevant
    // URL is only scraped for an email when it IS a real business domain. Social
    // profiles, marketplaces/platform pages, document/PDF sharing platforms,
    // newsletter & email-marketing platforms, directories/listing sites,
    // article/blog/news pages, forum/event pages and generic site-builder
    // platform domains never yield a genuine business email, so they are
    // rejected and never scraped. Entries starting with a "." are platform
    // subdomain suffixes (".myshopify.com"); every other entry also matches any
    // of its subdomains, so "myemail.constantcontact.com" is rejected via
    // "constantcontact.com" and "buyer.threads.com" via "threads.com".
    private static final Set<String> NON_COMPANY_HOSTS = Set.of(
        // Social media / content platforms
        "threads.com", "threads.net", "lemon8-app.com", "lemon8.com",
        "facebook.com", "messenger.com", "instagram.com",
        "twitter.com", "x.com", "linkedin.com",
        "youtube.com", "youtu.be", "twitch.tv", "vimeo.com",
        "pinterest.com", "pinterest.ca", "tiktok.com", "snapchat.com",
        "reddit.com", "tumblr.com", "medium.com", "substack.com",
        "wordpress.com", "blogger.com", "blogspot.com",
        // Document / PDF / presentation sharing platforms
        "calameo.com", "issuu.com", "scribd.com", "slideshare.net",
        "docdroid.net", "yumpu.com", "flipsnack.com", "anyflip.com",
        "fliphtml5.com", "docs.google.com", "drive.google.com",
        "dropbox.com", "box.com", "onedrive.live.com", "1drv.ms",
        // Newsletter / email-marketing platforms
        "constantcontact.com", "mailchimp.com", "mailerlite.com",
        "convertkit.com", "kit.com", "mailjet.com",
        "sendinblue.com", "brevo.com", "campaignmonitor.com", "cmail19.com",
        "aweber.com", "klaviyo.com", "benchmarkemail.com",
        "getresponse.com", "omnisend.com", "icontact.com",
        "verticalresponse.com", "activecampaign.com", "hubspot.com",
        "mailgun.com", "sendgrid.net", "sparkpost.com",
        // Directory / listing / review sites
        "yelp.com", "yelp.ca", "yellowpages.com", "yellowpages.ca",
        "manta.com", "whitepages.com", "bbb.org", "cybo.com",
        "thomsonlocal.com", "superpages.com", "city-data.com",
        "houzz.com", "angieslist.com", "mapquest.com", "foursquare.com",
        "glassdoor.com", "indeed.com", "trustpilot.com",
        "crunchbase.com", "zoominfo.com", "chamberofcommerce.com",
        "merchantcircle.com", "hotfrog.com", "brownbook.net",
        "is24.at", "is24.de",
        // Marketplace / shopping platforms
        "amazon.com", "amazon.ca", "amazon.co.uk", "amazon.de", "amazon.fr",
        "amazon.it", "amazon.es", "amazon.in", "amazon.ae", "amazon.com.au",
        "ebay.com", "ebay.co.uk", "ebay.de", "ebay.com.au", "ebay.in",
        "etsy.com", "etsy.co.uk", "etsy.com.au",
        "alibaba.com", "aliexpress.com", "1688.com", "taobao.com", "tmall.com",
        "walmart.com", "target.com", "bestbuy.com", "homedepot.com", "lowes.com",
        "costco.com", "wayfair.com", "overstock.com", "macys.com",
        "flipkart.com", "shopclues.com", "meesho.com", "shein.com", "temu.com",
        "noon.com", "jd.com", "wish.com", "dhgate.com", "zoro.com", "uline.com",
        "shopee.com", "ubuy.com", "desertcart.com",
        // Forum / event platforms
        "eventbrite.com", "eventbrite.ca", "eventbrite.co.uk",
        "meetup.com", "proboards.com", "freeforums.net", "tapatalk.com",
        // Generic site-builder / platform domains
        ".myshopify.com", "myshopify.com", ".shopify.com",
        ".wixsite.com", ".wix.com", ".squarespace.com", ".weebly.com",
        ".bigcartel.com", ".godaddysites.com", ".webnode.com",
        ".yolasite.com", ".carrd.co", ".about.me", ".square.site",
        ".strikingly.com", ".simdif.com", ".ezywebsite.com"
    );

    private static final Set<String> FREE_EMAIL_DOMAINS = Set.of(
        "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "live.com",
        "msn.com", "aol.com", "icloud.com", "me.com", "protonmail.com",
        "proton.me", "ymail.com", "gmx.com", "mail.com", "zoho.com",
        "yandex.com", "fastmail.com"
    );

    private static final int MAX_DISCOVERED_LINKS = 8;

    // How many buyer websites are scanned for emails at once in a batch
    // (Discover Emails). Parallel so slow hosts do not stall the whole batch.
    private static final int EMAIL_DISCOVERY_BATCH_PARALLELISM = 8;

    // How many relevant (non-homepage) pages per website are scanned for emails.
    // Homepage + up to 5 ranked contact/about/wholesale pages covers the vast
    // majority of sites while keeping discovery fast. Scanning every ranked page
    // (instead of stopping at the first email) maximizes how many emails are
    // collected from each buyer website.
    private static final int MAX_RELEVANT_PAGES_TO_SCAN = 5;

    private static final String[] ContactKeywords = {
        "contact", "get-in-touch", "getintouch", "reach", "reachus", "touch",
        "enquir", "inquiry", "support", "email", "customerservice"
    };
    private static final String[] AboutKeywords = {"about", "who-we-are", "whoweare", "company"};
    private static final String[] WholesaleKeywords = {"wholesale", "trade", "b2b", "distributor"};

    private static final String[] LINK_KEYWORDS = {
        "contact", "customerservice", "about", "wholesale", "enquir", "inquiry",
        "touch", "reach", "sales", "email", "support", "help", "get-in-touch"
    };

    // Obfuscation markers such as "name [at] domain [dot] com" / "name(AT)domain(DOT)com".
    // Only well-delimited bracketed / parenthesised forms are decoded, so plain "at"
    // or "dot" words in normal prose are never touched. Numeric HTML entities
    // (&#64;, &#46;) are decoded by the HTML parser.
    private static final Pattern OBFUSCATED_AT = Pattern.compile(
        "\\s*(?:\\[at\\]|\\(at\\)|\\{at\\})\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBFUSCATED_DOT = Pattern.compile(
        "\\s*(?:\\[dot\\]|\\(dot\\)|\\{dot\\})\\s*", Pattern.CASE_INSENSITIVE);

    @Autowired
    @Qualifier("webScrapingWebClient")
    private WebClient webClient;

    @Autowired
    private BuyerRepository buyerRepository;

    public String findEmailForWebsite(String website) {
        return discoverEmail(website, null);
    }

    /**
     * Fetches only the homepage of a website. The returned HTML can be reused
     * for both US-location verification and email extraction so the homepage
     * is not requested twice.
     */
    public String fetchHomepage(String website) {
        // Non-business / platform hosts and missing websites are not scanned;
        // there is no real company page to reveal a business email.
        if (!isRealBusinessWebsite(website)) {
            return null;
        }
        try {
            return fetchPage(rootOf(website) + "/");
        } catch (Exception e) {
            logger.debug("Failed to fetch homepage for '{}': {}", website, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches a single sub-page (Contact / About / footer-page) of a business
     * website by path. Plain HTTP - never Firecrawl - so locating buyers on a
     * USA-wide search costs no Firecrawl credits. Unavailable or invalid pages
     * return null and never throw.
     */
    public String fetchWebsitePage(String website, String path) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String root = websiteRoot(website);
        String cleanPath = (path == null || path.isBlank()) ? "/" : path.trim();
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/" + cleanPath;
        }
        try {
            String html = fetchPage(root + cleanPath);
            if (html == null || html.isEmpty()) {
                logger.debug("Page {} unavailable for {}", cleanPath, website);
                return null;
            }
            return html;
        } catch (Exception e) {
            logger.debug("Failed to fetch page {} for '{}': {}", cleanPath, website, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches an arbitrary page by its FULL URL - typically the ORIGINAL
     * Firecrawl result URL (sourceUrl), which is often a product/collection/
     * shop page rather than the homepage. Plain HTTP - never Firecrawl - and
     * never throws: unavailable or invalid URLs return null so buyer
     * verification falls back to the homepage.
     */
    public String fetchUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String html = fetchPage(url);
            if (html == null || html.isEmpty()) {
                logger.debug("Page unavailable for result URL '{}'", url);
                return null;
            }
            return html;
        } catch (Exception e) {
            logger.debug("Failed to fetch result URL '{}': {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Scheme + host only (no path/query/fragment) for a business website, so
     * callers can build standard /contact or /about URLs to fetch.
     */
    public String websiteRoot(String website) {
        if (website == null || website.isBlank()) {
            return website;
        }
        try {
            String root = rootOf(website);
            return root != null ? root : website;
        } catch (Exception e) {
            return website;
        }
    }

    /**
     * Scrapes the buyer's public website and returns the single best business
     * email (used as the Buyer's canonical email). Scanning always tries the
     * homepage plus the most relevant Contact/About/Wholesale pages, and keeps
     * trying secondary pages even when the homepage is unreachable because
     * emails usually live on a dedicated contact page.
     */
    public String discoverEmail(String website, String homepageHtml) {
        EmailScan scan = scanForEmails(website, homepageHtml);
        return scan == null ? null : selectBestEmail(scan.candidates, scan.siteDomain);
    }

    /**
     * Scrapes the buyer's public website for EVERY valid email address present
     * (homepage + up to MAX_RELEVANT_PAGES_TO_SCAN ranked contact/about/
     * wholesale pages). Unlike discoverEmail this never collapses the result to
     * a single address - it returns the maximum set of emails found, so callers
     * can save/export them all. Returns an empty set (never null).
     */
    public Set<String> discoverAllEmails(String website, String homepageHtml) {
        EmailScan scan = scanForEmails(website, homepageHtml);
        if (scan == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(scan.candidates);
    }

    /**
     * Scans the buyer's website and returns the raw candidate set plus the
     * site domain (needed for ranking). Outer gates: no website / not a real
     * business website -> null (nothing scraped).
     */
    private EmailScan scanForEmails(String website, String homepageHtml) {
        if (website == null || website.isBlank()) return null;

        // If the "website" is not the actual business/company website (a social
        // profile, marketplace, document/pdf platform, newsletter platform,
        // directory, article/blog/news page, forum/event page or generic platform
        // domain), do not attempt to scrape it - the email is simply not found.
        if (!isRealBusinessWebsite(website)) {
            logger.info("EMAIL NOT FOUND | website={} | reason=not the actual business/company website - email scraping skipped",
                    normalizeBaseUrl(website));
            return null;
        }

        try {
            // Emails are always scraped from the business website's ROOT domain
            // (the verified Relevant URL), never from a deep search-result path:
            // the homepage is scanned first, then contact/about/wholesale pages.
            String baseUrl = rootOf(website);
            String siteDomain = extractDomain(baseUrl);
            logger.debug("Scraping website {} for public email addresses", baseUrl);

            Set<String> candidates = new LinkedHashSet<>();
            List<String> scannedPages = new ArrayList<>();
            List<String> discovered = new ArrayList<>();

            // STEP 1: scan the homepage first (reuse the already-fetched HTML when
            // supplied so it is not requested twice).
            String homeHtml = (homepageHtml != null) ? homepageHtml : fetchPage(baseUrl + "/");
            if (homeHtml != null && !homeHtml.isEmpty()) {
                scannedPages.add(baseUrl + "/");
                collectEmailsFromHtml(homeHtml, candidates);
                if (siteDomain != null) {
                    discovered = extractRelevantLinks(homeHtml, baseUrl, siteDomain);
                }
            } else {
                // Unreachable / SSL-handshake-timeout homepage: the contact/about
                // pages are still tried below - a site that only exposes its email
                // on a dedicated contact page must still yield it.
                logger.debug("Homepage unreachable for {}, still trying contact/about pages", baseUrl);
            }

            // STEP 2: fetch the most relevant Contact/About page(s) that were not
            // already scanned, best candidate first. These are ALWAYS attempted -
            // even when the homepage was unreachable - because the email usually
            // lives on a dedicated contact page. Every ranked page is scanned so
            // the MAXIMUM number of valid emails is collected; only pages that are
            // unavailable/timing-out are skipped (never retried).
            List<String> relevantPages = buildRelevantPageList(discovered, baseUrl, scannedPages);
            for (String pageUrl : relevantPages) {
                String html = fetchPage(pageUrl);
                if (html == null || html.isEmpty()) {
                    logger.debug("Skipping unavailable page {} for {}", pageUrl, baseUrl);
                    continue;
                }
                scannedPages.add(pageUrl);
                collectEmailsFromHtml(html, candidates);
            }

            // Log the outcome with an explicit code (EMAIL FOUND / EMAIL NOT FOUND)
            // plus the website / pages scanned / every email that was found.
            String selected = selectBestEmail(candidates, siteDomain);
            if (selected != null) {
                logger.info("EMAIL FOUND | website={} | email={} | emails_found={} | pages_scanned={}",
                        baseUrl, selected, candidates, scannedPages);
            } else {
                logger.info("EMAIL NOT FOUND | website={} | pages_scanned={} | emails_found={}",
                        baseUrl, scannedPages, candidates);
            }

            return new EmailScan(candidates, siteDomain);
        } catch (Exception e) {
            // Never let scraping failures affect the buyer search results.
            logger.info("EMAIL SCRAPING FAILED | website={} | error={}",
                    normalizeBaseUrl(website), e.getMessage());
            logger.debug("Email discovery failed for '{}': {}", website, e.getMessage());
            return null;
        }
    }

    /** Holds the full candidate set + the site domain needed for email ranking. */
    private static final class EmailScan {
        final Set<String> candidates;
        final String siteDomain;

        EmailScan(Set<String> candidates, String siteDomain) {
            this.candidates = candidates;
            this.siteDomain = siteDomain;
        }
    }

    /**
     * Builds a short, relevance-ordered list of the pages worth scanning for a
     * website. Links discovered on the homepage reflect the site's real
     * structure, so they are preferred; the well-known fixed paths (/contact,
     * /contact-us, /about, /pages/contact, ...) are then added so a standard
     * contact/about page is ALWAYS checked even when the homepage links to it
     * under a different name. The list is ranked with contact pages first, then
     * about, then wholesale, and bounded so only the most relevant pages are
     * fetched.
     */
    private List<String> buildRelevantPageList(List<String> discovered, String baseUrl, List<String> scannedPages) {
        List<String> relevant = new ArrayList<>(discovered);
        for (String path : PAGE_PATHS) {
            if (!"/".equals(path)) {
                String url = baseUrl + path;
                if (!relevant.contains(url)) {
                    relevant.add(url);
                }
            }
        }

        // Rank by relevance so the most promising page is fetched first.
        relevant.sort(Comparator.comparingInt(this::scorePage).reversed());

        List<String> result = new ArrayList<>();
        for (String url : relevant) {
            if (url == null || scannedPages.contains(url)) {
                continue;
            }
            result.add(url);
            if (result.size() >= MAX_RELEVANT_PAGES_TO_SCAN) {
                break;
            }
        }
        return result;
    }

    /**
     * Higher score = more likely to contain a business/contact email.
     * Contact-style pages score highest, then about, then wholesale.
     */
    private int scorePage(String url) {
        if (url == null) return 0;
        String lower = url.toLowerCase();

        if (containsAny(lower, ContactKeywords)) return 300;
        if (containsAny(lower, AboutKeywords)) return 200;
        if (containsAny(lower, WholesaleKeywords)) return 150;
        return 0;
    }

    private static boolean containsAny(String text, String[] keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts every real email address from the page. Emails are taken from
     * mailto: links (including multi-address mailto links) and from the page
     * text/attributes. HTML entities and well-delimited obfuscation forms such
     * as "name [at] domain [dot] com" are decoded first. Only addresses that
     * were actually present on the page are accepted - nothing is ever guessed.
     */
    private void collectEmailsFromHtml(String html, Set<String> candidates) {
        if (html == null || html.isBlank()) {
            return;
        }

        try {
            Document doc = Jsoup.parse(html);

            // mailto: links are the most reliable signal and may contain several
            // addresses separated by commas or semicolons.
            for (Element a : doc.select("a[href^=mailto:]")) {
                String href = a.attr("href").trim();
                if (href.startsWith("mailto:")) {
                    String rest = href.substring("mailto:".length());
                    int queryIndex = rest.indexOf('?');
                    if (queryIndex >= 0) {
                        rest = rest.substring(0, queryIndex);
                    }
                    if (rest.contains("&#64;") || rest.contains("&amp;#64;")) {
                        rest = rest.replaceAll("&amp;#64;|&#64;", "@");
                    }
                    for (String part : rest.split("[,;]")) {
                        addCandidate(part, candidates);
                    }
                }
            }

            // Visible page text: the HTML parser already decodes numeric entities
            // (&#64;, &#46;, &amp;, ...) and naturally re-joins text split by tags.
            String text = doc.text();
            String deobfuscatedText = deobfuscate(text);
            for (String email : extractEmails(deobfuscatedText)) {
                addCandidate(email, candidates);
            }

            // Raw markup may hold emails in title/alt/data-* attributes, so scan
            // the deobfuscated serialized HTML too.
            String rawHtml = deobfuscate(doc.html());
            for (String email : extractEmails(rawHtml)) {
                addCandidate(email, candidates);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse HTML for emails: {}", e.getMessage());
        }
    }

    /**
     * Reconstructs emails hidden behind common obfuscation, e.g.
     * "name [at] domain [dot] com", "name(AT)domain(DOT)com", "name&#64;domain". Only
     * the well-delimited bracketed / parenthesised markers and numeric entities are
     * rewritten - plain "at"/"dot" words are left untouched. The result is still
     * validated by strict email matching, so nothing invalid is ever produced.
     */
    private String deobfuscate(String s) {
        if (s == null) {
            return null;
        }
        return OBFUSCATED_DOT.matcher(OBFUSCATED_AT.matcher(s).replaceAll("@")).replaceAll(".");
    }

    private void addCandidate(String rawEmail, Set<String> candidates) {
        String normalized = normalizeEmail(rawEmail);
        if (normalized != null && isValidEmail(normalized)) {
            candidates.add(normalized);
        }
    }

    /**
     * Finds internal Contact/About/Wholesale-style pages linked from the
     * homepage itself (same host only). A link is considered relevant if its
     * resolved path OR its visible text OR its title attribute contains a
     * keyword such as contact, about, wholesale, email, support or sales, so
     * sites using non-standard URLs/footers are still checked without wasting
     * requests on unrelated pages.
     */
    private List<String> extractRelevantLinks(String html, String baseUrl, String siteDomain) {
        List<String> links = new ArrayList<>();
        if (html == null || siteDomain == null) {
            return links;
        }

        try {
            URI baseUri = URI.create(baseUrl);
            Document doc = Jsoup.parse(html);
            Set<String> seenPaths = new HashSet<>();

            for (Element a : doc.select("a[href]")) {
                if (links.size() >= MAX_DISCOVERED_LINKS) {
                    break;
                }

                String href = a.attr("href").trim();
                if (href.isEmpty() || href.startsWith("mailto:") || href.startsWith("#")) {
                    continue;
                }

                String resolved;
                try {
                    resolved = baseUri.resolve(href).toString().split("#")[0];
                } catch (Exception e) {
                    continue;
                }

                URI uri;
                try {
                    uri = URI.create(resolved);
                } catch (Exception e) {
                    continue;
                }
                String host = uri.getHost();
                if (host == null || !host.toLowerCase().replaceFirst("^www\\.", "").equals(siteDomain)) {
                    continue;
                }

                String path = uri.getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) {
                    continue;
                }
                String normalizedPath = path.toLowerCase().replaceAll("/+$", "");
                if (!seenPaths.add(normalizedPath)) {
                    continue;
                }

                String anchorText = a.text().toLowerCase();
                String title = a.attr("title").toLowerCase();
                String matchOn = normalizedPath + " " + anchorText + " " + title;

                for (String keyword : LINK_KEYWORDS) {
                    if (matchOn.contains(keyword)) {
                        if (!isPlannedPath(normalizedPath)) {
                            links.add(baseUrl + normalizedPath);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract relevant links: {}", e.getMessage());
        }

        return links;
    }

    private boolean isPlannedPath(String normalizedPath) {
        for (String p : PAGE_PATHS) {
            String np = p.toLowerCase().replaceAll("/+$", "");
            if (!"/".equals(np) && np.equals(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    private String selectBestEmail(Set<String> candidates, String siteDomain) {
        if (candidates.isEmpty()) {
            return null;
        }

        // A business email only ever comes from the business's OWN domain.
        // When any same-site candidate exists, an address from an unrelated
        // external website is never selected as the buyer's email.
        Set<String> pool = candidates;
        if (siteDomain != null) {
            Set<String> sameDomain = new LinkedHashSet<>();
            for (String email : candidates) {
                if (matchesSiteDomain(email, siteDomain)) {
                    sameDomain.add(email);
                }
            }
            if (!sameDomain.isEmpty()) {
                pool = sameDomain;
            }
        }

        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (String email : pool) {
            String[] parts = email.split("@", 2);
            String localPart = parts[0];
            String domain = parts[1];

            int score = 0;

            int roleIndex = roleIndexOf(localPart);
            if (roleIndex >= 0) {
                score += 100 - roleIndex;
            } else {
                score += 10;
            }

            if (siteDomain != null && matchesSiteDomain(email, siteDomain)) {
                score += 50;
            }

            if (FREE_EMAIL_DOMAINS.contains(domain)) {
                score -= 40;
            }

            if (score > bestScore) {
                bestScore = score;
                best = email;
            }
        }

        return best;
    }

    private int roleIndexOf(String localPart) {
        for (int i = 0; i < ROLE_PREFIXES.length; i++) {
            String prefix = ROLE_PREFIXES[i];
            if (localPart.equals(prefix)) {
                return i;
            }
            if (localPart.length() > prefix.length() && localPart.startsWith(prefix)
                    && !Character.isLetter(localPart.charAt(prefix.length()))) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchesSiteDomain(String email, String siteDomain) {
        String domain = email.split("@", 2)[1];
        return domain.equals(siteDomain) || domain.endsWith("." + siteDomain);
    }

    private String extractDomain(String url) {
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

    /**
     * Returns true only when the given string looks like a real company website
     * worth scraping for an email. Blank values and social/professional-network,
     * marketplace, document/pdf, newsletter/email-marketing, directory, article/
     * blog/news, forum/event or generic site-builder platform hostnames (Threads,
     * LinkedIn, Facebook, Amazon, Calameo, Constant Contact, Lemon8, ...) are
     * not discoverable - those buyers are simply left with no email.
     */
    public boolean isDiscoverableWebsite(String website) {
        if (website == null || website.isBlank()) {
            return false;
        }
        String domain = extractDomain(normalizeBaseUrl(website));
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        return !isRejectedHost(domain) && !domain.endsWith(".onion");
    }

    /**
     * Strict "real business website" gate used BEFORE email scraping: a candidate
     * is only sent to EmailDiscoveryService when its Relevant URL is the actual
     * business/company website. Social profiles, marketplaces/platform pages,
     * document & PDF platforms, newsletter & email-marketing platforms,
     * directories/listing sites, article/blog/news pages, forum/event pages and
     * generic site-builder platform domains are rejected and never scraped.
     */
    public boolean isRealBusinessWebsite(String website) {
        return isDiscoverableWebsite(website);
    }

    private boolean isRejectedHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) {
            h = h.substring(4);
        }
        for (String entry : NON_COMPANY_HOSTS) {
            if (entry.startsWith(".")) {
                // Platform subdomain suffix, e.g. ".myshopify.com"
                // (matches "acme.myshopify.com" or the bare "myshopify.com").
                String bare = entry.substring(1);
                if (h.equals(bare) || h.endsWith(entry)) {
                    return true;
                }
            } else if (hostOrParentMatches(h, entry)) {
                // Exact host or any parent base domain
                // ("myemail.constantcontact.com" -> "constantcontact.com").
                return true;
            }
        }
        return false;
    }

    private boolean hostOrParentMatches(String host, String domain) {
        if (host.equals(domain)) {
            return true;
        }
        int idx = host.indexOf('.');
        while (idx > 0 && idx < host.length() - 1) {
            host = host.substring(idx + 1);
            if (host.equals(domain)) {
                return true;
            }
            idx = host.indexOf('.');
        }
        return false;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String cleaned = email.trim().toLowerCase();
        if (cleaned.startsWith("mailto:")) {
            cleaned = cleaned.substring("mailto:".length());
        }
        cleaned = cleaned.replaceAll("^[<\\[(\"']+|[>)\\]\"']+$", "");
        while (!cleaned.isEmpty() && ".,".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Discovers emails for a set of stored buyers and returns EVERY unique
     * address found across all of them. For each buyer the per-website scan
     * runs (homepage first, then up to MAX_RELEVANT_PAGES_TO_SCAN ranked
     * contact/about/wholesale pages) and every valid email found on the site is
     * collected - not just the single best one - so the result maximises the
     * emails obtained. The best email, if any, is saved back to the Buyer's
     * `email` field when it does not already have one. A discovery failure
     * never removes the buyer.
     */
    public EmailDiscoveryResult discoverEmails(List<Long> buyerIds) {
        List<Buyer> buyers = buyerRepository.findAllById(buyerIds);

        if (buyers.isEmpty()) {
            throw new ResourceNotFoundException("No buyers found with the provided IDs");
        }

        Set<String> allEmails = Collections.synchronizedSet(new LinkedHashSet<>());
        int[] buyersUpdated = {0};

        // Websites are scanned in parallel so a batch of buyers does not stall
        // sequentially on slow hosts. The email set is synchronized; saving the
        // best email back to each Buyer is independent per buyer.
        ExecutorService executor = Executors.newFixedThreadPool(EMAIL_DISCOVERY_BATCH_PARALLELISM);
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (Buyer buyer : buyers) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        String website = buyer.getWebsite();
                        if (website == null || website.isBlank()) {
                            logger.info("Email discovery: skipping buyer '{}' — no website", buyer.getCompanyName());
                            return;
                        }

                        Set<String> found = discoverAllEmails(website, null);
                        if (found == null || found.isEmpty()) {
                            logger.info("EMAIL NOT FOUND | buyer='{}' | website={} - buyer kept",
                                    buyer.getCompanyName(), buyer.getWebsite());
                            return;
                        }

                        allEmails.addAll(found);
                        logger.info("EMAIL FOUND | buyer='{}' | website={} | emails={}",
                                buyer.getCompanyName(), buyer.getWebsite(), found);

                        // Save the best email to the existing Buyer email field,
                        // unless the buyer already has an assigned email (keep it).
                        if (buyer.getEmail() == null || buyer.getEmail().isBlank()) {
                            String siteDomain = extractDomain(normalizeBaseUrl(website));
                            String selected = selectBestEmail(found, siteDomain);
                            if (selected != null) {
                                buyer.setEmail(selected);
                                try {
                                    buyerRepository.save(buyer);
                                    buyersUpdated[0]++;
                                    logger.info("Email discovery: saved '{}' as email for '{}'",
                                            selected, buyer.getCompanyName());
                                } catch (Exception e) {
                                    // Duplicate unique email / DB hiccup must not
                                    // abort the batch.
                                    logger.warn("Email discovery: could not persist email for '{}' (possibly duplicate) - buyer kept: {}",
                                            buyer.getCompanyName(), e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Email discovery failed for buyer '{}' - buyer kept: {}",
                                buyer.getCompanyName(), e.getMessage());
                    }
                }, executor));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        List<String> sortedEmails = allEmails.stream()
                .map(String::toLowerCase)
                .sorted()
                .collect(Collectors.toList());

        logger.info("Email discovery complete: {} buyers processed, {} unique email(s) found, {} buyer(s) updated",
                buyers.size(), sortedEmails.size(), buyersUpdated[0]);

        return new EmailDiscoveryResult(buyers.size(), sortedEmails.size(), sortedEmails);
    }

    public byte[] generateCsv(List<String> emails) {
        StringBuilder sb = new StringBuilder();
        sb.append("email\n");
        for (String email : emails) {
            sb.append(email).append("\n");
        }
        return sb.toString().getBytes();
    }

    private String normalizeBaseUrl(String website) {
        String url = website.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Scheme + host only (no path/query/fragment). Emails are always scraped
     * from the business website's ROOT, never from a deep product/search-result
     * path, so the homepage and the standard /contact, /about pages are checked.
     */
    private String rootOf(String website) {
        if (website == null || website.isBlank()) {
            return website;
        }
        try {
            URI uri = URI.create(normalizeBaseUrl(website));
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return normalizeBaseUrl(website);
            }
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
                scheme = "https";
            }
            return scheme.toLowerCase() + "://" + host;
        } catch (Exception e) {
            return normalizeBaseUrl(website);
        }
    }

    private String fetchPage(String url) {
        try {
            return webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        if (e instanceof WebClientResponseException) {
                            WebClientResponseException wcre = (WebClientResponseException) e;
                            logger.debug("HTTP {} fetching {}", wcre.getStatusCode(), url);
                        }
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            logger.debug("Error fetching {}: {}", url, e.getMessage());
            return null;
        }
    }

    private Set<String> extractEmails(String html) {
        Set<String> emails = new LinkedHashSet<>();
        Matcher matcher = EMAIL_PATTERN.matcher(html);

        while (matcher.find()) {
            String email = matcher.group().toLowerCase().trim();

            if (email.length() > 254) continue;
            if (!isValidEmail(email)) continue;

            emails.add(email);
        }

        return emails;
    }

    private boolean isValidEmail(String email) {
        String[] parts = email.split("@", 2);
        if (parts.length != 2) return false;

        String localPart = parts[0];
        String domain = parts[1];

        if (localPart.isEmpty() || localPart.length() > 64) return false;
        if (domain.isEmpty() || domain.length() > 253) return false;
        if (localPart.startsWith(".") || localPart.endsWith(".")) return false;
        if (localPart.contains("..")) return false;
        if (domain.startsWith("-") || domain.endsWith("-")) return false;
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) return false;
        if (!domain.contains(".")) return false;

        for (String ext : EXCLUDED_EXTENSIONS) {
            if (email.endsWith(ext)) return false;
        }

        for (String excludedDomain : EXCLUDED_DOMAINS) {
            if (domain.endsWith(excludedDomain)) return false;
        }

        // Template placeholder local parts ("your@", "test@", "name@",
        // "demo@", ...) are the placeholders site-builder themes plant in
        // contact forms/footers - never a real buyer's email (requirement 4).
        // Only the exact local part and a trailing-separator form are matched
        // so real person emails ("john.doe@company.com") are never dropped.
        String localTrim = localPart.toLowerCase(Locale.ROOT);
        String localBase = localTrim.replaceAll("[._\\-]+$", "");
        if (PLACEHOLDER_LOCAL_PARTS.contains(localTrim)
                || PLACEHOLDER_LOCAL_PARTS.contains(localBase)) {
            return false;
        }

        // Disposable / throwaway mailbox domains (mailinator, yopmail, ...)
        // never hold a real business contact email.
        for (String disposable : DISPOSABLE_EMAIL_DOMAINS) {
            if (domain.endsWith(disposable)) return false;
        }

        // Only non-personal system/auto address forms are skipped: a reply to them
        // never reaches a person. Real business mailboxes (support@, admin@,
        // orders@, john@, abc123@, ...) are always accepted.
        String[] skipPatterns = {"noreply", "no-reply", "mailer-daemon", "postmaster",
                "webmaster", "hostmaster", "nobody", "donotreply", "do-not-reply",
                "abuse@", "spam@", "unsubscribe@"};
        for (String skip : skipPatterns) {
            if (email.startsWith(skip)) return false;
        }

        return true;
    }
}
