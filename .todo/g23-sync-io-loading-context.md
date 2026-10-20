# g23. `sync`/`io!`/`with-loading-context` body wrappers (slice of `.todo/g15`)

Difficulty: Small

`sync`, `io!` and `with-loading-context` are the three of the `.todo/g15`
presence gap whose oracle semantics need no new machinery here: transactions
run one at a time under a single lock (see `doc/en/clojure/deviations.md`),
so `sync`'s "run in a transaction if not already" is its body behind the same
discipline the refs section already lowers, and `io!`'s "fail inside a
transaction" is its body plus the in-transaction check against that lock.
`with-loading-context` is its body as-is (loading has no separate context to
thread through). Where refs are unsupported the body still runs, per the `g15`
DoD.

## Definition of done

- All three lower and run on all four backends, pinned by
  `clojure-spec.yaml` cases: `sync` runs the body and answers its value,
  `io!` runs the body outside a transaction and refuses inside one in the
  oracle's wording, `with-loading-context` runs the body.
- Values (`(def x sync)`) come via `.todo/g16`; if `g16` lands first, pin the
  value here, otherwise leave it to `g16` and note it.

## Non-goals

- Real STM join semantics (a `sync` inside `dosync` joining the outer
  transaction with retry): single-lock transactions make this unobservable
  beyond pass-through; document any edge as a deviation instead.
- The other `.todo/g15` names -- each slice has its own item.
