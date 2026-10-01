# b13 — protocols/records/types (defprotocol/defrecord/deftype/reify/extend)

Difficulty: High (revisits a rejected-by-design value model; same representation required on all four backends)

Status: open. Currently REJECTED by design (`protocols are not supported yet`,
`ClojureLowering:481`; `.kb/clojure-frontend.md` b08). The corpus forces a
revisit for the foundations track: three whole chapters use nothing else.

Corpus: https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip

## Gap (measured 2026-10-01)

- `defprotocol` x4, `deftype` x1, `defrecord` x4+, `reify` x4, `extend-protocol`
  x2, `extend-type` x1, `satisfies?` x1:
  - `instant.clj`: `InstantProvider` protocol + `extend-protocol` to
    `Instant`/`Date`/`nil`/`Object`, `Simulation` protocol + `SimClock` deftype,
    `instance?` dispatch + `^Instant` hints.
  - `note.clj`: `MidiNote` protocol (`:extend-via-metadata true`), `Note` +
    `ToDo` + `Theme` records (`->ToDo`/`->Note`/`->Theme` ctors, `assoc` for
    `:velocity`), `extend-type Note`, `reify MidiNote` (two sites), `with-meta`
    extension.
  - `hangman/core.clj`: `Player` protocol, `ChoicesPlayer` record,
    `random-player`/`interactive-player` via `reify`, `satisfies?` in specs.
- Supporting gaps in the same files: `instance?`, `class`, type hints
  (`^Instant`, `#^Class`), `->Ctor`/`map->Ctor` constructors, `assoc` onto a
  record (returns same type in the oracle), `update-in`.

## Oracle (host `clj` 1.12.6, verified 2026-10-01)

```clojure
(defprotocol P (foo [x]))
(deftype T [a] P (foo [_] a))   (foo (T. 42)) ; => 42
(defrecord R [a] P (foo [_] a)) (foo (->R 7)) ; => 7
```

Dispatch is by type (incl. `nil`/`Object` defaults); `extend-protocol` adds
existing types without redefining; records are maps + type (equality, `assoc`,
`keys`/`vals` work; printing is `#user.R{:a 7}`).

## Scope (minimal story, decision spike first in `.todo/artefacts/b13-protocols/`)

- Value model: record = map-entry table + type tag every backend already
  prints/hashes/compares (reuse the b02 `equal`-table runtime; do NOT invent a
  per-backend struct — the b08 rejection reason). `->Ctor`/`map->Ctor`/`assoc`/
  `dissoc`/`get`/`contains?`/`keys`/`vals`/`=` keep working over it.
- Dispatch: protocol fns = multimethod-shaped dispatcher over the type tag
  (reuse the b05/b08 table + hierarchy search); `extend-protocol`/`extend-type`
  = `defmethod` rows; `satisfies?` = table membership; `reify` = single-shot
  map + methods (no `proxy` confusion — `reify` is data, `proxy` is `java:proxy`).
- `instance?` + `class` + ignored `^` hints (hints never affect dispatch;
  `set!` stays refused).
- `:extend-via-metadata`/`with-meta` only if cheap: otherwise named refusal
  with the `to-note` use case documented out.

Out: `gen-class`/`gen-interface` (stay refused), Java-interface extension
beyond `proxy`, method arities per type beyond single dispatch, performance
parity with the JVM.

## Design constraints

- Same representation on all four backends (interpreter + JVM + both WASM) or
  the feature stays refused — no per-backend value model (b02/b08 rule).
- Lowering only; no backend learns a Clojure name; `CompileFrontend.expand`
  order untouched. `.kb/adding-primitives.md` applies only to genuinely new
  CL primitives (`instance?` if lowered to `typep`, else prelude).
- `defrecord`/`deftype` names join the whole-file pre-scan (forward refs like
  `defn`); constructors `->Ctor`/`map->Ctor` are mangled `defun`s.

## Acceptance

- Port `instant.clj` (`InstantProvider` + `SimClock`) and the `Player` slice of
  `hangman/core.clj` into `clojure-spec.yaml`, green on all four backends.
- `ClojureLoweringTest` pins the stays-refused set (`gen-class`,
  multi-arity protocol fns if deferred, `with-meta` extension if deferred).
- Docs: `doc/en+ja/clojure/{semantics,deviations,reference}.md` + lowering-table
  rows in `.kb/clojure-frontend.md` (record-printing + `assoc`-returns-type
  deviations).
