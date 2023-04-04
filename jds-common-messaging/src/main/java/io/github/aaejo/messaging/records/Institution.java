package io.github.aaejo.messaging.records;

/**
 * Represents the data for an Institution.
 *
 * @param name      the name of the institution
 * @param country   the country the institution is located in
 * @param address   the mailing address of the institution or a relevant department
 * @param website   the URL of the institution's home page
 * 
 * @author Omri Harary
 */
public record Institution(String name, String country, String address, String website) {
}
