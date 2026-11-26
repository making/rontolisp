// Shiori: a whole page -- a template inside the layout.
package com.example.shiori.web;

import java.io.StringWriter;

import com.samskivert.mustache.Template;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

// A flash message is shown once and its cookie cleared in the same response. When htmx
// expected a fragment -- an in-place update that met a missing book or an error -- the
// page is swapped in for the whole body instead of into the fragment's place.
public final class Page {

    private final Template template;

    private final Object model;

    private String title = "";

    private Nav nav;

    private int status = 200;

    private Page(Template template, Object model) {
        this.template = template;
        this.model = model;
    }

    public static Page of(Template template, Object model) {
        return new Page(template, model);
    }

    public Page title(String title) {
        this.title = title;
        return this;
    }

    public Page nav(Nav nav) {
        this.nav = nav;
        return this;
    }

    public Page status(int status) {
        this.status = status;
        return this;
    }

    // What the layout shows around a page: its title, the flash message, the assets,
    // which navigation link is the current page.
    private record Layout(String title, String flash, String stylesheet, String script, boolean navHome,
            boolean navLibrary, boolean navNew) {
    }

    public ServerResponse response(ServerRequest request) {
        String flash = Html.flash(request);
        Layout layout = new Layout(this.title, flash, Assets.STYLESHEET_URL, Assets.SCRIPT_URL, this.nav == Nav.HOME,
                this.nav == Nav.LIBRARY, this.nav == Nav.NEW);
        ServerResponse.BodyBuilder response = Html.status(this.status);
        if (flash != null) {
            response.header("Set-Cookie", Html.flashCleared());
        }
        if (Requests.partial(request)) {
            response.header("HX-Retarget", "body").header("HX-Reswap", "innerHTML");
        }
        StringWriter body = new StringWriter();
        this.template.execute(this.model, layout, body);
        return response.body(body.toString());
    }

}
