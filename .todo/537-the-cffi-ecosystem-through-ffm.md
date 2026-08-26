# 537. Run the real CFFI: the ecosystem's C bindings, through FFM

Difficulty: High (the umbrella item; the children are sized individually)

Spiked 2026-08-26. Probes, the working backend and the full findings:
`.todo/537-the-cffi-ecosystem-through-ffm/` (`README.md` there is the record).

**The goal is the ASSETS, not an FFI.** rontolisp does not need a foreign-function
interface of its own -- `java:` and `objc:` already exist. What it does not have is the
one thing every C-binding library in Common Lisp is written against: `cffi`. cl+ssl,
cl-sqlite, static-vectors, cl-charms, cl-cairo, the whole binding half of Quicklisp is
`(:depends-on :cffi)` and `defcfun`. Today `cffi.asd` ends in `(error "Sorry, this Lisp is
not yet supported")` and every one of those libraries is unreachable here. So the item is
NOT "add a cffi-shaped API" -- it is **implement upstream CFFI's own backend seam and load
upstream's source**, the way every other implementation does.

The seam is `cffi-sys`: one package, ~30 names (`src/package.lisp`), one file per
implementation (`src/cffi-sbcl.lisp`, 403 lines; the ABCL one, JVM+JNA, is the nearest
relative). Everything above it -- the type system, `defcfun`, `defcstruct`, enums,
bitfields, the translate/expand protocol -- is portable Lisp we do not write and do not
maintain.

## What the spike established

`.todo/537-the-cffi-ecosystem-through-ffm/use.lisp` runs the real upstream CFFI on today's
`java -jar` build and ends in a live sqlite3 session: `defcfun`,
`define-foreign-library`/`use-foreign-library`, `foreign-funcall`, `with-foreign-object` +
`mem-ref`, `defctype` + `foreign-type-size`, `defcstruct` + `foreign-slot-value`,
`with-foreign-string`. Every portable file of upstream loads **unmodified** except
`strings.lisp`; four external gaps blocked the load, all small (item 540 and item 539).

That spike's backend is written against `java:` interop, which was the cheapest way to
reach `java.lang.foreign` without touching Java -- it is NOT the design. `java:` needs
reflection, so it cannot be INTERPRETED in the native binary (`.kb/java-interop.md`),
which is the one REPL people actually run. The real backend binds FFM directly, exactly as
`objc:` does (`am.ik.objc` -> `eval/ObjcInterop` -> `eval/ObjcBridge`), and for the same
reason.

## Scope

**The JVM family only**: the interpreter, the native binary, and the JVM class output.
Both WASM backends refuse a program that reaches the foreign primitives, permanently and
by name in `CompileFrontend` -- there is no FFM there and never will be. This is the
`objc:` precedent, stated the same way.

## Two things FFM buys that upstream CFFI does not have

- **Structures by value need no libffi.** Upstream signals "Unable to call structures by
  value without cffi-libffi loaded" and offers a restart that loads a system built around
  a C library. FFM passes and returns structs by value from a `StructLayout` built at run
  time, so `*foreign-structures-by-value*` can just work here. `cffi-libffi` stays
  unloadable and stops mattering.
- **`errno` without a helper.** `Linker.Option.captureCallState("errno")` reads the value
  the call left, with no separate `strerror` dance and no thread mismatch.

What it does not buy: **`cffi-grovel`**. Grovelling compiles and runs a C program to read
the platform's headers; that needs a C toolchain at load time and is out of scope. A
system that names `cffi-grovel` in `:defsystem-depends-on` must fail with a message that
says so (item 539). Most bindings do not: of the CFFI consumers in the local Quicklisp
cache, cl+ssl and static-vectors do not grovel.

## The children

| item | what it is | difficulty |
| --- | --- | --- |
| `.todo/538` | `am.ik.ffi` + the interpreter's foreign primitives -- the Java half | High |
| `.todo/539` | the `cffi-sys` backend in Lisp, and shipping upstream cffi as a built-in system | Medium |
| `.todo/540` | the four load blockers: `subsetp`, the babel shim, `asdf:operate`, `:64-bit` | Low |
| `.todo/541` | the native binary's downcall registration, and the JVM class output | High |
| `.todo/542` | the consumers that justify it (cl+ssl, cl-sqlite), the docs and the coverage | Medium |

Order: 540 and 538 are independent and come first; 539 needs both; 541 and 542 need 539.

## The one measurement that constrains the design

A downcall handle costs ~24 µs to build and ~0.5 µs to call. `defcfun` therefore must
cache by shape (library + symbol + descriptor), never per call -- and, since `.todo/476`
already found that a handle in a non-`static final` field defeats the JIT's constant
folding, the cache's read path deserves the same attention as `am.ik.gpu`'s.
