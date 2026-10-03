# JVM backedges: a loop head must sit at operand stack depth 0

**Invariant: no backward branch in an emitted class may target a bci whose operand stack is
non-empty.** HotSpot enters an OSR compilation only at an empty-stack backedge ("COMPILE
SKIPPED: stack not empty at OSR entry point"); a method entered once never reaches the
invocation counter, so a refused OSR leaves the loop interpreted for the process lifetime --
silently, with every functional test green. Same cliff as
[hot-path-method-size.md](hot-path-method-size.md).

## Two fixes
- **Hoist the loop into its own method.** `JvmNthcdrRuntimeBuilder` emits
  `_nthcdr(int, Object)`, unconditionally like `_length`
  ([length-runtime.md](length-runtime.md)): `nth`/`elt`/`loop`/`destructuring-bind`/`format`'s
  `~*` generate `nthcdr` internally, so a source-symbol gate would miss those sites.
- **Spill pending operands around the loop.** `JvmEmitHelper.inLoopScope`
  (`Ctx.spillLoopEntryStack`), or its halves `enterLoopScope`/`leaveLoopScope` called directly
  by `JvmTagbodyCompiler`/`JvmWhileCompiler` (every Lisp loop lowers to `tagbody` or `while`).
  Every other emitter with its own inline backedge wraps it in `inLoopScope` -- grep that name.
  The one exception is `JvmSelfTailCall`: its backedge targets bci 0, which no operand can sit
  under, so instead of spilling it declines (keeps the call) unless the stack is empty at the
  jump ([jvm-self-tail-calls.md](jvm-self-tail-calls.md)).

## Traps
- **A loop with two entries refuses OSR on Graal.** Irreducible control flow -- a cycle entered
  at two of its heads -- fails Graal's OSR compile with `Multiple OnStackReplacementNodes
  generated` (`-Djdk.graal.CompilationFailureAction=Print` shows it), so one long call stays
  interpreted; the invocation-counted compile still succeeds. `JvmTailGroup` keeps every cycle
  through a tail group single-entry for this ([jvm-self-tail-calls.md](jvm-self-tail-calls.md),
  "Mutual tail calls").
- A `return`/`go` escaping to an ENCLOSING block must reload from the outermost escaped
  `JvmLispCompiler.SpillScope` (`JvmReturnCompiler`/`JvmGoCompiler`). In `JvmTagbodyCompiler`
  the push must happen BEFORE `TagbodyScope` records its spill depth, or a `go` to that
  tagbody's own labels is mistaken for an escape.
- `enterLoopScope` declines the spill and emits the loop unspilled in two cases: a
  half-constructed object is live (none today -- `JvmErrorCompiler` was the last), or one-byte
  local slots run out (`Ctx.hasRoomToSpillOperandStack`, 255 ceiling).

## The check
`StackMapFrames.osrHostileBackedges(byte[])` reads the generated `StackMapTable` back
([stack-map-frames.md](stack-map-frames.md)); a frame-free class is framed first. It is
a TEST, not a compile-time throw. Hoisting also frees the enclosing method's bytecode budget
([jvm-method-size-limits.md](jvm-method-size-limits.md)); with the spill in front of it a
typed loop ([jvm-typed-loops.md](jvm-typed-loops.md)) reaches argument position too.

## Tests
- `JvmDeadMethodEliminationCorpusTest#noEmittedLoopHeadCarriesPendingOperands` -- whole `ci-spec.yaml` at
  `OptimizeLevel.NONE` and `DEFAULT`, over the same two compiles the class-shaker guards use.
- `ExamplesE2eTest` -- same assertion on every `jvm` / `jvm-compile` leg.
