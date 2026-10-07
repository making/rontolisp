# d90. An arithmetic aref index is an opaque leaf of a fused tree

Difficulty: Medium

In a fused integer tree, `(aref v (+ i 1))` reads the element raw, but its index is not part
of the tree: the JVM classifies only a literal, a symbol or a `random` draw as an index node
(`JvmIntFusionCompiler.arefIndexNode`) and the WASM aref leaf evaluates its index as an
ordinary expression (`WasmIntFusionCompiler.ArefLeaf.indexExpr`). Either way the call site
computes the index boxed -- through a fused method of its own on the JVM -- before the tree
runs.

That opaque index is also what most often puts a check between a fused tree's leaves
(`.kb/jvm-int-fusion.md`, "The interpreter's order"): it is an observable leaf, so a
`LeafGuard` tests its variables to let the earlier aref reads wait. The 2026-10-07 census
counted 502 aref reads pending in front of an observable leaf over the examples and report
programs, mostly UTF-8 decoding and MD5's buffer reads in front of such an index.

Plan: classify an arithmetic index into the tree (the read's index computed raw in the
prologue, inside the overflow region; the fallback computing it generically), on both
backends, and measure what the UTF-8 and MD5 loops gain before keeping it.
