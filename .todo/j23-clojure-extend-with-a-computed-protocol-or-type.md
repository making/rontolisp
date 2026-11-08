# Clojure: `extend` of a protocol held in a variable, and `extend-protocol` of a computed type, are refused

Difficulty: Medium

Two libraries of the j07 round extend protocols with values computed at run time:

- specter 1.1.6, `com/rpl/specter.cljc:491-498` (`extend-protocolpath*`, behind the public
  `extend-protocolpath` macro): the protocol is a function PARAMETER, and the method map's
  function is built by `eval` at run time:

  ```clojure
  (defn extend-protocolpath* [protpath-prot extensions]
    (let [m (-> protpath-prot :sigs keys first)
          params (-> protpath-prot :sigs first last :arglists first)]
      (doseq [[atype path-code] extensions]
        (extend atype protpath-prot
          {m (binding [*compile-files* false] (eval `(fn ~params (path ~path-code))))}))))
  ```

  Load stops at `specter.cljc:496:13: No such protocol: protpath-prot`. It also reads the
  protocol map's `:sigs` (keys, `:arglists`).
- schema 1.4.2, `schema/core.cljc:191-218` (`extend-primitive`): the TYPE is an expression,
  the class of a core fn value: `(extend-protocol Schema (class @(resolve 'double)) (spec ...)
  (explain ...))` -- then `(Class/forName "[D")` and the other primitive array classes. Load
  stops at `core.cljc:203:3: extend-protocol takes a type name`.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defprotocol P (f [x]))
(defn ext! [proto t] (extend t proto {:f (fn [_] :ext)}))
(ext! P String)
(prn (f "s"))
(defmacro ext-class [expr] `(extend-protocol P ~expr (f [_#] :computed)))
(ext-class (class 1))
(prn (f 2))
```

- oracle: `:ext` then `:computed`
- interpreter and JVM class: `error: f.clj:2:22: No such protocol: proto`
  (`ClojureProtocolLowering`/`ClojurePredicateLowering`); the second half, run alone, stops with
  `extend-protocol takes a type name`.

## Analysis

`extend` of a computed MAP is supported (KB "`extend` of a computed map"); the protocol and the
type are still resolved at lowering. The oracle's `extend` is a plain function over a protocol
VALUE (the var's map: `:on`, `:sigs`, `:method-builders` ...) and a class value. Needed:

1. A protocol as a run-time value: `P` evaluates to something `extend`, `extends?`,
   `satisfies?`, `(:sigs P)`, `(:on P)` accept (what does `P` evaluate to today? measure). Then
   `extend` dispatches on it at run time, the row family chosen at run time.
2. A computed type in `extend`/`extend-type`/`extend-protocol`: any expression yielding a class
   value -- here `(class 1)` and `(class @(resolve 'double))`, the class of a core fn (j15's
   `clojure.core$NAME`), and `(Class/forName "[D")`.
3. specter's `eval` of a `fn` form at run time on the compiled backends (KB "lowering at run
   time": the JVM lowers again, wasm refuses by name) -- the interpreter and JVM path should
   work once 1 does; record what wasm does.

Size: the run-time `extend` path must stay out of programs that do not use it (the arm-family
gating every KB section on extensions describes).

## Done when

- The repro prints the oracle's lines on the interpreter and the JVM, and on wasm (or the KB names
  the refusal) -- ci-spec case.
- specter loads without the `extend-protocolpath*` hunk and `(extend-protocolpath ...)` from its
  README works; schema loads without the `extend-primitive` hunk. (Measured: in the oracle
  `(s/check double 1.0)` itself throws `No implementation of method: :spec of protocol:
  #'schema.core/Schema found for class: clojure.core$double` -- `extend-primitive` extends the
  class of the var's value at load time, so compare behaviour, not an assumed `nil`.)
