# Clojure: a record or deftype name used as a value is `unknown name`

Difficulty: High

A `defrecord`/`deftype` name is accepted where the front end recognizes a class position
(`instance?`, `new`/`R.`, `extend-type`, a protocol body, `defmethod` dispatch value), but
anywhere else the symbol is a compile-time `unknown name`. Three libraries of the j07 round use
the class as an ordinary value:

- specter 1.1.6, `com/rpl/specter/impl.cljc:473`:
  `(contains? #{DynamicPath DynamicVal DynamicFunction} (type o))`.
- rewrite-clj 1.3.59: `(node/make-printable! CommentNode)` and 14 more calls passing a node
  record class to a function (`rewrite_clj/node/*.cljc`, e.g. `comment.cljc:24`).
- schema 1.4.2, `schema/macros.clj:86` `defrecord-schema`: `` `(do (defrecord ~n ~@args) ... ~n) ``
  -- the class is the `do`'s value.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defrecord R [a])
(deftype T [])
(defn reg [c] (str c))
(prn (contains? #{R} (type (->R 1))) (= T (type (T.))) (reg R) [R T])
```

- oracle: `true true "class user.R" [user.R user.T]`
- interpreter and JVM class: `error: f.clj:4:19: unknown name: R`

```clojure
(do (defrecord R [a]) R)
(prn (R. 1))
```

- oracle: `#user.R{:a 1}`; here `error: f.clj:1:23: unknown name: R`

## Analysis

What `(type x)`/`(class x)` of a record answers today must be the value the bare name evaluates
to, so `(= R (type r))`, set membership and map keys agree. `.todo/h09` decides what a class
value IS on the host backends (`java.lang.Class` objects, generated for records and deftypes);
this item is the lowering half, needed on every backend whatever h09 decides: a record/deftype
name in value position evaluates to the type's class value (on wasm, the designator `type`
answers there). Take care of: a local or a var of the same name shadowing it (the oracle: a
local wins, a var named like a class -- check), a name used before its `defrecord` later in the
file (the oracle: `Unable to resolve symbol`), the record printed (`user.R`), `str` of it
(`"class user.R"`), and `(defmethod print-method R ...)`, which already works and must keep
working.

## Done when

- Both repros print the oracle's lines on all four backends (ci-spec case; on wasm the printed
  form follows whatever h09/the KB fixes for a class value there, stated as a deviation in
  `doc/{en,ja}/clojure/deviations.md` if it differs).
- specter, rewrite-clj and schema run without the hunks of j07's `patches.diff` that rewrite
  these sites (rewrite-clj's `make-printable!` calls also need j14).
