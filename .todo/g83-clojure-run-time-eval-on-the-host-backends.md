# Clojure: run-time `eval` / computed `resolve` / `require` / `load-string` / `intern` on the host backends

Difficulty: High

Every run-time compiler entry of the Clojure front end refuses: "no compiler runs at run
time" (`.kb/clojure-frontend.md` "Reading", "Macros"; the "refused by name" row of the
lowering table). Measured 2026-10-29 against clj 1.12 and ClojureWasm (cljw) 1.15.4, which
both answer:

| form | clj / cljw | rontolisp (interpreter) |
|---|---|---|
| `(eval (list '+ 1 2))` | `3` | `eval is not supported at run time` |
| `(eval (read-string "(+ 1 2)"))` | `3` | same |
| `((resolve (symbol "inc")) 1)` | `2` | `resolve of a computed symbol is not supported at run time` |
| `(let [n 'clojure.set] (require n) ...)` | works | `Could not locate n__init.class` (the local's name read as the namespace) |
| `(load-string (str "(+ 1" " 2)"))` | `3` | `load-string of a computed string is not supported` |
| `(intern *ns* 'zz 5) zz` | `5` | `unknown name: zz` at lower time |

The fourth row is also a plain bug: a computed `require` argument must not be read as a
literal namespace name.

Scope: the interpreter and the JVM (whether a compiled class carries the lowerer
interacts with `.todo/330`). The WASM backends keep a named refusal. Bringing the lowerer to run time is
what makes a REPL server (nREPL, which cljw ships) possible; record that as a follow-up
here rather than a separate item until eval works.

This creates a deliberate interpreter/JVM vs WASM split: document it in
`doc/{en,ja}/clojure/deviations.md` and pin the refusal on the WASM side.
