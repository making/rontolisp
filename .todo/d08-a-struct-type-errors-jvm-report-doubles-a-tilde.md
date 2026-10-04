# d08. The JVM's report of a struct accessor's type error doubles every `~` of its datum

Difficulty: Medium

```lisp
(defstruct pt (x 0 :type integer))
(pt-x "a~b")
```

The interpreter reports `Unhandled condition: PT-X: The value "a~b" is not of type PT`; a compiled
JVM class reports `"a~~b"` (measured 2026-10-04; the wasm-GC module outside EH mode traps without
a report). `%struct-type-error` (`LispMacroExpander.structTypeErrorDefun`) signals
`(error 'type-error ... :format-control (%text-control <rendered pieces>))`, and where the
program routes no report the typed signal's message is the `:format-control` value itself --
the text control, every `~` doubled -- which the JVM throws as the `RuntimeException`'s message.

Since 2026-10-04 a typed signal over `(%text-control v)` of a VARIABLE reports `v` and routes no
report (`signalsItsTextControl` / `textControlVariable`, `.kb/error-handling.md`, "The routing
gate"). Binding the rendered pieces to a variable inside the defun would give the struct error
the same treatment: the right message, and possibly the renderer gone from programs whose only
report reader it is. Measure both (the examples whose classes carry `%STRUCT-TYPE-ERROR`, e.g.
`examples/console/contact-book.lisp`, `examples/scheme/streams.scm`, the llm-from-scratch
chapters) and pin the report on all four backends (`standalone:` in `ci-spec.yaml`).
