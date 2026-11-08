# Clojure: `instance?` of a protocol interface from another namespace misses types defined after the call site

Difficulty: Medium

Found by the j07 probe round in two libraries:

- core.match 1.1.1, `clojure/core/match.clj:434` (`dag-clause-to-clj`), lowered before the
  pattern deftypes at lines 905-1680 that implement the protocol:
  `(if (instance? clojure.core.match.protocols.IPatternCompile pattern) (to-source* ...) (to-source ...))`.
  Here it answers false for a `LiteralPattern`, so `match` falls to the `to-source` multimethod,
  which has no methods: `No method in to-source for dispatch value: :clojure.core.match/literal`
  on the first `match`.
- core.logic 1.1.1, `clojure/core/logic.clj:131,140,176,949,2070` and
  `clojure/core/logic/protocols.clj:166,171,189`: `(instance? clojure.core.logic.protocols.IX x)`
  in helpers defined before the deftypes in `logic.clj`.

## Repro (2026-11-08, develop `a3c68a63f`) -- needs two namespaces

`deps.edn` `{:paths ["src"]}`;

```clojure
;; src/my/proto.clj
(ns my.proto)
(defprotocol P (f [x]))

;; src/my/impl.clj
(ns my.impl (:require [my.proto :refer [P]]))
(defn t? [x] (instance? my.proto.P x))
(deftype T [] P (f [_] 1))
(defn mk [] (T.))

;; main.clj
(require '[my.impl :as i])
(prn (i/t? (i/mk)) (i/t? 1))
```

- oracle: `true false`
- interpreter: `false false`

With the protocol in the SAME file (`(instance? user.P x)` above the deftype) it answers `true`,
and with the `deftype` above `t?` it answers `true` -- only the cross-namespace,
call-site-first order misses.

## Analysis

`.kb/clojure-frontend.md` (the protocol-interface paragraph, "A protocol's interface ...
`%clojure-implements-p`"): the call site answers from the class list of the types whose body
names the protocol, "quoted at lowering, so a type a later REPL input defines is not seen". A
type later in the same file is apparently found (the pre-scan's `BodyProtocols`), a type later
in the file that `require`s the protocol's namespace is not. The oracle's answer is dynamic: the
protocol's interface is a real class and every deftype implementing it, defined whenever, is an
instance. The fix is to read the list when the test runs (a per-protocol registry the deftype's
definition adds to, which `satisfies?` presumably already has: `satisfies?` answers `true`
here), not to widen the lowering-time snapshot -- a snapshot still misses a type from a file
loaded later or from run-time `eval`. Weigh the size note in that KB paragraph (two
`instance?` sites cost +694 B on P1) against a run-time read.

## Done when

- The repro prints `true false` on all four backends (a project-shaped ci-spec case if ci-spec
  can hold one, else the `ClojureProjectNamespacesTest` style), plus a type defined by a later
  `eval`/REPL buffer if the interpreter session supports it.
- core.match's and core.logic's `instance?` sites answer like `satisfies?` without the patches
  (j07 `core-match`/`core-logic` `patches.diff`, the `satisfies?` hunks).
- The KB paragraph is rewritten with the new rule and its size numbers.
