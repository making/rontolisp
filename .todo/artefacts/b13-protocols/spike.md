# b13 decision spike: protocols/records over the existing table runtime (2026-10-01)

## Decision: implement (reject the b08 rejection)

The b08 rejection argued a protocol needs type-based dispatch and
`defrecord`/`deftype` a value representation every backend prints, hashes and
compares -- a new runtime on all four backends for no measured user. The
measurement changed: three corpus chapters (`instant.clj`, `note.clj`,
`hangman/core.clj`) use nothing else, and both halves already exist in the
tree:

- **Dispatch**: the b05/b08 multimethod table (`c%name%methods` + dispatcher
  `defun` + hierarchy search) is the shape protocol dispatch takes. A protocol
  is a method table keyed by dispatch tag plus one dispatcher `defun` per
  method; `extend-protocol`/`extend-type`/`extend` are `defmethod` rows;
  `satisfies?` is table membership. No hierarchy search (exact tag match plus
  the `Object` default row), which is the documented deviation from `isa?`
  widening.
- **Values**: the b02 `equal`-table runtime already prints/hashes/compares on
  all four backends. A record is a plain 4-list wrapper
  `(:C%RECORD (:C%KEYWORD "Name") (fields...) table)` beside the existing
  `(:C%SET table)` / `(:C%KEYWORD spelling)` / `(:C%LAZY cell)` wrappers -- no
  per-backend struct, which was the b08 rejection reason. `deftype`/`reify`
  share the shape with opaque tags (`:C%TYPE`, `:C%REIFY`) so map verbs keep
  refusing them the way the oracle does.

Oracle probes (`clj` 1.12.6, 2026-10-01) pin the semantics the lowering keeps:

- `(= (->R 7) {:a 7})` is false; `(= (->R 7) (->R 7))` true; `assoc` keeps the
  type (`#user.R{:a 7, :b 1}`); `dissoc` of a base field drops to a plain map
  while `dissoc` of an extension key keeps the record; `merge`/`update` keep
  the record; `select-keys`/`into {}` answer plain maps; `(keys r)` is
  `(:a)`; `(get (T. 9) :a)` is nil and `(keys (T. 9))` throws; `deftype`
  equality is identity; `map?` is true of records, false of deftypes;
  `extend-protocol` dispatches `nil`/`String`/`Long`/`Object` separately with
  `Object` as the catch-all; `satisfies?` with an `Object` row is true of
  everything; each `reify` is a distinct value; `deftype` has no `map->T`;
  `(class nil)` is nil.

## Representation (identical on all four backends)

- Record: `(:C%RECORD tag fields table)` where `tag` is the type keyword
  `(:C%KEYWORD "Name")`, `fields` the declared field-keyword list, `table` an
  `equal` table. Map verbs unwrap to the table (`get`/`contains?`/`keys`/
  `vals`/`count`/`seq`/`select-keys` read through it); writers rebuild the
  table copy-on-write and rewrap (`assoc`/`update`/`conj`/`merge` keep the
  tag; `dissoc` rewraps only while every base field is still present, else a
  plain map -- the oracle rule). `=` is structural over tag + table entries,
  so a record never equals a plain map.
- `deftype`: `(:C%TYPE tag fields table)` -- opaque to map verbs (reads miss,
  writers signal, like the oracle), `=` is `eq` (identity, like the oracle).
- `reify`: `(:C%REIFY tag table)` with a per-evaluation unique tag, `=` is
  `eq`.
- Printing stays the wrapper list (deviation: no `#user.R{...}` printer;
  pinned in `.kb/clojure-frontend.md`).
- `class` of a record/deftype answers its tag keyword (deviation: the oracle
  answers a host class, which no wasm backend has); `instance?` of a record/
  deftype name is tag equality. `^` hints stay dropped (they never affect
  dispatch).

## Dispatch (lowering only; no backend learns a Clojure name)

- `(defprotocol P (m [self & args])+ )` emits one `equal` table global
  `c%P%methods` plus one dispatcher `defun` per method. The dispatch tag is
  the record/deftype/reify tag, else the `class` kind keyword (`:nil` for nil,
  `:string`, `:number`, ...); `Object` rows are stored under the default key
  and consulted on a miss. One shared `c%protocol-tag` defun per file computes
  the tag. A miss with no `Object` row signals
  (`No implementation of method ...`), like the oracle.
- `extend-protocol`/`extend-type`/`extend` store lambdas (with the usual
  destructuring prologue) under the target's tag. Extend targets are the
  `class`-keyword kinds (`String`, `Number`/`Long`/`Double`, `Boolean`,
  `Keyword`, `Symbol`, `Character`, `Map`, `Vector`, `Set`, `List`/`Seq`,
  plus `nil` and `Object`) and known record/deftype names; anything else
  (e.g. `Instant`, `Date`) is a named refusal.
- `deftype`/`defrecord` inline `(m [self & args] body...)` groups lower to the
  same rows. Method bodies see the fields as locals bound from the instance
  table. Constructors `->T`/`map->T` (records only) are mangled `defun`s;
  `(T. ...)`/`(new T ...)` rewrite to `->T`. `defrecord`/`deftype` names join
  the whole-file pre-scan (forward refs like `defn`); protocol method names
  too.
- Single signature per method (multi-arity protocol methods stay refused).
  `:extend-via-metadata`/`with-meta` extension stays a named refusal
  (`with-meta` itself keeps answering its object); `definterface`/
  `gen-class`/`gen-interface` stay refused. `.-field` on a record/deftype
  reads the field table, else the host field path.
