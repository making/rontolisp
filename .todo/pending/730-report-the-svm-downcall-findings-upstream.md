# 730. Report the two SubstrateVM downcall findings upstream (oracle/graal)

Difficulty: Low

Filed 2026-09-07 by `.todo/727`. Both reproducers are minimal, standalone and checked in under
`.todo/artefacts/727-an-ffm-downcall-costs-2-1-us-inside-a-native-image/`. **Surveyed and written
2026-09-10: the two texts are finished and paste-ready in
`.todo/artefacts/730-report-the-svm-downcall-findings-upstream/`. All that is left is a person
pressing post, and recording the two numbers afterwards.**

## What the survey changed

This item assumed two new issues. It is one comment and one issue:

- **The performance half is already open upstream** --
  [oracle/graal#12219](https://github.com/oracle/graal/issues/12219) (GR-75754, "[Native Image] Bad
  performance of FFM API"), filed 2025-09-23 from a Java/SDL engine seeing 20x, assigned, still
  open, and with no attribution in the thread. `.todo/727` has exactly what it lacks: the cause
  named in SVM's own source (`Target_java_lang_invoke_LambdaForm.forceInterpretation()`), a `perf`
  profile, the ~1.7 us + ~0.4 us/argument model, and the same-image `@InvokeCFunctionPointer`
  comparison at 10.7 ns. A second issue would split the evidence, so it goes in as a COMMENT:
  `comment-on-12219.md`.
- **The build crash is not the closed issue it resembles.**
  [#9727](https://github.com/oracle/graal/issues/9727) (closed completed 2025-08-27) and
  [#7531](https://github.com/oracle/graal/issues/7531) carry the same `VMError` text from an
  ordinary `DowncallStub.invoke` (Quarkus / jline, Windows). Ours is a handle held by a
  BUILD-TIME-initialised class, failing in `PolymorphicSignatureWrapperMethod.buildGraph` during
  inlining, and it still reproduces on 25.0.4. New issue: `issue-build-time-handle.md`, which cites
  both closed ones so triage sees the difference at once.

## What to do

1. Post `comment-on-12219.md` on https://github.com/oracle/graal/issues/12219 .
2. Open `issue-build-time-handle.md` at https://github.com/oracle/graal/issues/new (title is its
   first heading; labels are triage's). Attach `BuildTimeHandle.java`,
   `build-time-handle.error.txt` and `meta/` from the 727 artefacts.
3. Record the two numbers in `.kb/gpu.md` beside "An FFM downcall inside a native image costs" --
   the survey's half of that note is written there already -- and close this item.
