# l06. servlet-httpserver: discovery (annotations, web.xml, initializers) and static resources

Difficulty: High

Follows k96 (`.kb/servlet-httpserver.md`). Everything is registered in code through
`ServletContext`; nothing is discovered and no file is served.

- **Static resources**: a resource base (a directory, or the class path), the members that
  answer `null` today (`getResource`, `getResourceAsStream`, `getResourcePaths`,
  `getRealPath`), a default servlet at `/` (media type from `MimeTypes`, `Last-Modified` /
  `If-Modified-Since`, HEAD, ranges if asked), welcome files. This part needs no reflection
  and is the first to do.
- **`ServletContainerInitializer`** through `ServiceLoader` (rontolisp reads a root's
  `META-INF/services`: `.kb/java-frontend.md`, "Resources"), with `@HandlesTypes` left out
  unless class scanning comes.
- **Annotations** (`@WebServlet`, `@WebFilter`, `@WebListener`, `@WebInitParam`,
  `@MultipartConfig`, `@ServletSecurity`) and **`web.xml`** (servlets, filters, mappings,
  listeners, context parameters, error pages, the locale-encoding mapping, session
  configuration; an XML parser the JVM has and the compiled side may not). On the JVM both
  are reflection over named classes; compiled by rontolisp there is no reflection
  (`.todo/k89`), so decide there: a step at build time that turns the annotations and the
  descriptor into registration code is the lead, and the container then needs only a hook to
  run it. Keep `Instances` the one place a class becomes an instance.
- A WAR or an exploded directory as the application (`WEB-INF/classes`, `WEB-INF/lib`, a class
  loader of its own) is the JVM-only end of this; say whether it is wanted at all.
