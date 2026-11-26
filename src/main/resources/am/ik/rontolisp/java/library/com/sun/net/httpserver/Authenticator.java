// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

public abstract class Authenticator {

    protected Authenticator() {
    }

    public abstract static class Result {

        protected Result() {
        }
    }

    /** The request is refused with a status. */
    public static class Failure extends Result {

        private final int responseCode;

        public Failure(int responseCode) {
            this.responseCode = responseCode;
        }

        public int getResponseCode() {
            return responseCode;
        }
    }

    /** The request is from someone known. */
    public static class Success extends Result {

        private final HttpPrincipal principal;

        public Success(HttpPrincipal p) {
            this.principal = p;
        }

        public HttpPrincipal getPrincipal() {
            return principal;
        }
    }

    /** The request is to be sent again, with what the response's fields ask for. */
    public static class Retry extends Result {

        private final int responseCode;

        public Retry(int responseCode) {
            this.responseCode = responseCode;
        }

        public int getResponseCode() {
            return responseCode;
        }
    }

    public abstract Result authenticate(HttpExchange exch);
}
