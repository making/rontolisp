# 533. `rontolisp-maven-plugin` builds a war

Difficulty: Low (once `.todo/530` exists this is one mojo parameter and two
files landing in `target/classes`. The E2E is the only real work)

Child of `.todo/529`. Blocked by `.todo/530` (needs the two adapter classes and
the register-instead-of-serve arm).

## Why this is nearly free, and therefore worth doing properly

`AbstractLispCompileMojo` already compiles `src/main/lisp` into
`${project.build.outputDirectory}` -- `target/classes` -- with every JVM-backend
flag named exactly as the command line names it. `maven-war-plugin` copies
`target/classes` into `WEB-INF/classes` with no configuration at all. And
because `.todo/530` registers through a `ServletContainerInitializer` rather
than a `web.xml`, EVERYTHING the war needs is a file in that directory:

```
target/classes/App.class
target/classes/am/ik/rontolisp/runtime/*.class
target/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer
```

So a `<packaging>war</packaging>` project whose Lisp compiled in servlet mode is
a deployable war with no `webXml` configuration, no generated file in a source
tree, and nothing for the user to wire up. That is what makes this the route a
real deployment takes -- `-o app.war` is the CLI convenience, this is how a war
gets a version, a dependency list and a CI pipeline. Treat it as the primary
path in the docs, not the footnote.

## What lands

- **`rontolisp.servlet`** (`--servlet`, or whatever `.todo/530` named the mode)
  as a `@Parameter` on the compile mojo, sitting beside `simd` / `blas` /
  `noMain`. It selects `Features.JVM_SERVLET` and the register arm; the plugin
  keeps emitting loose `.class` files rather than an archive, because packaging
  is `maven-war-plugin`'s job.
- **The travelling runtime classes and the service file must reach
  `target/classes`.** The plugin already copies the runtime classes there for
  other features (CLAUDE.md's `runtime` note names the plugin's `target/classes`
  as one of the three places the package travels into); in servlet mode the two
  adapter classes and the one-line service file join them.
- **The `provided` dependency is the user's**, not the plugin's: a war project
  declares `jakarta.servlet-api` itself, the way every servlet project does.
  Document the coordinates and the version floor (6.0.0) rather than injecting
  them.
- **A user's own `web.xml` stays theirs.** `src/main/webapp/WEB-INF/web.xml` is
  picked up by `maven-war-plugin` as usual and does not disturb the initializer
  -- verified in the `.todo/529` spike against both `metadata-complete="true"`
  and `<absolute-ordering/>`. The plugin writes no `web.xml` and needs no
  opinion about one.

## Acceptance

- `MavenBuildE2eTest` (`-Drontolisp.plugin.e2e=true`) gains a `packaging=war`
  project: `src/main/lisp/app.lisp` with a `rontolisp:http-handler`, a
  `provided` servlet-api, `mvn package`, then the produced war deployed into an
  embedded container and driven. It is the only test that proves the whole
  chain -- plugin, war plugin, container -- holds together, and the pom in it is
  the thing the docs should show.
- `rontolisp-maven-plugin`'s README and `doc/{en,ja}` gain the war project
  layout, mirrored. The `install -DskipTests` prerequisite (CLAUDE.md) applies
  as it does to every plugin change.
- A war project that forgets `<packaging>war</packaging>`, or compiles without
  the servlet flag, fails with a message that says which one is missing --
  otherwise the symptom is a war that deploys and 404s.
