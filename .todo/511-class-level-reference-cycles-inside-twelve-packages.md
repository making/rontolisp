# Class-level reference cycles remain inside twelve packages

Difficulty: High

Filed 2026-08-24 alongside `src/test/java/am/ik/rontolisp/PackageCycleTest.java`, which now
pins the PACKAGE half of the "no circular references" rule in CLAUDE.md (the one cycle it
found -- `eval` <-> `web`, `Target_HttpSupport` importing `web.BrowserHttp` while
`web.RontoPlayground` imports half of `eval` -- was closed in the same commit by moving
`BrowserHttp` down into `eval`, where its `BrowserFuture` / `BrowserHttpResponses`
siblings already live). The CLASS half is not pinned, and does not hold.

## What is there

Strongly connected components of the class reference graph (imports plus same-package
simple-name references, over `src/main/java`, `src/web/java`, `docs-tool/` and
`rontolisp-maven-plugin/`). Every one of them is INSIDE a single package -- there is no
cross-package class cycle left once the package graph is a DAG:

| classes | package |
| --- | --- |
| 171 | `am.ik.rontolisp.codegen.wasm` |
| 159 | `am.ik.rontolisp.codegen.jvm` |
| 32 | `am.ik.rontolisp` |
| 12 + 2 | `am.ik.wasm` |
| 6 | `am.ik.rontolisp.eval` |
| 5 | `am.ik.jvm` |
| 4 | `am.ik.gpu` |
| 3 | `am.ik.rontolisp.reader` |
| 2 | `am.ik.rontolisp.macro` |
| 2 | `am.ik.rontolisp.cli` |
| 2 | `am.ik.rontolisp.docgen` |

The two big ones are one shape, not 330 accidents: `Wasm`/`JvmExprCompiler` dispatches to a
per-form compiler, which calls back into `compileExpr` for its subexpressions. The
recursion is the design; what makes it a REFERENCE cycle is that the callee names the
dispatcher class directly instead of a seam it is handed.

## What to decide

Either state the exemption or pay for it -- what must not stay is CLAUDE.md claiming a rule
the tree breaks 12 times.

1. **A seam for the dispatch cycle.** Give the per-form compilers an interface (the
   "compile this subexpression for me" callback, plus whatever emit state they reach for)
   that the dispatcher implements and passes in. That turns 330 classes into two leaf sets
   over one interface each, and would let a single per-form compiler be tested without the
   dispatcher. It is a wide mechanical change across both backends and must not shift a
   single emitted byte: the gate is the four-backend run plus a byte-for-byte compare of a
   compiled module against the pre-refactor one (`.kb/emitted-output-determinism.md`).
2. **The cheap three** (`cli`, `macro`, `docgen`, and probably `reader`) are each one
   static helper reaching backwards: `JLineRepl` calls `RontoLispCli.isBalanced` /
   `evalBuffer`, `PureBuiltinFolder` calls `LispMacroExpander.usesPrintCase`, `LispLexer`
   calls `LispReader.parsesAsExpressions`. Moving the shared helper into a third class
   breaks those four with no design risk.
3. **Or scope the rule to packages** in CLAUDE.md and say why: a recursive compiler's
   dispatch is mutual by nature, and the package DAG is what actually keeps the tree
   extractable.

Done when `PackageCycleTest` carries the class-level assertion too -- with whatever
exemption list the decision leaves, spelled out in the test rather than in prose.
