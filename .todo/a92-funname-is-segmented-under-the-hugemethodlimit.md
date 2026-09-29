# `_funName` is segmented under HotSpot's HugeMethodLimit

Difficulty: Low

## The problem

`JvmLibraryMethodSizeTest.noCompiledClackNingleMethodCrossesHotSpotsHugeMethodLimit` fails:
`{"_funName"=23386}`. It is gated on `RONTOLISP_NINGLE_E2E=1`, so the default suite never runs it.

Measured 2026-09-29 on `examples/net/httpbin-ningle.lisp` (`-o H.class`): 2,138 rows, funcIds
0-2137 dense, each `iload_0; <id>; if_icmpne; ldc_w <name>; areturn` (11 B). A jar built before
a83 emits the same 23,386 B, so a84 did not cause it.

`JvmRuntimeBuilder.buildFunNameBody` is one linear chain with no budget. The other funcId tables
already handle this cliff (`.kb/hot-path-method-size.md`, "Dispatch tables"):
- `_invoke_<arity>` is cut at `DISPATCH_SEGMENT_BUDGET` (6000) and searched with a binary tree
  (`emitDispatchTree`, `emitSegmentRouter`).
- `_lookup$g` is cut at `LOOKUP_SEGMENT_BUDGET`.

It is also the first method whose own references outgrow one pool as the program grows
(`.kb/jvm-method-size-limits.md`, "Still bounded"). The class splitter cannot divide a single
method body, so segments lift that bound as well.

## What is needed

- Write a failing non-gated test first: a `JvmLispCompilerTest` program with enough nameable
  function values to push the table past 8000 B. It asserts that no method outside the test's
  exclusions is over the limit, and that the first, a middle and the last function each print as
  `#<function NAME>`.
- Cut the rows by funcId (already sorted) under `DISPATCH_SEGMENT_BUDGET`, reusing the dispatch
  tree and segment router rather than adding a third segmenting scheme. The rows load the same
  string constants, so the pool grows only by the segments' own method references.
- Measure the ningle class's size and pool count before and after, then rerun the gated test.
