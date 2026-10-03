# c53. Clojure `*err*`, the `clojure.main` flag specials and `with-in-str` are unknown

Difficulty: Medium

Only `*out*`, `*in*` and `*agent*` have a value (2026-10-03). `(binding [*out* *err*] ...)`
fails with `unknown name: *err*`; `*print-length*`, `*print-level*`, `*print-meta*`,
`*ns*`, `*warn-on-reflection*`, `*assert*`, ... are unknown names as values, and `binding`
refuses them (`needs a ^:dynamic var`), so `#'*print-length*` stays refused too.
`with-in-str` is an unknown name. The oracle (`clj` 1.12.6) has all of them;
`clojure.main` binds the flags around a script (`thread-bound?` true), not the streams.

Plan: `*err*` as `*error-output*` (a third stream alias beside `*out*`/`*in*`, with its
binding-depth counter in the `STREAM_DEPTH` family); decide per flag whether the printer
honours it (`*print-length*`/`*print-level*` map onto the CL printer variables) or it is a
plain value; `with-in-str` over a string input stream binding `*in*`. Pin in clojure-spec.
