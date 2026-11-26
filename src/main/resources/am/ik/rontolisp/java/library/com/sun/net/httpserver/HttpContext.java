// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

import java.util.List;
import java.util.Map;

public abstract class HttpContext {

    protected HttpContext() {
    }

    public abstract HttpHandler getHandler();

    public abstract void setHandler(HttpHandler handler);

    public abstract String getPath();

    public abstract HttpServer getServer();

    public abstract Map<String, Object> getAttributes();

    public abstract List<Filter> getFilters();

    public abstract Authenticator setAuthenticator(Authenticator auth);

    public abstract Authenticator getAuthenticator();
}
