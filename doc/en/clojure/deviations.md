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
  A Common Lisp `format`'s `~S`/`~A` on Clojure values stay Common Lisp notation (it is
  a CL surface; `clojure.pprint/cl-format` writes them as Clojure); `print-method` stays
  absent.
- A map, set or memo key finds an `=` key like the oracle's, vectors, lists, maps and
  sets included, but a stored collection key is the first `=` key of its kind (vector,
  list, lazy seq) the program stored, so its metadata and the spelling of a nested
  member follow that earlier object; those keys stay alive for the whole run, one
  per distinct value and kind.
  `=` compares vectors, lists and lazy seqs element-wise like the oracle, and since `nil`
  is the empty list, `(= [] nil)` and `(= (java.util.ArrayList.) nil)` are `true` where the
  oracle answers `false`. As keys `[]` and `nil` stay apart like the oracle's, so an empty
  list or seq misses an empty vector key: `(get {[] 1} ())` is `nil` (the oracle: `1`).
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
  `int?` answer for the rational (`(ratio? 1.5M)`, `(int? 2N)` are `true`); for the same
  reason a map or set literal holding `1` and `1M` is refused as a duplicate. `identical?`
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
  `aget` throws `ArrayIndexOutOfBoundsException`); `subs`, `.substring` and `.charAt` past a
  string's bounds throw its `StringIndexOutOfBoundsException` (a double bound past the int range
  too, where the oracle's `subs` throws an `ArithmeticException` or an `IllegalArgumentException`). A catch must name a class that resolves on this host
  (`java.*`, `clojure.lang`'s throwables, a class of the program's Java class path:
  `--java-classpath`, `--java-dep`); one the oracle finds on its class path only is
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
  `.getStackTrace` answers an empty vector (so `Throwable->map` answers `:trace []` and no
  `:at` in its `:via` maps); `.getClass` answers what `class` does. On the
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
  `false`. Any other class (`java.util.AbstractList`) is its class object, in a dispatch value too,
  like the oracle.
  Protocol dispatch reads no hierarchy (`derive`): past the exact tag it tries only the
  classes the protocol was extended to, then the `Object` default. It merges
  `Long`/`Double` into `:number`, where the oracle tells them apart, and two
  `clojure.lang` interfaces one value implements are ordered by the kinds of value each
  holds (`IRef` ahead of `IDeref`), since `clojure.lang` is not on this class path.
- `(methods mt)` and `get-method`, `remove-method`, `prefer-method` take the multimethod's
  name (a `defmulti` var, through an alias or a referred one), not an expression: a local
  bound to a multimethod is refused at lowering. The map `methods` answers keys a host
  class row by the keyword `class` answers for it, where the oracle keys it by the `Class`.
- A record prints as its literal (`#user.R{:a 7}`, like the oracle), but `str` of one
  spells that literal too, where the oracle answers `user.R@<hash>`. A deftype prints
  as its wrapper list (`(:C%TYPE ...)`), a reify as `(:C%REIFY ...)`; only the entry
  maps print deterministically. One whose body overrides `toString` prints as the oracle's
  `#object[user.T "text"]` without the identity hash, a reify's class spelled
  `user$reify` without the oracle's number.
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
  `eval` of a macro call expands the same way. A macro body runs at compile time,
  apart from the program: it sees the top-level definitions above the call site
  (functions, multimethods, protocol extensions, and a `def`'s value, built when the
  body first reads it), but no other top-level statement runs there, and what the body
  changes (a `swap!` of a program atom) the program never sees -- the oracle compiles
  and runs in one process. A `defmethod` or protocol extension a program macro expands
  to is not seen there. A call above a macro's definition is refused, and a macro has
  no function value. A `defmacro` of a special
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
- An instance call on a collection, keyword, symbol, ratio, atom or fn answers through the core
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
- `reduce` and `reduce-kv` (and the verbs built on them) consult
  `clojure.core.protocols/CollReduce` and `IKVReduce` for a record, deftype or `reify`
  only: an extension of either to `nil`, `Object` or a core kind is reached through
  `coll-reduce` or `kv-reduce` themselves, where the oracle's `reduce` also takes one for
  a collection that does not reduce itself (a string, a map). A call of a protocol method
  with a count an inline body leaves out signals an `ArityException` (the oracle: an
  `AbstractMethodError`).
- A `reify`, `deftype` or `defrecord` body implements the `clojure.lang` interfaces the core
  functions consult -- `IReduceInit`, `IReduce`, `IKVReduce`, `Seqable`, `Counted`,
  `Indexed`, `ILookup`, `IFn` (with `Callable` and `Runnable`), `IDeref`, `IMeta`, `IObj` --,
  the collection interfaces (`IPersistentMap`, `ISeq`, `Sequential`, `Iterable`,
  `java.util.List` ...) and overrides `Object`'s methods
  ([reify](reference/reify.md#host-interfaces)); any other interface (`IChunkedSeq`,
  `java.util.Deque` ...) is refused by name. `first`, `next` and `rest` of an `ISeq` type read
  it through its `seq`, a verb may call a method another number of times than the oracle, and
  `str` of a collection type spells its contents
  ([collection interfaces](reference/reify.md#collection-interfaces)). A type's `equals` and
  `hashCode` answer `=` and `.hashCode` but never key a map or a set, which hold such a value
  by identity. `sort` and `distinct` take a type implementing `Seqable` alone through its
  seq, where the oracle refuses both.
- `clojure.core.reducers` folds on the calling thread, its parts one after the other, and
  `cat` of two non-empty collections answers one accumulator (a vector) holding both, where
  the oracle answers a `Cat` tree whose fold combines its halves' folds.
- The `unchecked-` arithmetic verbs wrap integers at 64 bits (`-int` verbs at 32) and the casts
  `short`, `byte`, `char` and `float` match the oracle, with one deviation: an integer past 64
  bits is a plain integer here, so the oracle's unwrapped bigint operand
  (`(unchecked-add 9223372036854775807N 1)`) wraps too. `int` and `long` are the oracle's casts,
  range checks and messages included. A literal argument takes the cast of its own type, any
  other the object cast: a double the oracle's compiler types as a primitive (a `let` local
  bound to a double literal, `(* 2.0 x)`) refuses with `Value out of range for int: 2.0E10`
  there and `integer overflow` here. `double` of a ratio is its nearest double (`(double 2/3)` is `0.6666666666666666`), where the
  oracle rounds it to 16 significant digits first (`0.6666666666666667`). `inc`, `dec` and the
  checked verbs never overflow (integers are bignums).
- `bigint` and `biginteger` answer a plain integer, and `bigdec` a plain rational (`(bigdec "1.5")`
  prints `3/2`, the oracle `1.5M`), like the `N` and `M` literals; `bigdec` of a ratio with an infinite
  decimal expansion signals, like the oracle.
- `format` renders `%s`/`%d`/`%x`/`%X`/`%o`/`%c`/`%b`/`%f`/`%%`/`%n` (with widths, float
  precision); `%e`/`%g`, flags and non-literal patterns are named refusals. `%s` spells
  `nil` `"null"`, like the oracle.
- `line-seq` takes an open reader (such as a `clojure.java.io/reader`, which
  `with-open` closes), or a path, a File, a URL or a byte stream it opens, and answers
  strictly either way (the oracle takes a reader only and answers lazily);
  `spit`/`slurp`/`line-seq`/`reader` run on every backend, on wasm with a `--dir` preopen
  covering the file.
  A `java.io.InputStreamReader` over a reader is that reader, on every backend: here a
  Ring request `:body` is a reader, where the oracle's is an `InputStream`.
- The Ring adapter (`ring.adapter.rontolisp/run-server`) puts `:content-type` and
  `:content-length` in the request map but not `:character-encoding` or
  `:ssl-client-cert`; a request without a body has `:body` `nil` (Jetty's adapter
  supplies an empty stream). An asynchronous handler and a second concurrent server are
  refused or replaced, and a `java.io.File` body naming no file signals (500) where Jetty
  answers an empty 200 (see [the adapter](reference/ring.md)).
- The built-in [Ring utilities](reference/ring-util.md) name a charset by a string (UTF-8,
  ISO-8859-1, US-ASCII and the JDK's aliases for them; any other is refused), have
  `ring.util.request/body-string` as a function rather than an extensible multimethod,
  read only ASCII digits in `content-length`, and leave out what needs a byte array. The
  file responses resolve no symbolic link and find no resource computed at run time inside
  a jar.
- A sorted map or set orders, prints and finds keys like the oracle's, but every verb
  copies it (an association costs the collection's size, like a hash map's); `class`
  answers `:map`/`:set`; a `subseq` or
  `rsubseq` walking from the first member that finds nothing answers `nil` (the oracle
  `()`); a test passed to `subseq` as a value is recognized by how it answers `(1 0)`,
  `(0 0)` and `(-1 0)`, where the oracle compares it with the core functions. `compare`
  orders strings by code point (the oracle by UTF-16 unit, which differs past U+FFFF).
- `float` answers a double, so `(float 1/3)` is `0.3333333333333333` (the oracle's Float prints
  `0.33333334`); a value past the float range still signals.
- `mod` and `rem` of a NaN or infinite dividend throw an `ArithmeticException` (the oracle: a
  `NumberFormatException`).
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
  reduced; `println` prints it as that seq where the oracle prints the object. An input
  reducing through its own `CollReduce` row is reduced at the `eduction`, so its `seq`
  answers where the oracle's refuses. A `reduced` value prints as its wrapper list.
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
- The root `with-redefs` replaces is the var's value cell, so inside a `binding` of a
  `^:dynamic` var it changes that binding (the oracle changes the root and the binding
  stays). A `clojure.core` var is refused by name; in the REPL, so is a `defn` an
  earlier input defined without `^:redef`.
- `locking` holds a mutex kept per value, and the lock table keeps every value it was
  handed for the program's lifetime. The `NullPointerException` of a `nil` lock names
  the local `locklocal` (the oracle names a generated one).
- `with-meta` answers a copy carrying the metadata; a value derived from it (`assoc`,
  `conj`, ...) starts without metadata, where the oracle keeps it, and a symbol carries
  none (`with-meta` answers the symbol). A `:tag` from reader metadata on a collection
  literal stays the symbol as written (`String`), where the oracle resolves the class
  (`java.lang.String`).
- `with-open` closes through the `close` method, so only closeables the backend
  reaches work (a `clojure.java.io` byte stream on every backend, a Java closeable on the
  interpreter and the JVM); `time` answers its value but its
  millisecond count never pins, and it counts whole milliseconds (`42.0`) where the
  oracle's carries nanosecond digits.
- `read-string`/`read` answer what a quote answers: `@x` reads `(deref x)` and a
  syntax-quote stays unexpanded, where the oracle reads `(clojure.core/deref x)` and
  expands it. A record literal reads
  for any class the program defines, also one a later `require` loads (the oracle needs
  the class loaded first); a deftype literal is refused. `read` takes a stream -- a
  plain `clojure.java.io/reader` too, where the oracle requires a `PushbackReader` -- and
  refuses a host reader.
- `#inst` and `#uuid` read as the oracle's `java.util.Date` and `java.util.UUID` on every
  backend; [Instants and UUIDs](reference/instants.md) lists the few differences (`str` of an
  instant answers in UTC, a host value from interop is never `=` to a read one).
- `clojure.java.io`'s `java.io.File`, `java.net.URL`, `java.net.URI` and byte streams are
  values of this front end's own on every backend; [clojure.java.io](reference/clojure-java-io.md)
  lists the differences (no byte arrays, three charsets, no connection behind an `http:`
  URL, a resource found on the source path rather than the class path, WASM's directories).
- A data reader runs as the program compiles, so its answer in source loses its metadata
  and needs a spelling here: a function, a deftype instance and a host object other than a
  UUID or Date are `Can't embed object in code`, where the oracle compiles one its
  `print-dup` prints; `()` is `nil` here, so an empty list answer is `No dispatch macro`. A
  `set!` of `*data-readers*` or `*default-data-reader-fn*` changes what `read-string` and
  `read` read, never the program's source, where the oracle's load reads the file's later
  forms (and the REPL its later inputs) with it. The `data_readers` files of the entry
  file's own root count too, where the oracle reads only its classpath's.
- `*out*`/`*in*`/`*err*` are `*standard-output*`/`*standard-input*`/`*error-output*`
  (rebinding rebinds the standard streams); read at the root, `*out*` and `*in*` are
  stream values over the process standard streams;
  `defonce` keeps the root on reload where `def` resets it.
- A fn passed where a Java interface is expected implements every abstract method of
  any interface, each called with the method's arguments; the oracle converts a fn only
  to an interface annotated `@FunctionalInterface` (a `PropertyChangeListener` is a
  `ClassCastException` there). A `Comparator` fn answering `nil` or a non-number is
  refused by name, where the oracle throws a `NullPointerException` or a
  `ClassCastException`.
- A map, set, record, sorted collection or lazy seq passed to Java is a fresh Java copy
  (a set or sorted set a `java.util.LinkedHashSet`; a map, record or sorted map a
  `java.util.LinkedHashMap`; a lazy seq, like a vector or list, a `List`), each member
  converted as an argument is, where the oracle passes the collection itself: `str` of the
  copy, or of a Java collection holding it, spells it the Java way (`[1, 2]`, the oracle
  `#{1 2}`; `{a=[1, 2]}`, the oracle `{a=[1 2]}`). A Java sorted collection orders keywords
  before symbols, where the oracle refuses to compare the two. A ratio and an atom have no
  Java value, so a member taking one finds no match.
- An integer receiver is called as an `Integer` when it fits one, else as a `Long`
  (the oracle's is always a `Long`): `(.getClass 1)` answers `java.lang.Integer`.
- A `_` param tag leaves that parameter to the cost rule of the `java:` surface, so
  `(^[_] Math/abs -2)` answers `2` where the oracle refuses tags that leave more than one
  overload.
- Only `.clj` and `.cljc` files below the source roots are read. A dependency's jar holding
  classes also joins the Java class path; a directory's classes do not (give a prepped
  library's `target/classes` with `--java-classpath`). The project's `deps.edn` is the
  nearest one at or above the entry file, where the oracle reads the working directory's.
  Without the command line (an embedder, the browser playground) a Maven or git coordinate
  is not fetched: a namespace only it could hold is refused, naming it, and the rest of the
  program runs. A built-in library fetches nothing, its own dependencies included; the
  built-in Ring namespaces load without a `ring/ring-core` coordinate (the oracle needs one)
  and stand in for an older ring-core than the one shipped. Without the command line a
  `pom.xml` project is not read either. A library not fetched gives no data readers either: a tag only
  its `data_readers.clj` maps has no reader function, naming it.
  `settings.xml` credentials answer Basic authentication only (the oracle also answers Digest
  and NTLM), and a download, `maven-metadata.xml` included, is always checked against its `.sha1` (the
  oracle's default only warns). A file no repository had is not asked for again until the
  repository's update policy says so, `:daily` unless its `:update` names another (the
  oracle asks on every run). A git tag is checked against the
  local clone, which is fetched only when the tag is missing or names another commit (the
  oracle fetches on every resolution); checkouts live in `~/.rontolisp/gitlibs`, not
  `~/.gitlibs`. Two commits of one library neither of which descends from the other are
  refused naming both, where the oracle throws without a message. A dependency's
  `:deps/prep-lib` is checked, never run. Top-level dependencies are
  expanded in the file's order, where the oracle takes a map of more than eight in its hash
  order: the two differ only where the selection rests on which was seen first (one
  version spelled two ways, `1.0` and `1.0.0`).
- `-M -m` and `-X` name a namespace or function that is missing the way any program
  does (`Could not locate my/app.clj ...`, `No such var: my.app/-main`), where the oracle
  says `Namespace could not be found on classpath`, `loaded but function not found`, or
  throws a `NullPointerException` for a missing `-main`. `clojure.main`'s `-e`, `-i` and
  `--report`, `-X` arguments read from standard input (`-`) and `-T` tools are refused.
  `:jvm-opts` is ignored, and a selected alias whose value is no map adds nothing. A run
  without a file (`-M -m`, `-X`, `-e`, the REPL) searches the working directory first, which
  the oracle's classpath does not hold. `rontolisp test` is no `clj` command: it follows
  the cognitect test-runner's defaults (namespaces ending in `-test` under `test`).
- A reader conditional takes `:rontolisp` too, ahead of `:clj` where a form names it
  first. Under `{:read-cond :preserve}`, `class` of a reader conditional or tagged literal
  answers `:clojure.lang.ReaderConditional`/`:clojure.lang.TaggedLiteral` like every class
  keyword here, `str` spells the hash `=` keeps (not the oracle's `hashCode`), and
  `(reader-conditional nil false)` prints `#?()`, as one read from `#?()` does. In a branch not
  taken, `::alias/kw` of an unknown alias reads (the oracle refuses it); the runtime
  reader splices `#?@(:clj nil)` as nothing (the oracle refuses it) and takes `:features`
  as a hash set only.
- Records and deftypes of one simple name in two namespaces share a dispatch tag, which
  `class`, protocol dispatch and `=` read.
- A name referred from two namespaces keeps the later refer (the oracle refuses it), and
  a definition replaces a refer of its name without the oracle's warning.
