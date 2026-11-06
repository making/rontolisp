# asdf:defsystem

`(asdf:defsystem name &rest options)`

Defines a **system** — a named collection of source files with load-order constraints — for a later [`asdf:load-system`](asdf-load-system.md), and returns the system name as a symbol. This is a limited, API-compatible subset of ASDF's `defsystem`: the options are plain data and are never evaluated. `name` is a literal designator (a string `"my-lib"`, a keyword `:my-lib`, or a symbol). Supported options:

- Metadata — `:description`, `:long-description`, `:version`, `:author`, `:maintainer`, `:license` (also `:licence`), `:homepage`, `:bug-tracker`, `:source-control`, `:mailto`, `:long-name`, `:properties` — accepted for `.asd` compatibility and ignored, except that a `:version` is read back by [`asdf:component-version`](asdf-component-version.md), written as a string or, in a `.asd` file, as `(:read-file-form "version.sexp")` or `(:read-file-line "version.txt")`.
- `:depends-on (system...)` — systems loaded before this one, located through the same search path as `load-system`.
- `:defsystem-depends-on (system...)` — systems real ASDF loads while the `.asd` is being **read**, so they are located the same way and loaded before `:depends-on`. They are not dependencies of the system (they never appear in [`asdf:component-sideway-dependencies`](asdf-component-sideway-dependencies.md)). A built-in shim system named here also **announces its features** to this system: `:defsystem-depends-on ("trivial-features")` is what puts `:unix` and `:little-endian` in force while this system's own clauses and component files are read. A third-party system announces nothing to the clauses, which are decided before it loads; what its files push onto `*features*` reaches this system's component files, which are read after it, as any push does.
- `:weakly-depends-on (system...)` — systems loaded like `:depends-on` when they can be found on the search path (or are built in), and skipped otherwise; nothing is downloaded for one. A loaded one is listed by [`asdf:component-sideway-dependencies`](asdf-component-sideway-dependencies.md).
- `:around-compile hook` — a function called around each component's load with one argument, a function that loads the component (and ignores any arguments). The hook is a lambda expression, a symbol naming a function, or a string that reads as either; a function it names may come from a `:defsystem-depends-on` system. It applies to the components below it unless one says otherwise, and `:around-compile nil` on a component means no hook. A component whose hook never calls its argument fails the load. The features the hook adds to `*features*` around that call hold for the component's `#+`/`#-` and for its macros. In a compiled program the hook runs at compile time and nothing else of it reaches the program.
- `:encoding :utf-8`, `:encoding :default` — accepted on a system or a component. Source is read as UTF-8; any other encoding is an error naming it.
- `:entry-point`, `:build-operation`, `:build-pathname` — accepted and ignored: they drive `program-op`, which has no meaning here.
- `:serial t` — each component implicitly depends on the previous one.
- `:pathname "dir"` — a directory prefix for every component of the system, so the components can be named bare (`(:file "main")` under `:pathname "src"` is `src/main.lisp`). A literal namestring or `#P"dir"` only; an empty string adds no directory level. A `:module` prefix and a component-level `:pathname` nest inside it.
- `:components (component...)` — the source files: `(:file "name" [:depends-on ("other"...)] [:pathname "path"])` names `name.lisp`, or `path.lisp` with `:pathname` (the extension is appended past a dot too, as in real ASDF; a `#P` literal names its file as written); `(:module "dir" [:serial t] [:depends-on (...)] [:pathname "other-dir"] :components (...))` prefixes its children with `dir/`; `(:static-file "name")` is accepted and contributes no source. A component may also carry `:if-feature expr`, which keeps its place in the load order but contributes no source when the feature expression does not hold. Components load in ASDF's plan order: in definition order, each after its `:depends-on` components (depth first), so a component declaring no dependency still loads after the components listed before it.
- `:class :package-inferred-system` — the system has no `:components` at all and its graph is derived from the sources: a sub-system name is a file path under the system's directory (`my-lib/util/text` is `util/text.lisp`, below `:pathname` when the system has one), and that file's leading `defpackage` names its dependencies. See the [Systems guide](../../guides/asdf-systems.md#what-is-and-is-not-supported).
- `:class name` — the class of the system, which its `:defsystem-depends-on` may define in its source: a symbol, a keyword or a string read as a symbol (`:class "hu.dwim.asdf:hu.dwim.system"`). A component type or a `:default-component-class` may name such a class too. An instance of the class is what [`asdf:find-system`](asdf-find-system.md) answers, and its methods run ([Systems guide](../../guides/asdf-systems.md#what-is-and-is-not-supported)).

The test-op wiring options `:in-order-to` and `:perform` are driven by [`asdf:test-system`](asdf-test-system.md). Any other option (`:output-files`, a computed `:pathname`, ...) or a component type no class answers for is an error naming the unsupported clause. Normally the form lives in a `NAME.asd` file next to the sources; a `.asd` file runs as Lisp, and its `defsystem` forms are read as **data**. An inline top-level `(asdf:defsystem ...)` in a program also registers the system. Component paths resolve against the directory of the `.asd` file (or of the defining source).

```console
;; my-lib.asd
(defsystem :my-lib
  :description "A small example system"
  :version "0.1.0"
  :serial t
  :components ((:file "package")
               (:file "main")))
```

The system loads `package.lisp` then `main.lisp` (`:serial t`), both from the directory of `my-lib.asd`. See the [Systems guide](../../guides/asdf-systems.md) for a complete project walkthrough.
