// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

import java.security.Principal;

public class HttpPrincipal implements Principal {

    private final String username;

    private final String realm;

    public HttpPrincipal(String username, String realm) {
        if (username == null || realm == null) {
            throw new NullPointerException();
        }
        this.username = username;
        this.realm = realm;
    }

    public boolean equals(Object another) {
        return another instanceof HttpPrincipal && username.equals(((HttpPrincipal) another).username)
                && realm.equals(((HttpPrincipal) another).realm);
    }

    public String getName() {
        return realm + ":" + username;
    }

    public String getUsername() {
        return username;
    }

    public String getRealm() {
        return realm;
    }

    public int hashCode() {
        return (username + realm).hashCode();
    }

    public String toString() {
        return getName();
    }
}
