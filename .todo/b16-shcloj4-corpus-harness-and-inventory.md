# shcloj4 corpus harness + gap inventory (Clojure coverage round)

Difficulty: Low (harness only, no language change).

## Goal

Make the Programming Clojure 4th-edition corpus
(`https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip`,
67 `.clj` files, ~3000 lines) runnable as a gap probe: every
`code/src/examples/*.clj`, `code/src/examples/macros/*.clj` and
`code/hangman/src/hangman/*.clj` runs under the host oracle AND under
rontolisp, and the delta is a table, not folklore.

## Oracle

Host `clj` (`Clojure CLI version 1.12.6.1673`, Clojure 1.12.6).
Pin the version in the artefact log; re-verify any semantic claim
against it, not against the book text (the book predates some 1.12
behaviours, e.g. `binding` a non-`^:dynamic` var fails on the oracle
too -- see `pi.clj`'s `(def *random* nil)` + `binding`, verified
2026-10-01).

## Method (verified 2026-10-01 with `target/rontolisp-*-exec.jar`)

```bash
unzip shcloj4-code.zip -d /tmp/opencode/shcloj4
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar --source-language clojure probe.clj
timeout 60 clj -M probe.clj   # oracle
```

Per-file verdict classes: runs-identical / refused-by-name
(rontolisp names the gap, e.g. `unknown name: letfn`) / runtime
divergence / oracle-also-fails (stale book idiom, not our gap).

## Inventory (each row verified by execution, not by reading)

Already green (no todo): `defn`/`fn`/`#()` arities, `let`/`loop`/`recur`-to-loop,
destructuring (`&`, `:as`, `:keys`/`:or`, nested), `->`/`->>`/`as->`/`doto`,
`doseq`/`dotimes`/`for`/`dorun`/`doall`, `lazy-seq`/`lazy-cat`/`iterate`/
`repeat`/`cycle`/`range`, `take`/`drop`/`partition` (incl. step),
`map`/`filter`/`comp`/`partial`/`memoize`/`trampoline`, maps/sets/records
(`assoc`/`dissoc`/`update`+extra-args/`get-in`/`assoc-in`), keyword-call,
`defmulti` keyword-dispatch + `derive`/`isa?`, single-arity
`defprotocol`/`extend-protocol`/`reify`/`deftype`, `defmacro`+syntax-quote,
`atom`/`ref`/`agent` STM basics, `try`/`ex-info`, `comment`, `..`, `with-open`,
`with-out-str`, `time`, `spit`/`slurp`/`line-seq`-over-path.

Gaps (one child todo each): b17 `letfn` + `recur`-to-`fn`/`defn`;
b18 strict/random/coercion fns (`mapv`/`filterv`/`mapcat`,
`rand`/`rand-int`/`rand-nth`/`shuffle`, `symbol`/`keyword`/`name`/`namespace`,
`char`/`boolean`, `ffirst`/`nfirst`, `assert`); b19 `::` auto-resolve +
multimethod host-class dispatch + custom `:default`; b20 interop-as-value
(static field, member-as-value, zero-arg static call,
`make-array`/`aget`/`aset`, `*in*`); b21 regex literals + `re-*` +
`str/split` regex overload; b22 reader-object IO (`jio/reader`,
`line-seq`-over-reader).

Explicit non-goals for this round (no todo): Swing GUI (`snake.clj`,
`atom_snake.clj`: multi-interface `proxy` + `proxy-super` + AWT event
loop), SAX/`javax.xml`, MIDI (`note.clj`), `clojure.spec.*`,
transducers 3-arity `into`/`transduce`/`eduction` (`eager.clj` only),
`file-seq`/`load-string`/`read-string`/`eval`, `clojure.test` harness,
`future`/`delay`/`promise` (refused by design), `:extend-via-metadata`
protocol option, multi-arity protocol methods.

## Acceptance

- `.todo/artefacts/b16-shcloj4-inventory/NOTES.md`: per-file verdict
  table + the refusal transcript for every gap row (copy-pasteable
  `java -jar ...` / `clj -M` pairs).
- Child todos b17-b22 each name their corpus files + oracle lines.
- No `src/` change in this item (harness + docs only).

## Rules reminders

- `.kb/adding-primitives.md` checklist per new lowering; `clojure-spec.yaml`
  case per row, run on all four backends (`.kb/running-backends.md`).
- User-facing behaviour mirrored `doc/en/**` + `doc/ja/**` in the same
  commit (`.kb/documentation-site.md`); `.kb/clojure-frontend.md` lowering
  table row per form.
- One maven run per worktree; `git merge origin/develop` right before the
  final test run; number claims via `.todo/claim-number.sh`.
