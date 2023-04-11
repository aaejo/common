package io.github.aaejo.finder.client;

import java.util.ArrayList;
import java.util.List;

public class FinderClientProperties {
    public static final long DEFAULT_DELAY_MILLIS = 2000L;

    private String userAgent;
    private boolean enablePlaywright = true;
    private List<String> chromeArgs = new ArrayList<>();
    private long courtesyDelayMillis = DEFAULT_DELAY_MILLIS;
    private ThrowOnHttpStatus throwOn = ThrowOnHttpStatus.SERVER_ERROR;

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
}
