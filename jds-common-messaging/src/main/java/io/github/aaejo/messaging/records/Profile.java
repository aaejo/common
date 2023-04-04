package io.github.aaejo.messaging.records;

/**
 * Represents a reviewer profile for scraping.
 *
 * @param htmlContent   the raw HTML content of the profile if it is not too large
 * @param url           the URL where this profile is accessible
 * @param department    the department that this profile is a part of
 * @param institution   institution that this profile came from
 * 
 * @see Institution
 * 
 * @author Omri Harary
 */
public record Profile(String htmlContent, String url, String department, Institution institution) {
}
