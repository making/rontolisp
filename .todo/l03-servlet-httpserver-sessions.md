# l03. servlet-httpserver: sessions

Difficulty: Medium

Follows k96 (`.kb/servlet-httpserver.md`). The container has no `HttpSession`:
`getSession(true)` throws `UnsupportedOperationException`, `getSession(false)` is `null`, and
the context's session members refuse or answer empty.

- An in-memory `HttpSession` (attributes, creation and last access times, `invalidate`, the
  maximum inactive interval and `ServletContext.setSessionTimeout`), found by the cookie
  (`SessionCookieConfig`: name, path, `HttpOnly`, `Secure`, attributes; default `JSESSIONID`).
  Cookie tracking only unless URL rewriting is asked for (`encodeURL` is the identity today).
- `changeSessionId`, `getRequestedSessionId` and the three `isRequestedSessionId..` members,
  `HttpSession.getAccessor()` (6.1).
- The listeners `addListener` refuses today: `HttpSessionListener`,
  `HttpSessionAttributeListener`, `HttpSessionIdListener`, `HttpSessionBindingListener`.
- Expiry needs a clock and something that runs it. A timer thread is the JVM's way; a compiled
  `wasi:http` component runs only while it has a request in hand and may be dropped after 1 s
  idle (`.kb/java-frontend.md`, "com.sun.net.httpserver": an instance's state lasts a few
  requests), so measure what a session in the instance's memory is worth there before designing
  a store; expiring on access needs no thread.
- The session id needs random octets: `SecureRandom` is not in rontolisp's library yet
  (`.todo/k99`), so say what the compiled side uses.
- Measure the room against Tomcat and Jetty in `ServletContainerTest` as k96 did (the cookie's
  attributes, the id after `changeSessionId`, an invalid id sent by the client), and add the
  rows to the note's table.
