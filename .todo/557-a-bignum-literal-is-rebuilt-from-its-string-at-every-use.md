# 557. A bignum literal is rebuilt from its decimal string at every use site

Difficulty: Medium (a constant pool of static fields plus its `<clinit>` line;
the care is in what a `<clinit>` appearing in a program that had none does to
the shaker, the travelling runtime and the byte-identity tests)

`JvmEmitHelper.compileBigInteger` emits, per USE:

```
new java/math/BigInteger ; dup ; ldc "18446744073709551615" ; invokespecial <init>(String)
```

12 bytes of bytecode and, at run time, a full decimal-string parse plus an
allocation -- every time the value is loaded, inside the hottest loop a program
has. A `BigInteger` is immutable, so one instance per distinct literal in a
`private static final` field initialized once in `<clinit>` is the same value:
the use site becomes a 3-byte `getstatic` and costs nothing.

## What it is worth (measured 2026-08-28, linux/x86-64, temurin 25)

ironclad SHA-512, `-o Prog.class`, 400 digests per timed batch. Every `mod64+`
is `(ldb (byte 64 0) (+ a b))`, i.e. `(logand ... #xFFFFFFFFFFFFFFFF)`, and that
mask is over `Long.MAX_VALUE`, so it is a bignum literal: EIGHT of them per
SHA-512 round, 640 per block. JFR `ExecutionSample`, 1 ms period:

| frame | samples |
| --- | --- |
| `java.math.BigInteger.<init>(String, int)` | 291 |
| `java.lang.String.lastIndexOf(int, int)` (the parse) | 100 |
| `Prog.UPDATE-SHA512-BLOCK` itself | 45 |

~40% of the run is the literal, against 4% for the compression function. It is
also 640 x 9 = 5,760 bytecodes of `update-sha512-block` alone -- a third of what
made it the item `.todo/526` split (`.kb/hot-path-method-size.md`).

## What to build

A per-compilation pool keyed on the `BigInteger` value (the shape
`JvmLispCompiler.LayoutPool` already has), a `_bi$N` static field per entry, and
one `<clinit>` line each. `compileRatio` builds a `BigInteger[2]` per use and
goes through the same emitter -- the ARRAY is mutable and must stay per-use, but
its two elements come from the pool.

Watch:

- `<clinit>` is currently emitted only under a specific set of conditions; a
  program whose only reason for one is a bignum literal must get it, and the
  emission must stay byte-identical for a program with no bignum literal at all.
- `JvmClassShaker` must keep a pooled field and its initializer line alive from
  the use site.
- `runtime` imports nothing, and this adds no class to it -- the field lives in
  the generated class, so nothing new travels (`.kb/jvm-export.md`).

## Acceptance

- The SHA-512 batch above improves by roughly the 40% the profile attributes to
  the literal, digests byte-identical on all four backends (`IroncladE2eTest`).
- `update-sha512-block` and its continuations shrink by ~5,700 bytecodes in
  total.
- A test that a bignum literal used twice loads ONE instance, and that a program
  with no bignum literal compiles to the same bytes as before.
