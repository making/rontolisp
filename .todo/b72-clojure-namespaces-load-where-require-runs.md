# b72. Clojure project namespaces load where their `require` runs

Difficulty: High

b56 lowers a required namespace's file once per program read and hoists its forms ahead
of the top-level form whose lowering loaded it (`.kb/clojure-frontend.md`, "Namespaces and
project files"). The oracle loads when the `require` executes, re-runs the file under
`:reload`/`:reload-all`, and loads a namespace once per process, whichever file asks.

Measured differences:

- shcloj4 `test/examples/test/preface.clj`:
  `(is (= "hello\n" (with-out-str (use :reload 'examples.preface))))` inside a `deftest`.
  The file's `println` runs ahead of the test form and the capture is `""` (1 failure; the
  oracle passes).
- A Common Lisp program that `load`s two `.clj` files requiring one namespace lowers and
  runs that namespace once per file.

## Design notes from b56

- Shape: the namespace's `defun`s stay top-level; its statements go into one init the
  `require` site calls behind a `(defvar |c%n%loaded| nil)` flag (`:reload` calls it
  unconditionally). `%init`/`%loaded` are safe suffixes: a lone `%` followed by a letter
  other than `c` is spelled by no mangled identifier.
- `GlobalVarCollector` registers a global only for a top-level `setq` or one nested in a
  top-level non-`defun` form (`collectNestedAssignedNames` does descend into lambdas
  there): an init `defun` hides its `setq`s. A top-level
  `(setq |c%n%init| (lambda () ...))` keeps them visible.
- `SpecialVarCollector.collectDeclared` reads `defvar`/`defparameter` only at a form's
  head: a `^:dynamic` `def` moved into the init needs a hoisted `(declaim (special x))`.
- One init body per namespace meets the JVM's 64 KB method limit and wasm's function-body
  cap for a large namespace, while top-level forms are chunked (`WasmToplevelEmit`): split
  the statements into chunks.

## Acceptance

- The preface shape passes on all four backends (a `ClojureProjectNamespacesTest` case).
- `:reload` re-runs a namespace (`defonce` keeps its value, `def` resets); a `require`
  inside a body loads when the body runs.
- A namespace required by two separately lowered files runs once.
