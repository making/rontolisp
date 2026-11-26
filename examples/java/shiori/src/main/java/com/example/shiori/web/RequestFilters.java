// Shiori: what runs around every route.
package com.example.shiori.web;

import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class RequestFilters {

    private RequestFilters() {
    }

    // A form is read as UTF-8, as the pages are written: a container whose default for a
    // form is ISO-8859-1 (Tomcat) would read its fields wrong otherwise.
    public static ServerResponse utf8(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        request.servletRequest().setCharacterEncoding("UTF-8");
        return next.handle(request);
    }

    // Against cross-site request forgery: a page on another site can make the visitor's
    // browser post here. There is no login to borrow, but the post still reaches an app
    // the visitor can reach and that site cannot (on localhost, on a LAN). A POST from
    // another origin gets a 403 before any handler runs.
    public static ServerResponse sameOrigin(ServerRequest request, HandlerFunction<ServerResponse> next)
            throws Exception {
        HttpMethod method = request.method();
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method) || HttpMethod.OPTIONS.equals(method)
                || fromThisSite(request)) {
            return next.handle(request);
        }
        return new ErrorPage(403, "別のサイトからの送信です", "栞の画面から操作してください。").response(request);
    }

    // False when a browser says another site sent the request. Sec-Fetch-Site is the
    // browser's own word on it; a browser too old to send it still sends Origin, compared
    // with Host. A request carrying neither came from no browser page (curl, a script) and
    // has no visitor's browser to abuse.
    private static boolean fromThisSite(ServerRequest request) {
        String site = request.headers().firstHeader("Sec-Fetch-Site");
        if (site != null) {
            return site.equals("same-origin") || site.equals("none");
        }
        String origin = request.headers().firstHeader("Origin");
        if (origin != null) {
            int authority = origin.indexOf("://");
            String host = request.headers().firstHeader("Host");
            return authority >= 0 && host != null && origin.substring(authority + 3).equals(host);
        }
        return true;
    }

    // Anything a handler did not foresee becomes the 500 page; what happened goes to the
    // log rather than to the visitor.
    public static ServerResponse failed(Throwable ex, ServerRequest request) {
        System.err.println(request.method() + " " + request.path() + ": " + ex);
        return new ErrorPage(500, "頁を開けませんでした", "データベースに接続できるか確かめてから、もう一度開いてください。").response(request);
    }

}
