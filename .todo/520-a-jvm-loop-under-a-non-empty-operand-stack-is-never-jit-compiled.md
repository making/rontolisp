# 520. A JVM loop emitted under a non-empty operand stack is NEVER JIT-compiled

Difficulty: Medium (the rule is one line; finding every emitter that breaks it,
and pinning it so it cannot come back, is the work)

Child of `.todo/517`.

## The defect

HotSpot can only enter an on-stack-replacement compilation at a backedge whose
**operand stack is empty**. A loop whose head carries pending operands is
refused at every tier, and -- when the enclosing method is entered once, which
is every top-level form and every `defun` called once with a long loop inside --
the method is never compiled by any other route either. It runs in the bytecode
interpreter forever.

rontolisp emits exactly that shape. `JvmNthcdrCompiler` writes an inline
`cdr`-walk loop wherever `nth`/`nthcdr` appears (`nth` expands to
`(car (nthcdr n l))`, `LispMacroExpander.expandNth`). The loop itself is clean --
both operands go to locals first -- but the ENCLOSING expression's operands are
still on the stack. In `(setq s (+ s (nth 999 lst)))` the emitter has already
pushed `s` for the pending `_add`, so the loop head at that bci has stack depth 1:

```
  289: getstatic     _g$S            <-- pending operand for the _add at 348
  ...
  312: iload  4                      <-- loop head, stack depth 1
  322: aload  5 / checkcast / aaload / astore 5
  331: iinc   4, -1
  334: goto   312
  348: invokestatic  _add
```

```
$ java -XX:+PrintCompilation -cp . B_nthfix
  88  225 %  3  B_nthfix::_top$0 @ 312 (420 bytes)
  89  225 %  3  B_nthfix::_top$0 @ 312 (420 bytes)  COMPILE SKIPPED: stack not empty at OSR entry point (retry at different tier)
 112  226 %  4  B_nthfix::_top$0 @ 312 (420 bytes)
 113  226 %  4  B_nthfix::_top$0 @ 312 (420 bytes)  COMPILE SKIPPED: OSR with stack entries not supported: FrameState|187 ... [bci: 312] BeforePop
```

Both tiers refuse; nothing else ever compiles the method. Moving the loop into a
`defun` does not help -- the `defun` is called once, so OSR is still the only
route, and it is refused at the same bci.

## What it costs (2026-08-25, this machine)

10^9 `cdr` steps -- `(nth 999 lst)` over a 1,000-element list, 10^6 times:

| backend | real | per `cdr` step |
| --- | --- | --- |
| SBCL 2.2.9 | 1.18 s | 1.18 ns |
| rontolisp wasm-GC | 1.88 s | 1.88 ns |
| rontolisp interpreter | ~0.7 s / 10^8 | ~7 ns |
| **rontolisp JVM** | **15.99 s** | **15.99 ns** |

The JVM backend is **8.5x slower than the wasm backend on identical source**,
and **slower than rontolisp's own tree-walking interpreter**. The wasm number is
the proof that nothing about the work is expensive: the same walk, same
representation, 1.6x off SBCL. This is the only row in `.todo/517`'s table where
a compiled backend loses to the interpreter, and it is the row the benchmark
that started `.todo/517` was actually about.

## What to build

An emitter invariant: **no backward branch may target a bci whose operand stack
is non-empty.** Two halves.

1. Enforce it. Before emitting an inline loop in expression position, spill the
   enclosing pending operands to locals and reload them after the loop -- or
   hoist the loop body into its own static method, which also removes it from
   the enclosing method's bytecode budget (see below). `JvmNthcdrCompiler` is the
   measured offender; audit every emitter that writes an inline `Opcode.GOTO`
   backedge reachable from expression position -- `JvmMapcarCompiler`,
   `JvmMapcCompiler`, `JvmMapcanCompiler`, `JvmReduceCompiler`,
   `JvmSubseqCompiler`, `JvmSortCompiler`, `JvmWhileCompiler` and the
   `Jvm*RuntimeBuilder` hand-assembled bodies are the candidate set. Note that
   `(print (loop ...))` is already SAFE -- that emitter stores the value to a
   local before touching `System.out` -- so the audit is per-emitter, not
   blanket.

2. Pin it. `StackMapAugmenter` (`.kb/stackmap-augmenter.md`) already runs a
   verifier-style dataflow over the finished bytes and records the fixpoint
   operand stack at **every branch target**. A check for "backward target, stack
   depth > 0" is nearly free there, and it is the only place that sees what all
   the emitters together produced. Decide deliberately whether it throws at
   compile time or fails a test over a corpus; a hard throw is the shape that
   cannot be forgotten, but it must not fire on a loop the JIT would never care
   about, so measure the corpus first.

Hoisting a loop into its own method interacts with `.kb/hot-path-method-size.md`
and `.kb/jvm-method-size-limits.md` in the good direction, and with
`.todo/412`'s worry about doubled fused sites -- coordinate if both land.

## Acceptance

- `-XX:+PrintCompilation` on the 10^9-`cdr` benchmark shows the loop's method
  compiled, with no `COMPILE SKIPPED: stack not empty at OSR entry point`.
- That benchmark lands within 2x of the wasm backend's 1.88 s, and no compiled
  backend is slower than the interpreter on it.
- A test fails if any emitted class contains a backward branch to a bci with a
  non-empty operand stack, over the `ci-spec.yaml` corpus and the examples.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical output on all four
  backends (the emitted bytes change; the printed results must not).
