# `--optimize` should take a LEVEL, not grow a second flag

Difficulty: High

Carved out of `.todo/261` (the funcall-dispatch gate), which measured the numbers
below and deliberately did not take this on. Nothing here depends on 261's fix.

## The decision that shapes it

261 proposed a separate `--optimize-size` / `-Os` flag. **Rejected: two flags
whose names differ by one word do not tell anyone what either of them does**, and
a reader hitting `--optimize-size` in a build script cannot tell whether it
replaces `--optimize`, adds to it, or contradicts it.

The flag takes a VALUE instead: `--optimize=<level>`. The vocabulary is part of
the work -- pick names that say what the level is FOR, not how it is implemented.
The starting point:

| spelling | means |
| --- | --- |
| (flag absent) | no optimization -- unchanged |
| `--optimize` | today's behavior, unchanged |
| `--optimize=default` | a spelling of the bare `--optimize`, for a build script that wants the level written down |
| `--optimize=size` | `default` plus the two trades measured below |
| `--optimize=high` | proposed, **and it has no content today** -- see below |

**Backward compatibility is not negotiable.** `--optimize` with no value must
keep working and keep meaning what it means now: it is in every doc page, every
`.kb` passage, the CI jobs, the examples and the README.

**Do not ship a level that is an alias of another.** `high` as a synonym for
`default` teaches a user that levels are decoration. Either it gets real content
in the same change -- and the candidates need naming and measuring first, since
nothing in the compiler today is held back for being too aggressive -- or the
released set is `default` / `size` and `high` lands when there is something for
it to mean. Same test for any other level someone proposes.

## What `size` actually does (2026-08-05, `examples/db/postgres-hello --component`)

8.2 MB, 2618 functions, 8,139,447 B of code, with the dispatch gate out of the
picture:

- WASM code is **3.1x** the JVM bytecode for the same program (2,014,123 vs
  6,201,390 over the 1060 functions both emit). Integer-heavy library code is far
  worse: `IRONCLAD::UPDATE-SHA256-BLOCK` is 6,746 B on the JVM and 191,320 B on
  WASM (**28.4x**), `MD5:UPDATE-MD5-BLOCK` 14.6x.
- The two causes are both documented speed/size trades, and turning them off
  measures the price: integer fusion emits the tree TWICE (fast + generic
  fallback, `.kb/wasm-int-fusion.md`) -- worth 6.9% of the module -- and unboxed
  dual-representation locals (`.kb/wasm-unboxed-locals.md`) are worth 16.3%.
  Both together: 8,519,343 -> 6,656,381 (**-21.9%**), with the ci-spec pinning
  tests green either way (they exist to assert the two paths agree).

Both emitters already have a single decision point each, and both already have
ci-spec cases pinning that the fast and the generic path answer the same thing --
which is what makes the level cheap to trust, and what a new case must keep true.

## Implementation notes, grounded in what is there now

- **The CLI has no `=` parsing at all.** `CliOptions.build` reads `--key value`
  (space separated), and `--optimize` sits in `CliOptions.noValueKeys`. Moving it
  out of that set is NOT an option: `rontolisp app.lisp --optimize -o out.wasm`
  would then take `-o` as the value. So the parser grows an `=`-suffixed form
  while `--optimize` stays a no-value key. That piece is shared by any future
  valued flag, so it is worth doing as its own step with its own test.
- **`boolean optimize` is threaded a long way**: `RontoLispCli.compileToFile` /
  `compileRecorded`, then `WasmLispCompiler` (telescoping constructors, up to six
  positional booleans), `JvmLispCompiler`, `NoGcWasmCompiler`, plus 27 test
  classes that construct a compiler and `web/RontoPlayground`. Replacing it with
  an enum is mechanical but wide -- and the telescoping boolean constructors are
  how the signatures got this long, so adding a seventh positional argument is
  the thing NOT to do.
- **Docs**: `--optimize` is documented in `doc/{en,ja}/compiling/wasm.md` and
  `.../jvm.md` and in the CLI `--help` text. The level needs the bilingual update
  and a `-h` line that lists the values, plus the run-time cost of `size`
  (below) beside its size number.

## Open questions to settle with numbers, not by argument

- whether the two `size` trades switch independently or as one level (16.3% and
  6.9% are measured separately, but their interaction is not);
- what `size` costs in run time on the hot loops those two exist for (`md5`,
  `ironclad`, the `vec:`/`linalg:` kernels) -- the level is only honest with that
  number in the docs beside the size one;
- whether the JVM backend has anything worth a `size` level at all, or whether
  the level is WASM-only and says so (the 3.1x above says the JVM does not have
  this problem);
- what, if anything, fills `high`.
