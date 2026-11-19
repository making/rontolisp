// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

import java.net.URI;
import java.util.List;
import rontolisp.internal.HttpServing;

public interface Request {

    URI getRequestURI();

    String getRequestMethod();

    Headers getRequestHeaders();

    default Request with(String headerName, List<String> headerValues) {
        return HttpServing.with(this, headerName, headerValues);
    }
}
