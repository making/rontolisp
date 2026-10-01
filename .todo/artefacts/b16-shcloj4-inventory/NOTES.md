# b16 artefact: shcloj4 gap probes (2026-10-01)

Oracle: `Clojure CLI version 1.12.6.1673` (Clojure 1.12.6).
rontolisp: `target/rontolisp-0.1.0-SNAPSHOT-exec.jar`
(`./mvnw -q -DskipTests package`), run as
`java -jar ...-exec.jar --source-language clojure probe.clj`.

Corpus: `https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip`
(67 `.clj` files, ~3000 lines).

## Refusal transcript (rontolisp output verbatim, oracle answers in b17-b22)

- `(letfn ...)` -> `unknown name: letfn` / oracle `120` -> b17
- named-`fn` + `recur` -> `recur outside loop` / oracle `15` -> b17
- `(println ::checking)` -> `auto-resolved keywords are not supported yet` -> b19
- `(mapv ...)` / `(filterv ...)` / `(mapcat ...)` -> `unknown name` -> b18
- `(rand-int 5)` / `(shuffle ...)` -> `unknown name` -> b18
- `(char 97)` / `(boolean 1)` / `(symbol "a" "b")` / `(name :foo/bar)` /
  `(assert ...)` / `(ffirst ...)` -> `unknown name` -> b18
- `(into [] (map inc) [1 2 3])` -> `transducers are not supported yet: into`
  -> non-goal (b16)
- `(re-find #"a+" "aaab")` -> `regex literals are not supported yet` -> b21
- `(make-array String 2)` -> `unknown name: make-array` -> b20
- `(defmethod mm String ...)` over `(defmulti mm class)` ->
  `unknown name: String` -> b19
- `(every? Character/isWhitespace "   ")` -> `unknown name` -> b20
- `(System/currentTimeMillis)` zero-arg -> `NoSuchFieldException`
  (field spelling; method needs `(. Class m)`) -> b20
- `KeyEvent/VK_LEFT` as value (even with `:import` wired; call-position
  imports verified working incl. vector/multi forms) -> `unknown name` -> b20
- `(set! *warn-on-reflection* true)` -> `set! is not supported yet` ->
  non-goal (b16)
- `(ns foo (:require [clojure.set :as s]))` -> `unknown namespace:
  clojure.set` -> non-goal (b16); `clojure.string` `:as`/`:refer`/bare all green
- `(file-seq ".")` / `(reader ...)` / `(load-string ...)` /
  `(read-string ...)` / `(eval ...)` -> named refusals / `unknown name` ->
  non-goal (b16)
- `(proxy-super ...)` -> `proxy-super is not supported yet` -> non-goal
- `(proxy [Object] ...)` single non-interface -> runtime
  `java:proxy expects an interface` -> non-goal (multi-proxy + GUI)
- `(println *in*)` -> `unknown name: *in*` -> b20
- `(defprotocol P :extend-via-metadata true ...)` -> refusal -> non-goal
- `(defprotocol P (m [x] [x y]))` + record impl -> `multi-arity protocol
  methods are not supported yet` -> non-goal
- `(binding [*p* 1] ...)` without `^:dynamic` -> `needs a ^:dynamic var`
  (oracle 1.12 also rejects: `Can't dynamically bind non-dynamic var`) ->
  corpus idiom fix, not a gap (`pi.clj` `*random*`)

## Green baseline (spot probes, all identical to oracle modulo documented notation)

destructuring (`&`/`:as`/`:keys`/`:or`/nested), `->`/`->>`/`doto`,
`doseq`/`for`+`:when`/`dotimes`, `iterate`/`repeat`/`cycle`/`take`,
`map-indexed`/`keep-indexed`, `update`+extra-args/`get-in`/`assoc-in`,
`partition` 3-arity, `sort`/`group-by`/`frequencies`, `defmulti` keyword
dispatch + `derive`/`isa?`, single-arity protocols/`reify`/`deftype`,
`->R` constructors, `defmacro`+syntax-quote, atom STM basics,
`(.. "abc" (substring 1) (toUpperCase))` -> `BC`, `(Math/sqrt 4.0)` -> `2.0`.
