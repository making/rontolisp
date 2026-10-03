# Deviations

Conformance is partial by design. Where behavior departs from the Clojure oracle
(Clojure CLI 1.12), it does so here, on every backend alike:

- `false` is a distinct object from `nil`. Both are falsey, so `if`/`when`/`cond`/`and`/
  `or`/`not` treat them alike, while `=` and `nil?` tell them apart; `false?`/`true?`/
  `boolean?` answer accordingly.
- `println`/`print` join their parts with a single space and spell the three values
  `true`/`false`/`nil`; `str` concatenates bare and spells them `true`/`false`/`""` (a
  collection inside it spells readably, strings quoted, like the oracle's `toString`);
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
- A map, set or memo key finds an `=` key like the oracle's, vectors, lists, maps and
  sets included, but a stored collection key is the first `=` key of its kind (vector,
  list, lazy seq) the program stored, so its metadata and the spelling of a nested
  member follow that earlier object; those keys stay alive for the whole run, one
  per distinct value and kind. A repeated set-literal element is refused by spelling.
  `=` compares vectors, lists and lazy seqs element-wise like the oracle, and since `nil`
  is the empty list, `(= [] nil)` is `true` where the oracle answers `false`.
- `clojure.set/union` whose largest input is a map signals, where the oracle conjoins
  the other inputs' `[k v]` members into it; a `clojure.set` answer carries no metadata.
- A map entry is a plain two-member vector, so `map-entry?` is `true` of every `[k v]`
  (the oracle: `false` for one the program built) and `key`/`val` read any such vector. Keywords
  are not interned, so `find-keyword` answers the keyword for a spelling no keyword ever used
  (the oracle: `nil`).
- The type predicates follow the representation. `nil` is the empty list, so `seq?`,
  `list?`, `coll?`, `sequential?` and `counted?` answer `false` for `()`; a seq a verb answers
  over a strict input is a list, so `list?`, `counted?` and `realized?` answer `true` for it
  (the oracle's lazy or chunked seq: `false`); no seq is chunked (`chunked-seq?` is always
  `false`), and an `iterate`/`cycle` seq is `realized?` only once forced. A decimal or `N`
  literal is a plain rational, so `decimal?` is always `false` and `ratio?`, `integer?` and
  `int?` answer for the rational (`(ratio? 1.5M)`, `(int? 2N)` are `true`). `identical?`
  compares numbers, characters and symbols by value (`(identical? 1000 1000)` is `true`) and
  two keywords of one spelling as one object. `bound?` is `true` of every var (a value-less
  `def` binds `nil`).
- A program's own top-level definition of a core name (`(defn peek ...)`) shadows the
  core verb in the whole file, calls above the definition included (the oracle's calls
  above it still reach the core verb); a local binding shadows it in its scope, like the
  oracle.
- The seq family's empty `rest`/`next` is `nil`, where the oracle prints `()`; `nth` past
  the end answers the default instead of throwing; map/set seq order is the table's walk
  order; strings seq to characters printing in Common Lisp notation. Lazy seqs realize
  one element at a time (no chunking); `str` of a lazy seq spells its members, where the
  oracle answers `clojure.lang.LazySeq@<hash>`; `map` takes any number of collections.
- A `for` over strict collections answers a strict list, realized when the `for` runs
  (the oracle's waits to be consumed); with no elements it is `nil`, where the oracle
  prints `()` (the same empty-as-`nil` position as `rest`/`next`/`take`). From its first
  lazy collection on, it is lazy like the oracle's.
- `cond` keeps the lenient reading: an odd trailing arm is the default, where Clojure
  signals. A threading step over a collection literal signals (collections are not
  functions here).
- A function that calls itself by name in tail position (a `defn`, a named `fn`, a
  `letfn` entry) runs in constant stack on every backend, as `recur` does, and so do
  functions that call each other in tail position (`letfn` entries, `defn`s). The oracle
  keeps a frame per such call, so a deep one overflows there and runs to completion here: a
  test asserting that overflow (`(is (thrown? StackOverflowError (tail-fibo 1000000N)))`)
  fails.
- `clojure.test` runs the tests in definition order (the oracle's order is its
  namespace map's); `thrown?`/`thrown-with-msg?` match any condition whatever the class
  names, like `catch`; an error report prints the condition's message (an `ex-info` the
  oracle's way) with no stack trace, at the `is` form's line where the oracle names the
  frame that threw; a failed `thrown-with-msg?` shows the condition's message where the
  oracle prints `#error {...}`; a host stack overflow (`catch StackOverflowError`,
  `(is (thrown? StackOverflowError ...))`) is no condition on the interpreter, where it ends
  the program with the one-line report, and a trap on WASM; only the JVM backend catches it,
  like the oracle. `use-fixtures` is refused by name.
- `try` catch clauses are catch-all in order: the first handles any condition, where the
  oracle dispatches by class; the catch variable binds the Common Lisp condition.
- Multimethod dispatch values compare like map keys (by `=`, vectors included);
  dispatch through a hierarchy prefers the strictly most specific method, then
  `prefer-method` choices. A `defmethod` over a host class stores under the keyword
  `class` answers for it, merging every numeric spelling into `:number` (where the
  oracle tells `Long` from `Double`); a true nil maps onto the `(:C%NIL)` marker,
  so no table ever keys on nil, while a dispatch value that literally is `:nil`
  keeps its keyword row, like the oracle (a `class` call inside the dispatch
  function answers nil itself for a nil argument, so the null test maps it onto
  the marker too -- bare, wrapped in another function, through a named `defn`
  / `def`'d function re-lowered from its recorded definition, or a call to one
  nested inside an inline dispatch datum (inlined at the call site the same way),
  like the oracle);
  an `Object` method catches past the search but ahead of the default.
  Protocol dispatch reads no hierarchy (exact tag match
  plus the `Object` default) and merges `Long`/`Double` into `:number`, where the
  oracle tells them apart.
- A record prints as its literal (`#user.R{:a 7}`, like the oracle), but `str` of one
  spells that literal too, where the oracle answers `user.R@<hash>`. A deftype prints
  as its wrapper list (`(:C%TYPE ...)`), a reify as `(:C%REIFY ...)`; only the entry
  maps print deterministically.
- A deftype's `^:volatile-mutable` field is the same plain slot as an
  `^:unsynchronized-mutable` one (no cross-thread ordering). `.-field` of a mutable
  field signals `No such field: ...` (the oracle: `No matching field found: ...`).
- `split`/`replace` answer seqs, never vectors, and plain strings stay literal (only
  pattern values match by pattern); `index-of` answers `-1` when missing, like the
  oracle (where `clojure.string/index-of` answers `nil`).
- `def` inside a body sets the global when the body runs; `defn` inside a body works only
  in statement position (a multi-arity one only at the top level).
- Verbs assume the right collection kind; misuse may signal the Common Lisp type error
  instead of the oracle's.
- Macros expand while lowering, so every backend runs expanded code; the interpreter's
  `eval` of a macro call expands the same way. A macro body sees the core builtins and
  the `clojure.lisp` library, not the program's own definitions; a call above its
  definition is refused, and a macro has no function value. A `defmacro` of a special
  form (`if`, `do`, `let*`, `new`, ...) or of a head the reader spells (`deref`,
  `fn`, `syntax-quote`, `ns`, `in-ns`) is refused by name, where the oracle
  accepts it (and ignores it at call sites, for a special form).
- Syntax-quote qualifies every symbol but a special form, like the oracle: a core name
  spells `clojure.core/name` (one a `(:refer-clojure ...)` filter hides spells its own
  namespace instead), any other unresolved spelling the defining namespace, an alias
  head its namespace, a class head its fully qualified name. Each `x#` binds one gensym per expansion -- the oracle resolves one per
  compilation, so two expansions share its suffixes where ours differ (fresher, never
  captured). `macroexpand-1`/`macroexpand` answer the mangled data itself, so `=`
  against a quoted form holds and printing spells the oracle's lowercase. Nested syntax-quote
  evaluates its levels in the one expansion.
- A var's metadata comes from the definitions lowered above the `#'` site: a body
  lowered above a redefinition keeps the older docstring where the oracle's var shows
  the newest (the same split as its calls). `:ns` is the namespace's symbol (the oracle's
  is a Namespace object), `:file` of the entry file is the path as given (the oracle
  absolutizes it), and a var defined by anything but `def`/`defn`/`defn-`/`defmacro`
  (`defmulti`, `deftest`, a record's factory, ...) carries only `:name` and `:ns`.
  Deref of a macro's var signals (the oracle answers its expander function), and a
  `clojure.core` var (`#'println`) is refused by name.
- `class` answers a keyword naming the kind (`:string`, `:number`, `:keyword`, ...);
  the oracle answers host classes, which no wasm backend has. A record or deftype
  answers its tag keyword instead; a host object (interpreter and JVM) its host class.
- `instance?` over the core classes (`String`, `Long`, ...) and known record/deftype
  names; any other class is a named refusal instead of a wrong answer.
- `unchecked-add` never wraps (integers are bignums); the other `unchecked-*` verbs are
  absent.
- `format` renders `%s`/`%d`/`%x`/`%X`/`%o`/`%c`/`%b`/`%f`/`%%`/`%n` (with widths, float
  precision); `%e`/`%g`, flags and non-literal patterns are named refusals. `%s` spells
  `nil` `"null"`, like the oracle.
- `line-seq` takes a path or an open reader (such as a `clojure.java.io/reader`,
  which `with-open` closes) and answers strictly either way (the oracle takes a
  reader and answers lazily); `spit`/`slurp`/`line-seq`/`reader` run on the
  interpreter and the JVM, and on wasm with a `--dir` preopen covering the path.
- `sort` without a comparator orders numbers, strings, characters and keywords; anything
  else (or mixed kinds) signals.
- `partition` takes no pad. `partition-all` with a non-positive size or step signals,
  where the oracle answers an endless seq of `()`. `pmap` is `map`, run in order on the
  calling thread. `take-nth` with a zero step signals, and its seq arity steps by the
  magnitude of a negative one, where the oracle's repeats the first member forever.
- Transducers are the oracle's functions over reducing functions, but an `eduction` is
  the `sequence` of its input through them, computed once (strictly over a strict input,
  lazily over a lazy one), where the oracle re-runs the transformation every time it is
  reduced; `println` prints it as that seq where the oracle prints the object. A
  `reduced` value prints as its wrapper list.
- Transactions are single-threaded extents: `dosync` never retries, `commute`
  runs its function once (the oracle may run it twice), validators run on the
  write and a failed one leaves the old value; `alter` and friends outside
  `dosync` signal.
- Agents are synchronous atoms: `send`/`send-off` apply at once and answer the
  agent (which prints unreadably, `#<Atom ...>`, not the oracle's object),
  `await` and `shutdown-agents` answer `nil`, and `*agent*` is bound only while
  a send runs (`nil` outside one, where the oracle leaves it unbound).
- `binding` rebinds only `^:dynamic` vars (anything else is refused, like the
  oracle's non-dynamic error); a `^:dynamic` `defn` is rebindable too (its calls
  go through the var while the definition stays direct); reader metadata on names
  and locals otherwise parses and drops, never affecting dispatch.
- In the REPL, a local named like a `^:dynamic` var that a later input defines binds
  that var for its extent once it is defined, so a function called there reads the
  local's value; the oracle binds a local lexically, and the function reads the var.
  In a file such a local is lexical.
- `with-meta` answers a copy carrying the metadata; a value derived from it (`assoc`,
  `conj`, ...) starts without metadata, where the oracle keeps it, and a symbol carries
  none (`with-meta` answers the symbol). A `:tag` from reader metadata on a collection
  literal stays the symbol as written (`String`), where the oracle resolves the class
  (`java.lang.String`).
- `with-open` closes through the `close` method, so only closeables the backend
  reaches work (Java closeables need the JVM); `time` answers its value but its
  millisecond count never pins, and it counts whole milliseconds (`42.0`) where the
  oracle's carries nanosecond digits.
- `read-string`/`read` answer what a quote answers: `@x` reads `(deref x)` and a
  syntax-quote stays unexpanded, where the oracle reads `(clojure.core/deref x)` and
  expands it; `#(...)` reads the source reader's `(fn %anon ...)`. A record literal reads
  for any class the program defines, also one a later `require` loads (the oracle needs
  the class loaded first); a deftype literal is refused. `read` takes a stream -- a
  plain `clojure.java.io/reader` too, where the oracle requires a `PushbackReader` -- and
  refuses a host reader.
- `*out*`/`*in*` are `*standard-output*`/`*standard-input*` (rebinding rebinds the
  standard streams);
  `defonce` keeps the root on reload where `def` resets it.
- A host-object boolean answers `false` only when the receiver's class is known
  at lowering (a construction literal, a `let`/`if-let`/`when-let` local bound
  to one, or a `..` step's declared return) and every
  overload at that arity answers a primitive boolean; any other host boolean
  keeps the shared `java:` unmarshal and prints `nil` for `false`.
- A `_` param tag leaves that parameter to the cost rule of the `java:` surface, so
  `(^[_] Math/abs -2)` answers `2` where the oracle refuses tags that leave more than one
  overload.
- Only `.clj` files below the source roots are read (no `.cljc`, no classpath).
- Records and deftypes of one simple name in two namespaces share a dispatch tag, which
  `class`, protocol dispatch and `=` read.
- A name referred from two namespaces keeps the later refer (the oracle refuses it), and
  a definition replaces a refer of its name without the oracle's warning.
