# 955. wasmtime compile time is quadratic in the GC allocations of one function

Difficulty: Low

Found by `.todo/953`. The measurement on 2026-09-24 changed the plan. The reduction and the
real-program census are done, and the helper-function split is not built. Only the upstream
report is left, and it is on hold.

- **Reduced.** One function doing N x `struct.new $s; drop` on an EMPTY struct compiles in
  1.6 / 4.8 / 15.2 s at 5,000 / 10,000 / 20,000 under wasmtime 49's default `copying`
  collector. Register allocation is 3.8 of 4.4 s at 10,000.
- **Real programs reach it, but it does not decide their compile time.** The largest count is
  5,813, in the hello-ningle Worker's baked `%asdf-registry%` datum, which costs 1.8 s on one
  core. Every example outside the ningle/tiny-routes/clack Worker family stays at or below
  317. In each of those Workers another function compiles as long or longer. In ningle it is
  fast-http's `parse-request`: 46.6 s, the landing-pad refresh, now `.todo/957`. So splitting
  quoted data into helper functions would save no wall time on any measured artifact. Why it
  is not built, and when to build it: `.kb/quoted-data.md`.

Numbers, the reducer and the census tools: `.todo/artefacts/955-wasmtime-compile-quadratic-in-allocations-per-function/`.

## Left

- **File the report against `bytecodealliance/wasmtime`.** The draft is `upstream-report.md`
  in the artefact directory, and `min.py` is the attachment. **On hold:** filing needs the
  user's explicit word. Record the issue number in `.kb/quoted-data.md` once it exists.
  Before filing, re-run `minrun.sh` on the wasmtime version pinned at that time. If the
  quadratic is gone, close this item with the new numbers instead.
