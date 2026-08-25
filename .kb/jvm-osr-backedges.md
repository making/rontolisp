# JVM backedges: a loop head must sit at operand stack depth 0

**Invariant: no backward branch in an emitted class may target a bci whose
operand stack is non-empty.** HotSpot can only enter an on-stack-replacement
(OSR) compilation at a backedge whose operand stack is EMPTY. A loop head that
carries pending operands is refused at every tier:

```
$ java -XX:+PrintCompilation -cp . B_nth
  88  225 %  3  B_nth::_top$0 @ 312 (420 bytes)
  89  225 %  3  B_nth::_top$0 @ 312 (420 bytes)  COMPILE SKIPPED: stack not empty at OSR entry point (retry at different tier)
 112  226 %  4  B_nth::_top$0 @ 312 (420 bytes)
 113  226 %  4  B_nth::_top$0 @ 312 (420 bytes)  COMPILE SKIPPED: OSR with stack entries not supported: FrameState|187 ... [bci: 312] BeforePop
```

Nothing else compiles such a method either, because a method that is ENTERED
once -- every top-level form, and every `defun` called once with a long loop
inside -- never reaches the invocation counter that would trigger an ordinary
compilation. OSR is the only route in, and it is refused. The loop runs in the
bytecode interpreter for the lifetime of the process, and nothing reports it:
no warning, no flag in a stack trace, every functional test still green. It is
the same class of silent cliff as
[hot-path-method-size.md](hot-path-method-size.md).

## What it cost

`nth` was the measured offender: `JvmNthcdrCompiler` wrote an inline `cdr`-walk
loop at the call site, and `(setq s (+ s (nth 999 lst)))` has the pending `s`
for the `_add` on the stack when the loop head is emitted. 10^9 `cdr` steps --
`(nth 999 lst)` over a 1,000-element list, 10^6 times (2026-08-25, this machine,
SBCL 2.2.9 / wasmtime 47):

| backend | before | after |
| --- | --- | --- |
| SBCL | 1.18 s | 1.18 s |
| rontolisp wasm-GC | 1.9 s | 1.9 s |
| rontolisp interpreter | ~7 s | ~7 s |
| **rontolisp JVM** | **15.4 s** | **3.6 s** |

The JVM backend was 8x the WASM backend on identical source, and slower than
rontolisp's own tree-walking interpreter -- the only row of `.todo/517`'s table
where a compiled backend lost to the interpreter.

## How each emitter satisfies it

Two shapes, both keeping the emitted bytes IDENTICAL when the operand stack
already was empty (the overwhelmingly common case):

- **Move the loop into its own method.** `JvmNthcdrRuntimeBuilder` emits
  `_nthcdr(int n, Object list)`; the call site is one `invokestatic`. A method
  called once per `nthcdr` is compiled by the ordinary invocation counters, so
  OSR never enters the picture. Emitted unconditionally, like `_length`
  ([length-runtime.md](length-runtime.md)): `nthcdr` is generated internally by
  a long tail of expanders (`nth`, `elt`, `loop`'s list stepping,
  `destructuring-bind`, `format`'s `~*` family), so a source-symbol gate would
  miss those sites, and the body is ~20 bytes.
- **Spill the pending operands around the loop.** `JvmEmitHelper.inLoopScope`
  saves the live operand stack into locals (`Ctx.spillLoopEntryStack`, the same
  machinery `handler-case` uses), runs the emitter, and reloads them UNDER the
  loop's result. `JvmTagbodyCompiler` and `JvmWhileCompiler` call the halves
  (`enterLoopScope`/`leaveLoopScope`) directly, because their value is the
  constant nil and needs no reordering. Between them those two cover the whole
  `loop`/`do`/`do*`/`dotimes`/`dolist`/`getf` family -- every Lisp-level loop
  lowers to a `tagbody` or a `while`.

  The emitters that write their own inline backedge go through `inLoopScope`:
  `JvmMapcarCompiler`, `JvmMapcCompiler`, `JvmMapcanCompiler`,
  `JvmReduceCompiler`, `JvmSortCompiler`, `JvmSubseqCompiler`,
  `JvmRemfTailCompiler`, `JvmStringTrimCompiler`, `JvmStringCaseFold`,
  `JvmHashTableCompiler` (`maphash`), `JvmObjCompiler` (`%obj-slots`), and the
  `dotimes` dispatch in `JvmExprCompiler`, which brackets the typed-loop attempt
  and its fallback expansion together.

### The escape-path bookkeeping the spill needs

A `return`/`go` that leaves the loop for an ENCLOSING block reaches that block's
exit with the operand stack the block was entered with -- and those operands are
now in the spill. So a non-empty loop spill is pushed as a
`JvmLispCompiler.SpillScope`, exactly as `handler-case` pushes its own, and
`JvmReturnCompiler`/`JvmGoCompiler` reload from the outermost escaped scope. In
`JvmTagbodyCompiler` the push happens BEFORE the `TagbodyScope` records its
spill depth, so a `go` to one of that tagbody's own labels does not treat the
scope as escaped -- those labels are at depth 0 now.

### The two cases that decline the spill

`enterLoopScope` emits the loop unspilled -- accepting the hostile backedge --
rather than failing a compile that would otherwise have succeeded, in two
places. A `handler-case` has no such choice and still throws; a loop spill is an
optimization and asks first.

- **A half-constructed object is live.** `new` executed, constructor not yet
  run, is a distinct verification type and no local may hold one. No emitter
  leaves one live across an argument today: `JvmErrorCompiler` was the last, and
  now computes the message into a local BEFORE `new RuntimeException` -- which
  also lifts the old "cannot compile a catching form while an object is under
  construction" limit for every `(error (format ...))`. The corpus check pins
  that it stays that way.
- **The method is out of one-byte local slots** (`Ctx.hasRoomToSpillOperandStack`,
  the 255 ceiling `.todo/137` is about). Not reached by anything in the corpus.

## The check

`StackMapAugmenter.osrHostileBackedges(byte[])` reuses the verifier-style
dataflow the augmenter already runs
([stackmap-augmenter.md](stackmap-augmenter.md)) -- it computes the fixpoint
operand stack at every branch target anyway -- and reports every backward branch
whose target is non-empty. Dead code is skipped: it never runs, so no JIT looks
at it. Unlike `augment`, the analysis accepts an already-augmented class (a
`StackMapTable` present in a `Code` attribute is skipped), so it can be pointed
at a finished `.class` file.

It is a TEST, not a compile-time throw. The decision was made against the
measured corpus: the check has to fire on nothing a program can legitimately
produce, and the un-spillable `new` case above is exactly such a shape, so a
hard throw would refuse valid programs to protect a performance property. The
pins instead are:

- `JvmOsrBackedgeCorpusTest` -- the whole `ci-spec.yaml` corpus, at
  `OptimizeLevel.NONE` and `DEFAULT`, asserted to hold zero hostile backedges.
- `ExamplesE2eTest` -- the same assertion on the `Prog.class` of every `jvm`
  and `jvm-compile` leg.

The corpus went from 3333 hostile backedges across 92 example programs to zero.

## Related

Hoisting a loop into its own method also removes it from the enclosing method's
bytecode budget ([jvm-method-size-limits.md](jvm-method-size-limits.md),
[hot-path-method-size.md](hot-path-method-size.md)), so the two invariants pull
the same way. The typed-loop emitter
([jvm-typed-loops.md](jvm-typed-loops.md)) declined a loop nested inside an
expression with live operands; with the spill in front of it that condition is
now satisfiable, so a typed loop reaches argument position too.
