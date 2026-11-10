# A wall clock below the second (`local-time:now` carries no fraction)

Difficulty: Medium

No API reads the calendar time below a second. `get-universal-time` counts whole seconds, and
`get-internal-real-time`'s milliseconds are only meaningful against another reading (its reference
page says so, though the interpreter happens to answer Unix milliseconds). `local-time` loads
(`.kb/asdf.md`, "local-time 1.0.6"), but its `now` has no fraction: `%get-current-time`
(`src/local-time.lisp`) reads the clock of allegro, cmu, sbcl, abcl, ccl and lispworks, and any
other implementation falls back to `get-universal-time` with 0 nanoseconds. On `d198df6`'s
interpreter:

```lisp
(local-time:format-rfc3339-timestring nil (local-time:now))   ; => "2026-11-10T23:17:03.000000+09:00"
(local-time:nsec-of (local-time:now))                         ; => 0
```

Found writing [counter-api](https://github.com/categolj/counter-api-lisp), a wasi:http component
writing an ECS line per request: its `@timestamp` stops at the second, so the lines of one second
cannot be ordered and a request's duration cannot be read from them.

## Goal

`local-time:now` carries the wall clock's fraction (microseconds at least) on the interpreter, the
JVM, Preview 1, `--component` and `--no-wasi`, and a program without `local-time` reads the same
clock through one function.

## Direction

- One primitive answering Unix seconds and nanoseconds, beside the time built-ins
  (`.kb/time-environment-builtins.md`): `Instant.now()` on the interpreter and the JVM, WASI
  `clock_time_get(CLOCK_REALTIME)` on Preview 1, `wasi:clocks/wall-clock`'s `now` (a `datetime`:
  u64 seconds, u32 nanoseconds) under `--component`, and under `--no-wasi` the cell
  `__ronto_set_time` already writes in nanoseconds (`.kb/wasm-export-no-wasi.md`).
- `local-time` reads it through a branch of `%get-current-time`, as it reads SBCL's
  `sb-ext:get-time-of-day` (seconds, microseconds). Where rontolisp carries that branch (a feature
  local-time tests, or the loaded source adjusted) is the implementer's decision, recorded in the
  `.kb/asdf.md` entry.
- The clock guide (`doc/*/guides/clock-and-random.md`) names the fraction's source per build.
