# JVM: a temp's local slot is never released, so a method burns one per statement

Difficulty: Medium

**Status:** open, narrowed. The INDEX half is done (todo-562, 2026-08-29): past
slot 255 a load or store now takes the `wide` prefix and a two-byte index, so a
past-255 slot is no longer a wrong answer -- see `.kb/stackmap-augmenter.md`,
"The `wide` prefix". What is left here is the COUNT, which is now a SIZE
question, not a correctness one.

## The remaining problem

`Ctx.allocTemp()` hands out an ever-increasing slot number and (in most
compilers) never gives it back: a temp allocated to build one expression stays
allocated for the rest of the method. A straight-line body therefore burns a
slot per temporary and reaches 256 in a few hundred statements
(`JvmEqGeneralCompiler.compile` allocates two per `eq` and restores neither, and
it is one of many).

What that costs now:

- Every load and store of a slot past 255 is 4 bytes instead of 2.
- `max_locals` sizes every `full_frame` the `StackMapTable` carries, and those
  frames list one verification type per local. A method with 600 locals pays for
  600 entries in each of its full frames.

The hard ceiling is the u2 `max_locals`: `allocTemp` throws past 65535 naming
the limit.

## The fix

Give temps a scope. The blocker for the obvious one-liner -- save/restore
`ctx.nextLocal` around every `JvmExprCompiler.compileExpr` -- is that
`allocLocal` mixes **persistent** locals into the same counter:
`JvmSetqCompiler` and `JvmDefvarCompiler` allocate a slot for a variable that
must outlive the form that created it (a later sibling statement reads it), so a
blanket restore would hand its slot to the next expression. Options:

1. Separate the two: persistent locals grow from the bottom, temps from a
   scope-restored high-water mark; `compileExpr` restores the temp mark only.
2. Keep one counter but have `allocLocal` raise a floor that the restore in
   `compileExpr` must not cross (`let`/lambda scoping already restores its own).
3. Leave the counter alone and give the ~40 leaking compilers the
   `savedNextLocal` discipline the others already have (mechanical, but every
   miss is silent).

Prefer (1) or (2): one place.

## Definition of done

- Measure first: the peak `maxLocals` and the emitted class size over the
  ci-spec corpus and a few examples, before and after. If the saving does not
  show up in bytes, that measurement IS the result -- record it here and close
  the item rather than landing the churn.
- Four-backend + native E2E (JVM slot numbering changes, so the emitted classes
  do -- nothing else should).
