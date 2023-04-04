package io.github.aaejo.messaging.records;

/**
 * Represents necessary reviewer data.
 *
 * @param name              the reviewer's name
 * @param salutation        the reviewer's salutation
 * @param email             the reviewer's email address
 * @param institution       the institution the reviewer is associated with
 * @param department        the reviewer's department name
 * @param specializations   a list of the reviewer's specializations
 * 
 * @see Institution
 * 
 * @author Omri Harary
 */
public record Reviewer(
        String name,
        String salutation,
        String email,
        Institution institution,
        String department,
        String[] specializations
    ) {}
