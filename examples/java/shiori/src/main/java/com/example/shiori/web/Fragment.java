// Shiori: a piece of a page, for htmx to swap in.
package com.example.shiori.web;

import com.samskivert.mustache.Template;

import org.springframework.web.servlet.function.ServerResponse;

public final class Fragment {

    private final Template template;

    private final Object model;

    private int status = 200;

    private String toast;

    private Fragment(Template template, Object model) {
        this.template = template;
        this.model = model;
    }

    public static Fragment of(Template template, Object model) {
        return new Fragment(template, model);
    }

    // No body: what a form swaps nothing on (hx-status:422), or a removal (hx-swap="delete").
    public static Fragment empty() {
        return new Fragment(null, null);
    }

    public Fragment status(int status) {
        this.status = status;
        return this;
    }

    // The toast the change raises on the page.
    public Fragment toast(String message) {
        this.toast = message;
        return this;
    }

    public ServerResponse response() {
        ServerResponse.BodyBuilder response = Html.status(this.status);
        if (this.toast != null) {
            response.header("HX-Trigger", Html.toastTrigger(this.toast));
        }
        return response.body((this.template == null) ? "" : this.template.execute(this.model));
    }

}
