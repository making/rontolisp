# g07 close-out: host-specific, not a product regression

## Measurements

- Local full suite (tree `c1d1f5598`, no workers active):
  `FetchSpecE2eTest` 259 run / 21 failures, all `[3]` NATIVE leg,
  `NATIVE printed ... Expected size: 261 but was: 191`. Log kept on the
  orchestrator host only (`/tmp/opencode/fulltest-final.log`, not in repo).
- Local solo rerun right after (load 1m 4.38): same 21, same 191/261
  (`/tmp/opencode/fetchspec-rerun.log`).
- CI `Verify native image` on the same tree range (runs `38481770107` at
  `cefdcc2a0` and `38498658217` at `ef274e5c8`): `FetchSpecE2eTest`
  259 run / 0 failures, NATIVE leg live (the `--native` pair is built and
  uploaded in the same job).

## Environment delta (same wasmtime both sides)

- Local: wasmtime 49.0.0, kernel 6.8.0-139-generic, 64 procs, shared box.
- CI: wasmtime 49.0.0 (runner image), ubuntu-24.04 runners.

## Conclusion

Identical tree, identical engine version: red on this host (twice,
deterministic counts), green on CI (twice). No product change can address a
host-only failure, and the gate (CI) is green — closing as investigated, no
fix. If it recurs, re-run the solo leg first: a differing count means a new
cause, the same 191/261 means this host again.
