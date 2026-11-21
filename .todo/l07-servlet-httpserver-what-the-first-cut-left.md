# l07. servlet-httpserver: what the first cut left

Difficulty: Medium

Follows k96 (`.kb/servlet-httpserver.md`, "Not there" and the table of measured room). Each
is made, or measured and decided with the numbers in that note.

- **Error pages and the ERROR dispatch**: the Servlet API has no programmatic registration of
  an error page, so this needs a member of `ServletHttpServer` (status or exception type to a
  path) until `web.xml` is read (`.todo/l06`); `sendError` then stops committing at once
  (today `Response.error` writes the page and ends the response) and the `jakarta.servlet.error.*`
  attributes are set.
- **An exception after the commit** ends the response as if it were whole (the chunked
  stream is closed normally); Tomcat cuts the connection so that the client sees the
  failure. `HttpExchange` has no abort: measure what closing the exchange without closing
  the chunked stream does on JDK 25 and on rontolisp's transports.
- **Security**: `setServletSecurity` throws; `authenticate` / `login` throw
  `ServletException`; `isUserInRole` is false. Decide what an embedded container owes here
  (a filter of the application's own covers most uses).
- **HTTPS**: `HttpsServer` with an `SSLContext`, then `getScheme()` / `isSecure()` /
  the server port default. rontolisp has no `HttpsServer` (`.todo/050`), so the class must
  not be named on the path a plain server takes.
- **Limits and logging**: the form limit is a constant (`Request.MAX_FORM_SIZE`, 2 MiB) and
  `ServletContext.log` writes to `System.err`; give both a member of `ServletHttpServer`.
- **Trailers, `upgrade`, `PushBuilder`**: refused or absent; `HttpExchange` offers none.
- **A second context on one server** and a context root served without the redirect.
- **`stop`** destroys the servlets after waiting `stop(seconds)` for the container's own
  pool; with an executor of the caller's nothing is waited for.
- **The move to a repository of its own** (user decision, k96): the directory, its workflow
  (`.github/workflows/servlet-httpserver.yaml`), the `.kb` note, the `servlet-httpserver/`
  prefix in `PathCitationTest`, publishing to Maven Central under `am.ik.servlet`.
- **Performance** was not measured: the writer encodes each `write` through
  `String.getBytes`, and a request allocates its maps eagerly in places.
