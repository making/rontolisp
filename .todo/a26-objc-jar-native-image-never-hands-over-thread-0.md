# An objc: jar built into a native image never hands thread 0 over

Difficulty: Medium

`-o counter.jar` of `examples/macos/counter.lisp`, built with `native-image` (agent config from
one `java -jar` run, GraalVM 25.0.3, macOS aarch64, measured 2026-09-27), builds and opens its
window, then hangs: no timer fires, no click is answered, `appkit:wait` never returns.
`RONTOLISP_OBJC_TRACE=1` prints only `sync inline on thread 0`.

Cause: in a native image `main` IS thread 0 (the `java` launcher is what parks thread 0 in a
`CFRunLoop` for a `java -jar`). The compiled `main` of a `usesObjc` class stays on the calling
thread (`JvmSizedMainTest#anObjcProgramKeepsItsMainOnThreadZero`), so the program itself runs on
thread 0, every `MainThread.sync` runs inline, and nothing ever runs the run loop that
`appkit::%app`'s `performSelectorOnMainThread: run` was queued on. Before the bridge shipped as
class files the same image crashed on `defineClass`; this route has never worked.

Plan: the compiled twin of `RontoLispCli.main`'s hand-over (.kb/objc.md, "AppKit belongs to
thread 0"). When the shipped `<Program>$ObjcMainThread.handOverRequired()` answers true, `main`
starts the program on a worker (sized like `JvmSizedMainBuilder`'s), parks thread 0 in
`runLoop()`, and the worker ends the process with `System.exit` -- keeping the uncaught-condition
report and exit code 1. Under `java`/`java -jar` the answer is false and the bytes' behaviour must
stay what it is. Failing test first: an `objc:` case in `ShippedBridgeNativeImageE2eTest`
(macOS-only; a timer that closes its own window, so no hand is needed). Then the GUI checklist in
CLAUDE.md on all routes, plus the native image.
