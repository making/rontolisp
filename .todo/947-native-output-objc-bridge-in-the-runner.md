# `--native` for `objc:` / `appkit:` / `metal:` / `scene:` programs: an Objective-C host in the runner

Difficulty: High

Depends on .todo/943 (and 944 for a signed macOS output). Today both WASM backends refuse a
program that references `objc:` or a library built on it (`AppKitLibrary.firstObjcReference`,
`CompileFrontend`); `.kb/objc.md` calls that refusal permanent because a wasm runtime offers no
FFM. A `--native` output is different: its host is the Rust runner stub, which can call the
Objective-C runtime itself. So a GUI program could ship as one ~2 MB executable instead of a
jar plus a bundled JRE.

## Shape

The wasm-GC module imports a small `objc` host surface; the runner implements it in Rust over
libobjc / AppKit (and Metal for `metal:`); the compile path lowers the `objc:` verbs to those
imports the way the JVM backend lowers them to `ObjcBridge` calls. Only the `--native` target
accepts such a program: `-o x.wasm` keeps refusing it (no host provides the imports).

## What is needed (each point has a JVM-side answer to mirror in `.kb/objc.md`)

- **Sends.** `objc_msgSend` is not variadic-safe on arm64: one call per SHAPE, derived from
  `method_getTypeEncoding` (`TypeEncoding`). Decide how shapes cross the wasm boundary: a
  generic import carrying the selector and an argument buffer, dispatched by the runner over a
  shape table (Rust needs every shape compiled in -- mirror the closed table the native
  binary already serves), vs one import per shape. Measure call cost for both on a `metal:`
  frame loop.
- **Object references.** Native pointers as i64 handles in wasm; ownership is "one retain per
  wrapper, released on thread 0" -- on wasm-GC there is no finalizer, so decide between an
  explicit release, an autorelease scope per callback, or a host-side handle table swept at
  exit. This is the riskiest design point; settle it before the send surface.
- **Upcalls.** `objc:define-class` IMPs, target/action, timers and `on-main` bodies call back
  INTO the module: the runner exports trampolines for the closed IMP shape set
  (`v@:`, `v@:@`, `v@:@@`, `B@:@`, `@@:@`, `q@:@`) that re-enter the instance through a
  wasm-exported dispatcher keyed by a closure id. A Lisp error inside must be printed and
  contained, never unwound through a native frame.
- **Thread 0.** The runner's `main` IS thread 0: run the module on a spawned thread and park
  thread 0 in the CFRunLoop exactly as `RontoLispCli.main` does in the native binary, with
  `-[NSApplication run]` started by `appkit::%app`; every entry hops with `dispatch_sync_f`,
  inline on thread 0. wasmtime `Store` is not `Sync`: callbacks arriving on thread 0 while the
  module runs on another thread need a design (run the module ON thread 0 between run-loop
  turns, or a lock around the store) -- prototype this first, it decides whether the whole
  item is feasible.
- **Bytes / out-parameters / `:error`**, `objc:data` and Metal buffers: copies across linear
  memory or GC arrays; measure `metal:` frame cost.
- Tests: the objc corpus that runs on the interpreter and the JVM class output, on
  `--native`, macOS only. A GUI change is still verified by hand with
  `examples/macos/counter.lisp` (no test opens a window).

## First step

A spike: a hand-written wasm module + a runner with only `objc_getClass`, `sel_registerName`,
two send shapes, one `define-class` IMP shape and the run-loop hand-over, opening a window with
a button whose click prints. It answers the thread-0 / `Store` question and the handle-lifetime
question before anything is lowered.
