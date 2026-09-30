# The ClassFile migration record leaves `.kb`, and the shaker tests lose a deleted class's name

Difficulty: Low

## The problem

The move onto `java.lang.classfile` (a82-a91) is finished, but its working notes stay in `.kb`:

- `.kb/jvm-method-size-limits.md`, "Emission on `java.lang.classfile`" (lines ~97-395 of 465,
  2026-09-30) is mostly the migration record: per-slice measurements, "How a slice moves" (the
  old-to-new call map, `JvmAsm`/`ctx.emit`/`patchBranch` patterns, the `ctxmig.py`/`raw.py`/
  `prep.py` conversion scripts). Nothing is left to migrate, so the recipe has no reader.
- `JvmClassShakerTest` and `JvmClassShakerCorpusTest` (`src/test/java/am/ik/jvm/`) are named
  after `JvmClassShaker`, deleted in a84; dead-method removal is now `OwnCallGraph` plus a
  `ClassTransform`. The names are referenced from 11 `.kb` files, `ci-spec.yaml`,
  `CorpusFixtures`, `ExamplesE2eTest`, `CompileFrontendAccess`, `LispFormatterTest` and
  `CompileFrontend` (javadoc).

## What is needed

1. Rewrite the section to what holds now: how a method body is recorded (`MethodCode`), measured
   (`size()` is the written size) and written (`CodeReplay`, the far-branch computation that
   replaced `FIX_SHORT_JUMPS` and why), with the before/after numbers that justify it (corpus
   compile time, output size, write phase). Drop the per-slice recipe and conversion-script
   notes; they stay reachable through git history and `.todo/artefacts/a8*`/`a9*`.
2. Sweep the other `.kb` files for the same kind of leftover (a82-a91 touched ~15), keeping only
   dated one-line history where a present-day rule depends on it.
3. Rename the two tests to what they pin now (dead-method removal over the emitted class), and
   update every reference listed above in the same commit.
4. `./mvnw spring-javaformat:apply test` green; the renamed tests run.
