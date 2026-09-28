# `objc`: port everything to the new base, then delete the old verbs and bridge

Difficulty: Medium

Depends on .todo/a71 and .todo/a72 (`define-objc-method` must work on compiled output before
`objc:define-class` can go); .todo/a73 and .todo/a74 are not prerequisites.

## Port

Every use of the old ten names (`objc:send` alone: 606 occurrences in 62 files on 2026-09-28):

- the shipped libraries under `src/main/resources/am/ik/rontolisp/eval/`: `appkit.lisp`,
  `metal.lisp`, `scene.lisp` and the others that name `objc:`;
- `examples/macos/*`, `examples/browser/minesweeper/minesweeper-macos.lisp`, the
  `examples/browser/webgl-*` files that mention `objc:`;
- `doc/{en,ja}/guides/objc-appkit.md`, `doc/{en,ja}/compiling`, the per-operator pages under
  `doc/{en,ja}/reference/functions/` and `_catalog.yaml` (both languages, same commit);
- the test corpora (`src/test/resources`, `objc-native-corpus.lisp`) and the tests that pin the old
  verbs (`ObjcInteropTest`, `JvmObjcInteropCompilerTest`, `NativeObjcE2eTest`,
  `ObjcNativeLibraryTest`, `AppKitLibraryTest`, `MetalLibraryTest`).

A structure result changes shape (a list today, the manual's cons/vector on the new base): check
every send that answers one by hand, not by search-and-replace.

## Delete

The old exports and everything only they use: `eval/ObjcBridge`, `ObjcCaller`,
`LispObjcObject`, `codegen/jvm/JvmObjcTemplate`, `JvmObjcHandle`, the old verbs of
`eval/objc-native.lisp`, `am.ik.objc.ObjcClasses`' closed shape set if .todo/a72 left it, the
`LispNames.OBJC_*` constants for the old names, the gates keyed on them (`JvmObjcInteropCompiler`,
`WasmExprCompiler`'s `OBJC_SLEEP_INTERNAL` path, `PackageRegistry`, `ClosRegistry`),
`src/web/java/.../Target_ObjcInterop.java`'s substitutions, and native-image rows no remaining
selector needs. `objc:string` and `objc:class` going away also removes their clash with
`cl:string` / `cl:class` under `(:use #:cl #:objc)`.

## Done when

- No `objc:` name outside the new vocabulary remains in source, docs or tests.
- `.kb/objc.md` describes only the new base.
- `./mvnw -Pweb compile` and `./mvnw -Pnative clean package -DskipTests` pass.
- The GUI check from `CLAUDE.md` passes by hand: `counter.lisp` under `java -jar`, the native
  binary, `-o Counter.class` and `-o counter.jar`; `minesweeper-macos.lisp`, `life-macos.lisp`
  and `menubar.lisp` on `java -jar`, the native binary and `-o Life.class`; every
  `examples/macos` program starts and draws under `--native`.
