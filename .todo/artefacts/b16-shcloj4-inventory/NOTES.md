# b16 artefact: shcloj4 corpus harness + gap inventory (2026-10-01)

Oracle: `Clojure CLI version 1.12.6.1673` (Clojure 1.12.6; `clj --version`
pinned 2026-10-01). Every semantic claim below was executed against it,
never taken from the book text.

rontolisp: `target/rontolisp-0.1.0-SNAPSHOT-exec.jar` built from the
identical commit (`c11202388`, both trees; main-tree jar used because this
item's single maven run is the test suite, which does not `package`),
run as:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar --source-language clojure probe.clj
timeout 60 clj -M probe.clj   # oracle
```

Corpus: `https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip`
(67 `.clj` files, ~3000 lines). In scope for the table: the 30 files in
`code/src/examples/*.clj`, the 6 in `code/src/examples/macros/*.clj`, the
2 in `code/hangman/src/hangman/*.clj`. Out of scope (not tabled):
`code/test/**` (mirrors of the same examples as `clojure.test` suites)
and `code/data/snippets/**` (2 editor snippets).

Harness: `probes/` (one runnable `.clj` per row below) + `capture.sh`
(regenerates `expected/`) + `run.sh` (re-runs every probe under both
runners and diffs against `expected/`; pattern-matched where the oracle
is volatile -- timestamps, temp paths, object hashes, directory counts).

Verdict classes: **green-identical** (byte-identical stdout) /
**green-noted** (runs; differs only by a documented notation from
`.kb/clojure-frontend.md`) / **gap-bNN** (rontolisp names the gap;
child todo) / **gap-b23..b25** (new gaps found in this item; new todos) /
**diverge** (both run, outputs differ beyond notation) /
**oracle-also-fails** (stale book idiom; the oracle rejects it too) /
**non-goal** (explicitly out of this round; rontolisp refusal quoted).

## Per-file verdict table

| File | Verdict | Evidence |
|---|---|---|
| `examples/preface.clj` | green-identical | `green-baseline.clj` `(println "hello")` -> `hello` / `hello` |
| `examples/introduction.clj` | gap-b20 + gap-b23 | `blank?` needs member-as-value (`b20-member-val`); `(swap! visitors conj username)` needs `conj`-as-value (`b18-conjval`); `hello-world`/`fibo`/`hello` shapes green (`green-shapes`, `green-fibo`) |
| `examples/trampoline.clj` | green-identical | named `fn` + `trampoline` over 10k mutual calls (`green-baseline`) -> `true` / `true` |
| `examples/chat.clj` | gap-b23 | `(alter messages conj msg)` / `(commute … conj …)` need `conj`-as-value (`b18-conjval`); record/defrecord/ref/dosync/deref otherwise green (`green-baseline`, `green-swapuser`) |
| `examples/test.clj` | non-goal | `clojure.test` harness (`ng-cljtest`: `unknown namespace: clojure.test`) |
| `examples/wallingford.clj` | green-noted | `declare` + mutual recursion green (`green-baseline`); `()` prints `nil` here vs `()` there (`green-shapes` first line) -- the documented nil-is-`()` position |
| `examples/replace_symbol.clj` | green-noted | keyword-dispatch multi + `lazy-seq` + `coll?`/`defn-` green; the `:collection` method answers an explicit `lazy-seq`, which prints `#<LazySeq>` here vs realized there (`green-baseline` 5th line) -- the documented never-print-lazy position |
| `examples/male_female.clj` | green-identical | `declare` + mutual recursion (`green-baseline` -> `[0 0 1 2 2 3 4 4 5 6]`) |
| `examples/male_female_seq.clj` | green-identical | `defn-` + `memoize` + `iterate` + `map` shapes (`green-baseline`, `green-fibo`); seqs print via `apply vector` (same lazy-print note as above when printed raw) |
| `examples/memoized_male_female.clj` | green-identical | same shapes as above |
| `examples/life_without_multi.clj` | gap-b24 + green | `(require '[clojure.string :as str])` quoted spec refused (`rq-quoted`); `.write`/`str/join`/`cond` shapes green (`green-baseline`); unquoted/`ns` spellings green (`rq-unquoted` ronto side, `rq-ns`) |
| `examples/lazy_index_of_any.clj` | green-identical | `lazy-seq` + `when-let` + `for` destructuring (`green-shapes` last line -> `(0 1)` / `(0 1)`) |
| `examples/index_of_any.clj` | green-identical | `^{:test (fn [] (assert …))}` metadata is dropped, the `assert` never evaluates (`green-meta-assert` -> `1` / `1`) |
| `examples/primes.clj` | gap-b17 | `primes-from` is a named `fn` with `recur` (`b17-namedfn-recur`: oracle `15`, ronto `recur outside loop`) |
| `examples/concurrency.clj` | gap-b25 + gap-b23 | `(binding [slow-double (memoize slow-double)] …)` over `(defn ^:dynamic …)` refused (`gap-defn-dynamic`: oracle `42`); `(commute messages conj msg)` needs `conj`-as-value (`b18-conjval`); `alter`+`inc`, `agent`/`send-off`/`spit`, `time`/`dorun` green (`green-baseline`, `green-senddefn`) |
| `examples/functional.clj` | gap-b17 | `tail-fibo`/`recur-fibo` are `letfn` (`b17-letfn`: oracle `120`); `count-heads` `partition`/`filter`/`comp`/`partial` shapes green |
| `examples/sequences.clj` | gap-b20+b21+b22+b24+non-goal | `System/currentTimeMillis` zero-arg (`b20-zeroarg-static`); `re-matcher`/`re-find` (`b21-refind`); `(reader file)`+`line-seq` (`b22-reader2`); `file-seq` non-goal (`ng-fileseq`); `clojure.set`/`clojure.xml` non-goals (`ng-cljset`, `ng-cljxml`); quoted `require` also gap-b24 |
| `examples/exploring.clj` | gap-b21 + gap-b17 | `(str/split text #"\W+")` (`b21-split-regex`); `countdown` is `recur`-to-`defn` (`b17-defn-recur`: oracle `:done`); multi-arity `greeting`, `map-indexed`, `assert`-in-metadata green (`green-shapes`, `green-meta-assert`) |
| `examples/eager.clj` | gap-b23 + gap-b18 + gap-b22 + non-goal | `(vec (map square …))` (`gap-vec-eager`); `(mapcat vals …)` (`b18-mapcat`); `jio/reader`+`line-seq` (`b22-reader2`); `into`+transducer (`ng-transducer`), `eduction` (`ng-eduction`), `all-ns` (`ng-allns`: `unknown name: all-ns`) non-goals |
| `examples/interop.clj` | gap-b20 + gap-b18 + non-goal | `make-array`/`aset`/`aget` (`b20-array`); `(rand 500)` (`b18-rand`); SAX `proxy` over a class (`ng-proxyiface`: `java:proxy expects an interface`); `Thread/sleep`, `Class/forName`, `unchecked-add`, `int` green (`green-shapes`) |
| `examples/multimethods.clj` | gap-b19 | `String`/`Number`/`nil`/`Collection` methods over `class` (`b19-classdispatch`); `::checking`/`::savings` + `derive` (`b19-autokw`); custom `:default :everything-else` ALREADY WORKS (`b19-customdefault` -> `else!` / `else!`, no todo needed) |
| `examples/pi.clj` | gap-b20 + gap-b19 + oracle-also-fails | `(System/currentTimeMillis)` zero-arg (`b20-zeroarg-static`); `[Number]`/`[Map Number]` vector dispatch (`b19-classdispatch` shape); `(binding [*random* …])` without `^:dynamic` fails on BOTH (`diverge-pi-binding`: oracle `Can't dynamically bind non-dynamic var`) -- corpus idiom fix, not a gap |
| `examples/utils.clj` | green + non-goal | `defmacro`/`memfn`/`instance? String` green (`green-unless`, `green-memfn`, `green-shapes`); `load-string`/`read-string`/`eval` (`ng-loadstring` etc.), `file-seq` (`ng-fileseq`), computed `:reload-all` Symbols non-goals (literal `:reload-all` itself works: `green-reload`) |
| `examples/macros.clj` | gap-b20 + green + diverge | `bench`/`evil-bench` expand `(System/nanoTime)` zero-arg (`gap-bench-nanotime`); `unless` macro green (`green-unless`); `with-out-str-as-fn` over a `StringWriter` diverges (`diverge-stringwriter`: `str` of a Java object is not an output stream here) |
| `examples/import_static.clj` | gap-b20 + non-goal | `import-field` generates `(. Class FIELD)`, which tries a zero-arg method (`gap-static-dotform*`); `Class/FIELD` value spellings all refuse (`b20-mathpi`, `gap-static-dotted`, `gap-static-imported`); `clojure.set` require non-goal (`ng-cljset`) |
| `examples/instant.clj` | non-goal | `set!` first (`ng-setbang`); host-class `extend-protocol` refused (`ng-extend-host`: `extend-protocol needs a core type, not Instant`); MIDI `note.clj` sibling below |
| `examples/note.clj` | green + gap-b18 + gap-b20 + non-goal | `defrecord Note` + `extend-type` + `note->key` map green (`green-shapes`, `green-note`); `reify`+`rand-int` needs b18 (`b18-randint`); `aget` needs b20 (`b20-array`); `MidiNote :extend-via-metadata` (`ng-extendmeta`) + `perform` MIDI non-goals |
| `examples/spec.clj` | non-goal | `clojure.spec.alpha` (`ng-cljspec`: `unknown namespace: clojure.spec.alpha`) |
| `examples/snake.clj` | gap-b20 + gap-b18 + gap-b23 + non-goal | `KeyEvent/VK_LEFT` as value (`b20-staticfield-val`: oracle `37`); `(rand-int width)` (`b18-randint`); `(vec (apply map …))` (`gap-vec-eager` shape); multi-interface `proxy` + Swing event loop non-goals (`ng-proxyiface`, `ng-proxysuper`) |
| `examples/atom_snake.clj` | gap-b20 + gap-b18 + gap-b23 + non-goal | same three gaps; `(swap! game update-positions)` user-fn values green (`green-swapuser`) |
| `hangman/core.clj` | gap-b22 + gap-b18 + gap-b20 + green | `jio/reader`+`line-seq` (`b22-reader2`); `(mapv char (range …))` (`b18-mapv`, `b18-char`); `(rand-nth …)` (`b18-randnth`); `take-guess` `*in*` (`b20-in`); `game` loop/`update-progress`/`report`/`Player` protocol green (`green-hangman`, `green-shapes`) |
| `hangman/specs.clj` | non-goal | `clojure.spec.*` (`ng-cljspec`); `satisfies?` itself green (`green-shapes`) |
| `examples/macros/bench_1.clj` | gap-b20 | unhygienic `bench` expands `(System/nanoTime)` (`gap-bench-nanotime`) |
| `examples/macros/chain_1.clj` | green-identical | single-`.` macro (`green-chain` -> `bc` / `bc`) |
| `examples/macros/chain_2..5.clj` | green-identical | `..`-family widenings of the same shape (probe `green-chain` covers the `chain_1` core; `..` call chains green in `green-baseline`) |

## Refusal transcript (copy-pasteable pairs)

Run each pair from `probes/` as `timeout 60 clj -M <probe>` then
`java -jar <exec-jar> --source-language clojure <probe>`
(basename invocation: refusals embed the probe path).
Verbatim outputs in `expected/` (`run.sh` replays all of them).

b17 (`b17-letfn.clj`, `b17-namedfn-recur.clj`, `b17-defn-recur.clj`):

- `(println (letfn [(f [x] (if (zero? x) 1 (* x (f (dec x)))))] (f 5)))`
  oracle `120` / ronto `error: b17-letfn.clj:1:10: unknown name: letfn`
- `(println (let [f (fn f [n acc] (if (zero? n) acc (recur (dec n) (+ acc n))))] (f 5 0)))`
  oracle `15` / ronto `error: b17-namedfn-recur.clj:1:50: recur outside loop`
- `(defn tg [n] (if (zero? n) :done (recur (dec n)))) (println (tg 3))`
  oracle `:done` / ronto `error: b17-defn-recur.clj:1:34: recur outside loop`

b18 (`b18-*.clj`, oracle left, ronto `unknown name: <fn>` right):

- `(println (mapv inc [1 2 3]))` -> `[2 3 4]`
- `(println (filterv odd? [1 2 3 4]))` -> `[1 3]`
- `(println (mapcat reverse [[1 2] [3 4]]))` -> `(2 1 4 3)`
- `(println (contains? #{0 1 2 3 4} (rand-int 5)))` -> `true`
- `(println (and (<= 0.0 (rand 5)) (< (rand 5) 5)))` -> `true`
- `(println (contains? #{:a :b} (rand-nth [:a :b])))` -> `true`
- `(println (= (set (shuffle [1 2 3])) #{1 2 3}))` -> `true`
- `(println (symbol "a" "b"))` -> `a/b`
- `(println (keyword "a" "b"))` -> `:a/b`
- `(println (name :foo/bar))` -> `bar`
- `(println (namespace :foo/bar))` -> `foo`
- `(println (char 97))` -> `a`
- `(println (boolean 1))` -> `true`
- `(println (ffirst [[1 2]]))` -> `1`
- `(println (nfirst [[1 2 3]]))` -> `(2 3)`
- `(println (assert (= 1 1)))` -> `nil`
- Random fns pin shapes/membership only, never values.

b19 (`b19-*.clj`):

- `(ns b19probe) (println ::checking)`
  oracle `:b19probe/checking` / ronto
  `error: b19-autokw.clj:1:24: auto-resolved keywords are not supported yet: ::checking`
- `(defmulti mm class)` + `String`/`nil`/`:default` methods, `(mm "a")` etc.
  oracle `str:a`/`was-nil`/`dflt` / ronto
  `error: b19-classdispatch.clj:1:35: unknown name: String`
- `(defmulti mp class :default :everything-else)` + `:everything-else` method
  oracle `else!` / ronto `else!` -- custom default already works, no todo.

b20 (`b20-*.clj`, `gap-static-*.clj`):

- `(ns snake (:import (java.awt.event KeyEvent))) (println KeyEvent/VK_LEFT)`
  oracle `37` / ronto `error: b20-staticfield-val.clj:1:57: unknown name: KeyEvent/VK_LEFT`
- `(println (every? Character/isWhitespace "   "))`
  oracle `true` / ronto `error: b20-member-val.clj:1:18: unknown name: Character/isWhitespace`
  (`memfn` itself is green: `green-memfn.clj` -> `(.)` / `(.)`)
- `(println (System/currentTimeMillis))`
  oracle `<epoch-millis>` / ronto
  `Unhandled condition: error reading field currentTimeMillis: java.lang.NoSuchFieldException: currentTimeMillis`
- `(let [a (make-array String 3)] (aset a 0 "x") (println (aget a 0)))`
  oracle `x` / ronto `error: b20-array.clj:1:9: unknown name: make-array`
- `(println (nil? *in*))`
  oracle `false` / ronto `error: b20-in.clj:1:16: unknown name: *in*`
- Static-field spellings: `(println Math/PI)`, `(println java.lang.Math/PI)`,
  imported `Math/PI`, `(. Math PI)`, `(. java.lang.Math PI)` all answer
  `3.141592653589793` on the oracle; rontolisp refuses (`unknown name`, resp.
  `No matching method java.lang.Math.PI with 0 argument(s)` for the `(. …)`
  spellings) -- `gap-static-*.clj`.

b21 (`b21-*.clj`):

- `(println (re-find #"a+" "aaab"))`
  oracle `aaa` / ronto `error: b21-refind.clj:1:24: regex literals are not supported yet`
- `(str/split "a,b;c" #"\W+")` -> oracle `[a b c]` / same reader refusal
- `(println (re-seq #"\w+" "hi there"))` -> oracle `(hi there)` / same refusal

b22 (`b22-reader.clj` quoted-require spelling, `b22-reader2.clj` `ns` spelling):

- oracle `[apple Banana fig]` both spellings / ronto quoted:
  `error: b22-reader.clj:1:1: require takes library specs, not …` (see b24);
  ronto `ns` spelling: `error: b22-reader2.clj:1:1: unknown namespace: clojure.java.io`

New gaps found in this item (todos b23-b25):

- b23 `vec` + verb values (`b18-vec.clj`, `b18-conjval.clj`,
  `b18-assocval.clj`, `b18-getval.clj`, `gap-vec-eager.clj`):
  `(println (vec '(1 2)))` oracle `[1 2]` / ronto `unknown name: vec`;
  `(println (map conj [[1] [2]] [3]))` oracle `([1 3])` / ronto
  `unknown name: conj` (same for `assoc`/`dissoc`/`get`/`contains?`/
  `keys`/`vals`/`merge`/`disj`/`set`/`hash-map`/`array-map` as values;
  all lower in call position today).
- b24 quoted specs (`rq-quoted.clj`, `rq-unquoted.clj`, `rq-ns.clj`):
  `(require '[clojure.string :as str])` oracle `a,b` / ronto
  `require takes library specs, not …`; note the asymmetry is total --
  the unquoted spelling the oracle rejects (`ClassNotFoundException`)
  is the one rontolisp accepts.
- b25 `defn ^:dynamic` (`gap-defn-dynamic.clj`, `green-def-dynamic.clj`):
  `(defn ^:dynamic slow …)` + binding oracle `42` / ronto
  `binding slow needs a ^:dynamic var`; `(def ^:dynamic …)` + binding is
  `42` / `42` already. Only `def`/`defonce` register `dynamicVars`
  (`ClojureLowering.java`), contradicting the `binding` row's
  "`^:dynamic` `defn` lowers to `defparameter`" claim -- fix that row in
  the b25 item.

Non-goals (oracle runs, rontolisp names the refusal; no todo):

- `(println (into [] (map inc) [1 2 3]))` oracle `[2 3 4]` /
  `transducers are not supported yet: into` (`ng-transducer`)
- `(eduction …)` / `unknown name: eduction` (`ng-eduction`); `(all-ns)` /
  `unknown name: all-ns` (`ng-allns`, oracle answers a count)
- `(file-seq (clojure.java.io/file "."))` oracle answers a count /
  `file-seq is not supported yet: directory walks need a design`
- `(load-string "(+ 1 2)")` -> `3` / `unknown name: load-string`;
  `(read-string "42")` -> `42` / `unknown name: read-string`;
  `(eval '(+ 1 2))` -> `3` / `unknown name: eval`
- `(set! *warn-on-reflection* true)` oracle `set!` (returns the value) /
  `set! is not supported yet: mutable fields need a design`
- `(future 1)`/`(delay 1)`/`(promise …)` -> values on oracle /
  `future/delay/promise is not supported yet: …` (no thread pool / memo
  cells / rendezvous)
- `(ns ngs (:require [clojure.set :as s]))` / `unknown namespace:
  clojure.set` (same `clojure.test`, `clojure.xml`, `clojure.spec.alpha`,
  `clojure.java.io` until b22)
- `(defprotocol P :extend-via-metadata true (m [x]))` oracle defines /
  `extend-via-metadata is not supported yet: metadata never affects
  dispatch`; multi-arity protocols define but refuse at `extend`
  (`ng-multiarity2.clj`: `multi-arity protocol methods are not supported
  yet: m`)
- `(proxy [ArrayList] …)` (single non-interface class) / oracle defines /
  `java:proxy expects an interface, got java.util.ArrayList`
  (multi-interface `proxy` + `proxy-super` + GUI stay out with it)
- `(extend-protocol Instant …)` (instant.clj) / oracle `extended` /
  `extend-protocol needs a core type, not Instant` (file already dead on
  `set!`; recorded, no todo)
- Type predicates `integer? number? keyword? map? set? list? fn? seq?
  char? float? decimal? ratio?` are all `unknown name` here
  (`gap-predicates.clj`, `b20-integerp.clj`); the only in-scope corpus
  use is `spec.clj` (non-goal), so no todo.

Notation divergences (documented positions, not gaps):

- `(println ())` -> oracle `()` / ronto `nil` (nil IS `()`)
- `(println (replace-symbol …))` over an explicit `lazy-seq` -> realized /
  `#<LazySeq>` (never print a lazy tail)
- `(println (Class/forName "java.lang.String"))` -> `class …` /
  `#<java …>` (host objects print unreadably)
- `(println (apply vector (take 8 (fibo))))` bignums -> `[0N 1N …]` /
  `[0 1 …]` (marks print bare)
- `(binding [*random* 1] …)` without `^:dynamic` fails on BOTH
  (`diverge-pi-binding`): oracle `Can't dynamically bind non-dynamic
  var`, ronto `binding *random* needs a ^:dynamic var` -- mirrored
  refusal, corpus idiom fix.
- `(binding [*out* string-writer] …)` + `str` (`diverge-stringwriter`):
  oracle `captured` / ronto `not an output stream` -- Java objects are
  not Lisp streams here.

## Child todos

b17-b22 each name their corpus files + oracle lines (checked 2026-10-01;
no edits needed). New follow-ups filed in this item: b23 (`vec` +
verb-as-value), b24 (quoted `require` specs), b25 (`defn ^:dynamic`).

## Method note

Whole corpus files are not executed as units: most need the book's
`deps.edn` classpath, sibling namespaces, GUI/MIDI hardware, or the 32k
`words.txt`. Each table row rests on a minimal probe of the file's forms
above, run under both implementations. `run.sh` replays all of them.
