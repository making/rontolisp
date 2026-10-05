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
  parts with a space like `pr`); `print-str`/`prn-str`/`println-str` are the same arms answered as a
  string. The print family answers `nil`, like the oracle.
- Collections print in Clojure notation (`[1 :a s]`, `{:a 1}`, `#{1}`,
  `(true false nil :k)`); a quoted symbol demangles from behind `c%`. `nil` stays `nil`
  (never `()`), and map/set walk order stays unspecified (same as `keys`/`vals`), so only
  single-entry maps and single-member sets print deterministically. A value that closes a
  cycle prints with a datum label (`#0=(1 . #0#)`), like Scheme's `write`; sharing
  without a cycle prints twice. An atom prints unreadably (`#<Atom value>`), a function
  as `#<procedure>`, an exception as its `toString` (`clojure.lang.ExceptionInfo: m {}`;
  the oracle prints `#error {...}`), an unbound var's root as `#<Unbound: #'user/x>` (the
  oracle's `#object` carries a hash). A stream prints as the oracle's `#object` of the
  host class its kind is without the identity hash (`#object[java.io.StringWriter "ab"]`,
  `#object[java.io.OutputStreamWriter "java.io.OutputStreamWriter"]`), and `str` answers
  its `toString` the same way: a string input stream is a
  `clojure.lang.LineNumberingPushbackReader` (`with-in-str`'s), also where the oracle's is
  a `java.io.PushbackReader` over a `StringReader`.
- `*print-meta*` writes a value's metadata ahead of it like the oracle, but no quoted
  list carries the oracle reader's `:line`/`:column` metadata; `*print-dup*` is a plain
  value the printer does not read. `assert` reads `*assert*` where it expands, so a
  top-level `set!` of it to a literal switches off the asserts after it; a `set!` inside
  a function, or to a computed value, does not (the oracle's takes effect once it runs).
  `~S`/`~A` on Clojure values stay Common Lisp notation (`format` is a CL surface);
  `print-method`/`pprint` stay absent.
- A map, set or memo key finds an `=` key like the oracle's, vectors, lists, maps and
  sets included, but a stored collection key is the first `=` key of its kind (vector,
  list, lazy seq) the program stored, so its metadata and the spelling of a nested
  member follow that earlier object; those keys stay alive for the whole run, one
  per distinct value and kind. A repeated set-literal element is refused by spelling.
  `=` compares vectors, lists and lazy seqs element-wise like the oracle, and since `nil`
  is the empty list, `(= [] nil)` and `(= (java.util.ArrayList.) nil)` are `true` where the
  oracle answers `false`.
  `=` asks a Java object on the left its `equals` like the oracle, but hands it only a
  number, string, character, `true`, `nil` or Java object: `false`, a keyword, a symbol
  or a collection is `=` to no Java object but a Java `List`, `Map` or `Set` of its kind.
  A map or set finds a Java collection key by its own `equals`, like the oracle's hash maps
  and sets, never by `=`, which the oracle's small array maps use:
  `(get {[1 2] :v} (java.util.ArrayList. [1 2]))` is `nil` here, `:v` there.
- `seq` and the verbs over it, `count`, `empty?`, `get`, `contains?`, `keys` and `vals` read a
  Java `Iterable`, `Map` or `CharSequence`, and `find`, `select-keys`, `reduce-kv`,
  `update-vals`, `update-keys`, `conj`, `merge` and `merge-with` a Java `Map`, like the
  oracle, but the seq is read whole when it is taken (the oracle's walks the iterator
  lazily) and a `Map`'s entries are `[k v]` vectors (the oracle's are the Java entries,
  printed `#object[...]`).
- `clojure.set/union` whose largest input is a map signals, where the oracle conjoins
  the other inputs' `[k v]` members into it; a `clojure.set` answer carries no metadata.
- A map entry is a plain two-member vector, so `map-entry?` is `true` of every `[k v]`
  (the oracle: `false` for one the program built) and `key`/`val` read any such vector. For the same reason `(conj {} #{[1 2]})`,
  `(conj {} (seq [[1 2]]))` and `(merge {} (seq [[1 2]]))` answer `{1 2}` (the oracle: `ClassCastException`, the members must be real entries). Keywords
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
  two keywords of one spelling as one object.
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
  namespace map's); an error report prints the exception's `toString` (a runtime error
  its report) with no stack trace, at the `is` form's line where the oracle names the
  frame that threw; a failed `thrown-with-msg?` shows the condition's message where the
  oracle prints `#error {...}`; a host stack overflow (`catch StackOverflowError`,
  `(is (thrown? StackOverflowError ...))`) is no condition on the interpreter, where it ends
  the program with the one-line report, and a trap on WASM; only the JVM backend catches it,
  like the oracle. `use-fixtures` is refused by name.
- A `catch` (and `thrown?`) takes a runtime error by the class the oracle throws where the
  runtime signals its Common Lisp condition, and a refusal of the Clojure runtime by the class
  the oracle throws for the same call (`(first 5)` an `IllegalArgumentException`, a failed
  `assert` an `AssertionError`, which an `Exception` catch does not take). An error naming no
  class -- a refusal of a construct the oracle accepts (a regex lookahead,
  `(partition 0 coll)`) -- is taken by the first catch of any class but
  `clojure.lang.ExceptionInfo`. A misuse a lower verb refuses first carries that verb's class:
  `(shuffle 5)` is the `IllegalArgumentException` of `seq`, where the oracle casts to
  `java.util.Collection`. An index past its bound is an
  `IndexOutOfBoundsException` that a catch of any of its subclasses takes too (the oracle's
  `aget` throws `ArrayIndexOutOfBoundsException`, `.charAt` a
  `StringIndexOutOfBoundsException`). A catch must name a class that resolves on this host
  (`java.*`, `clojure.lang`'s throwables); one the oracle finds on its class path only is
  refused.
- An exception is a condition carrying its class, a message, data and a cause. A runtime
  error is the Common Lisp condition the runtime signals, whose message is the Common Lisp
  report (`(.getMessage e)` of a failed `(inc nil)` is `+: The value NIL is not of type
  NUMBER`, the oracle's a `NullPointerException` text) and whose `str` is that report
  without the oracle's class prefix. A throwable construction is an exception only
  for a class that carries nothing but a message and a cause; one with members of its own
  (`java.net.URISyntaxException`) stays a host object. On the interpreter and the JVM, an
  exception a Java member throws, and a host object thrown, is the host's own, like the
  oracle's: a catch takes it by its class and binds that very object. `class` of an
  exception the program built answers its class name as a keyword (`:java.lang.Exception`, where the oracle
  answers the host class), `:java.lang.RuntimeException` for an error naming no class; `.printStackTrace` writes the `toString` line to `*err*` (the oracle writes it
  and a line per frame to the process's stderr, whatever `*err*` is bound to) and
  `.getStackTrace` answers an empty vector; `.getClass` answers what `class` does. On the
  interpreter and the JVM, any other method is called on, and a Java member is passed, a
  host exception of the exception's class built once from its message and cause (an
  `ex-info`'s is a `RuntimeException`): `(.getCause (UncheckedIOException. "u" e))` answers
  that host exception, not `e` itself (the oracle's is `identical?` to `e`). A runtime error
  and a refusal of the runtime have no such host exception: a Java member taking a
  `Throwable` finds no match for one. `throw` of a value that is
  no exception is a `ClassCastException` whose message is the value's rendering, where the
  oracle's message names the two classes.
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
  an `Object` method catches past the search but ahead of the default. A throwable or
  stream class stores under its name as a keyword (the one `class` answers), and the search
  follows its Java supers like the oracle's inheritance, interfaces (`java.io.Serializable`,
  `java.io.Closeable`) and `Object` included; `isa?`, `derive`, `underive`, `parents`,
  `ancestors` and `descendants` read a class spelling as the same keyword, so
  `(isa? (class "a") String)` is `true` and `parents`/`ancestors` of a class add its Java
  supers like the oracle. A core kind (`:string`, `:number`, ...), a record or a deftype
  stands for host classes that are no one value here: it `isa?` `Object` and its
  `ancestors` add `Object`, but its host class's other supers are not modeled. A program
  that spells no class in those positions and uses no host interop reads only the
  hierarchy, so `(ancestors (class e))` there answers what `derive` recorded. Since the
  keyword is the class here, a keyword spelled `:java.lang.Exception` is that class too.
  A host class object (`class` of a host object, interpreter and JVM) is the same class as
  its name's keyword and, by its simple name, a core kind's, so
  `(isa? (class (java.util.ArrayList.)) java.util.List)` is `true` -- and so is its `isa?`
  of `clojure.lang.IPersistentList`, which also spells `:list`, where the oracle answers
  `false`. Any other class (`java.io.File`) is its class object, in a dispatch value too,
  like the oracle.
  Protocol dispatch reads no hierarchy (exact tag match
  plus the `Object` default) and merges `Long`/`Double` into `:number`, where the
  oracle tells them apart.
- `(methods mt)` and `get-method`, `remove-method`, `prefer-method` take the multimethod's
  name (a `defmulti` var, through an alias or a referred one), not an expression: a local
  bound to a multimethod is refused at lowering. The map `methods` answers keys a host
  class row by the keyword `class` answers for it, where the oracle keys it by the `Class`.
- A record prints as its literal (`#user.R{:a 7}`, like the oracle), but `str` of one
  spells that literal too, where the oracle answers `user.R@<hash>`. A deftype prints
  as its wrapper list (`(:C%TYPE ...)`), a reify as `(:C%REIFY ...)`; only the entry
  maps print deterministically.
- A deftype's `^:volatile-mutable` field is the same plain slot as an
  `^:unsynchronized-mutable` one (no cross-thread ordering).
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
  form (`if`, `do`, `let*`, `new`, ...), of a head the reader spells (`deref`,
  `syntax-quote`, `ns`, `in-ns`) is refused by name, where the oracle
  accepts it (and ignores it at call sites, for a special form).
- `#(...)` reads as the oracle's `(fn* [p1__N# ...] (body))` in source, under a quote and
  in `read-string`/`read`, but N restarts at each top-level form (each datum read), where
  the oracle's counter runs across the process: the parameter names differ, and two reads
  of one text answer `=` forms here. A regex literal passed to a macro reaches the
  expansion as a fresh pattern compiled from the same source, where the oracle's
  expansion holds the one `Pattern` object.
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
  Deref of a macro's var signals (the oracle answers its expander function). A
  `clojure.core` var's metadata is only `:name`, `:ns` and a macro's `:macro` (the
  oracle's also carries `:arglists`, `:doc`, `:added` and the position), and a core var
  with no value here (`#'all-ns`) is refused.
- A namespace prints as the oracle's `#object[clojure.lang.Namespace "user"]` without the
  identity hash, and `class` of one answers `:clojure.lang.Namespace`. `the-ns` and
  `find-ns` know the namespaces the program created above the call, the libraries it
  required and the four `clj -M` loads first (`clojure.core`, `clojure.edn`,
  `clojure.java.io`, `clojure.string`); `in-ns` answers `nil` (the oracle's answers the
  namespace). `set!` and `binding` of `*ns*` change what `*ns*` reads, not the namespace the
  forms below resolve in, which `ns` and `in-ns` with a literal name decide. A compiled
  program's `*file*` is the entry file's path when it was compiled.
- `class` answers a keyword naming the kind (`:string`, `:number`, `:keyword`, ...);
  the oracle answers host classes, which no wasm backend has. A record or deftype
  answers its tag keyword instead; a host object (interpreter and JVM) its host class.
- An instance call on a collection, keyword, symbol, ratio or atom answers through the core
  functions and shares their deviations (`.getClass` answers what `class` does). A method
  left unmapped is refused as `Method m taking N args is not supported for class C`, where the
  oracle may answer (`.hashCode`); the class named for a map is an array map up to eight
  entries and a hash map past them, by size alone. `nil` is the empty list here, so a
  collection method answers on it (`(.count nil)` is `0`) where the oracle throws a
  `NullPointerException`; any other method on `nil` is one. On a record, deftype or reify,
  a name that is no protocol method and no field of a record or deftype the program defined
  is refused that way too, where the oracle says `No matching field found`; a site lowered
  before a later REPL input defines a record or deftype does not see its methods or
  fields.
- `instance?` answers a class by the oracle classes of each kind of value: a list or strict
  seq is a `clojure.lang.PersistentList`, so `IPersistentList` and `Counted` are `true` of
  `(map inc [1])` and `LazySeq` is not (the oracle's is a `LazySeq`); every two-member vector
  is a `java.util.Map$Entry` (`map-entry?`); an integer is a `Long`, `(int 1)` too, never an
  `Integer`. A `clojure.lang` class no kind is an instance of
  (`clojure.lang.PersistentQueue`) is an unknown name, where the oracle answers `false`. A
  protocol's interface (`user.P`) knows the records and deftypes defined when the site is
  lowered, so one a later REPL input defines is not an instance there; the protocol's own
  name (`P`, a var) is an unknown name, where the oracle throws a `ClassCastException`.
- The `unchecked-` arithmetic verbs wrap integers at 64 bits (`-int` verbs at 32) and the casts
  (`int`, `long`, `short`, `byte`, `char`, `double`, `float`) match the oracle, with one deviation:
  an integer past 64 bits is a plain integer here, so the oracle's unwrapped bigint operand
  (`(unchecked-add 9223372036854775807N 1)`) wraps too. `inc`, `dec` and the checked verbs never
  overflow (integers are bignums).
- `bigint` and `biginteger` answer a plain integer, and `bigdec` a plain rational (`(bigdec "1.5")`
  prints `3/2`, the oracle `1.5M`), like the `N` and `M` literals; `bigdec` of a ratio with an infinite
  decimal expansion signals, like the oracle.
- `format` renders `%s`/`%d`/`%x`/`%X`/`%o`/`%c`/`%b`/`%f`/`%%`/`%n` (with widths, float
  precision); `%e`/`%g`, flags and non-literal patterns are named refusals. `%s` spells
  `nil` `"null"`, like the oracle.
- `line-seq` takes a path or an open reader (such as a `clojure.java.io/reader`,
  which `with-open` closes) and answers strictly either way (the oracle takes a
  reader and answers lazily); `spit`/`slurp`/`line-seq`/`reader` run on the
  interpreter and the JVM, and on wasm with a `--dir` preopen covering the path.
- A sorted map or set orders, prints and finds keys like the oracle's, but every verb
  copies it (an association costs the collection's size, like a hash map's); `nth` steps
  through one where the oracle refuses; `class` answers `:map`/`:set`; a `subseq` or
  `rsubseq` walking from the first member that finds nothing answers `nil` (the oracle
  `()`); a test passed to `subseq` as a value is recognized by how it answers `(1 0)`,
  `(0 0)` and `(-1 0)`, where the oracle compares it with the core functions. `compare`
  orders strings by code point (the oracle by UTF-16 unit, which differs past U+FFFF).
- `float` answers a double, so `(float 1/3)` is `0.3333333333333333` (the oracle's Float prints
  `0.33333334`); a value past the float range still signals. `int` and `long` truncate and do not
  refuse a value out of range (the oracle: `integer overflow`, `Value out of range for long: ...`).
- `vector-of` answers an ordinary vector: a later `conj` or `assoc` stores its value as
  given, where the oracle's keeps casting, and `:float` holds doubles.
- `empty` of a list, a lazy seq or a seq answers `nil` (the oracle `()`, the empty-as-`nil` position
  of `rest`), so it carries no metadata, and of a map entry `[]` (the oracle `nil`).
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
  expands it. A record literal reads
  for any class the program defines, also one a later `require` loads (the oracle needs
  the class loaded first); a deftype literal is refused. `read` takes a stream -- a
  plain `clojure.java.io/reader` too, where the oracle requires a `PushbackReader` -- and
  refuses a host reader.
- `*out*`/`*in*`/`*err*` are `*standard-output*`/`*standard-input*`/`*error-output*`
  (rebinding rebinds the standard streams); read at the root, `*out*` and `*in*` are
  stream values over the process standard streams;
  `defonce` keeps the root on reload where `def` resets it.
- A host-object boolean answers `false` only when the receiver's class is known
  at lowering (a construction literal, a `let`/`if-let`/`when-let` local bound
  to one, or a `..` step's declared return) and every
  overload at that arity answers a primitive boolean, or when the receiver is a
  string, number or character and every overload at that arity of its class answers
  one (`(.matches "abc" "x")`); any other host boolean
  keeps the shared `java:` unmarshal and prints `nil` for `false`.
- An integer receiver is called as an `Integer` when it fits one, else as a `Long`
  (the oracle's is always a `Long`): `(.getClass 1)` answers `java.lang.Integer`.
- A `_` param tag leaves that parameter to the cost rule of the `java:` surface, so
  `(^[_] Math/abs -2)` answers `2` where the oracle refuses tags that leave more than one
  overload.
- Only `.clj` files below the source roots are read (no `.cljc`, no classpath).
- Records and deftypes of one simple name in two namespaces share a dispatch tag, which
  `class`, protocol dispatch and `=` read.
- A name referred from two namespaces keeps the later refer (the oracle refuses it), and
  a definition replaces a refer of its name without the oracle's warning.
