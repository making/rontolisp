# ASDF front-end gaps that block the verbatim lack/clack `.asd` files

Difficulty: 低 (all in `eval/AsdfSystems` + one interpreter arity fix; the
parser is data-only and each item has a clear precedent in the existing code)

Part of the Clack milestone `.todo/223`.

## 1. System-level `:pathname "dir"`

`lack.asd` opens with `:pathname "src"` and bare component names
(`(:file "lack")` meaning `src/lack.lisp`). Today only the COMPONENT-level
`:pathname` is supported (`.kb/asdf.md`); the system-level option is a hard
error — the very first blocker the spike hit:

    ASDF:DEFSYSTEM lack: unsupported option :PATHNAME (supported: ...)

Fix: accept a literal string `:pathname` at system level as a path prefix for
every component (same semantics as a module prefix; empty string adds no level,
computed values stay a hard error). `clack.asd` does not use it, but lack's
sibling `.asd` files (`lack-middleware-*.asd`) spell paths out and mix both
styles, so the prefix must compose with a component-level `:pathname` and with
`:module`.

## 2. Top-level `(register-system-packages ...)` is skipped

`lack-component.asd`, `lack-util.asd`, `lack-middleware-backtrace.asd` end with

    (register-system-packages "lack-component" '(:lack.component))

Today an unknown top-level form in a `.asd` is a hard error. Real ASDF uses
this to map package names to systems for `find-package-or-load`-style lookups;
our loader never consults such a map, and lack gives every package its
dotted NICKNAME in `defpackage` anyway. Parse-and-skip is enough (like
`in-package`/`defpackage`). Optional: record the mapping for future use.

## 3. `asdf:load-system` / `ql:quickload` keyword tolerance

lack's `find-package-or-load` calls `(asdf:load-system name :verbose nil)` at
runtime; the interpreter's `load-system` errors ("expects 1 argument, got 3" —
probed). Accept and ignore `:verbose` (and other keywords) on the interpreter's
`asdf:load-system` and `ql:quickload` (`:silent t` is the other spelling in the
wild, `load-with-quicklisp` passes it).

Runtime `(asdf:find-system name nil)` already works (probed: returns NIL for an
unknown system).

## 4. Verify: `lack/tests`-style secondary systems parse

With 1 fixed, the UNPATCHED `lack.asd` must parse whole: the one-line alias
systems (`(defsystem "lack/component" :depends-on ("lack-component"))`), and
the big `lack/tests` system (`:pathname "tests"`, nested `:module`,
`:perform`, `#+todo`) — `:perform`/`:in-order-to` are already tolerated, so
this should fall out; pin it in `AsdfSystemsTest` with the verbatim file shape.

## Test

`AsdfSystemsTest` cases from the verbatim `lack.asd` / `lack-component.asd`
shapes (system-level pathname prefix, register-system-packages skip, alias
system, the tests system). Spike verified the rest of the chain: with these
parsed, every lack/clack component file LOADS on the interpreter.
