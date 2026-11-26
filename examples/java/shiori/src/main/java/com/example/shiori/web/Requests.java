// Shiori: what a request says beyond its route -- whether htmx sent it, and the id of
// its path.
package com.example.shiori.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import am.ik.yavi.core.StringCoercion;
import am.ik.yavi.core.Validated;
import am.ik.yavi.core.ValueValidator;
import am.ik.yavi.validator.Yavi;

import org.springframework.web.servlet.function.ServerRequest;

public final class Requests {

    private Requests() {
    }

    // What a form or a query string sends is text, trimmed.
    public static final StringCoercion FORM = StringCoercion.strict().trimming();

    // An id in a path is text: the digits of a positive number, at most 18 of them.
    private static final ValueValidator<String, Long> ID = Yavi
        ._string("id", c -> c.lessThanOrEqual(18).pattern("0*[1-9][0-9]*"))
        .map(Long::valueOf);

    // The id of the path; none when it is not one, so there is no such page.
    public static Optional<Long> id(ServerRequest request) {
        return switch (ID.validate(request.pathVariable("id"))) {
            case Validated.Valid<Long>(Long id) -> Optional.of(id);
            case Validated.Invalid<Long> refused -> Optional.empty();
        };
    }

    public static boolean htmx(ServerRequest request) {
        return request.headers().firstHeader("HX-Request") != null;
    }

    // The id of the element htmx swaps the answer into. htmx 4 names the target as
    // tag#id (div#shelf); only the id is the page's contract.
    public static String htmxTargetId(ServerRequest request) {
        String target = request.headers().firstHeader("HX-Target");
        int hash = (target == null) ? -1 : target.indexOf('#');
        return (hash < 0) ? null : URLDecoder.decode(target.substring(hash + 1), StandardCharsets.UTF_8);
    }

    // Whether htmx swaps the answer into a part of the page, not the whole body: a
    // fragment is expected.
    static boolean partial(ServerRequest request) {
        return "partial".equals(request.headers().firstHeader("HX-Request-Type"));
    }

}
