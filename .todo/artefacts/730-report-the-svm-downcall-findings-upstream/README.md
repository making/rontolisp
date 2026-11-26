# 730: the upstream report, as it should actually be filed

Surveyed 2026-09-10 against github.com/oracle/graal before writing. **`.todo/730` assumed two
new issues; the survey says one comment and one issue**, and that is the finding:

- **The performance half is ALREADY OPEN upstream: [#12219](https://github.com/oracle/graal/issues/12219),
  internal ticket GR-75754, "[Native Image] Bad performance of FFM API"** -- filed 2025-09-23 from a
  Java/SDL game engine seeing a 20x slowdown, labelled `bug` / `native-image`, assigned, still open,
  with no attribution in the thread. What `.todo/727` has is exactly what that issue is missing: a `perf` profile, a per-shape cost model and a same-image
  comparison against `@InvokeCFunctionPointer`. So it goes in as a COMMENT
  (`comment-on-12219.md`) -- a second issue for one cause would split the evidence.
- **The build-crash half is NOT the closed issue it looks like.**
  [#9727](https://github.com/oracle/graal/issues/9727) reports the same `VMError$HostedError`
  ("unexpected input could not be handled: linkToNative") and was closed as COMPLETED on
  2025-08-27, but its trigger is a Quarkus/jline `DowncallStub.invoke` on Windows;
  [#7531](https://github.com/oracle/graal/issues/7531) (2023) is the same message again, also
  closed. Ours is a different path -- a downcall handle held by a class initialised at BUILD time,
  reached through `PolymorphicSignatureWrapperMethod.buildGraph` while the analysis inlines the
  call -- and it still fails on 25.0.4. That is a new issue (`issue-build-time-handle.md`), and it
  cites both closed ones so the triage sees the difference immediately.

## What to do

1. Post `comment-on-12219.md` on https://github.com/oracle/graal/issues/12219.
2. Open `issue-build-time-handle.md` at https://github.com/oracle/graal/issues/new (labels are the
   triage's to set). Attach or paste `BuildTimeHandle.java`, `build-time-handle.error.txt` and
   `meta/` (here).
3. Record the two numbers in `.kb/gpu.md` beside "An FFM downcall inside a native image costs".
   GR-75754 / #12219 is recorded there already, from this survey.

Both texts are self-contained (every number in them was measured by item 727), and nothing in either
identifies this project beyond the one sentence that says what the workaround was.
