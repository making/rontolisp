# Deviations

Conformance is partial by design. Where behavior departs from the Clojure oracle
(Clojure CLI 1.12), it does so here, on every backend alike:

- `false` is a distinct object from `nil`. Both are falsey, so `if`/`when`/`cond`/`and`/
  `or`/`not` treat them alike, while `=` and `nil?` tell them apart; `false?`/`true?`/
  `boolean?` answer accordingly.
- `println`/`print` join their parts with a single space and spell the three values
  `true`/`false`/`nil`; `str` concatenates bare and spells them `true`/`false`/`""`;
  `pr`/`prn`/`pr-str` are the readable arms (strings print quoted, `pr-str` joining its
  parts with a space like `pr`). The print family answers `nil`, like the oracle.
- Collections print in Clojure notation (`[1 :a s]`, `{:a 1}`, `#{1}`,
  `(true false nil :k)`); a quoted symbol demangles from behind `c%`. `nil` stays `nil`
  (never `()`), and map/set walk order stays unspecified (same as `keys`/`vals`), so only
  single-entry maps and single-member sets print deterministically. A value that closes a
  cycle prints with a datum label (`#0=(1 . #0#)`), like Scheme's `write`; sharing
  without a cycle prints twice. An atom prints unreadably (`#<Atom value>`), a function
  as `#<procedure>`, an `ex-info` as its condition object (`#<C%E-EX-INFO ...>`).
- `*print-length*`/`*print-level*` are not honored, and `~S`/`~A` on Clojure values stay
  Common Lisp notation (`format` is a CL surface); `print-method`/`pprint` stay absent.
- Vector and table keys compare by identity, so a vector key misses a lookup its oracle
  answers; a repeated set-literal element is refused by spelling.
- The seq family's empty `rest`/`next` is `nil`, where the oracle prints `()`; `nth` past
  the end answers the default instead of throwing; map/set seq order is the table's walk
  order; strings seq to characters printing in Common Lisp notation.
- A strict `for` with no elements answers `nil`, where the oracle prints `()` (the same
  empty-as-`nil` position as `rest`/`next`/`take`).
- `cond` keeps the lenient reading: an odd trailing arm is the default, where Clojure
  signals. A threading step over a collection literal signals (collections are not
  functions here).
- `try` catch clauses are catch-all in order: the first handles any condition, where the
  oracle dispatches by class; the catch variable binds the Common Lisp condition.
- Multimethod dispatch values compare like `equal` table keys (vectors by identity);
  dispatch through a hierarchy prefers the strictly most specific method, then
  `prefer-method` choices.
- `split`/`replace` match literal strings, never patterns; `index-of` answers `-1` when
  missing, like the oracle (where `clojure.string/index-of` answers `nil`).
- `def` inside a body sets the global when the body runs; `defn` inside a body works only
  in statement position (a multi-arity one only at the top level).
- Verbs assume the right collection kind; misuse may signal the Common Lisp type error
  instead of the oracle's.
- Macros expand while lowering, so every backend runs expanded code; the interpreter's
  `eval` of a macro call expands the same way. A macro body sees the core builtins and
  the `clojure.lisp` library, not the program's own definitions; a call above its
  definition is refused, and a macro has no function value. A `defmacro` shadows a core
  function at call sites, never a special form.
- Syntax-quote qualifies every symbol behind `c%` (there are no namespaces to qualify
  against). Each `x#` binds one gensym per expansion -- the oracle resolves one per
  compilation, so two expansions share its suffixes where ours differ (fresher, never
  captured). `macroexpand-1`/`macroexpand` answers print demangled and uppercased
  (case folds, print-only); their data takes bare operator names. Nested syntax-quote
  evaluates its levels in the one expansion. `var`/`#'` stays refused everywhere:
  bodies quote symbols instead.
