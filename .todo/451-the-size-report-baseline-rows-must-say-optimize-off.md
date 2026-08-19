# 451. The measurement baselines must SAY `--optimize=off`, not say nothing

Difficulty: Low

Child of `.todo/448`; needs `.todo/449`'s `off` spelling to exist first.

## The defect this prevents

`size-report/measure.sh` spells its unoptimized baseline as an EMPTY flag cell:

```
"hello_world_plain|programs/hello_world/hello_world.lisp||-W gc|$hello_expected"
"pi_approx_plain|programs/pi_approx/pi_approx.lisp||-W gc|$pi_expected"
"zlib_plain|programs/zlib/zlib.lisp||-W gc -W exceptions=y|filter"
```

and renders it as `(none)` (the flag-cell helper at ~L80). After the flip those
three rows re-measure the OPTIMIZED module while still being labelled the
baseline: `hello_world (none)` would go 152,080 -> ~538 and read as if the
compiler had shrunk 280x overnight. `.github/workflows/size-report.yaml`
re-runs daily and commits only when a number moves, so it would commit exactly
that, unattended, with no change behind it.

## The change

- The three `*_plain` rows take `--optimize=off`.
- The flag-cell helper's `(none)` fallback: with every row now carrying a flag
  it has no callers left in this table. Either delete it or leave it and say in
  the comment that no row uses it -- do not leave a `(none)` label that means
  "optimized" for a future row that omits flags.
- Re-run `size-report/measure.sh` and commit the regenerated
  `results/*.md` + `results/sizes.json`. The NUMBERS must not move: the
  `_plain` rows measure the same artifact under its new spelling, and every
  other row already names its level. A moved number here is a real finding,
  not a re-labelling -- stop and explain it.
- `size-report/notes/wasm-flags.md` is the prose appended below the table
  (`results/` is generated; edit `notes/`). Its baseline sentence, if it says
  "no flags", becomes "`--optimize=off`" -- and it is the natural place to say
  in one line that the flag is on by default now, since that report is where a
  reader comes to compare flag shapes.

## Also re-anchor, same item

- `examples/examples.yaml`'s header comment documents each backend token's
  command line and says "Every WASM compile passes `--optimize` (the dead-code
  tree-shaker)". Both statements survive the flip as *redundant* rather than
  wrong. Decide once and write it down: either strip the now-redundant bare
  `--optimize` from the four backend tokens in `ExamplesE2eTest` (~L316, L330,
  L336, L342) and this comment, or keep them and say in the comment that they
  are explicit for the record. Do not leave the comment claiming the flag is
  what makes the difference.
- The ~105 explicit `--optimize` occurrences under `examples/` (build scripts,
  `count-vowels/pom.xml`, the browser shims' comments) stay correct either way.
  Leave them: 28 of them are `--optimize=size`, which is not redundant, and a
  mass edit of the other 77 buys nothing and would churn every worker's
  build line.

## Acceptance

A `size-report/measure.sh` run on an unchanged tree produces a diff containing
nothing but the date and commit stamps -- which is exactly the condition the
daily workflow uses to decide whether to commit.
