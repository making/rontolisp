# rontolisp:jvm-export

`(rontolisp:jvm-export 'name :params '(type...) :returns type :as "javaName")`

Declares a typed, **Java-callable** entry point on a compiled `.class`: the JVM
backend emits a `public static` method with a primitive/`String`/`byte[]`
signature next to the compiled `defun`, converting between Java values and the
internal representation at the boundary. It is the JVM twin of
[`rontolisp:wasm-export`](rontolisp-wasm-export.md) — the same directive shape,
the same type vocabulary, solving the same problem: a compiled `defun`'s untyped
method takes and returns internal representations no host can safely construct.
It is a compile-time directive, not an ordinary function: on the
**interpreter** it is a no-op that simply returns the named symbol, and the
**WASM** backends skip it, so the same source runs on every backend. See
[Export a JVM library](../../guides/jvm-library.md) for the full guide.

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:jvm-export 'fact :params '(:s64) :returns :s64)   ; => FACT
```

Compiled with `-o Fact.class`, the class then carries, next to the untyped
`FACT(Object)`:

```java
public static long fact(long n);
```

## Arguments

- A quoted symbol naming the top-level `defun` to export. It resolves in the
  current [package](../packages.md) like a `defun` name.
- `:params` — a list of boundary type designators, one per parameter. Omitted,
  `nil` or `'()` means no arguments.
- `:returns` — the result boundary type designator. Omitted, `nil`, `'()` or
  `:void` declares a `void` method (the Lisp return value is discarded).
- `:as` — the Java method name, as a string. Defaults to the Lisp name
  lower-camel-cased (`scaled-sum` becomes `scaledSum`), which is what a Java
  caller expects. The name must be a valid Java identifier (and not a Java
  keyword); a Lisp name whose derivation is not one (`*scale*`, `string=`)
  must be renamed with `:as`. A name that would duplicate a method already on
  the class — another export's, or a compiled `defun`'s own method name — is
  rejected at compile time.

The type designators are the same vocabulary `rontolisp:wasm-export` accepts,
mapped to Java types:

| Designator | Java parameter/return type | Notes |
| --- | --- | --- |
| `:s8` `:s16` `:s32` `:s64` | `byte` `short` `int` `long` | `:int` / `:long` are permanent aliases of `:s32` / `:s64`; the ranges coincide with Java's, so nothing to guard |
| `:u8` `:u16` | `int` | the smallest conventional Java carrier of the whole declared range; a value outside it throws |
| `:u32` `:u64` | `long` | a `:u64` value of 2^63 or more throws (it has no exact representation in the signed 64-bit integers the backend computes with) |
| `:float` | `double` | rontolisp has no single-precision float |
| `:bool` | `boolean` | `false` is `nil`, `true` is `t`; any non-`nil` result is `true` |
| `:string` | `String` | the wrapper converts to and from the internal string representation |
| `:s-expr` | `String` | s-expression text: read on the way in, printed (prin1 form) on the way out — any value except a function |
| `:bytes` | `byte[]` | an `(unsigned-byte 8)` vector as raw bytes, copied in each direction |

**The boundary carries the value exactly, or it throws** — the same
trapping-not-masking rule as the WASM boundary. An argument the declared type
cannot state (`300` through `:u8`, a negative `long` through `:u64`) throws
`IllegalArgumentException`; a result outside the declared range throws
`ArithmeticException`; a result of the wrong kind entirely (a `:string` export
whose function answered a number) throws `ClassCastException`. Nothing is
silently wrapped, masked or mis-decoded.

## The top level runs at class initialization

A typed method may be the first call into the class, so an export-carrying
class runs its top-level forms (`defvar`/`defparameter` initialization
included) in the class initializer — once, when the JVM first touches the
class — instead of in `main`. This is the JVM spelling of the `--no-wasi`
reactor's "runs its top level at instantiation", with the same two
consequences: a top-level form that signals surfaces to the Java caller as
`ExceptionInInitializerError` and poisons the class permanently, and a
top-level `(uiop:quit ...)` terminates the calling JVM. `main` (when
kept) still runs the program exactly once — invoking it triggers class
initialization first, and the JVM's own initialization locking is the
idempotence.

## Exports are kept under `--optimize`

Dead-code elimination (on by default) keeps every method reachable from
`main`; each export is an extra root, so a library whose functions are only
reached from Java survives with the default's size instead of needing
`--optimize=off`. See
[Compile to JVM Bytecode](../../compiling/jvm.md#optimize-dead-code-elimination).

## `--no-main`

A pure library wants no `main` at all: compile with `--no-main` and the class
is entered only through its exports (the flag requires at least one — `main`
is the only tree-shaker root otherwise). The twin of `--no-wasi` naming the
WASM reactor. See [Export a JVM library](../../guides/jvm-library.md).

## Limitations

- Only a top-level `defun` with fixed parameters can be exported: the declared
  parameter count must match its arity, and a lambda list taking
  `&optional`/`&rest`/`&key` is refused (it has no fixed Java signature) —
  wrap it in a fixed-arity `defun` and export that.
- The packed float array (`linalg:`/`vec:` values) is not yet a boundary type;
  `:bytes` is the only array designator today.
- The directive is top-level only, like `wasm-export`.
