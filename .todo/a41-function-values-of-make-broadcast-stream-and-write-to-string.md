# `#'make-broadcast-stream` components and `#'write-to-string` keywords

Difficulty: Medium

A built-in's function value takes the operator's standard lambda list
(`.kb/error-handling.md`, "A built-in's function VALUE") except for these two, whose
`BuiltinCallArity.STANDARD_WIDER` rows stay:

- `(funcall #'make-broadcast-stream s)` is `MAKE-BROADCAST-STREAM expects 0 arguments, got 1` on
  JVM/wasm and `supports the zero-argument (sink) form only as a value` on the interpreter. The
  call position builds the `%broadcast-stream` Gray instance; the prelude entry, the CLOS
  instance gates and the Gray rewrite are all decided from the program's own spelling, before
  the wrapper exists.
- `(funcall #'write-to-string 10 :base 2)` is `WRITE-TO-STRING expects 1 argument, got 3` on
  JVM/wasm. The call position binds printer variables (`expandWriteToStringKeywords`), whose
  `defvar`s and renderer are gated on `mentionsPrinterVariable`, which cannot see a wrapper.

Both need the wrapper and the gates it relies on to agree: e.g. a wrapper variant injected where
the program spells the name (as the prelude selection already keys on it), with the scans counting
the designator. Pin the twins in `ci-spec.yaml`. `read-from-string`'s row is `.todo/214`'s.
