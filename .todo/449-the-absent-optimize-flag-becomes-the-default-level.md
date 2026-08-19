# 449. The absent `--optimize` becomes `DEFAULT`; `NONE` gets the `off` spelling

Difficulty: Medium

Child of `.todo/448` -- read the parent for the measurement and for why this is
not one item. This is the flip itself: the smallest change that makes
`rontolisp app.lisp -o app.wasm` emit what `--optimize` emits today, and
`--optimize=off` the only way back.

Read `.kb/optimize-dead-code-elimination.md` ("The levels") first.

## The change

`compiler/OptimizeLevel`:

- `NONE(null)` -> `NONE("off")`. The enum CONSTANT keeps its name; only the
  `--optimize=` value it answers to is new. `spellings()` then reads
  `off, default, size`, which is what the unknown-level error message and both
  help texts print.
- `parse(null)` returns `DEFAULT` instead of `NONE`. `parse("")` (the bare
  flag) already returns `DEFAULT` and must not move -- that pin is the
  compatibility guarantee for every build script in the tree.
- The javadoc on `NONE` currently says it is "spelled by leaving the flag off
  entirely" and that "every module compiled without it stays byte-identical".
  Re-anchor both to `--optimize=off`: the byte-identity claim is still TRUE and
  is worth keeping as the thing this item promises, it just has a different
  spelling now.
- `parse`'s javadoc says `null` (flag absent) is `NONE`. Same.

Nothing else in `compiler/`, `codegen/` or `macro/` changes: every one of the
six `eliminatesDeadCode()` gates reads the level, never the flag.

Do NOT add a second flag (`--no-optimize`, `-O0`). The reason a level is a
VALUE and not a sibling flag is written down in `OptimizeLevel`'s class javadoc
and in the `.kb` file, and it applies to the off switch exactly as it applied
to `size`.

## The two help texts

- `cli/RontoLispCli.printUsage()` (the `--optimize[=LEVEL]` block, ~L1053):
  it currently reads as "add this to shrink the output". It has to read as "on
  by default; here is what it does and here is how to turn it off", with an
  `off` row beside `default` and `size` in the LEVEL list.
- `cli/TestCommand.printUsage()` (~L334) names `--optimize` in its "every
  compiler flag" list; check the sentence still parses once the flag is not
  something you add.

`--optimize` stays in `CliOptions.noValueKeys` -- moving it out would make
`rontolisp app.lisp --optimize -o out.wasm` read `-o` as the level. That
constraint is unchanged and pinned by `CliOptionsTest`.

## The pins that change

Three assertions in `compiler/OptimizeLevelTest` pin the OLD meaning by name,
and they are the only three failures the spike produced across the whole
7,903-test suite:

- `theAbsentFlagIsNoOptimizationAtAll` -> becomes "the absent flag is the
  DEFAULT level", and the NONE half of it moves to a new
  `theOffSpellingIsNoOptimizationAtAll`.
- `everyLevelIsDistinguishableAndOnlyNoneHasNoSpelling` -> every level now has
  a spelling; keep the distinguishability half verbatim (it is the "do not ship
  a level that is an alias" rule made mechanical) and re-pin `spellings()` to
  `off, default, size`.
- `anUnknownLevelNamesTheAcceptedOnes` -> the message text moves with
  `spellings()`. Keep `parse("SIZE")` and `parse("2")` rejected; add
  `parse("none")` and `parse("no")` rejected, so `off` is the one spelling and
  a near-miss is an error rather than a silent DEFAULT.

Add one pin that did not exist and is the whole promise of this item: a program
compiled at `--optimize=off` is BYTE-IDENTICAL to the same program compiled by
the pre-change binary with no flag, and a program compiled with no flag is
byte-identical to `--optimize`. The second half is expressible in-tree today
(`assertThat(compile(src, NONE)).isNotEqualTo(compile(src, DEFAULT))` plus the
CLI parse pin); the first half is a one-off check against a stashed pre-change
jar, and belongs in the commit message rather than as a test.

## Watch

- `-e`/`--eval` and the REPL never reach `compileToFile`, so the level is
  irrelevant there. Do not start rejecting `--optimize` without `-o`; it is
  ignored today and that is fine.
- `--dynamic` composes with every level and is NOT rejected. Under the flip,
  `rontolisp app.lisp --dynamic -o app.class` now runs the shakers with the
  dispatch gate held open -- a supported combination (`dispatchableFuncIds`
  returns every id), just one nothing exercised by default before.
- `--emit-wit` and `--emit-js-glue` narrow their output to what the module
  really imports when the level eliminates dead code (`WitEmitter.emit`'s
  `wasiInterfaces`, `HostGlueEmitter`). After the flip a build that emitted the
  full fixed surface starts emitting the narrowed one. That is the more correct
  answer and the `--component` path already ships it, but it is a visible
  change for anyone diffing generated glue.

## Acceptance

`./mvnw test` green; `rontolisp --help` describes a flag that is on;
`--optimize=off` byte-identical to today's flagless build and bare `--optimize`
byte-identical to today's, checked on the JVM and on all three WASM shapes.
