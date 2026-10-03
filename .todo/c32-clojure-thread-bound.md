# c32. Clojure `thread-bound?` is an unknown name

Difficulty: Low

`(thread-bound? #'*out*)` answers `unknown name: thread-bound?` (2026-10-03). The oracle (`clj` 1.12.6):
true while a `binding` of every given var is in effect, else false; a non-var signals.

A `^:dynamic` var keeps a `%bound-depth` counter special (`.kb/clojure-frontend.md`, "State"), but a var
value `(:C%VAR "ns/name" getter)` carries no link to it. Either the var site passes a depth reader (cost on
every `#'` site of a dynamic var only) or a literal `#'x` argument lowers to the counter directly. Measure
the wasm size of a program with `#'` but no `thread-bound?` before and after. Pin on all four backends and
add a `doc/{en,ja}/clojure/reference` page beside the other predicates (`predicates.md`).
