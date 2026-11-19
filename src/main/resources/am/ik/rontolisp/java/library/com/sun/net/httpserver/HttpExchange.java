// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

public abstract class HttpExchange implements AutoCloseable {

    protected HttpExchange() {
    }

    public abstract Headers getRequestHeaders();

    public abstract Headers getResponseHeaders();

    public abstract URI getRequestURI();

    public abstract String getRequestMethod();

    public abstract HttpContext getHttpContext();

    public abstract void close();

    public abstract InputStream getRequestBody();

    public abstract OutputStream getResponseBody();

    public abstract void sendResponseHeaders(int rCode, long responseLength) throws IOException;

    public abstract InetSocketAddress getRemoteAddress();

    public abstract int getResponseCode();

    public abstract InetSocketAddress getLocalAddress();

    public abstract String getProtocol();

    public abstract Object getAttribute(String name);

    public abstract void setAttribute(String name, Object value);

    public abstract void setStreams(InputStream i, OutputStream o);
}
