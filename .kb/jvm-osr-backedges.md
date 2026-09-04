# JVM backedges: a loop head must sit at operand stack depth 0

**Invariant: no backward branch in an emitted class may target a bci whose operand stack is
non-empty.** HotSpot enters an OSR compilation only at a backedge with an EMPTY stack ("COMPILE
SKIPPED: stack not empty at OSR entry point"). A method entered once -- every top-level form, every
`defun` called once around a long loop -- never reaches the invocation counter for an ordinary
compilation, so OSR is the only route in; refused, the loop runs interpreted for the process
lifetime, with no warning and every functional test green. Same silent cliff as
[hot-path-method-size.md](hot-path-method-size.md). Original offender: `JvmNthcdrCompiler`'s inlined
`cdr`-walk under `(setq s (+ s (nth 999 lst)))`, whose pending `s` (for the `_add`) sits on the
stack at the loop head.

## Two shapes, both byte-identical when the stack was already empty

- **Hoist the loop into its own method.** `JvmNthcdrRuntimeBuilder` emits
  `_nthcdr(int n, Object list)`; the call site is one `invokestatic`, and ordinary invocation counters
  compile it. Emitted unconditionally like `_length` ([length-runtime.md](length-runtime.md)) --
  `nthcdr` is generated internally by `nth`, `elt`, `loop`'s list stepping, `destructuring-bind` and
  `format`'s `~*` family, so a source-symbol gate would miss those sites; body ~20 bytes.
- **Spill pending operands around the loop.** `JvmEmitHelper.inLoopScope` saves the live stack into
  locals (`Ctx.spillLoopEntryStack`, `handler-case`'s machinery), runs the emitter, reloads UNDER the
  loop's result. `JvmTagbodyCompiler`/`JvmWhileCompiler` call the halves
  (`enterLoopScope`/`leaveLoopScope`) directly since their value is constant nil; between them they
  cover `loop`/`do`/`do*`/`dotimes`/`dolist`/`getf` (every Lisp loop lowers to a `tagbody` or
  `while`). Emitters with their own inline backedge go through `inLoopScope`: `JvmMapcarCompiler`,
  `JvmMapcCompiler`, `JvmMapcanCompiler`, `JvmReduceCompiler`, `JvmSortCompiler`, `JvmSubseqCompiler`,
  `JvmRemfTailCompiler`, `JvmStringTrimCompiler`, `JvmStringCaseFold`, `JvmHashTableCompiler`
  (`maphash`), `JvmObjCompiler` (`%obj-slots`), and `JvmExprCompiler`'s `dotimes` dispatch (which
  brackets the typed-loop attempt and its fallback expansion together).

### Escape paths
A `return`/`go` leaving the loop for an ENCLOSING block reaches that block's exit with the stack the
block was entered with, now in the spill. So a non-empty loop spill is pushed as a
`JvmLispCompiler.SpillScope` (as `handler-case` does) and `JvmReturnCompiler`/`JvmGoCompiler` reload
from the outermost escaped scope. In `JvmTagbodyCompiler` the push must happen BEFORE `TagbodyScope`
records its spill depth, so a `go` to that tagbody's own (depth-0) labels is not treated as escaping.

### Two cases decline the spill
`enterLoopScope` then emits the loop unspilled -- a hostile backedge beats failing a compile that
would otherwise succeed (a loop spill is an optimization; `handler-case` has no choice and throws):
- **A half-constructed object is live**: `new` executed, constructor not run, is a distinct
  verification type no local may hold. No emitter leaves one live across an argument today --
  `JvmErrorCompiler` was the last and now builds the message into a local BEFORE
  `new RuntimeException`, which also lifted the old "cannot compile a catching form while an object is
  under construction" limit for `(error (format ...))`. The corpus check pins it.
- **Out of one-byte local slots** (`Ctx.hasRoomToSpillOperandStack`, the 255 ceiling). Not reached by
  the corpus.

## The check
`StackMapAugmenter.osrHostileBackedges(byte[])` reuses the augmenter's verifier-style dataflow
([stackmap-augmenter.md](stackmap-augmenter.md)) and reports every backward branch to a non-empty
target; dead code skipped. Unlike `augment`, it accepts an already-augmented class (a `StackMapTable`
in a `Code` attribute is skipped), so it can be pointed at a finished `.class`.

It is a TEST, not a compile-time throw -- a throw would refuse valid programs (the un-spillable `new`
case) to protect a performance property. Pins:
- `JvmOsrBackedgeCorpusTest` -- the whole `ci-spec.yaml` corpus at `OptimizeLevel.NONE` and `DEFAULT`,
  zero hostile backedges.
- `ExamplesE2eTest` -- same assertion on the `Prog.class` of every `jvm` and `jvm-compile` leg.

## Related
Hoisting also removes the loop from the enclosing method's bytecode budget
([jvm-method-size-limits.md](jvm-method-size-limits.md),
[hot-path-method-size.md](hot-path-method-size.md)). The typed-loop emitter
([jvm-typed-loops.md](jvm-typed-loops.md)) used to decline a loop nested in an expression with live
operands; with the spill in front of it, a typed loop reaches argument position too.
