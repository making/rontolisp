# f75. Clojure: the remaining `clojure.core` vars with no lowering

Difficulty: Medium

Follow-up of `.todo/f67` (done 2026-10-13: bit operations, the seq/printing backlog and the
answerable namespace group; host-only refused by name). Still `unknown name`:

| group | names |
|---|---|
| checked arithmetic | `inc'` `dec'` `+'` `-'` (integers are bignums here, so these are `inc`/`dec`/`+`/`-` with checks that never fire) `cast` (host classes exist on no wasm backend) |
| refs, multimethods, protocols, structs | `ref-history-count` `ref-max-history` `ref-min-history` `prefers` `remove-all-methods` `extenders` `find-protocol-impl` `find-protocol-method` `create-struct` `accessor` |

Out of scope (`.todo/f57`, parallel): the array constructors and
`parse-long`/`parse-double`/`parse-boolean`.

Possibly revisitable: `get-thread-bindings`/`bound-fn*` (need a run-time binding registry),
`read+string` (needs a position-tracking reader), `load-reader` (refuses at run time),
`var-set` of a thread-bound var (always refuses).
