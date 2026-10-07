# e08. Interpreter: a Gray base class named before gray.lisp loads is unknown

Difficulty: Medium

```lisp
(print (find-class 'rontolisp:fundamental-stream nil))
(print (subtypep 'rontolisp:fundamental-stream 'standard-object))
(print (subtypep 'rontolisp:fundamental-character-input-stream 'rontolisp:fundamental-stream))
```

Measured 2026-10-07 (the program above is the whole program; `sb-gray:` on SBCL):

| Call | SBCL 2.2.9 | interpreter | JVM, P1, component |
|---|---|---|---|
| `find-class` | the class | `NIL` | the class |
| `subtypep` ... `standard-object` | `T` | `NIL` | `T` |
| `subtypep` input ... `fundamental-stream` | `T` | `NIL` | `T` |
| `(subtypep 'rontolisp:fundamental-stream 'stream)` | `T` | `NIL` | `T` |

The compile paths splice gray.lisp for any program NAMING a Gray name
(`GrayStreamsLibrary.process`). The interpreter loads it on the first Gray write, a `defclass`
over a Gray base class, or a definition on a protocol generic (`ensureGrayStreamsLoaded`
callers in `LispEvaluator`), so a name used only as data (`find-class`, `subtypep`) before any
of those finds no class.

## Plan

- Fixture first, four backends: the rows above as the program's first Gray references.
- Load gray.lisp on the interpreter where a class lookup misses a `rontolisp:` Gray base class
  name (the registry-miss seam `find-class` and `subtypep` share), not per operator.
