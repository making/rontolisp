// Shiori: where a POST sends the browser next.
package com.example.shiori.web;

import java.net.URI;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

// A browser gets 303 and Location, so a reload does not post again. htmx follows a
// redirect inside its fetch, where it cannot see it, so it gets HX-Location instead and
// navigates itself. Either way the message rides in a cookie the next page shows once.
public final class Redirect {

    private final String location;

    private String flash;

    private Redirect(String location) {
        this.location = location;
    }

    public static Redirect to(String location) {
        return new Redirect(location);
    }

    public Redirect flash(String message) {
        this.flash = message;
        return this;
    }

    public ServerResponse response(ServerRequest request) {
        boolean htmx = Requests.htmx(request);
        ServerResponse.BodyBuilder response = htmx ? ServerResponse.ok().header("HX-Location", this.location)
                : ServerResponse.seeOther(URI.create(this.location));
        if (this.flash != null) {
            response.header("Set-Cookie", Html.flashCookie(this.flash));
        }
        return htmx ? response.body("") : response.build();
    }

}
