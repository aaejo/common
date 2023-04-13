package io.github.aaejo.finder.client;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.helper.HttpConnection;

public class FinderClientProperties {
    public static final long DEFAULT_DELAY_MILLIS = 2000L;

    private String userAgent = HttpConnection.DEFAULT_UA;
    private boolean enablePlaywright = true;
    private List<String> chromeArgs = new ArrayList<>();
    private long courtesyDelayMillis = DEFAULT_DELAY_MILLIS;
    private ThrowOnHttpStatus throwOn = ThrowOnHttpStatus.SERVER_ERROR;
    // private boolean retries = true; // TODO: Implement this being false
    private int retryAttempts = 3;
    private long retryBackoff = 2000L;
    private boolean blockTrackers = true;
    private long fetchTimeoutMillis = 30000L;

    public enum ThrowOnHttpStatus {
        NOT_SUCCESS,
        CLIENT_ERROR,
        SERVER_ERROR,
        CLIENT_OR_SERVER_ERROR
    }

    /**
     * @return the userAgent
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * @return the enablePlaywright
     */
    public boolean isEnablePlaywright() {
        return enablePlaywright;
    }

    /**
     * @return the chromeArgs
     */
    public List<String> getChromeArgs() {
        return chromeArgs;
    }

    /**
     * @return the courtesyDelayMillis
     */
    public long getCourtesyDelayMillis() {
        return courtesyDelayMillis;
    }

    /**
     * @return the throwOn
     */
    public ThrowOnHttpStatus getThrowOn() {
        return throwOn;
    }

    // /**
    //  * @return the retries
    //  */
    // public boolean isRetries() {
    //     return retries;
    // }

    /**
     * @return the retryAttempts
     */
    public int getRetryAttempts() {
        return retryAttempts;
    }

    /**
     * @return the retryBackoff
     */
    public long getRetryBackoff() {
        return retryBackoff;
    }

    public boolean doBlockTrackers() {
        return blockTrackers;
    }

    /**
     * @return the fetchTimeoutMillis
     */
    public long getFetchTimeoutMillis() {
        return fetchTimeoutMillis;
    }

    /**
     * @param userAgent the userAgent to set
     */
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    /**
     * @param enablePlaywright the enablePlaywright to set
     */
    public void setEnablePlaywright(boolean enablePlaywright) {
        this.enablePlaywright = enablePlaywright;
    }

    /**
     * @param chromeArgs the chromeArgs to set
     */
    public void setChromeArgs(List<String> chromeArgs) {
        this.chromeArgs = chromeArgs;
    }

    /**
     * @param courtesyDelayMillis the courtesyDelayMillis to set
     */
    public void setCourtesyDelayMillis(long courtesyDelayMillis) {
        this.courtesyDelayMillis = courtesyDelayMillis;
    }

    /**
     * @param throwOn the throwOn to set
     */
    public void setThrowOn(ThrowOnHttpStatus throwOn) {
        this.throwOn = throwOn;
    }

    // /**
    //  * @param retries the retries to set
    //  */
    // public void setRetries(boolean retries) {
    //     this.retries = retries;
    // }

    /**
     * @param retryAttempts the retryAttempts to set
     */
    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    /**
     * @param retryBackoff the retryBackoff to set
     */
    public void setRetryBackoff(long retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public void setBlockTrackers(boolean blockTrackers) {
        this.blockTrackers = blockTrackers;
    }

    /**
     * @param fetchTimeoutMillis the fetchTimeoutMillis to set
     */
    public void setFetchTimeoutMillis(long fetchTimeoutMillis) {
        this.fetchTimeoutMillis = fetchTimeoutMillis;
    }

}
