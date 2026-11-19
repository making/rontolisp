// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The one server there is serves through the transport of the target
// (rontolisp.internal.HttpServing): what configures a connection, a filter or an
// authenticator, has no member here.
package com.sun.net.httpserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import rontolisp.internal.HttpServing;

public abstract class HttpServer {

    protected HttpServer() {
    }

    public static HttpServer create() throws IOException {
        return create(null, 0);
    }

    public static HttpServer create(InetSocketAddress addr, int backlog) throws IOException {
        HttpServer server = HttpServing.server();
        if (addr != null) {
            server.bind(addr, backlog);
        }
        return server;
    }

    public abstract void bind(InetSocketAddress addr, int backlog) throws IOException;

    public abstract void start();

    public abstract void setExecutor(Executor executor);

    public abstract Executor getExecutor();

    public abstract void stop(int delay);

    public abstract HttpContext createContext(String path, HttpHandler handler);

    public abstract HttpContext createContext(String path);

    public abstract void removeContext(String path) throws IllegalArgumentException;

    public abstract void removeContext(HttpContext context);

    public abstract InetSocketAddress getAddress();
}
