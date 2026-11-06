# i84. cffi-grovel: run the real groveller where a C toolchain exists

Difficulty: High

`cffi-grovel` is refused by name (`ShimLibraries.REFUSED`, `.kb/cffi.md`: "consumers were not
probed and never will be"). Measured 2026-11-06: 101 of the 6208 systems in the Quicklisp index
depend on it, among them osicat, cl-libuv (so cl-async, blackbird, wookie), iolib-adjacent
bindings. osicat stops at:

    Cannot load system 'cffi-grovel': grovelling compiles and runs a C program to read the
    platform's headers, which needs a C toolchain at load time -- rontolisp has none.

The premise is the toolchain, and it is the HOST's, not rontolisp's: `cc` is on this machine, and
the groveller is plain Lisp (it writes a `.c`, runs `cc` through `uiop:run-program`, runs the
binary, reads back a Lisp file of constants). The `:cffi-grovel-file` / `:cffi-wrapper-file`
component classes come from `:defsystem-depends-on`, which the class lookup already supports.

Plan: measure how far upstream `cffi-grovel` gets on the interpreter with `uiop:run-program`
(`.todo/363`), then decide where the generated constants live for a compile path (generated at
compile time on the build host, baked into the artifact; the WASM backends refuse `ffi:` anyway).
Keep the refusal with a sentence naming the missing `cc` when none is on PATH. Rewrite the
`.kb/cffi.md` line with the measurement either way.

Done when: osicat loads on the interpreter and the JVM with a C compiler present, or the
measurement that rules it out is recorded.
