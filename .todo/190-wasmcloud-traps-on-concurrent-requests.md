# 190 - A wasmCloud component traps on concurrent requests

`wash dev` (2.5.2), `examples/db/postgres-web.lisp` (a PostgreSQL connection per
request), 12 concurrent POSTs, measured 2026-07-27: **10 succeed, 2 answer 500**
and wash logs

```
P3 run_concurrent failed err=error while executing at wasm backtrace:
    wasm trap: cast failure
P3 component task ended before producing a response
```

Sequentially the same program is 12/12. The same component under
`wasmtime serve` is 12/12 concurrent as well (its log shows interleaved task
ids, so those requests genuinely overlapped), so this is wasmCloud-specific --
or at least it needs wasmCloud's scheduling to show up.

## Not the two obvious causes

Both were isolated and came back **negative** under `wash dev`:

- **Module globals / special bindings.** The `.todo/189` reproduction (a handler
  that binds a special and does real work) answers all 8 concurrent requests
  correctly here. Whatever `.todo/189` fixes will not fix this.
- **The socket registry.** A handler doing nothing but
  `(rontolisp:tcp-connect ...)` + `(close ...)` per request -- no specials, no
  library -- is 12/12 concurrent. So `*sock-table*` / `*sock-next-fd*` under
  concurrent registration is not enough on its own to break.

## Where to look next

`cast failure` is how a **null global read** surfaces on wasm-GC (it is exactly
how the serve top-level-init bug announced itself -- `.kb/tcp-sockets.md`), so
the question is which state a second concurrent request finds uninitialised
inside one wash instance.

The instance model is the thing to pin down first, because the two observations
do not fit one story:

- **Sequentially**, wash re-runs the program's whole top level on every request
  (a `drop table` + `create table` startup pair emptied the table on each one),
  i.e. each request looks like a fresh instance.
- **Concurrently**, requests evidently share something, or there would be
  nothing to corrupt.

If wash starts an instance per request but lets a second request in before the
first has finished its serve-init `_start` (the `serveInitGlobalIndex` flag in
`WasmExportCompiler.emitBody`), then request two sees the flag already set and
runs against globals the top level has not filled yet -- which matches the
trap and matches why a program with NO meaningful top level (both isolations
above) never shows it. That is the first hypothesis to test: give the
socket-only reproduction a top level that defines a global the handler reads,
and hammer it.

Related: `.todo/189` (the JVM half of the same "concurrency matrix" session,
different cause), `.kb/tcp-sockets.md` (serve top-level init, wash's loopback
routing).

## Session 2026-07-30: the flag-race hypothesis did NOT reproduce

Tried to confirm the hypothesis above empirically (`wash` 2.5.2, `wasmtime`
46.0.1, macOS arm64, real `postgres:17-alpine` in Docker reached over the
host's LAN IP so wash's loopback virtualization is not in the way). Three
reproduction shapes, escalating toward the real program:

1. A top-level `(await (wait-for 2500))` + `(defvar *base* 41)` + a handler
   reading `*base*` — the minimal version of "next hypothesis to test" from
   above. 12/12 concurrent requests, every one correct (`42`), no trap.
2. A top-level `(tcp-connect ...)` + `(read-line ...)` against a slow
   (2.5s-delay) TCP peer, storing the result in a `defvar` a handler reads —
   this exercises `sockets.lisp`'s `*sock-table*`/`*sock-next-fd*` state
   during the same suspend window, closer to what `postgres-web.lisp`'s
   top-level connect does. 12/12 concurrent, no trap.
3. **The actual `examples/db/postgres-web.lisp`**, compiled `--component
   --optimize`, run under `wash dev` against a real PostgreSQL (the exact
   program from the bug report, with one change: `connect` had its
   `(uiop:getenv "DATABASE_URL")` replaced by a literal URL string — see the
   "separate finding" below for why). 12 concurrent POSTs to `/add`: 12/12.
   Repeated for 418 total concurrent requests across several bursts (3×12,
   5×24 GET/POST-mixed, then Apache Bench at `-c 50` and `-c 100`): **zero
   failures, zero traps**, table row count matched the POST count exactly
   every time.

So in this environment the described "cast failure" does not reproduce, even
at higher concurrency than the original 12-request measurement. This does not
rule out the hypothesis — it may need the original's exact host setup (Linux?
a real `wash app deploy`/wadm-managed instance rather than `wash dev`'s
lighter dev-mode hosting? more sustained load so instances live long enough to
actually get reused rather than each request getting a cold one?) — but it
means the hypothesis is still UNCONFIRMED, not confirmed-and-fixable. Per the
project's bug-fix discipline (a failing test comes before a fix), nothing in
`WasmExportCompiler.emitBody`'s serve-init flag was changed this session.

**False alarm, corrected**: the first pass of this session ran against a
`target/rontolisp` binary built 2026-07-27, three days stale. A bare
`(uiop:getenv "DATABASE_URL")` call in a served component trapped every
time against that binary, which looked like a candidate second bug — but
`.todo/217` (a served component cannot read the environment at all) had
already been fixed and closed on develop in the meantime (commit
`7eda857e`, 2026-07-30, before this session started). Rebuilding
(`./mvnw clean spring-javaformat:apply package -DskipTests`) and re-running
against the fresh jar still hit a trap through a `dev.environment:` YAML key
in `.wash/config.yaml` — but that is `database-url-parts` correctly erroring
"no database URL given" (an uncaught error, hence the bare `unreachable`
trap the file's own header warns about), because that YAML key was a guess
(inferred from the `wash` binary's own `DevConfig` struct field list via
`strings`, `wash dev --help` documents no such option) that simply does not
wire the variable through `wash dev` — not a code bug. All further testing
in this item, including the re-verification below, sidesteps the question
by hardcoding the connection string instead of exercising `wash dev`'s
env-var plumbing, which is orthogonal to this item's trap. No new `.todo`
item needed here.

**Re-verified against a freshly rebuilt current-HEAD binary**: reproduction
3 above was re-run after the rebuild (still with the literal connection
string, for the reason above): 3×12 concurrent POSTs, then Apache Bench at
`-c 50 -n 100` and `-c 100 -n 150` — 286 more requests, again **zero
failures, zero traps**, row count exactly matching the POST count. Combined
with the stale-binary run, 704 total concurrent requests across this session
without reproducing the trap.

**A real (if unconfirmed) ordering quirk found while reading the code**:
in `WasmExportCompiler.emitBody`, the serve-init block (`call $_start`,
lines ~394-422) runs **before** the block that re-establishes the `CURRENT`
task global for this invocation (`_task_begin` / the `CURRENT := null` reset,
lines ~437-449). So if `_start` itself suspends through the async machinery,
whatever it touches during that suspend runs while `CURRENT` still holds
whatever a *previous* call into this (possibly reused) instance left it as,
not a value scoped to this call. This was not proven to cause the trap in
this item (reproduction attempt 2/3 above exercises exactly this path and
did not trap), but it looks like a latent correctness gap independent of
this bug — reordering so `_task_begin` (or the `CURRENT := null` reset) runs
before the `call $_start` looks like the safe direction, but wants its own
reproduction/pinning test before touching it, per the same discipline.

## Closed 2026-07-30: could not reproduce

704 concurrent requests across three reproduction shapes (including the
verbatim `postgres-web.lisp` program the original report used, against a
real PostgreSQL, up to `-c 100`), on both a stale and a freshly rebuilt
current-HEAD binary, produced zero traps. Closing for lack of a
reproduction; if it resurfaces, the leads below are where the next attempt
should start:

- Match the original host setup more closely: a real wasmCloud deployment
  (`wash app deploy` / wadm, not `wash dev`) with sustained traffic (so
  instances are more likely to be actually reused across requests instead of
  each request racing a cold one), ideally on Linux.
- Capture the wasmtime/wash backtrace in full (the original report only has
  the two summary lines) — a symbol-address backtrace resolved against the
  `.wasm` (e.g. via `wasm-tools` or a debug build) would pin down which
  global/read actually casts on null, confirming or refuting the
  `serveInitGlobalIndex` theory directly instead of by inference.
- The `_task_begin`-after-`_start` ordering quirk noted above is unconfirmed
  but still real on inspection; worth a targeted look if a reproduction ever
  turns up.
