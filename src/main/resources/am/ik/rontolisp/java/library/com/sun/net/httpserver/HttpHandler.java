// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

import java.io.IOException;

public interface HttpHandler {

    void handle(HttpExchange exchange) throws IOException;
}
