package io.github.aaejo.messaging.records;

/**
 * Data for an incompletely scraped reviewer profile requiring manual intervention.
 *
 * @param profile   the profile object that was incompletely scraped
 * @param reviewer  the reviewer object to be completed
 * @param missing   an array of one or more {@code IncompleteScrape.MissingFlags} that indicate what is missing
 *
 * @see Profile
 * @see Reviewer
 */
public record IncompleteScrape(Profile profile, Reviewer reviewer, MissingFlags[] missing) {

    /**
     * Flags for which critical field is missing.
     */
    static enum MissingFlags {
        /**
         * Name missing
         */
        NAME,
        /**
         * Email address missing
         */
        EMAIL,
        /**
         * Specializations missing
         */
        SPECS
    }
}
