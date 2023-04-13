package io.github.aaejo.finder.client;

import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;

public record FinderClientResponse(int status, String location, Document document, String statusMessage,
        boolean isSuccess, Map<String, String> headers, Optional<Throwable> exception) {

    public FinderClientResponse(Document document) {
        this(document.connection().response().statusCode(),
                document.location(),
                document,
                document.connection().response().statusMessage(),
                (document.connection().response().statusCode() >= 200
                        && document.connection().response().statusCode() <= 299),
                document.connection().response().headers(),
                Optional.empty());
    }

    public FinderClientResponse(Response response, Page page) {
        this(response.status(),
                response.url(),
                Jsoup.parse(page.content(), page.url()),
                response.statusText(),
                response.ok(),
                response.headers(),
                Optional.empty());
    }

    public FinderClientResponse(Throwable exception) {
        this(-1, StringUtils.EMPTY, null, StringUtils.EMPTY, false, Map.of(), Optional.of(exception));
    }
}
