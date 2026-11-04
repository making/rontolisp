# i09. cxml on the compile paths: a 497,828-byte JVM method, a WASM start-up trap

Difficulty: Medium

Measured 2026-11-04 with the walked copy (`.todo/i06`, `.todo/i00` and `xml/catalog.lisp`'s slot read
patched), program `(ql:quickload "cxml") (print :loaded)`.

- **JVM**: `method CXML::NAME-START-RUNE-P: method code exceeds the JVM's 65535-byte limit: 497828`.
  The name is defined twice in `xml/xml-name-rune-p.lisp`: at line 122 as a macro-time local
  function, which `predicate-to-bv` turns into a table, and at line 172 as the `sbit` lookup in that
  table. Find out which one the backend is emitting and what its body contains: run
  `-Drontolisp.jvm.debug-method-sizes=true` and read `.kb/jvm-method-size-limits.md`.
- **WASM preview 1**: compiles, then traps `unreachable` during start-up before `:loaded` prints
  (`<wasm function 8>`). Unattributed; build with names and find the first failing top-level form.

The interpreter loads and stops on `.todo/i01`, so nothing has run end to end yet. Measure after
i01 whether the compile paths need more than the two items here.
