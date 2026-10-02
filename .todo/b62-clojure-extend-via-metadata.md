# b62. `:extend-via-metadata` protocol dispatch

Difficulty: Medium

`extend-via-metadata is not supported yet: metadata never affects dispatch` --
the b14 position. A corpus program declares
`(defprotocol Area (area [t]) :extend-via-metadata true)` and extends values by
metadata.

## Design

The protocol table gains a per-protocol `:extend-via-metadata` flag. When set,
the dispatcher checks the target's metadata map for a row keyed by the
qualified method symbol before the tag/Object rows -- which means values must
be able to carry metadata, and today metadata is dropped at lowering (`^`
parses and drops, `with-meta` answers its first argument). Minimum viable:
`with-meta` attaches a metadata side table keyed by object identity for the
value kinds the dispatcher sees (records/deftypes/collections), `meta` reads
it; widen only as far as the corpus cases need and keep the parse-and-drop
default for type hints.

Decide first whether metadata keys compare by the same `equal` the tables use
(the oracle keys on the qualified symbol) and what `meta` answers for an
untagged value (nil).

## Oracle

```bash
clj -M -e '
(defprotocol Area :extend-via-metadata true (area [t]))
(defmethod #{} []) ; remove this line
(def r (with-meta {:w 2 :h 3} {`Area/area (fn [t] 6)}))
(println (area r))'
```

(get the exact spelling from a scratch `clj` session; the metadata key is the
protocol-qualified symbol.)

Pin: dispatch through metadata with the tag row absent, tag row winning when
`extend-type` also covers the type, `meta`/`with-meta` round-trip, the no-flag
protocol ignoring metadata (today's behavior).

## Acceptance

`clojure-spec.yaml` (records+metadata are backend-clean), refusals in
`ClojureLoweringTest`.
