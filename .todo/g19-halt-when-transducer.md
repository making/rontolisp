# g19. `halt-when` transducer

Difficulty: Small-Medium

`halt-when` is the only transducer the front end still refuses by name
(`ClojureCoreLowering.java`: `halt-when is not supported yet: its transducer
has no lowering`; every other transducer verb -- `transduce`, `eduction`,
`sequence`, `completing`, `take-nth` -- lowers via
`ClojureTransducerLowering`). The rest of the machinery it needs
(`reduced` values, early termination of `transduce`/`sequence`/`eduction`)
already exists; only the `halt-when` wrapper itself has no lowering.

## Definition of done

- `(transduce (halt-when pred) ...)` / `(halt-when pred retf)` lowers and runs
  on all four backends, oracle-identical (short-circuits with the completing
  value, calls `retf` on early termination like the oracle), pinned by
  `clojure-spec.yaml` cases alongside the existing transducer pins.
- `halt-when` as a value (`#'...-v` shape, like the other transducer verbs)
  works too.
- `doc/en/clojure/semantics.md` (transducers section) + `doc/ja/` updated in
  the same commit if they name the refusal.

## Non-goals

- Changing `eduction`'s computed-once semantics (strict-over-strict,
  lazy-over-lazy) -- that is a separate, larger deviation with its own
  trade-offs, not this item.
- `streaming reduction` internals (`enumeration-seq`, `resultset-seq`) --
  host-boundary refusals, out of scope.
