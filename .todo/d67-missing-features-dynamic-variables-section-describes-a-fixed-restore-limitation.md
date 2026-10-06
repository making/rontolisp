# d67. missing-features: the dynamic-variables section describes a restore limitation that is gone

Difficulty: Low

`doc/en/guides/missing-features.md` and its `doc/ja` twin, "Dynamic (special) variables", say
that on the compiled backends an error caught by a handler outside a special `let`, a `go`
across it and, on WASM, a `return` across an `unwind-protect` / `handler-case` do not restore
the binding. `.kb/dynamic-special-variables.md` ("Compile-path limitations", item 1) records
every one of those exits as restored on all four backends since 2026-09-12. On 2026-10-06 a
program binding a special across `handler-case`, `catch`/`throw`, `block`/`return-from`,
`tagbody`/`go`, `unwind-protect` and a deep recursion answered SBCL 2.2.9's text on the
interpreter, the JVM, Preview 1 and the component.

The same page's "Numeric tower" section says complex numbers are not supported, which
`.kb/jvm-complex.md` and `.kb/wasm-complex.md` contradict.

## Plan

- Rewrite both sections in both trees in one commit to what holds now, or drop what is no
  longer missing; check the page's other sections against `.kb` while there (305 covers
  `loop`).
