# j95. Provenance leftovers the 2026-11-18 audit did not list

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` first.** Found while working j92; neither is in
the tables of `.todo/artefacts/j91-license-audit/README.md`.

- **The rest of the `deps.edn` classes** (compiler only, not embedded): `ClojureDepsEdn`
  (reading, the validation walk, the alias merge, `argMap`, `tool`), `ClojureDepsProcurer`,
  `ClojureBasis` and `eval/ClojureDepsRepositories` cite tools.deps' private function names
  in their Javadoc (`chase-key`, `merge-edns`, `remote-repos`, `repo-policy`,
  `detect-manifest`, `model-deps`, `find-pom`, `get-build-helper-paths`, `read-model-file`,
  `create-basis`, `deps-map`, `tool`), `.kb/clojure-frontend.md` "deps.edn" records that
  tools.deps (EPL-1.0) was read from the CLI jar in October 2026, and
  `.kb/maven-resolver.md` says `:update` was "read from its source". The audit compared
  them and called only the selection walk a translation, which j92 rewrote. Decide per
  class: where the behaviour is documented or can be asked of `clj` and the code has its
  own shape, keep the code and drop the private names; where a body follows the source
  function for function, delete it and write it the j92 way (the published reference,
  `clj` as a black box, the old code run beside it).
- **The Clojure HTTP client's redirects** (`clojure.lisp`: `%clojure-http-split`,
  `-normalize`, `-resolve`, `-redirect`; embedded): `.kb/clojure-frontend.md` "HTTP
  client" says `follow` "is the JDK's `RedirectFilter`" and that `URI.resolve` was
  "ported", and names JDK internals (`++hops < 5`, `ALLOWED_REDIRECT_HEADERS`). The code
  reads like the steps `java.net.URI`'s API documentation states for `resolve` and
  `normalize`; establish whether OpenJDK's source (GPLv2 with the Classpath exception) was
  the model. If it was, rewrite from the API documentation, RFC 3986 section 5 and a
  JDK's answers (the j91 way); if not, correct the wording and say what it was built from.
