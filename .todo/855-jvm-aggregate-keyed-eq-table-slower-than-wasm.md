# JVM: 300,000 aggregate keys in an `eq` table take 3.5 s where WASM takes 1.4 s

Difficulty: Medium

Measured while closing `.todo/839` (2026-09-17, linux-x86-64, one run each, other
builds on the machine): the program of
`LispEvaluatorTest.eqHashTableWithManyAggregateKeysStaysHashed` -- 100,000 conses,
100,000 one-element vectors and 100,000 structure instances keyed in one `eq` table, the
vectors also in an `equal` table, then looked up, mutated and half removed -- prints
`(250000 100000 1000000 40)` in 4,523 ms on the interpreter, 3,544 ms on the compiled
JVM class, 1,447 ms on WASM Preview 1 and 1,425 ms on the component. The cons-only
benchmark of the same item
(`.todo/artefacts/839-wasm-identity-table-aggregate-keys-share-one-bucket/ht-cons.lisp`)
puts the JVM at 54 / 26 ms fill / lookup for 10^5 keys, so the placement itself is not
what the JVM spends the seconds on.

## To do

1. Split the program by key kind and by phase (allocation, `puthash`, `gethash`,
   `remhash`) on the JVM and time each, several runs. Suspects: `(vector i)` (a plain
   general array starts PACKED and widens on the first store, `.kb/adjustable-arrays.md`),
   `make-wkey`, `System.identityHashCode` inflating every object header, and the
   `LinkedHashMap` bucket index of `.kb/hash-tables.md`.
2. Fix what the numbers name, or record them in `.kb/hash-tables.md` if the cost is
   inherent to the representation.
