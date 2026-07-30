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

**The invariant a wrapper must not break: a wrapper's arity is the OPERATOR's
arity, not the shape that was convenient to write.** The body puts the operator
in call position, where the backends inline it -- so anything the operator
decides STATICALLY (an argument count, a result type, a file mode) is fixed in
the wrapper while the caller's real arguments are runtime values. A wrapper
narrower than its operator does not signal: the surplus arguments simply go
nowhere, which is a wrong answer on the compile backends and a correct one on
the interpreter (the same call reaches a real variadic built-in there). Three
worked answers to that, in ascending cost: a `&rest` fold
(`variadicIdentity`/`variadicNonEmpty` for `+`/`min`), a `&rest` dispatch onto
the literal shapes the operator needs (`openWrapper`'s direction/element-type
plist, `concatenateWrapper`'s result-type family --
[concatenate-result-families.md](concatenate-result-families.md)), and the
wrapper implementing the general case itself (`mapcarWrapper` walks the
list-of-lists in a `do` loop, because `mapcar`'s list COUNT is static in call
position; `alexandria:mappend` is `(apply #'mapcar f lists)` and used to get
every list but the first dropped). When adding a wrapper, check the operator's
CL lambda list first; the rest of the map family is still narrow on purpose and
tracked in `.todo/218`.

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
