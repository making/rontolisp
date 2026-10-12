# f67. Clojure: the `clojure.core` vars with no lowering (bit operations first)

Difficulty: Medium

A census of `ClojureCoreNames.NAMES` (the oracle's 679 publics) on 2026-10-12, each lowered as
`(fn [a b c] (NAME a b))`: 150 non-macro names are `unknown name`. The probe libraries name some
of them (`.kb/clojure-frontend.md`, "Library probes"): malli `bit-and`/`bit-shift-left`/
`bit-shift-right`/`bounded-count`/`supers`/`ns-publics`/`ns-interns`/`all-ns`/`load-string`;
tools.reader `bit-and`/`ns-aliases`/`ns-unmap`/`print-dup`/`read+string`; edamame and honeysql
`alias`.

Out of scope here: the array constructors and `parse-long`/`parse-double`/`parse-boolean`
(`.todo/f57`).

| group | names |
|---|---|
| bit operations | `bit-and` `bit-or` `bit-xor` `bit-and-not` `bit-not` `bit-shift-left` `bit-shift-right` `unsigned-bit-shift-right` `bit-set` `bit-clear` `bit-flip` `bit-test` |
| seqs | `reductions` `random-sample` `bounded-count` `partitionv` `partitionv-all` `splitv-at` `replicate` `halt-when` `xml-seq` `enumeration-seq` `resultset-seq` |
| numbers | `inc'` `dec'` `+'` `-'` (integers are bignums here) `cast` |
| printing | `printf` `char-escape-string` `char-name-string` `print-simple` `print-dup` `print-ctor` `munge` `namespace-munge` |
| concurrency | `promise` `deliver` `future-call` `pcalls` `seque` `await1` `send-via` `release-pending-sends` `set-agent-send-executor!` `set-agent-send-off-executor!` `tap>` `add-tap` `remove-tap` |
| vars and namespaces | `var-set` `with-redefs-fn` `intern` `create-ns` `remove-ns` `alias` `refer` `ns-publics` `ns-interns` `ns-map` `ns-refers` `ns-aliases` `ns-imports` `ns-unmap` `ns-unalias` `all-ns` `loaded-libs` `requiring-resolve` `get-thread-bindings` `bound-fn*` `load-string` `load-file` `load-reader` `compile` `read+string` |
| refs, multimethods, protocols, structs | `ref-history-count` `ref-max-history` `ref-min-history` `prefers` `remove-all-methods` `extenders` `find-protocol-impl` `find-protocol-method` `create-struct` `accessor` |
| host-only or internal | `bean` `supers` `bases` `clojure-version` `destructure` `proxy-*` `construct-proxy` `init-proxy` `update-proxy` `get-proxy-class` `method-sig` `chunk*` `stream-*` `->ArrayChunk` ... |

## What decides the design

- The bit operations are the oracle's on longs: two's complement over 64 bits, a shift count
  masked to 6 bits (`(bit-shift-left 1 64)` is 1), `unsigned-bit-shift-right` over the unsigned
  64-bit value, and a refusal for a double or a `BigInt` (`bit operation not supported for:
  class java.lang.Double`) -- every backend, CL `logand`/`ash` over the wrapped value.
- Each group is a backlog verb (`ClojureCoreLowering`: worker + `-v` value) unless it needs
  run-time namespaces (`ns-publics` ...), which the lowering answers only for what it knows.

## Plan

1. Bit operations first (malli, tools.reader): clojure-spec rows oracle-identical, four backends.
2. Then the seq and printing groups; then the namespace group, as far as a lowered program can
   answer it; leave the host-only ones refused by name.
3. User docs en + ja per verb.
