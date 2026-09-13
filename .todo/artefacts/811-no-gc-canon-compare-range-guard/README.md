# 811: reproducing the two-compare range guard

`prototype.diff` is the change (against `c972efa5d`): `emitRangeChecks` switched to
`v != canon(v)` for `:s8`, `:s16`, `:s32` and `:u32`, with `:u8`, `:u16` and the 64-bit
lane left on their bound compare because the canon form is LARGER there.

Everything here expects two jars in this directory: `before.jar` (an unpatched build) and
`after.jar` (the same tree with `prototype.diff` applied). One `clean package` per
worktree -- two maven runs in one tree corrupt `target/` and void both results.

## Sizes

```bash
JAR=./after.jar
java -jar $JAR ../810-no-gc-dead-literal-length-headers/reactor.lisp \
     -o reactor.wasm --no-gc --no-wasi --optimize=size
node dsect.mjs reactor.wasm
```

930 -> 900, code 230 -> 200 (two `:s32` result narrowings). `small.lisp` is the minimal
shape -- two `:string` imports, `fib`, one `:s32` result -- at 271 -> 256. `fnsizes.mjs`
prints per-function body sizes, which is where the delta is legible.

## The hand verification, without building anything

`canon.mjs` rewrites every `:s32` guard in an emitted binary to the canon form:

```bash
node canon.mjs reactor.wasm canon.wasm      # -> 930 to 900
wasm-tools validate canon.wasm
```

It can be composed with `810`'s `strip.mjs` in either order; both together give 856.

## Trap behaviour -- the thing that actually matters

The guard is a TRAP, so the test is which values trap, not which bytes are emitted.

```bash
./trap.sh     # before/after x off/size x export-results/import-arguments, under Node
./extra.sh    # the same boundaries on a second engine, wasmtime --invoke
```

`probe-exp.lisp` exports one identity per width; `probe-imp.lisp` passes each width to an
import. `trap.mjs` drives every width at its min, max, min-1, max+1, 0, +-1, +-2^bits
around both ends and the +-2^63 extremes, printing one line per call with `TRAP` where the
host saw a `RuntimeError`. The trap sets must be identical before vs after AND between
`--optimize=off` and `=size`; a lowering bug shows up as a level disagreement.

`probe-oracle.lisp` is the interpreter oracle: compiled WITH wasi, run under `wasmtime`,
stdout diffed against `java -jar ... probe-oracle.lisp` (`probe-oracle-interp.lisp` is the
interpreter-side spelling).

## The fold decision genuinely moves

`fold-1.lisp` .. `fold-4.lisp` call one `(:string :s32)` import from one to four sites. A
shorter guard tilts `chooseFoldedImports`, because a site's cost carries one guard per site
while the wrapper's saving carries exactly one. At two sites the decision flips from
not-folding to folding. Every outcome is smaller, so the flip is a win -- but a test that
pins WHICH imports fold would see it.
