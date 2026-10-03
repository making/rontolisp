# c47. Clojure `#'` of a `clojure.core` var is refused

Difficulty: Medium

`(var *out*)`, `#'inc`, `(thread-bound? #'*out*)` all answer `var of a clojure.core var is not supported yet`
(`ClojureVarLowering.varOf`, 2026-10-03). The oracle (`clj` 1.12.6) answers a var for each:
`(thread-bound? #'*out*)` is `true` (clojure.main binds the stream specials), `(var? #'inc)` is `true`,
`(meta #'inc)` carries `:name`/`:ns`/`:arglists`/`:doc`.

The stream and flag specials (`*out*`, `*in*`, `*err*`, `*ns*`, `*print-length*`, ...) are the part with a
runtime value to read and a binding depth (`thread-bound?` is `true` for them at depth zero in `clj`, since
`clojure.main` pushes bindings); functions would need a value per core name (`ClojureCoreNames`). Decide the
split (specials first), keep `#'x` of a user var byte-identical, pin on all four backends and update
`doc/{en,ja}/clojure/reference/thread-bound-p.md`, which says the limit.
