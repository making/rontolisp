# Clojure: `defmethod print-method` whose dispatch value is computed at run time is refused at lowering

Difficulty: High

Two libraries of the j07 round register printing for their types through a FUNCTION that takes
the class:

- rewrite-clj 1.3.59, `rewrite_clj/node/protocols.cljc:150-166`:

  ```clojure
  (defmacro make-printable-clj! [class]
    `(defmethod print-method ~class [node# w#] (write-node w# node#)))
  (defn make-printable! [obj] (make-printable-clj! obj))   ; obj is a fn parameter
  ;; then, per node type:  (node/make-printable! CommentNode)
  ```

  Load stops at `protocols.cljc:166:11: print-method of |obj| is not supported yet: the printer
  consults a print-method of a record, deftype, :type keyword or host class no core value
  belongs to only`.
- schema 1.4.2, `schema/core.cljc:109-121`:

  ```clojure
  (defn register-schema-print-as-explain [t]
    (defmethod print-method t [s writer] (print-method (explain s) writer))
    (defmethod pprint/simple-dispatch t [s] (pprint/write-out (explain s))))
  (register-schema-print-as-explain schema.core.Schema)   ; a PROTOCOL's interface
  (doseq [m [print-method pprint/simple-dispatch]]
    (prefer-method m schema.core.Schema clojure.lang.IRecord)
    (prefer-method m schema.core.Schema java.util.Map)
    (prefer-method m schema.core.Schema clojure.lang.IPersistentMap))
  ```

  Same refusal at `core.cljc:110:3`.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defrecord R [a])
(defn printable! [c] (defmethod print-method c [x w] (.write w (str "<R " (:a x) ">"))))
(printable! R)
(prn (->R 1))
```

- oracle: `<R 1>`
- interpreter and JVM class: `error: f.clj:2:22: print-method of |c| is not supported yet: ...`
  (also needs j13 for the bare `R` argument)

## Analysis

`ClojureDispatchLowering` resolves a `print-method` (and `pprint/simple-dispatch`) dispatch value
at lowering because the printer is not a general multimethod: it consults user methods only for
values no core value belongs to (KB "Dispatch", print-method). A computed dispatch value needs
the run-time registry: a `defmethod` evaluating its dispatch value when it runs and storing the
method under it, the printer consulting that registry for a record/deftype/`:type` value -- with
the oracle's `isa?`-based selection, which is what schema's `prefer-method`s are about: a schema
record is an `IRecord`, a `Map`, an `IPersistentMap` AND implements the `Schema` protocol
interface, and without the preferences the oracle throws `Multiple methods in multimethod
'print-method' match dispatch value`. So the item has three parts:

1. (Measured: a plain user multimethod already takes a computed dispatch value --
   `(defn reg! [dv v] (defmethod g dv [_] v))` answers like the oracle. Only the printer's
   multimethods refuse.)
2. `print-method`/`simple-dispatch` methods registered at run time for a record/deftype class.
3. Dispatch on a protocol interface (`schema.core.Schema`) and `prefer-method` between
   interfaces, for `print-method` -- the class hierarchy a record's printing walks (`IRecord`,
   `java.util.Map`, `IPersistentMap`, its protocol interfaces).

Size: the printer's arms are gated per program (KB "Dispatch"); a program with no computed
`defmethod` must stay byte-identical.

## Done when

- The repro and a schema-shaped case (a protocol-interface method plus the three
  `prefer-method`s, printing a record implementing the protocol) print the oracle's lines on all
  four backends (ci-spec).
- rewrite-clj loads without the `make-printable!` hunks and prints a node like the oracle.
  Measured 2026-11-08: the oracle prints a parsed node in record syntax even with the methods
  registered (`(prn (p/parse-string ";; c\n"))` -> `#rewrite_clj.node.comment.CommentNode{:prefix
  ";", :s "; c\n"}`; find out why before deciding what "like the oracle" means here -- the method
  is registered through a fn parameter, `print-method`'s dispatch is `(class x)`). Schema
  loads without the registration hunk and prints what the oracle prints: measured 2026-11-08,
  `(prn (s/maybe s/Int))` -> `#schema.core.Maybe{:schema #schema.core.Predicate{:p? #object[...],
  :pred-name integer?}}` -- the record's own printer wins over the `Schema` method there, which
  is exactly the `isa?`/`prefer-method` selection this item must reproduce.
