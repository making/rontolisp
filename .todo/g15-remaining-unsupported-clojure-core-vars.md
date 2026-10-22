# g15. Remaining unsupported `clojure.core` vars

Difficulty: Medium

Presence probe over every public of `clojure.core` (the 679 names of
`ClojureCoreNames`, taken from `clj` 1.12.6): each name referenced once in
head position `(name)` and once as a value, `unknown name` from the lowering
marking the gap (scripts and raw results in
`.todo/artefacts/g15-clojure-core-presence-probe/`; latest re-run
2026-10-22: counted ok 675 of 677).

## Terminal dispositions (no further work)

- Documented refusals, must stay `unknown name` in both positions:
  `add-classpath`, `Inst`.
- Exempt with reasons recorded (`probe4.py` skips them in its gap counts):
  `PrintWriter-on`, `primitives-classnames`.

## Remaining gap

30 functions resolve in head position but answer `unknown name` as a value
(`(map isa? [1 2])` fails while `(isa? A B)` runs): `aget`, `alength`,
`ancestors`, `aset`, `await`, `await-for`, `descendants`, `ensure`,
`extend`, `extends?`, `get-method`, `instance?`, `isa?`, `list*`, `load`,
`make-array`, `make-hierarchy`, `methods`, `newline`, `parents`,
`pop-thread-bindings`, `prefer-method`, `prefers`, `push-thread-bindings`,
`remove-all-methods`, `remove-method`, `satisfies?`, `shutdown-agents`,
`struct`, `struct-map`. Tracked by `.todo/g39-core-function-value-arms.md`.

`deliver`/`promise` are head-only under their call-time refusal until
`.todo/g35-clojure-future-promise-deliver.md` lands, which resolves their
values with the primitives.

## Definition of done

This item is done when g39 (and g35 for the pair above) is done and a final
probe re-run reports every function in both positions, with the counted gap
still exactly the 2 documented refusals and the 2 exempts recorded.

## Non-goals

- Behavioral parity of the supported names beyond what existing tests pin.
