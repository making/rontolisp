# c55. Clojure `*ns*`, `*file*`, `*source-path*`, `*repl*` and `*1`/`*2`/`*3`/`*e` have no value

Difficulty: Medium

The other `clojure.core` specials have their oracle value (`ClojureCoreSpecials`); these
are still unknown names, `#'*ns*` is `var of a clojure.core var is not supported yet`, and
`(set! *ns* x)` answers `x` with no effect (2026-10-03). The oracle (`clj` 1.12.6,
`clj -M file`): `*ns*` is a Namespace object printing `#object[clojure.lang.Namespace 0x.. user]`
(`str` `"user"`, `ns-name` the symbol), changed by `ns`/`in-ns` while the file loads;
`*file*` the script's path, `*source-path*` its file name; `*repl*` false (true in the REPL);
`*1`..`*3`/`*e` nil in a file and the last results / exception in the REPL. All are
thread-bound at the root under `clojure.main` except `*repl*`.

Plan: a namespace value kind (printer `#object[clojure.lang.Namespace user]` without the
hash, `str`, `ns-name`, `the-ns`, `find-ns`) read lexically per top-level form; `*file*`
from the reader's file (`NO_SOURCE_PATH` in the REPL); the REPL echo rotating
`*1`/`*2`/`*3` and setting `*e`. Pin in clojure-spec and `ClojureSessionTest`.
