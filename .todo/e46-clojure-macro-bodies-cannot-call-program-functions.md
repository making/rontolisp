# e46. A Clojure macro body cannot call the program's functions

Difficulty: Medium

A `defmacro` body runs at lower time in `eval/ClojureMacroTime`, an evaluator holding the
core builtins and `clojure.lisp` only (`.kb/clojure-frontend.md`, "Macros"). So a macro that
calls a helper `defn` -- the common library shape, and the oracle's (each top-level form is
evaluated before the next compiles) -- fails at its first expansion:

```clojure
(defn helper [x] (list 'inc x))
(defmacro m [x] (helper x))
(prn (m 1))   ; oracle: 2; here: in macro `m`: The function c%helper is undefined
```

The same holds across namespaces (a macro of a required library calling that library's
functions), which blocks library loading (`e43`). `clojure.template/do-template` works
around it by substituting in its own body; the oracle's calls `apply-template`. The CL
side already registers top-level `defun`s with its macro-time evaluator
(`.todo/044-defmacro-followups.md`, "Compile-time side effects").

## Plan

1. Hand each lowered top-level `defun` (entry file, loaded files, session buffers) to the
   macro evaluator lazily: record them in order, and evaluate the ones not yet evaluated
   before an expansion runs, so a macro-free program pays nothing.
2. Decide `def`: the oracle evaluates every top-level form, so a macro reading a table
   `def`'d above it works there. Evaluating a `def`'s value at lower time can have effects
   (a server started at compile time); measure what the libraries `e43` probes need first.
3. A function the body reaches through a per-program runtime (protocol dispatch, the
   ex-info runtime, hierarchies) needs those runtimes in the macro evaluator too.
4. Tests: clojure-spec lines with a helper-calling macro (four backends: the expansion is
   lower-time, so one answer everywhere), a macro of a required namespace calling its
   namespace's function, a session buffer defining the helper and a later one the macro.
