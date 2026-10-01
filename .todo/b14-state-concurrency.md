# b14 — state/concurrency (ref/agent/future + binding/with-open + struct/meta)

Difficulty: Medium (atom-cell reuse + independent slices, but STM/binding semantics need investigation)

Status: open. Only `atom`/`volatile!` exist today; the book's state chapters
use everything else. Grouped as one track because every item is "an atom-shaped
cell plus a dynamic extent".

Corpus: https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip

## Gap (measured 2026-10-01)

- STM: `ref` x7, `dosync` x9, `alter` x6, `commute` x2, `ref-set` x4, `ensure`
  (`chat.clj` messages + validator, `concurrency.clj` counter,
  `snake.clj`/`atom_snake.clj` board, `pi.clj` seed).
- Agents/futures: `agent` x3, `send` x1, `send-off` x3, `await` x1,
  `shutdown-agents` x1 (`concurrency.clj` backup-agent, `pi.clj`
  `parallel-guess-pi`/`background-pi` with `*agent*`).
- Dynamic scope: `binding` x3 (`concurrency.clj` memoize demo, `pi.clj`
  `*random*`), `^:dynamic`, `set!` (`instant.clj` `*warn-on-reflection*`),
  `with-open` x4 + `with-out-str` (`utils.clj` `defdemo`, `macros.clj`
  `with-out-str-as-fn`, `eager.clj` `line-count`), `time` x5, `dorun` x1.
- Value constructors: `defstruct` x2 + `struct` x4 (`introduction.clj`
  `account`, `pi.clj` `sample-results`), `defonce` (`hangman` words),
  `defn-`/`^:private`/`^{:test}`/`^:dynamic` metadata everywhere,
  `with-meta` (`note.clj` `to-note`).
- Interop-adjacent in the same files: `import` (`interop.clj`, `snake.clj`),
  `proxy-super` (`snake.clj` `game-panel`), `.write *out*` (`life_without_multi`,
  `multimethods`), `format` (`introduction.clj`, `interop.clj`).

## Oracle (host `clj` 1.12.6, verified 2026-10-01)

```clojure
(def r (ref 0)) @r            ; => 0
(dosync (alter r inc)) @r     ; => 1
(with-out-str (print 1))      ; => "1"
(binding [*x* 2] *x*)         ; => 2 with ^:dynamic *x*
(struct (defstruct m :id) 1)  ; => {:id 1}
((memoize (fn [x] (* x 2))) 21) ; => 42
```

`dosync` retries on conflict (single-threaded equivalent: `progn`);
`commute` may run twice (document at-most-twice); agents are async
(`await` rendezvous); `binding` rebinds per thread; `defonce` keeps the root
on reload; metadata never affects dispatch.

## Scope (in slices, each independently shippable)

1. Metadata-ignored: `^:private`/`^:dynamic`/`^{...}`/`defn-`/`with-meta` w/o
   `:extend-via-metadata` parse + drop (documented), `declare` already works.
2. `defstruct`/`struct` as map literals over the b02 table (`:id` lookup works);
   `defonce` as `def` unless bound.
3. `ref`/`dosync`/`alter`/`commute`/`ref-set` over the atom cell
   (single-threaded: `dosync` = `progn` + validator run; validators run, retries
   don't — documented deviation); `binding` over CL specials if the `eval`
   package already has them, else the same cell with dynamic-extent restore.
4. `agent`/`send`/`send-off`/`await`/`shutdown-agents` as atom + synchronous
   `send` (async ordering documented out); `future`/`deref` + `delay`/`force` +
   `promise`/`deliver` only if (3) leaves room — else named refusals.
5. `with-open`/`with-out-str`/`time`/`dorun` + `import`/`proxy-super`/`.write`
   wiring already half-present (`proxy`/`..`/string receiver map exist).

Out: true MVCC retries, thread-pool agents, `pmap`/`pcalls`, STM validators
that block (run, don't block).

## Design constraints

- Reuse the b05 atom cell (`(:C%ATOM #(v))`); no new per-backend heap type.
- Lowering only; `.kb/adding-primitives.md` prelude pattern preferred (pure
  Lisp over existing primitives) over new `LispNames`/compiler cases.
- Four-backend parity in `clojure-spec.yaml`; concurrency timing never asserted
  (answers only, no sleeps in the spec).

## Acceptance

- Port `chat.clj` (validator + `alter`/`commute`), `concurrency.clj`
  (`binding` + `memoize` demo without `sleep`), `pi.clj` seed slice into
  `clojure-spec.yaml`, green on all four backends.
- `ClojureLoweringTest` pins deferred refusals (`future` etc. if deferred).
- Docs: `doc/en+ja/clojure/{semantics,deviations,reference}.md` +
  `.kb/clojure-frontend.md` rows (single-threaded-STM + sync-agent deviations).
