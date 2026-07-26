# `(type-of '(1 2))` crashes the JVM backend, and `class-of` on a lambda disagrees between the interpreter and WASM

Two independent defects in the same area, both found by the adversarial review of
`.todo/184` (which touched neither -- no compiler file changed there). Both are
pre-existing and neither is covered by any test.

## 1. JVM emits a call to `_hashP` in a class that has no `_hashP` (HIGH)

```console
$ echo "(print (type-of (list 1 2)))" > t.lisp
$ rontolisp t.lisp -o Prog.class && java Prog
Exception in thread "main" java.lang.NoSuchMethodError: 'boolean Prog._hashP(java.lang.Object)'
	at Prog.TYPE-OF(Unknown Source)
```

Interpreter prints `CONS`; both WASM backends print `CONS`; the JVM class does
not run at all. Same for `(type-of (lambda (x) x))` and `(type-of (make-array 3))`.

Cause: `JvmLispCompiler.programUsesAnyHashOp` scans the UNEXPANDED program for
`make-hash-table` / `gethash` / `remhash` / `clrhash` / `hash-table-count` /
`hash-table-p` / `maphash` and gates `JvmHashRuntimeBuilder` emission on it, but
`LispMacroExpander.expandClassOf` puts a `(hash-table-p v)` clause in the middle
of `class-of`'s `cond`, and that expansion runs AFTER the scan. So
`JvmHashTableCompiler` inlines an `invokestatic _hashP` into a class whose
runtime helper was never emitted. The sibling gate `programUsesAnyArrayOp` already
carries a comment describing exactly this hazard and compensates by listing the
derived names; the hash gate does not.

Prefixing the program with any hash operation (`(make-hash-table)`) makes it link
and run -- which is why the ci-spec corpus, where some case always uses a hash
table, never hit it.

Fix: add the names `expandClassOf` (and any sibling expander) introduces to the
hash gate, the way the array gate does. Then add a ci-spec case that calls
`type-of` / `class-of` on a cons, a function and an array in a program with NO
hash-table operation anywhere -- the corpus cannot express that, so it needs to be
a standalone compile-and-run test, or the gate list has to be derived rather than
hand-listed.

## 2. `class-of` on a user lambda: `T` interpreted, `FUNCTION` compiled (LOW)

```lisp
(print (class-of (lambda (x) x)))
;; interpreter -> T    wasm P1 / component -> FUNCTION    JVM -> defect 1 above
```

The interpreter implements `class-of` natively (`LispEvaluator`, over
`builtinTypeName`), whose `case LispFunction -> "function"` arm does not match the
closure object a `(lambda ...)` evaluates to, so it falls through to the default
`"t"`. The compile backends go through `LispMacroExpander.expandClassOf`, whose
`(functionp v)` clause matches. `(functionp (lambda (x) x))` itself agrees (`T`
everywhere), so only `class-of` / `type-of` disagree.

`FUNCTION` is the correct answer. Fix `builtinTypeName` to recognize the closure
representation, and pin it in the same ci-spec case as defect 1.

## Why they are filed together

Both are reached only through `class-of` / `type-of`, both are invisible to the
existing corpus, and one ci-spec case with no hash operation in it covers both --
but that case cannot go in `ci-spec.yaml`, which concatenates every case into one
program (`.kb`/`CLAUDE.md`: cases share global state and run in order), so the
"no hash op anywhere" precondition is unrepresentable there. That constraint is
the interesting part of this item: it is a class of JVM gating bug the corpus is
structurally blind to, and the same blindness applies to every other
`programUses*` gate.
