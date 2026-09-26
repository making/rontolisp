# Verify the shipped objc: bridge class files on macOS

Difficulty: Medium

The objc: bridge became program-named class files written beside the output
(`<Program>$ObjcBridge`, `$ObjcObject`, `$Objc*`) instead of a defineClass blob
(commit 74cd71c9d, .kb/template-class-embedding.md). It was built and unit-tested on
Linux only; nothing ran it on macOS.

Plan (CLAUDE.md "GUI change" checklist): with examples/macos/counter.lisp, run
`java -jar` of the CLI, the native CLI binary, `-o Counter.class --class-name Counter`
under `java Counter`, and `-o counter.jar` under `java -jar`; the window opens and
the button works on each. Also run the macOS-only objc tests and, opt-in,
ShippedBridgeNativeImageE2eTest (-Drontolisp.native-image.e2e=true) including an
objc: jar through native-image if feasible. Fix anything that breaks (failing test first).
