// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A filter runs around the rest of a context's filters and its handler: the order,
// and what a chain run a second time does, are a JDK 25's answers to a probe.
package com.sun.net.httpserver;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import rontolisp.internal.HttpServing;

public abstract class Filter {

    protected Filter() {
    }

    /** The filters after one, and the handler after the last. */
    public static class Chain {

        private final Iterator<Filter> rest;

        private final HttpHandler handler;

        public Chain(List<Filter> filters, HttpHandler handler) {
            this.rest = filters.iterator();
            this.handler = handler;
        }

        public void doFilter(HttpExchange exchange) throws IOException {
            if (rest.hasNext()) {
                rest.next().doFilter(exchange, this);
            }
            else {
                handler.handle(exchange);
            }
        }
    }

    public abstract void doFilter(HttpExchange exchange, Chain chain) throws IOException;

    public abstract String description();

    public static Filter beforeHandler(String description, Consumer<HttpExchange> operation) {
        if (description == null || operation == null) {
            throw new NullPointerException();
        }
        return new Around(description, operation, null, null);
    }

    public static Filter afterHandler(String description, Consumer<HttpExchange> operation) {
        if (description == null || operation == null) {
            throw new NullPointerException();
        }
        return new Around(description, null, operation, null);
    }

    public static Filter adaptRequest(String description, UnaryOperator<Request> requestOperator) {
        if (description == null || requestOperator == null) {
            throw new NullPointerException();
        }
        return new Around(description, null, null, requestOperator);
    }

    /** What the three makers above make: one thing done before, after, or to the request. */
    private static final class Around extends Filter {

        private final String description;

        private final Consumer<HttpExchange> before;

        private final Consumer<HttpExchange> after;

        private final UnaryOperator<Request> adapting;

        Around(String description, Consumer<HttpExchange> before, Consumer<HttpExchange> after,
                UnaryOperator<Request> adapting) {
            this.description = description;
            this.before = before;
            this.after = after;
            this.adapting = adapting;
        }

        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            if (before != null) {
                before.accept(exchange);
            }
            chain.doFilter(adapting == null ? exchange : HttpServing.adapted(exchange, adapting.apply(exchange)));
            if (after != null) {
                after.accept(exchange);
            }
        }

        public String description() {
            return description;
        }
    }
}
