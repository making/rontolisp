# JVM bignum literals: one instance per value, built in `<clinit>`

**Invariant: a `java.math.BigInteger` literal is constructed ONCE per compilation
per distinct value, in `<clinit>`, and every use site is a `GETSTATIC`.** A
`BigInteger` is immutable, so one instance is the same value as a fresh one at
every use; the identity is unobservable (nothing in the emitted runtime compares
a bignum by reference -- `_equal`/`_hash`/`eql` all go through `BigInteger`'s own
`equals`/`compareTo`).

## What it replaces

`JvmEmitHelper.compileBigInteger` used to emit, per USE:

```
new java/math/BigInteger ; dup ; ldc "18446744073709551615" ; invokespecial <init>(String)
```

12 bytes of bytecode, and at run time a full decimal-string parse plus an
allocation -- every time the value is loaded, inside the hottest loop a program
has. Every `(ldb (byte 64 0) ...)` masks with `#xFFFFFFFFFFFFFFFF`, which is past
`Long.MAX_VALUE` and therefore a bignum literal; a 64-bit-word crypto primitive is
nothing but such masks. The use site is now three bytes:

```
getstatic <this class>._bi$N : Ljava/math/BigInteger;
```

## The pool

`JvmLispCompiler.BigIntPool`, shaped exactly like `LayoutPool` and shared across
every `Ctx` of one compilation through the single `Ctx.Builder` (so a defun body,
a top-level chunk, a lambda body and an outlined fused site all intern into the
same pool). `intern(cp, className, value)` is keyed on the `BigInteger` VALUE and
mints `_bi$0`, `_bi$1`, ... in first-use order; `emitClinitInit` drains it into
the `<clinit>` body at class assembly, which is where the `CONSTANT_String`
decimal forms are minted -- late enough that the pool is complete (every body is
compiled by then), early enough that the constant pool has not been serialized.

Four things the wiring has to keep straight:

- **The `<clinit>` gate.** A `<clinit>` is emitted only when something needs one
  (condition channel, layout pool, dyn-var ThreadLocals, struct table, standard
  streams, or a top level that moved there). A bignum literal is now one more
  reason, and it is also the last fallback for the `<clinit>` name/descriptor
  Utf8 constants -- a program whose ONLY reason for a class initializer is a
  bignum literal has no other pool to borrow them from.
- **Order inside `<clinit>`.** The bignum initializers go in FIRST, before the
  layouts and the stream seeds and well before the top-level runner `<clinit>`
  invokes last: they have no dependency of their own and anything later may read
  them.
- **`max_stack`.** A bignum initializer peaks at 3 (the uninitialized instance,
  its dup, the string). `StackMapAugmenter` copies the declared maximum verbatim,
  so an under-declaration is a `VerifyError` at class load, not a compile error.
- **The field carries no attribute.** `JvmClassShaker` rejects field attributes,
  which is why the field is a plain `private static` rather than
  `ACC_FINAL`-with-`ConstantValue` -- a `BigInteger` has no constant-pool form
  anyway. The shaker keeps the field because `<clinit>` is always a root and
  references it; a use site that gets shaken away leaves a harmless one-line
  initializer behind.

Nothing new travels: the field lives in the generated class, and `runtime` gains
no class (`.kb/jvm-export.md`).

`compileRatio` builds a `BigInteger[2]` per use -- the ARRAY is mutable and stays
per-use -- but its two elements come from the pool like any other literal.

A program with no bignum literal interns nothing, emits no field, gets no
`<clinit>` it would not otherwise have had, and is byte-identical to what the
compiler emitted before the pool existed.

## What it is worth

ironclad SHA-512 through `asdf:load-system`, compiled with `-o Prog.class`, 400
`digest-sequence` calls per timed batch, best of 12 batches after a 400-digest
warm-up (linux/x86-64, temurin 25, measured 2026-08-28):

| | best batch |
| --- | --- |
| before | 386 / 388 / 366 ms |
| after | 175 / 171 / 189 ms |

2.1x, and the digest is byte-identical (`IroncladE2eTest` pins it on all four
backends). JFR `ExecutionSample` at a 1 ms period says why: `BigInteger.<init>(String)`
and the `String.lastIndexOf` inside it were the top two frames of the run and are
absent afterwards. The class also shrinks -- 726 `new java/math/BigInteger` sites
collapse into 45 pooled fields, ~4.4 KB of bytecode across the emitted class.

Pinned by `JvmLispCompilerTest.aBignumLiteralIsBuiltOnceAndLoadedFromAField` (one
field per distinct value, however many uses) and
`aProgramWithoutABignumLiteralGetsNoPoolAndNoClassInitializer` (no field, no
`<clinit>`).

The WASM backends have their own literal story and are untouched here
(`.kb/wasm-bignum.md`).
