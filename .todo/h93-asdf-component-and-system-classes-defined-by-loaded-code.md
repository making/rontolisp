# h93. ASDF component and system classes defined by loaded code (`:test-file`, `:coalton-file`, `hu.dwim.system`)

Difficulty: High

Measured 2026-11-04 (interpreter). The component-class surface reads the class out of the `.asd`'s
own SHAPE (`.kb/asdf.md`, "The component-class surface"); `:class` accepts only
`:package-inferred-system`. A class that a `:defsystem-depends-on` system defines in its SOURCE is
therefore refused, though ASDF's contract is that the dependency loads before the rest of the file
is read.

```
system pfix: unsupported component type :TEST-FILE (supported: :file, :module, ASDF's own
  component classes and the classes a defclass in the same .asd declares)
ASDF:DEFSYSTEM hu.dwim.def: unsupported :class "hu.dwim.asdf:hu.dwim.system"
  (the only supported class is :package-inferred-system)
```

Consumers, each `:defsystem-depends-on` + a class from that system's source:
- `prove-asdf`: `(:test-file "t1")`, `test-file` a `cl-source-file` subclass with `perform` methods on
  compile-op and load-op (`src/asdf.lisp`), plus a `compute-action-stamp :around`. 16 distinct
  `.asd` files in the 232 fetched releases name `:test-file`.
- `coalton-asdf`: `:coalton-file` components; `coalton.asd` depends on it.
- `hu.dwim.asdf`: `:class "hu.dwim.asdf:hu.dwim.system"`, a class named by a STRING read after the
  dependency loads; the class family are `system` subclasses with a `shared-initialize :around`.
  All of `hu.dwim.def.asd`'s systems take this path.
- `lass`: `lass-file` (`.kb/asdf.md` already records the gap).

Needed: after `:defsystem-depends-on` runs, resolve a `:class`, a component type keyword and a
`:default-component-class` against the live classes (`asdf`/current package, a string read as a
symbol), and give each instance the behavior of its methods: which file, which extension, and what
`perform` does. On the compile paths the build image holds the classes (`AsdImage`) and the program
needs the result as a plan plus the program's own methods. Decide that split first; the recorded
reason for ignoring `.asd` methods (`.kb/asdf.md`) is that they would only act in the build image.

Pin: prove's own `:test-file` consumer through `asdf:test-system` on all four backends (SBCL prints
the TAP lines and `All 1 file passed.`).
