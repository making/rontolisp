# Clojure: `tap>` / `add-tap` / `remove-tap`

Difficulty: Medium

Refused by name: "taps have no rendezvous design" (`.kb/clojure-frontend.md`, the
"refused by name" row). clj and cljw 1.15.4 both run
`(add-tap println) (tap> :t)` and print `:t`. Debugging tools (Portal, Reveal-style
helpers) and library code call `tap>` unconditionally, so a refused call at run time
breaks programs that never add a tap.

Design: on the interpreter and the JVM, the oracle's single daemon thread draining a bounded
queue (`tap>` answers false when full); on the WASM backends, deliver synchronously at the
`tap>` call. Write the difference in `deviations.md`.
