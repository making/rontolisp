# An `eq` hash table is not identity-keyed, and a shared object graph is exponential to hash

Difficulty: Medium

Member 4 of `563-solid-modeling-and-a-3d-viewer.md`, but NOT scoped to it -- this is an
ordinary hash-table defect that any program can hit. It is filed here because that is where
it surfaced: the viewer's first cut kept its per-solid GPU buffers in
`(make-hash-table :test 'eq)` keyed by the solid, which worked while the solids were
unparented and stopped returning the moment they were attached to a scene graph.
`563-solid-modeling-and-a-3d-viewer/repro.lisp` minimizes it; `.kb/hash-tables.md` is the
authority on the machinery.

## Two defects, one symptom

**1. `:test 'eq` and `:test 'eql` are accepted and ignored.** Every table places and
compares structurally. This is known and documented -- it is `012-hash-table-test-semantics.md`,
which owns the fix and has the design. What is new here is the CONSEQUENCE: it is not only
a semantic deviation on aggregate keys, it is a liveness cliff, because the structural hash
is what actually runs on a key the caller asked to be compared by identity. A program that
writes `:test 'eq` precisely BECAUSE its keys are large linked objects gets the most
expensive possible behaviour.

**2. The hash caps DEPTH but not WORK.** `LispEquality.hash(v, depth)` recurses into a
cons's car and cdr and into an instance's slots, decrementing `depth` from
`HASH_DEPTH_CAP` = 64, with no memo and no node budget. The number of distinct
root-to-leaf PATHS through a graph with sharing is exponential in its depth, so 64 levels
is an astronomically large amount of work whenever a key's substructure is shared or
cyclic -- which is what a scene graph, a doubly-linked list, a parse tree with parent
pointers and any ORM entity all are. This one hits an `equal` table too, so fixing (1)
does not fix it.

`.kb/hash-tables.md` currently argues the cap is "free correctness -- a hash need not be
injective". That is true of correctness and false of cost, and the file has to say so.

## The measurements

From `repro.lisp` (Apple M4 Max, `java -jar`), and reproduced on a compiled JVM class and
on WASM -- this is not an interpreter defect:

An `equal` table keyed by a DAG of n shared conses -- no cycle anywhere -- costs 2^n, and
the doubling is exact:

| n conses | 8 | 16 | 20 | 22 | 24 | 26 |
|---|---|---|---|---|---|---|
| one `gethash` | 2 ms | 1 ms | 3 ms | 9 ms | 33 ms | 130 ms |

An instance that knows its parent is far worse, because each level of the chain adds
several cons levels of fan-out: one `gethash` on an EMPTY `eq` table, keyed by a node two
links down a parent-linked chain, is 61 ms at depth 1 and did not return in 55 s at depth
2. On a compiled JVM class the same shape is 37 ms and then no return; the WASM output did
not finish either.

## Do

1. **Add a WORK budget beside the depth cap**, in all four hash implementations
   (`LispEquality.hash`, the JVM's `_hash(key, depth)`, the WASM `FUNC_HASH` and its
   depth global). A step counter decremented across the whole traversal and checked at
   every entry, folding a constant when exhausted.
   **The soundness argument is the same one the depth cap rests on, and it must be spelled
   out in the `.kb` file:** two `equal` keys have the same shape, so a deterministic
   traversal visits them in the same order and exhausts the budget at the same place, so
   they still hash equal. What the budget may NEVER be is order-of-insertion or
   address-dependent. Pick the number the way the depth cap was picked and say why.
2. Land `012-hash-table-test-semantics.md`, or at least its `eq`/`eql` half. Step 1 makes
   the cliff survivable; only step 2 makes `:test 'eq` mean what it says, and an identity
   table is what a caller keying by a live object actually wants. The two items should
   probably be done together -- 012 already names the machinery (`programMakesEqualpHashTable`
   is the shared source scan, and its flag is where an `eql` flag goes).
3. Tests, cross-backend: a DAG key and a parent-linked instance key must each answer in
   bounded time on all four backends, plus a `ci-spec.yaml` case. The existing
   `cyclic-hash-key-438` case pins that a cyclic key TERMINATES; these pin that it
   terminates *soon*, which is the part that was missing. A test with a wall-clock
   assertion is a bad test -- assert instead on a key whose exponential form would not
   finish inside the suite's own timeout, so a regression shows up as a hang rather than as
   a flaky number.
4. Update `.kb/hash-tables.md`: the depth cap's "free correctness" paragraph gains the cost
   qualifier, the new budget and its soundness argument go beside it, and the sentence
   about instance keys ("`equal` on instances is structural") gains the note that this is
   what makes a back-referencing object the worst case.
5. Re-examine `564-the-geom-package-transforms-scene-graph-and-solids.md`'s `user-data`
   slot once this lands. It exists partly as a workaround; it may well be the right design
   regardless, but the comment in the source must then state the real reason.

## Out of scope

`equal` on two DISTINCT cyclic structures, which ANSI leaves undefined and this
implementation may keep undefined (`.kb/hash-tables.md` says so already). This item is
about the HASH's cost, not about extending what comparison promises.
