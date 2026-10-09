# f06. Clojure: a function's arity refusal in the oracle's words

Difficulty: Medium

A call with a count no arity of a Clojure function takes throws `clojure.lang.ArityException`
on both sides, but the words differ. Measured 2026-10-09, clj 1.12.6 against the interpreter,
in `(ns my.app)`:

| Call | Oracle | Interpreter |
|---|---|---|
| `(one)`, `(defn one [x] x)` | `Wrong number of args (0) passed to: my.app/one` | `Function expects 1 argument, got 0` |
| `(apply one [1 2])` | `Wrong number of args (2) passed to: my.app/one` | `Function expects 1 argument, got 2` |
| `(two 1 2 3)`, `(defn two ([x] x) ([x y] y))` | `Wrong number of args (3) passed to: my.app/two` | `wrong number of arguments passed to: two` |
| `(anon)`, `(def anon (fn [x] x))` | `Wrong number of args (0) passed to: my.app/anon` | `Function expects 1 argument, got 0` |
| `(va)`, `(defn va [x & more] x)` | `Wrong number of args (0) passed to: my.app/va` | `Function expects at least 1 argument, got 0` |
| `((fn named [x] x))` | `Wrong number of args (0) passed to: my.app/eval176/fn--177/named--178` | `Function expects 1 argument, got 0` |

The lowered `clojure.core` workers already speak the oracle's words (`Wrong number of args (N)
passed to: clojure.core/NAME`, `.kb/clojure-frontend.md`); a `defn`'s refusal is the backend's
argument-count check (one arity) or the dispatch defun's (several). One visible consumer:
`rontolisp.http-client`'s verbs (`Wrong number of args (0) passed to:
babashka.http-client/get` in the oracle).

## What decides the design

- Where the words come from: the backends' count check is shared with Common Lisp and Scheme
  programs, whose words stay; a Clojure-side check costs every call unless it runs only on the
  refusal path (the check the backend already makes, handed the Clojure name).
- The name: `ns/name` for a var's function, the def'd name for `(def x (fn ...))`; a local `fn`'s
  gensym parts (`eval176/fn--177`) are not reproducible, so decide what it answers.
- The four backends together (`.kb/running-backends.md`), each count path: direct call,
  `apply`, `funcall` of the value, multi-arity dispatch, variadic.

## Plan

1. Measure the table on the JVM, a `--component` and a `--native` output.
2. Decide and implement; a `clojure-spec.yaml` case per row on all four backends.
3. `doc/*/clojure/reference/http-client.md` "Differences" loses its arity line;
   `doc/*/clojure/deviations.md` names what stays different (a local `fn`'s name).
