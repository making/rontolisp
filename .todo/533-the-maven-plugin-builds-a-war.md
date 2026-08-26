# 533. `rontolisp-maven-plugin` builds a war

Difficulty: Medium (most of it already works by accident; the work is one flag,
the runtime classes landing in `target/classes`, a generated `web.xml`, and an
E2E that runs a real Maven build)

Child of `.todo/529`. Blocked by `.todo/530` (needs `runtime/RontoHttpServlet`
and the register-instead-of-serve arm).

## Why this is nearly free, and therefore worth doing properly

`AbstractLispCompileMojo` already compiles `src/main/lisp` into
`${project.build.outputDirectory}` -- `target/classes` -- with every JVM-backend
flag named exactly as the command line names it. `maven-war-plugin` copies
`target/classes` into `WEB-INF/classes` with no configuration at all. So a
`<packaging>war</packaging>` project whose Lisp compiled in servlet mode is
already 90% of a deployable war; what is missing is the mode flag, the runtime
class files, and `web.xml`.

That makes the Maven route the one a real deployment will take -- `-o app.war`
is the CLI convenience, this is how a war gets a version, a dependency list and
a CI pipeline. Treat it as the primary path in the docs, not the footnote.

## What lands

- **`rontolisp.servlet`** (`--servlet`, or whatever `.todo/530` named the mode)
  as a `@Parameter` on the compile mojo, sitting beside `simd` / `blas` /
  `noMain`. It selects `Features.JVM_SERVLET` and the register arm; the plugin
  keeps emitting loose `.class` files rather than an archive, because packaging
  is `maven-war-plugin`'s job.
- **The travelling runtime classes must reach `target/classes`.** The plugin
  already copies them there for other features (CLAUDE.md's `runtime` note names
  the plugin's `target/classes` as one of the three places the package travels
  into); `RontoHttpServlet` joins that copy in servlet mode.
- **`web.xml`**: generate it into `${project.build.directory}/rontolisp-web/`
  and have the docs point `maven-war-plugin`'s `webXml` at it, rather than
  writing into `src/main/webapp` -- generated output does not belong in a source
  tree, and a user who wants their own `web.xml` (a filter, a security
  constraint, a second servlet) must be able to write one and keep it. The
  generated file is a fallback, not a fixture: skip generation when
  `src/main/webapp/WEB-INF/web.xml` exists.
- **The `provided` dependency is the user's**, not the plugin's: a war project
  declares `jakarta.servlet-api` itself, the way every servlet project does.
  Document the coordinates and the version floor (6.0.0) rather than injecting
  them.

## Acceptance

- `MavenBuildE2eTest` (`-Drontolisp.plugin.e2e=true`) gains a `packaging=war`
  project: `src/main/lisp/app.lisp` with a `rontolisp:http-handler`, a
  `provided` servlet-api, `mvn package`, then the produced war deployed into
  embedded Tomcat and driven. It is the only test that proves the whole chain --
  plugin, war plugin, container -- holds together.
- `rontolisp-maven-plugin`'s README and `doc/{en,ja}` gain the war project
  layout, mirrored. The `install -DskipTests` prerequisite (CLAUDE.md) applies
  as it does to every plugin change.
- A war project that forgets `<packaging>war</packaging>`, or compiles without
  the servlet flag, fails with a message that says which one is missing --
  otherwise the symptom is a war that deploys and 404s.
