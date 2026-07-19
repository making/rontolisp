# Core representation

The low-level encoding and pipeline invariants of the value/AST representation
shared across the interpreter and the two compilers. Most of these facts had no
other `.kb` home (they lived inline in CLAUDE.md's "Core representation" bullet
list); the two that do are cross-referenced below rather than duplicated.

## JVM Class Version 61 (Java 17)

Emitters still write frame-free version-50-style code; `am.ik.jvm.StackMapAugmenter`
(an offline post-pass at the end of `JvmLispCompiler.compile()`, after the optional
shake) computes the mandatory StackMapTable frames and stamps the version. Compiled
classes need a Java 17+ JRE.

See [stackmap-augmenter.md](stackmap-augmenter.md) for the full detail.

## WASM function types outside rec group

wasmtime's WASI host requires plain `(func ...)` types for imports; only the cons
struct goes inside a rec group.

## symbolp/stringp

Quoted symbols and string literals share a runtime representation, distinguished by
a leading `"`.

## consp in JVM

Cons cells and function references are both `Object[]`, distinguished by
`arr[0] instanceof Integer`.

## Three-pass compilation

Pass 1 collects defuns; 2a compiles defun bodies, 2b top-level, 2c iteratively
compiles lambda bodies (top-level must compile before lambda iteration).

## `%` prefix convention

Internal helpers outside the public API are `%`-prefixed (e.g. `%remf-tail`).

## Built-in function wrappers

`BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so
`#'+`/`#'car` work as first-class values -- internal encoding, not a real user
definition (Lisp-2).

## JVM method name mangling

`JvmLispCompiler.mangleMethodName()` maps `/ < > : .` to
`$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`/`$dot`, plus `%` -> `$pct`. `%` is legal in
a JVM method name, but OpenJDK's JVMCI uses the method name as a *format string*, so
a hot `%`-prefixed defun aborts its JIT compilation and prints a warning into the
program's stdout.

## Template-class embedding is a last resort

Prefer (1) macro expansion, (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`,
only then (3) an embedded Java template class (used by `java:` interop).

See [template-class-embedding.md](template-class-embedding.md) for the full detail.
