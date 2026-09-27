# The corpus class's constant pool: what fills it, and the quoted-datum fields

Difficulty: Medium

`JvmClassShakerCorpusTest#theCorpusClassKeepsConstantPoolHeadroom` holds the ci-spec corpus class
(`--optimize=off`) to 52,000 pool entries, and new ci-spec rows keep landing on it (the
`handlers-run-once-while-a-cleanup-signals` and array-shape rows were cut to one row per mechanism
for it, `.kb/error-handling.md`). Outlining the `handler-case` landing's synthesis moved it only
51,957 -> 51,945 (2026-09-27): a site that names class-wide constants adds no entry, so shrinking
code does not buy pool headroom.

What the pool held at 51,945: 19,358 `Utf8`, 10,258 `NameAndType`, 10,057 `String`, 6,462
`Methodref` (6,131 of them to the class's own methods), 3,865 `Fieldref`, 464 `Long`, 324
`Integer`, 295 `Double`, 101 `Class`.

The one per-SITE cost found: **2,755 `_qd$N` quoted-datum fields** (`JvmLispCompiler.QuotePool`,
`JvmQuoteCompiler.emitSharedConstant`), three entries each (`Fieldref`, `NameAndType`, name
`Utf8`) -- ~8,260 entries, 16% of the pool. One static `Object[]` indexed by `sipush N` would cost
one `Fieldref` for all of them. Watch:

- The field is `volatile` so a racing first evaluation publishes a fully built datum
  (`.kb/quoted-data.md`); a plain array store does not give that -- an `AtomicReferenceArray` or a
  `VarHandle` release store does, at a runtime-class cost.
- `JvmClassShaker` must still drop a quoted table together with the wrapper defun holding its only
  site (the reason the build is lazy at the site, not in `<clinit>`).
- The array has to be sized in `<clinit>` from the interned count.

Also worth a look before choosing: whether the 10,057 `String`s (median length 14) and the
self-`Methodref`s leave any other per-site cost, and whether the 52,000 tripwire itself (65,534 is
the JVM limit) should move once the pool shrinks. Measure the corpus pool and class size before and
after, and record them in `.kb/quoted-data.md`.
