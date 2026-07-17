# JVM: a method past 255 locals silently aliases slots (temps are never released)

**Status:** open, unstarted. A **latent correctness bug**, pre-existing. Found
2026-07-14 while fixing todo-131 (the `handler-case` operand-stack spill), by
a guard that was added and then narrowed -- see "How it surfaced".

## The bug

`Ctx.allocTemp()` hands out an ever-increasing slot number and (in most
compilers) never gives it back: a temp allocated to build one expression stays
allocated for the rest of the method. The load/store opcodes this backend emits
carry a **one-byte** slot operand (there is no `wide` form, and
`am.ik.jvm.OperandStack` does not model one), so `ctx.emit(slot)` with a slot
past 255 writes `slot & 0xFF` -- silently naming a **different** slot.

Today this does not visibly break, for a reason that is pure luck: a store and
its matching load wrap the *same* way, so a leaked temp that lands on slot 260
consistently reads and writes slot 4. It corrupts the moment the aliased slot is
also live -- e.g. slot 4 is a `let` variable, or two leaked temps alias each
other while both are live.

## How it surfaced

A hard guard in `allocTemp` (`slot > 255` -> compile error) made the ci-spec
corpus fail to compile on the JVM backend: the concatenated program's `_top$N`
chunks run past 256 locals. Two leak sites showed up immediately --

- `JvmQuoteCompiler.compileQuotedCons` allocated **one temp per cons cell** of a
  quoted literal, so `'(0 1 ... 399)` alone cost 400 locals. **FIXED** in the
  todo-131 commit (one temp per nesting level, `nextLocal` restored), pinned by
  `JvmLispCompilerTest.compileAndRunLongQuotedLiteralStaysWithinTheLocalSlots`.
- `JvmEqGeneralCompiler.compile` allocates two temps per `eq` and never restores
  them -- and it is one of many.

so the guard was narrowed to the one place where a wrapped slot would corrupt
something new: `Ctx.spillOperandStack()` (a spilled operand must survive the
protected region) raises `Cannot compile a catching form here: the function is
out of local variable slots`. Everything else is left as it was.

## The fix

Give temps a scope. The blocker for the obvious one-liner -- save/restore
`ctx.nextLocal` around every `JvmExprCompiler.compileExpr` -- is that
`allocLocal` mixes **persistent** locals into the same counter:
`JvmSetqCompiler:88` and `JvmDefvarCompiler:57` allocate a slot for a variable
that must outlive the form that created it (a later sibling statement reads it),
so a blanket restore would hand its slot to the next expression. Options:

1. Separate the two: persistent locals grow from the bottom, temps from a
   scope-restored high-water mark; `compileExpr` restores the temp mark only.
2. Keep one counter but have `allocLocal` raise a floor that the restore in
   `compileExpr` must not cross (`let`/lambda scoping already restores its own).
3. Leave the counter alone and give the ~40 leaking compilers the
   `savedNextLocal` discipline the others already have (mechanical, but every
   miss is silent).

Prefer (1) or (2): one place, and it makes the 255-slot guard affordable in
`allocTemp` -- which is the point (a slot that cannot be named must be a compile
error, never a wrapped write).

## Definition of done

- The guard in `allocTemp` is a hard error again, and the ci-spec corpus (the
  worst case) compiles well under it -- print the peak `maxLocals` to be sure.
- A regression test that today aliases: a method with >255 leaked temps whose
  wrapped slot collides with a live `let` variable.
- Four-backend + native E2E (JVM slot numbering changes, so the emitted classes
  do -- nothing else should).
