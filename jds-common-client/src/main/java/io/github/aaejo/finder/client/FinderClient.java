package io.github.aaejo.finder.client;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.fluent.Request;
import org.jsoup.Connection;
import org.jsoup.helper.HttpConnection;
import org.jsoup.nodes.Document;
import org.springframework.retry.support.RetryTemplate;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserType.LaunchOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

import crawlercommons.filters.URLFilter;
import crawlercommons.filters.basic.BasicURLNormalizer;
import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.BaseRobotsParser;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import crawlercommons.sitemaps.SiteMapParser;
import crawlercommons.sitemaps.UnknownFormatException;
import lombok.extern.slf4j.Slf4j;

/**
 * A utility for various webpage-fetching functionality to be used by the finder modules.
 * Respects Robots Exclusion Protocol (robots.txt) rules, including crawl-delay.
 * Enforces a default "courtesy" delay even when one isn't requested with the crawl-delay directive.
 *
 * If instructed, will attempt to start up and use Playwright with headless Chrom(e/ium) and
 * ChromeDriver to fetch pages in a manner that likely captures dynamic changes to the page HTML.
 * Otherwise, or if Playwright setup fails, a configured Jsoup Connection will be used.
 *
 * @author Omri Harary
 */
/*
 * NOTE: Someday I would really love to find a way to make this all work without needing
 * a real browser. HtmlUnit seemed really promising, but its JavaScript support
 * just wasn't good enough to handle arbitrary sites like we need it to. It couldn't even
 * handle the Oxford faculty list, which is the entire reason we discovered the significance
 * of not handling dynamic content in the first place.
 */
@Slf4j
public class FinderClient {

    private final Connection session;
    private final RetryTemplate retryTemplate;
    private final FinderClientProperties properties;

    private Browser browser;

    private URLFilter filter;
    private BaseRobotsParser robotsParser;
    private HashMap<String, BaseRobotRules> robotsRules;
    private HashMap<String, Instant> lastConnectionTimes;

    private String userAgent;
    private boolean playwrightEnabled;
    private long courtesyDelayMillis;

    /**
     * @param session       configured Jsoup Connection object for use in requests
     * @param retryTemplate configured Spring Retry RetryTemplate for request retries
     * @param properties    client configuration properties
     *
     * @see Connection
     * @see RetryTemplate
     */
    public FinderClient(Connection session, RetryTemplate retryTemplate, FinderClientProperties properties) {
        this.session = session;
        this.retryTemplate = retryTemplate;
        this.properties = properties;

        this.playwrightEnabled = properties.isEnablePlaywright();

        if (this.playwrightEnabled) {
            try {
                List<String> args = new ArrayList<>(properties.getChromeArgs());

                // Ensure default flags from environment are also used when starting with Playwright
                if (StringUtils.isNotBlank(System.getenv("CHROMIUM_FLAGS"))) {
                    args.addAll(Arrays.asList(System.getenv("CHROMIUM_FLAGS").split(" ")));
                }
                if (System.getProperty("user.name").equals("root")) {
                    // Required to run Chrome as root, and couldn't get it to run as non-root in container
                    args.add("--no-sandbox");
                }
                // The flag for new Chrome headless changed in 109
                // Presumably it may change again when it becomes the default
                // See here for more: https://www.selenium.dev/blog/2023/headless-is-going-away/
                // If CHROME_VERSION environment variable is not set, assume (effectively) latest
                int chromeVersion = StringUtils.isNotBlank(System.getenv("CHROME_VERSION"))
                        ? Integer.parseInt(System.getenv("CHROME_VERSION"))
                        : Integer.MAX_VALUE;
                if (chromeVersion <= 108) {
                    args.add("--headless=chrome");
                } else {
                    args.add("--headless=new");
                }
                if (StringUtils.isNotBlank(this.userAgent)) {
                    args.add("--user-agent=" + this.userAgent);
                }

                LaunchOptions options = new BrowserType.LaunchOptions();
                options.setArgs(args);
                if (StringUtils.isNotBlank(System.getenv("CHROME_BIN"))) {
                    options.setExecutablePath(Path.of(System.getenv("CHROME_BIN")));
                }

                this.browser = Playwright.create().chromium().launch(options);
            } catch (Exception e) {
                log.error("An exception occurred in setting up Playwright", e);
                if (this.browser != null) {
                    this.browser.close();
                    this.browser = null;
                }
                this.playwrightEnabled = false;
            }
        }

        this.filter = new BasicURLNormalizer();
        this.robotsParser = new SimpleRobotRulesParser();
        this.robotsRules = new HashMap<>();
        this.lastConnectionTimes = new HashMap<>();

        this.userAgent = StringUtils.isNotBlank(properties.getUserAgent()) ? properties.getUserAgent() : HttpConnection.DEFAULT_UA;
        this.session.userAgent(this.userAgent);

        this.courtesyDelayMillis = properties.getCourtesyDelayMillis() != 0 ? properties.getCourtesyDelayMillis()
                : FinderClientProperties.DEFAULT_DELAY_MILLIS;
    }

    /**
     * Shutdown the client instance. Unless Playwright is enabled this is effectively a
     * no-op. Otherwise, this shuts down the driver and browser.
     */
    public void shutdown() {
        log.debug("Shutting down FinderClient");
        if (playwrightEnabled) {
            browser.close();
        }
    }

    /**
     * Get the specified webpage as a {@code FinderClientResponse}, respecting robots.txt rules,
     * enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public FinderClientResponse get(String url) {
        return get(URI.create(filter.filter(url)), true);
    }

    /**
     * Get the specified webpage as a {@code FinderClientResponse}. Optionally respecting
     * robots.txt rules, but always enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public FinderClientResponse get(String url, boolean respectRobots) {
        return get(URI.create(filter.filter(url)), respectRobots);
    }

    /**
     * Get the specified webpage as a {@code FinderClientResponse}, respecting robots.txt rules,
     * enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public FinderClientResponse get(URI url) {
        return get(url, true);
    }

    /**
     * Get the specified webpage as a {@code FinderClientResponse}. Optionally respecting
     * robots.txt rules, but always enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public FinderClientResponse get(URI url, boolean respectRobots) {
        if (url == null) {
            return null;
        }

        if (!StringUtils.containsAny(url.getScheme(), "http", "https")) {
            log.debug("Cannot fetch non-http/s URLs.");
            return null;
        }

        String key = toKey(url);

        // Get the robots rules for the host if we don't have them already
        if (!robotsRules.containsKey(key)) {
            loadRobotsTxt(url);
        }

        // If the path we're trying to access isn't allowed, we won't do it
        if (respectRobots && !robotsRules.get(key).isAllowed(url.toString())) {
            log.warn("Access to {} is disallowed according to the robots.txt rules for {}", url, key);
            return null;
        }

        // If there's a noted last connection time for this host
        if (lastConnectionTimes.containsKey(key)) {
            // Determine if there's a crawl delay specified in robots.txt
            boolean hasCrawlDelay = robotsRules.get(key).getCrawlDelay() != BaseRobotRules.UNSET_CRAWL_DELAY;
            // Use the crawl delay from robots if there is one, otherwise use our default as a courtesy
            long requiredDelayMillis = hasCrawlDelay ? robotsRules.get(key).getCrawlDelay() // Rules parser already puts crawl-delay in millis
                    : courtesyDelayMillis;
            // Calculate how long it has been since the last connection to the host
            long millisSinceLastConnection = Duration.between(lastConnectionTimes.get(key), Instant.now()).abs()
                    .toMillis();

            // If the required delay hasn't been met yet
            if (millisSinceLastConnection < requiredDelayMillis) {
                // Calculate how much time until the required delay is satisfied
                long additionalDelayMillis = requiredDelayMillis - millisSinceLastConnection;

                // Inform the user differently if we're waiting on a delay requested by the server, or our own
                if (hasCrawlDelay) {
                    log.info(
                            "Waiting an additional {}ms before fetching from {} to respect crawl-delay directive in robots.txt",
                            additionalDelayMillis, url);
                } else {
                    log.info("Waiting an additional {}ms before fetching from {} out of courtesy delay of 2000ms",
                            additionalDelayMillis, url);
                }

                // Sleep until the delay time has been met
                try {
                    // Maybe someday this can be enhanced to continue handling other requests while
                    // awaiting this one.
                    Thread.sleep(additionalDelayMillis);
                } catch (InterruptedException e) {
                    // This should happen so infrequently that it's probably fine to just move on from it.
                    // Most of the time we'll still be providing the delay.
                    log.error("Waiting for crawl delay interrupted.", e);
                }
            }
        }

        // Get the page, using the configured retry template's strategy
        FinderClientResponse page = retryTemplate.execute(
            // Retryable part
            ctx -> {
                try {
                    // Update last connection time
                    lastConnectionTimes.put(key, Instant.now());
                    return playwrightEnabled ? playwrightGet(url) : jsoupGet(url);
                } catch (Exception e) {
                    log.error("Failed to fetch from {} on attempt {}. May retry.", url, (ctx.getRetryCount() + 1));
                    // Rethrowing as RuntimeException for retry handling
                    throw new RuntimeException(e);
                }
            },
            // Recovery part
            ctx -> {
                // Unwrap from RuntimeException
                Throwable lastException = ctx.getLastThrowable().getCause();
                log.error("Max retries exceeded for fetching from {}. Last exception was {}", url, lastException.toString());
                if (log.isDebugEnabled()) {
                    log.error("Last exception details", lastException);
                }
                // If we exceed max retries, return null
                return new FinderClientResponse(lastException);
            }
        );

        return page;
    }

    /**
     * Clear all stored rules and connection times for the given website's host.
     *
     * @param url   the website to clear contents for
     */
    public void clear(URI url) {
        clear(url.toString(), false);
    }

    /**
     * Clear all stored rules and connection times for the given website's host.
     *
     * @param url   the website to clear contents for
     */
    public void clear(String url) {
        clear(url, false);
    }

    /**
     * Clear all stored rules and connection times for the given website's host.
     * Optionally also clear the rules and connection times for all saved subdomains of said host.
     *
     * @param url               the website to clear contents for
     * @param includeSubdomains whether to clear contents for all subdomains of the same host as well
     */
    public void clear(URI url, boolean includeSubdomains) {
        clear(url.toString(), includeSubdomains);
    }

    /**
     * Clear all stored rules and connection times for the given website's host.
     * Optionally also clear the rules and connection times for all saved subdomains of said host.
     *
     * @param url               the website to clear contents for
     * @param includeSubdomains whether to clear contents for all subdomains of the same host as well
     */
    public void clear(String url, boolean includeSubdomains) {
        String key = toKey(url);
        if (includeSubdomains) {
            HashSet<String> keys = new HashSet<>();
            keys.addAll(robotsRules.keySet());
            keys.addAll(lastConnectionTimes.keySet());
            for (String k : keys) {
                if (k.endsWith(key)) {
                    robotsRules.remove(k);
                    lastConnectionTimes.remove(k);
                }
            }
        } else {
            robotsRules.remove(key);
            lastConnectionTimes.remove(key);
        }
    }

    /**
     * Get a set of all the URLs contained in the sitemap(s) of the host of the given website.
     * Recursively processes all sitemaps and sitemap indexes found in the host's robots.txt file,
     * if none are found the default location will be attempted.
     *
     * If an error occurs in processing, as many contained URLs as possible will still be returned.
     *
     * @param url   the website to get sitemap contents for
     * @return      recursive sitemap contents as URL strings
     */
    public HashSet<String> getSiteMapURLs(String url) {
        if (StringUtils.isBlank(url)) {
            return new HashSet<>();
        }
        return getSiteMapURLs(URI.create(url));
    }

    /**
     * Get a set of all the URLs contained in the sitemap(s) of the host of the given website.
     * Recursively processes all sitemaps and sitemap indexes found in the host's robots.txt file,
     * if none are found the default location will be attempted.
     *
     * If an error occurs in processing, as many contained URLs as possible will still be returned.
     *
     * @param url   the website to get sitemap contents for
     * @return      recursive sitemap contents as URL strings
     */
    public HashSet<String> getSiteMapURLs(URI url) {
        String key = toKey(url);

        if (!robotsRules.containsKey(key)) {
            loadRobotsTxt(url);
        }

        SiteMapParser smParser = new SiteMapParser();
        HashSet<String> flattenedSiteMap = new HashSet<>();

        List<String> sitemaps = robotsRules.get(key).getSitemaps();
        if (sitemaps.isEmpty()) {
            // Try default location if none are listed in robots.txt
            sitemaps.add(baseUrl(url) + "/sitemap.xml");
        }

        for (String sm : sitemaps) {
            try {
                smParser.walkSiteMap(new URL(sm), smUrl -> flattenedSiteMap.add(smUrl.getUrl().toString()));
            } catch (MalformedURLException e){
                log.error("Malformed URL encountered in processing sitemap", e);
            } catch (UnknownFormatException e) {
                log.error("Unable to process unknown sitemap format", e);
            } catch (IOException e) {
                log.error("Connection error occurred in processing sitemap", e);
            }
        }

        return flattenedSiteMap;
    }

    /**
     * Convert a given string to be used as a key in the rules and connection time maps.
     *
     * @param in    string to convert to key
     * @return      usable consistant key to use or null
     */
    private String toKey(String in) {
        if (StringUtils.isBlank(in)) {
            return null;
        }
        return toKey(URI.create(in));
    }

    /**
     * Convert a given URI to be used as a key in the rules and connection time maps.
     *
     * @param in    URI to convert to key
     * @return      usable consistant key to use or null
     */
    private String toKey(URI in) {
        return StringUtils.removeStart(in.getHost(), "www.");
    }

    /**
     * Get a base, connectable HTTP URL string from the given URI.
     * Effectively takes the hostname and scheme, if present. Otherwise sets the scheme as http.
     *
     * @param url   URI to reduce
     * @return      reduced base URL string
     */
    private String baseUrl(URI url) {
        if (url == null) {
            return null;
        }

        return (url.getScheme() != null ? url.getScheme() : "http") + "://" + url.getHost();
    }

    /**
     * Populate the robots rules for the specified website's host
     *
     * @param url   the website to get the robots rules for
     */
    private void loadRobotsTxt(URI url) {
        // Reduce the URL to its base (scheme and host) and append the path to the robots file
        String baseUrl = baseUrl(url);
        String robotsTxtUrl = baseUrl + "/robots.txt";

        // Get the robots.txt file contents, using the configured retry template's strategy
        byte[] robotsTxtBytes = retryTemplate.execute(
            // Retryable part
            ctx -> {
                try {
                    return Request
                            .get(robotsTxtUrl)
                            .userAgent(userAgent)
                            .execute()
                            .returnContent()
                            .asBytes();
                } catch (Exception e) {
                    log.error("Unable to get robots.txt file from {} on attempt {}. May retry.", robotsTxtUrl, (ctx.getRetryCount() + 1));
                    // Rethrowing as RuntimeException for retry handling
                    throw new RuntimeException(e);
                }
            },
            // Recovery part
            ctx -> {
                // Unwrap from RuntimeException
                Throwable lastException = ctx.getLastThrowable().getCause();
                log.error("Max retries exceeded for fetching robots.txt from {}. Last exception was {}", robotsTxtUrl, lastException.toString());
                if (log.isDebugEnabled()) {
                    log.error("Last exception details", lastException);
                }
                // If we exceed max retries, return null
                return null;
            }
        );

        if (robotsTxtBytes == null) {
            // Since robots is an exclusion protocol, we will assume the absence of a rules
            // file as having no exclusion
            robotsRules.put(toKey(url), new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL));
        } else {
            // Store the rules for this host for later
            robotsRules.put(toKey(url), robotsParser.parseContent(baseUrl, robotsTxtBytes, "text/plain", userAgent));
        }
    }

    /**
     * Gets the webpage using a Jsoup connection.
     *
     * @param url   webpage to fetch
     * @return      webpage contents and response details
     * @throws Exception
     */
    private FinderClientResponse jsoupGet(URI url) throws Exception {
        Document page = session.newRequest().url(url.toString()).get();

        if (shouldThrow(page.connection().response().statusCode())) {
            throw new IOException("Server error occurred. Status = [" + page.connection().response().statusCode()
                    + "] Message = [" + page.connection().response().statusMessage() + "]");
        }
        return new FinderClientResponse(page);
    }

    /**
     * Gets the webpage using Playwright and Chrom(e/ium). Gives time for page to load
     * fully and then extracts the full HTML after modification by scripts.
     *
     * @param url   webpage to fetch
     * @return      webpage contents and response details
     * @throws Exception
     */
    private FinderClientResponse playwrightGet(URI url) throws Exception {
        try (BrowserContext context = browser.newContext(); Page page = context.newPage()) {
            Response response = page.navigate(url.toString(),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE)); // Wait until network is quiet

            if (shouldThrow(response.status())) {
                throw new IOException("HTTP error occurred. Status = [" + response.status() + "] Message = ["
                        + response.statusText() + "]");
            }

            return new FinderClientResponse(response, page);
        }
    }

    private boolean shouldThrow(int status) {
        switch (properties.getThrowOn()) { // Based on the property, an exception should be thrown when...
            case NOT_SUCCESS: return status < 200 || status > 299; // ...the status is not a success
            case CLIENT_ERROR: return status >= 400 && status <= 499; // ...the status is a client error only
            case SERVER_ERROR: return status >= 500 && status <= 599; // ...the status is a server error only
            case CLIENT_OR_SERVER_ERROR: return status >= 400 && status <= 599; // ...the status is a client or server error
            default: return false;
        }
    }
}
