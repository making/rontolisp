# JVM runtime `_eval`: CHARACTER (`int[]`) is not treated as self-evaluating

A CHARACTER value that reaches the JVM `_eval` runtime interpreter is silently
walked as if it were a compound form, producing `nil`. The interpreter and the
compile path both give the right answer; only JVM `_eval` disagrees.

## Symptoms

    (eval #\A)                            ; interp -> #\A     JVM -> NIL
    (eval '#\A)                           ; interp -> #\A     JVM -> NIL
    (characterp (eval #\A))               ; interp -> T       JVM -> NIL
    (eval '(eq (code-char 65) #\A))       ; interp -> T       JVM -> NIL
    (eval (list 'eq (list 'code-char 65) #\A))  ; interp -> T JVM -> NIL

`(code-char 65)` inside `eval` works fine on JVM (the cons is walked, the call
is applied, a fresh `int[]{65}` comes back). It is only the bare CHARACTER
value -- a Java `int[]` -- reaching `_eval` that misbehaves.

Explicitly re-wrapping in `(quote ...)` recovers the value because QUOTE is a
special form that returns its argument verbatim without walking:

    (eval (list 'quote #\A))              ; JVM -> #\A (works via QUOTE fast path)

And the direct compile path is unaffected:

    (eq (code-char 65) #\A)               ; JVM -> T (compile-path _eqv int[] branch)

## Root cause

`JvmEvalRuntimeBuilder.evalBody` clause 1 enumerates the self-eval types
(`JvmEvalRuntimeBuilder.java:1511-1542`): `null`, `Long`, `Double`,
`BigInteger[]`. **`int[]` (a length-1 `int[]{codePoint}`, the CHARACTER
representation since todo 153) is missing.** A CHARACTER therefore falls
through the atom guards, fails the String and Object[]-cons checks, and
returns nil.

Same shape as [[164-wasm-eval-type-char-self-evaluating]]: CHARACTER became a
first-class value on every backend in todo 153, but the JVM `_eval`
self-evaluation enumeration was never taught about it.

## Plan

Add an `instanceof [I` (int[]) self-eval clause in `JvmEvalRuntimeBuilder`
alongside the existing `Long` / `Double` / `BigInteger[]` clauses. Placed
before the string check (same order as the WASM fix in todo 164).

Care needed: `int[]` is the CHARACTER shape today. If any other value type
would ever be represented as `int[]` (e.g. a bit vector), the clause needs a
length check (a CHARACTER is always length 1). Cross-check the discriminator
inventory in `.kb/characters-code-points.md` before landing.

## Verification

- New `LispEvaluatorTest` / `JvmLispCompilerTest` cases pinning
  `(eval #\A) -> #\A`, `(characterp (eval #\A)) -> T`,
  `(eval '(eq (code-char 65) #\A)) -> T`, and the list-built variant.
- Extend the ci-spec `eq-on-characters-by-code-point` case (or a sibling
  `eval-passes-characters-through-verbatim` case) with the eval-through
  variant so it runs on every backend byte-identically -- pairs with the
  matching WASM fix in [[164-wasm-eval-type-char-self-evaluating]].

## Discovered by

Exhaustive verification of todo 162 (`(eq #\A #\A)` byte-identity across the
four backends). Todo 162 was in scope for the WASM compile path only; this
JVM runtime-eval gap is a separate bug in the same neighbourhood.
