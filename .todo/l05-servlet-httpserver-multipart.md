# l05. servlet-httpserver: multipart/form-data

Difficulty: Medium

Follows k96 (`.kb/servlet-httpserver.md`). `getParts` / `getPart` throw
(`IllegalStateException` for a multipart request, `ServletException` otherwise) and
`ServletRegistration.Dynamic.setMultipartConfig` throws `UnsupportedOperationException`.

- A parser of RFC 7578 written here from the RFC (no container's parser is copied): the
  boundary, a part's fields (`Content-Disposition` with `name` / `filename`, also the
  `filename*` form, `Content-Type`), the body up to the next boundary, read as a stream.
- `Part` (`getInputStream`, `getSubmittedFileName`, `getSize`, the header members, `write`,
  `delete`) and the parts without a file name as parameters, in the request's encoding.
- `MultipartConfigElement`: the size limits (`IllegalStateException` past them) and the
  threshold above which a part goes to a file. Files need `java.nio.file` or `java.io.File`,
  which rontolisp's library has since 2026-11-21; a `wasi:http` component may have no
  directory to write to, so a part kept in memory must stay possible.
- Measure the room against Tomcat and Jetty in `ServletContainerTest`: a part with no
  `Content-Disposition`, a missing final boundary, the limits' exceptions, `getParameter` of a
  file part.
