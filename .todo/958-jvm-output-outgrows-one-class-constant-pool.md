# A large program's JVM output outgrows one class's constant pool

Difficulty: High

The JVM backend emits a whole program as ONE class, and a class holds at most 65534
constant-pool entries. mito's program no longer fits: `MitoE2eTest`'s three JVM legs
(`daoRoundTripOnJvm`, `migrationOnJvm`, `countDaoIsUndefinedOnTheCompiledBackends`) fail at
compile time with

```
error: mito-probe.lisp:32:1: constant pool overflow: this class needs more than 65534
constant pool entries, the JVM class-format limit; split the program
```

after ~250 s of compiling. The interpreter and `--component` legs are green (split off
`.todo/954`, 2026-09-24).

## Measured (2026-09-24, `ConstantPool.add`'s refusal lifted to count)

83,456 entries for the probe (mito-core + mito-migration + dbd-postgres, no `--optimize`):
Utf8 33,089, String 18,775, NameAndType 14,322, Methodref 9,217, Fieldref 5,129, Long 1,283,
Double 125, Class 102. 3,747 of the Fieldrefs are `QuotePool` fields (`.kb/quoted-data.md`),
47 `BigIntPool` ones (`.kb/jvm-bignum-literal-pool.md`), 438 globals.

Bisected on the first-parent line: last good `0e70625fa`, first bad `53a62530d` (2026-08-28),
the merge bringing `af1c467fe` (the bignum pool: 47 fields x 3 entries tipped it over). It has
grown ~18k past the limit since; the quote pool (`e20452690`, 2026-08-30) is the largest single
share (~11k: Utf8 name + NameAndType + Fieldref per datum).

Repro: the probe is `MitoE2eTest.crud(...)`'s source; the compile alone reproduces it (no
server needed): `java -cp target/classes am.ik.rontolisp.cli.RontoLispCli mito.lisp -o Probe.class`.

## What does not suffice

Holding every quoted datum and every bignum in ONE static array field each (index per value
instead of a field per value) saves ~11.4k and still leaves ~6.5k over. It is worth doing for
its own sake, but it is not the fix, and the program keeps growing with the libraries.

## The fix to design

Let one program span several classes: each class has its own pool. Questions to settle:

- The unit of splitting (defuns/lambdas grouped into holder classes; the runtime helpers,
  dispatch ladders, globals and pools stay in the main class or move too) and how call sites
  reference a method in another class (`invokestatic Holder.m`), including the dispatchers,
  `_lookup` and the class shaker (`am.ik.jvm.JvmClassShaker`, which is per class today).
- Every output shape: `-o X.class` (then several `.class` files beside it -- the "what travels"
  lists of `.kb/jvm-export.md`), `-o x.jar`, `-o app.war`, `rontolisp:jvm-export` handles, the
  Maven plugin, `JvmSourceCompiler` embedders.
- Byte-identical output for every program that fits one class today.

`.kb/jvm-method-size-limits.md` has the pool arithmetic.
