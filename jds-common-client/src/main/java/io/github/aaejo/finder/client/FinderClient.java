package io.github.aaejo.finder.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;

import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.fluent.Response;
import org.jsoup.Connection;
import org.jsoup.helper.HttpConnection;
import org.jsoup.nodes.Document;
import org.springframework.retry.support.RetryTemplate;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.BaseRobotsParser;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import lombok.extern.slf4j.Slf4j;

/**
 * A wrapper around Jsoup connection functionality to be used by the finder modules.
 * Respects Robots Exclusion Protocol (robots.txt) rules, including crawl-delay.
 * Enforces a default "courtesy" delay even when one isn't requested with the crawl-delay directive.
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

    /**
     * @param session       configured Jsoup Connection object for use in requests
     * @param retryTemplate configured Spring Retry RetryTemplate for request retries
     * @param userAgent     the user-agent string to use for all requests
     *
     * @see Connection
     * @see RetryTemplate
     */
    public FinderClient(Connection session, RetryTemplate retryTemplate, String userAgent) {
        this.session = session;
        this.retryTemplate = retryTemplate;

        this.robotsParser = new SimpleRobotRulesParser();
        this.robotsRules = new HashMap<>();
        this.lastConnectionTimes = new HashMap<>();

        this.userAgent = userAgent != null ? userAgent : HttpConnection.DEFAULT_UA;
    }

    /**
     * Alternate constructor that uses default Jsoup user-agent string
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
     * Get the specified webpage as a Jsoup {@code Document}, respecting robots.txt rules,
     * crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public Document get(String url) {
        return get(URI.create(url));
    }

    /**
     * Get the specified webpage as a Jsoup {@code Document}, respecting robots.txt rules,
     * crawl-delays, and with connection retries.
     *
     * @param url   URL of the webpage to get
     * @return      the specified webpage as a {@code Document} or null
     */
    public Document get(URI url) {
        if (url == null) {
            return null;
        }

        String host = url.getHost();

        // Get the robots rules for the host if we don't have them already
        if (!robotsRules.containsKey(host)) {
            getRobotsTxt(url);
        }

        // If the path we're trying to access isn't allowed, we won't do it
        if (!robotsRules.get(host).isAllowed(url.toString())) {
            log.warn("Access to {} is disallowed according to the robots.txt rules for {}", url, host);
            return null;
        }

        // If there's a noted last connection time for this host
        if (lastConnectionTimes.containsKey(host)) {
            // Determine if there's a crawl delay specified in robots.txt
            boolean hasCrawlDelay = robotsRules.get(host).getCrawlDelay() != BaseRobotRules.UNSET_CRAWL_DELAY;
            // Use the crawl delay from robots if there is one, otherwise use our default as a courtesy
            long requiredDelayMillis = hasCrawlDelay ? robotsRules.get(host).getCrawlDelay() // Rules parser already puts crawl-delay in millis
                    : DEFAULT_DELAY_MILLIS;
            // Calculate how long it has been since the last connection to the host
            long millisSinceLastConnection = Duration.between(lastConnectionTimes.get(host), Instant.now()).abs()
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
                    lastConnectionTimes.put(host, Instant.now());
                    return session.newRequest().url(url.toString()).get();
                } catch (IOException e) {
                    log.error("Failed to fetch from {}. May retry.", url, e);
                    // Rethrowing as RuntimeException for retry handling
                    throw new RuntimeException(e);
                }
            },
            // Recovery part
            ctx -> {
                log.info("Max retries exceeded for fetching from {}", url);
                // If we exceed max retries, return null
                return null;
            }
        );

        return page;
    }

    /**
     * Populate the robots rules for the specified website's host
     *
     * @param url   the website to get the robots rules for
     */
    private void getRobotsTxt(URI url) {
        // Reduce the URL to its base (scheme and host) and append the path to the robots file
        String baseUrl = (url.getScheme() != null ? url.getScheme() : "http") + "://" + url.getHost();
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
                } catch (IOException e) {
                    log.error("Unable to get robots.txt file from {}. May retry", robotsTxtUrl, e);
                    // Rethrowing as RuntimeException for retry handling
                    throw new RuntimeException(e);
                }
            },
            // Recovery part
            ctx -> {
                log.info("Max retries exceeded for fetching robots.txt from {}", robotsTxtUrl);
                // If we exceed max retries, return null
                return null;
            }
        );

        if (robotsTxtBytes == null) {
            // Since robots is an exclusion protocol, we will assume the absence of a rules
            // file as having no exclusion
            robotsRules.put(url.getHost(), new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL));
        } else {
            // Store the rules for this host for later
            robotsRules.put(url.getHost(), robotsParser.parseContent(baseUrl, robotsTxtBytes, "text/plain", userAgent));
        }
    }
}
