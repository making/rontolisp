# j54. Starlark: faster than CPython on every compiled backend

Difficulty: High

`.kb/starlark-frontend.md`, "Against the other implementations (2026-11-16)": rontolisp
beats Go Starlark on every program, but not CPython 3.12 everywhere (seconds, best of three,
whole process):

| program | JVM | WASM | component | CPython |
|---|---|---|---|---|
| `bench.star` | 0.16 | 0.07 | 0.07 | 0.12 |
| `fib.star` | 0.28 | 0.45 | 0.44 | 1.48 |
| `loop.star` | 0.20 | 0.69 | 0.64 | 1.82 |
| `calls.star` | 0.22 | 0.27 | 0.30 | 0.75 |
| `mixed.star` | 2.15 | 2.53 | 2.57 | 1.22 |

## Goal

Each program below, compiled to the JVM, WASM preview 1 and the component, runs in less
wall time than `python3` on the same file (best of three, start-up included, same
machine).

## Do

- Widen the set first. The five programs cover integers, calls and `range`; a Starlark
  program is mostly lists, dicts and strings. Add programs that a CPython user would call
  ordinary: list indexing and assignment (`a[i]`, `a[i] = x`), dict get/set/iteration,
  string building (`+`, `join`, `format`, `%`), comprehensions, `sorted`, method calls on
  values of unknown type. Each prints a checksum; check it against CPython and Go Starlark
  (`~/.local/bin/starlark -recursion`).
- Measure where each loser spends its time per backend (a profile, or hand-edited
  lowerings as `.todo/artefacts/j51-starlark-typed-fast-paths/` did) before changing
  anything. Known: `mixed.star`'s `flags[j]` stays a generic call; `bench.star` on the JVM
  is mostly JVM start-up.
- Make what pays. A cost the backend imposes (JVM start-up on a 0.1 s program, say) is
  measured and recorded with the reason, not chased; the goal is then restated for that
  case in `.kb/starlark-frontend.md`.
- The conformance corpus (`StarlarkSpecE2eTest#conformance`) and the four-backend parity
  guard every change. Record before/after times and output sizes in
  `.kb/starlark-frontend.md`.
