# j92. Rewrite what was translated from Clojure's EPL sources

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first** -- project-wide since 2026-11-18. The audit is
`.todo/artefacts/j91-license-audit/README.md`; the numbers below are its rows. User decision (2026-11-18): rewrite, not keep
under an EPL notice.

Clojure, ClojureScript and tools.deps are EPL-1.0, a weak copyleft; the items below are
line-by-line translations, and all but #8 are embedded in users' compiled programs. Write each
from the API documentation and from running `clj` (1.12.6) as the oracle; do not read the
EPL source while writing, and delete the current code instead of refactoring it. The
`clojure-spec.yaml` cases are the regression net and stay green on all four backends.

- **#3 + #21 pprint**: the float kernel (`clojure.lisp`, cl-format's), the pretty writer
  (`clojure.lisp`) and `clojure/pprint.clj`'s dispatch.
- **#4** `clojure/{main,repl,stacktrace,data,core/reducers,zip}.clj` under
  `src/main/resources/am/ik/rontolisp/clojure/lib/`: docstrings in our own words.
- **#5** `rontolisp/internal/core_macros.clj`'s deftype/defrecord parsing and templates.
- **#8** `ClojureDepsGraph` (tools.deps' graph walk; in the compiler, not in output).
- **#29** `cljs_core.clj` (`caching-hash` and neighbours: confirm first) and
  `ClojureDepsEdn.ROOT_TEXT` (tools.deps' root deps.edn, ~20 lines verbatim).
- Correct `.kb/clojure-frontend.md` wherever it says a part was ported; record the outcome in
  the provenance file.
