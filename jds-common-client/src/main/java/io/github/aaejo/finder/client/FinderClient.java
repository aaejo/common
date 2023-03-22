package io.github.aaejo.finder.client;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.fluent.Response;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.nodes.Document;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.JavascriptExecutor;
import org.springframework.retry.support.RetryTemplate;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.BaseRobotsParser;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import crawlercommons.sitemaps.SiteMapParser;
import crawlercommons.sitemaps.UnknownFormatException;
import lombok.extern.slf4j.Slf4j;

/**
 * A utility for various webpage-fetchign functionality to be used by the finder modules.
 * Respects Robots Exclusion Protocol (robots.txt) rules, including crawl-delay.
 * Enforces a default "courtesy" delay even when one isn't requested with the crawl-delay directive.
 *
 * If instructed, will attempt to start up and use Selenium with headless Chrom(e/ium) and
 * ChromeDriver to fetch pages in a manner that likely captures dynamic changes to the page HTML.
 * Otherwise, or if Selenium setup fails, a configured Jsoup Connection will be used.
 */
@Slf4j
public class FinderClient {

    private static final long DEFAULT_DELAY_MILLIS = 2000L;

    private final Connection session;
    private final RetryTemplate retryTemplate;

    private BaseRobotsParser robotsParser;
    private HashMap<String, BaseRobotRules> robotsRules;
    private HashMap<String, Instant> lastConnectionTimes;

    private String userAgent;
    private boolean seleniumEnabled;
    private WebDriver driver;

    /**
     * @param session       configured Jsoup Connection object for use in requests
     * @param retryTemplate configured Spring Retry RetryTemplate for request retries
     * @param userAgent     the user-agent string to use for all requests
     * @param enableSelenium    whether to attempt to enable Selenium-based page fetching
     *
     * @see Connection
     * @see RetryTemplate
     */
    public FinderClient(Connection session, RetryTemplate retryTemplate, String userAgent, boolean enableSelenium) {
        this.session = session;
        this.retryTemplate = retryTemplate;
        this.userAgent = StringUtils.isNotBlank(userAgent) ? userAgent : HttpConnection.DEFAULT_UA;
        this.seleniumEnabled = enableSelenium;

        if (this.seleniumEnabled) {
            try {
                // See: https://github.com/SeleniumHQ/selenium/issues/11750
                System.setProperty("webdriver.http.factory", "jdk-http-client");
                ChromeOptions options = new ChromeOptions();

                if (System.getProperty("user.name").equals("root")) {
                    // Required to run Chrome as root, and couldn't get it to run as non-root in container
                    options.addArguments("--no-sandbox");
                }
                // The flag for new Chrome headless changed in 109
                // Presumably it may change again when it becomes the default
                // See here for more: https://www.selenium.dev/blog/2023/headless-is-going-away/
                if (Integer.parseInt(System.getenv("CHROME_VERSION")) <= 108) {
                    options.addArguments("--headless=chrome");
                } else {
                    options.addArguments("--headless=new");
                }
                if (StringUtils.isNotBlank(userAgent)) {
                    options.addArguments("--user-agent=" + userAgent);
                }
                driver = new ChromeDriver(options);
            } catch (Exception e) {
                log.warn("Failed to set up Chromium and/or ChromeDriver. Client will fall back to Jsoup for page fetching.");
                if (log.isDebugEnabled()) {
                    log.error("Browser/Driver setup exception details:", e);
                }
                this.driver = null;
                this.seleniumEnabled = false;
            }
        }

        this.robotsParser = new SimpleRobotRulesParser();
        this.robotsRules = new HashMap<>();
        this.lastConnectionTimes = new HashMap<>();
    }

    /**
     * Alternate constructor that attempts to enable Selenium strategy by default
     *
     * @param session       configured Jsoup Connection object for use when not fetching with Selenium
     * @param retryTemplate configured Spring Retry RetryTemplate for request retries
     * @param userAgent     the user-agent string to use for all requests
     *
     * @see Connection
     * @see RetryTemplate
     */
    public FinderClient(Connection session, RetryTemplate retryTemplate, String userAgent) {
        this(session, retryTemplate, userAgent, true);
    }

    /**
     * Alternate constructor that uses default Jsoup user-agent string
     * and attempts to enable Selenium strategy by default
     *
     * @param session       configured Jsoup Connection object for use in requests
     * @param retryTemplate configured Spring Retry RetryTemplate for request retries
     *
     * @see Connection
     * @see RetryTemplate
     * @see HttpConnection#DEFAULT_UA
     */
    public FinderClient(Connection session, RetryTemplate retryTemplate) {
        this(session, retryTemplate, HttpConnection.DEFAULT_UA);
    }

    /**
     * Shutdown the client instance. Unless Selenium is enabled this is effectively a
     * no-op. Otherwise, this shuts down the driver and closes associated browser
     * windows.
     */
    public void shutdown() {
        log.debug("Shutting down FinderClient");
        if (seleniumEnabled) {
            driver.quit();
        }
    }

    /**
     * Get the specified webpage as a Jsoup {@code Document}, respecting robots.txt rules,
     * enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public Document get(String url) {
        return get(URI.create(url), true);
    }

    /**
     * Get the specified webpage as a Jsoup {@code Document}. Optionally respecting
     * robots.txt rules, but always enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public Document get(String url, boolean respectRobots) {
        return get(URI.create(url), respectRobots);
    }

    /**
     * Get the specified webpage as a Jsoup {@code Document}, respecting robots.txt rules,
     * enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public Document get(URI url) {
        return get(url, true);
    }

    /**
     * Get the specified webpage as a Jsoup {@code Document}. Optionally respecting
     * robots.txt rules, but always enforcing crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public Document get(URI url, boolean respectRobots) {
        if (url == null) {
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
                    : DEFAULT_DELAY_MILLIS;
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
        Document page = retryTemplate.execute(
            // Retryable part
            ctx -> {
                try {
                    // Update last connection time
                    lastConnectionTimes.put(key, Instant.now());
                    return seleniumEnabled ? seleniumGet(url) : jsoupGet(url);
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
                return null;
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
     * Get a base, connectable HTTP URL string from the given URL string.
     * Effectively takes the hostname and scheme, if present. Otherwise sets the scheme as http.
     *
     * @param url   URL string to reduce
     * @return      reduced base URL string
     */
    private String baseUrl(String url) {
        if (url == null) {
            return null;
        }

        return baseUrl(URI.create(url));
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
                    Response response = Request
                            .get(robotsTxtUrl)
                            .userAgent(userAgent)
                            .execute();
                    return response.returnContent().asBytes();
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
     * @return      webpage contents as a Jsoup Document
     * @throws Exception
     */
    private Document jsoupGet(URI url) throws Exception {
        return session.newRequest().url(url.toString()).get();
    }

    /**
     * Gets the webpage using Selenium, Chrom(e/ium), and ChromeDriver. Waits until page
     * reports as completely ready and then extracts the full HTML after modification by
     * scripts.
     *
     * @param url   webpage to fetch
     * @return      webpage contents as a Jsoup Document
     * @throws Exception
     */
    private Document seleniumGet(URI url) throws Exception {
        driver.get(url.toString());
        new WebDriverWait(driver, Duration.ofMillis(DEFAULT_DELAY_MILLIS))
                .until(d -> ((JavascriptExecutor) d).executeScript("return document.readyState").toString()
                        .equals("complete"));
        String page = ((JavascriptExecutor) driver)
                .executeScript("return document.getElementsByTagName('html')[0].outerHTML").toString();
        return Jsoup.parse(page, url.toString());
    }
}
