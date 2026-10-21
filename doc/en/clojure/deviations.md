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
  (never `()`). A map or set prints its entries, and `seq`, `keys` and `vals` walk them, in
  insertion order on every backend -- the oracle's order for a map of at most eight entries
  (its array map), not past eight (its hash map) nor for a set (its hash set). A value that closes a
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
  a CL surface; `clojure.pprint/cl-format` writes them as Clojure).
  [print-method](reference/print-method.md) takes a program's own records, deftypes and
  `:type` keywords only: a method for a class is refused by name, `methods` and
  `prefer-method` of it are too, and `get-method` of a type without one answers
  `print-method` itself where the oracle's answers its default method.
- A wrong argument count is the oracle's `Wrong number of args (N) passed to: name`, but a
  local `fn`'s name is the oracle's class name without its generated parts: the enclosing
  function's name (a `defn`'s `my.app/f`, the namespace outside any), then the `fn`'s own
  name or `fn` (`my.app/f/fn`; the oracle's `my.app/f/fn--177`, and at the top level
  `my.app/eval176/fn--177/named--178`, with the `evalN` and the function a `try` is
  wrapped in). A var's function -- a `defn`, a `fn` that is a `def`'s value -- names the
  var as the oracle's does.
- A map, set or memo key finds an `=` key like the oracle's, vectors, lists, maps and
  sets included, but a stored collection key is the first `=` key of its kind (vector,
  list, lazy seq) the program stored, so its metadata and the spelling of a nested
  member follow that earlier object; those keys stay alive for the whole run, one
  per distinct value and kind. An earlier deftype key is stored for a later `=` one only
  when both are of one type holding the same field values (never for a `reify` or a type
  with mutable fields), so a field `=` does not read stays the stored object's own.
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
- `hash`, `hash-ordered-coll`, `hash-unordered-coll`, `mix-collection-hash`, `hash-combine`
  and `.hashCode` answer the oracle's numbers but for a value hashed by its identity -- a
  function, an atom, a var, an exception, a deftype or reify implementing neither `hasheq`
  nor `hashCode` --, whose number differs (between backends and runs too, as between the
  oracle's runs); `()` and an empty `rest`, `nil` here, which hash to `0` (the oracle's are
  an empty seq's); a decimal (`1.5M`), a ratio here; and a `Calendar`, hashed as the `Date`
  of its milliseconds. `hash-ordered-coll` walks a map's, set's or record's members in this
  implementation's order (the unordered hashes agree). A refusal's `ClassCastException`
  message leaves out the oracle's module and loader text.
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
  the end answers the default instead of throwing; strings seq to characters printing in Common Lisp notation. Lazy seqs realize
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
  like the oracle. Naming `use-fixtures` compiles, and only the call signals.
- A `catch` (and `thrown?`) takes a runtime error by the class the oracle throws where the
  runtime signals its Common Lisp condition, and a refusal of the Clojure runtime by the class
  the oracle throws for the same call (`(first 5)` an `IllegalArgumentException`, a failed
  `assert` an `AssertionError`, which an `Exception` catch does not take). An error naming no
  class -- a refusal of a construct the oracle accepts (a regex `\X`,
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
- Six deprecated or internal `clojure.core` names resolve per name rather than as the
  oracle spells them. `EMPTY-NODE` is the empty vector ([EMPTY-NODE](reference/empty-node.md)):
  `(= EMPTY-NODE [])` and `(identical? EMPTY-NODE EMPTY-NODE)` are `true`, and a call
  reads like one of `[]` (a key and an optional default, any other count refused) --
  where the oracle's persistent-vector node is `=` to no vector, `count`/`nth` of it
  throw, and calling it is a `ClassCastException`. `StackTraceElement->vec` answers
  the oracle's `[class method file line]` vector of a host stack-trace element
  ([StackTraceElement->vec](reference/stack-trace-element-vec.md)): the class and the
  method as symbols, `nil` the oracle's `NullPointerException`, anything else its
  `ClassCastException`. Elements exist only on the interpreter and the JVM
  (`.getStackTrace` answers `[]`). `add-classpath`, `Inst`, `PrintWriter-on` and
  `primitives-classnames` stay refused: no class loader exists here (dependencies come
  from `deps.edn`, `--java-dep` and `--java-classpath`; the oracle deprecated
  `add-classpath` in 1.1), `Inst` is the host protocol object `inst-ms*` extends, which
  exists on no backend (`inst?` and `inst-ms` cover the predicate and the reading),
  `PrintWriter-on` builds a host `PrintWriter` over a proxied writer (print into a
  string with `with-out-str`, or rebind `*out*`, instead), and `primitives-classnames`
  is the print-dup helper map for primitive `Class` objects (a class prints by its
  name here). A library reaching for the host class loader (`clojure.lang.RT/baseLoader`,
  e.g. integrant's `load-hierarchy`) refuses at call time the same way: no class loader
  exists here.
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
  supers like the oracle. A core kind (`:string`, `:number`, ...) adds the supers of the
  values it stands for: a function `isa?` `clojure.lang.IFn` and its `ancestors` hold it,
  interfaces and `Object` included, like the oracle's inheritance; a class name in value
  position reads as that keyword (`(= clojure.lang.IFn (class (fn [])))` stays `false`).
  A record or a deftype stands for host classes that are no one value here: it `isa?`
  `Object` and its `ancestors` add `Object`, but its host class's other supers are not
  modeled. Since the
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
  `prefers` answers each dispatch value with the set preferred over it, and
  `remove-all-methods` clears the methods and the preferences, answering the
  multimethod; both take the name the same way and name no function value.
- A record prints as its literal (`#user.R{:a 7}`, like the oracle), but `str` of one
  spells that literal too, where the oracle answers `user.R@<hash>`. A deftype prints
  as its wrapper list (`(:C%TYPE ...)`), a reify as `(:C%REIFY ...)`; only the entry
  maps print deterministically. One whose body overrides `toString` prints as the oracle's
  `#object[user.T "text"]` without the identity hash, a reify's class spelled
  `user$reify` without the oracle's number.
- A deftype's `^:volatile-mutable` field is the same plain slot as an
  `^:unsynchronized-mutable` one (no cross-thread ordering).
- `split`/`replace` leave a plain string literal (only
  pattern values match by pattern); `index-of` answers `-1` when missing, like the
  oracle (where `clojure.string/index-of` answers `nil`).
- `def` inside a body sets the global when the body runs; `defn` inside a body works only
  in statement position. `definline` is `defn`: its inline body is a documented no-op
  (nothing here inlines call sites), so the definition is the function itself.
- Verbs assume the right collection kind; misuse may signal the Common Lisp type error
  instead of the oracle's.
- Macros expand while lowering, so every backend runs expanded code; the interpreter's
  `eval` of a macro call expands the same way. A macro body runs at compile time,
  apart from the program: it sees the top-level definitions above the call site
  (functions, multimethods, protocol extensions, and a `def`'s value, built when the
  body first reads it), but no other top-level statement runs there, and what the body
  changes (a `swap!` of a program atom) the program never sees -- the oracle compiles
  and runs in one process. A `defmethod` or protocol extension a program macro expands
  to is not seen there. A call above a macro's definition is refused, and a program
  macro has no function value. A `defmacro` of a special
  form (`if`, `do`, `let*`, `new`, ...), of a head the reader spells (`deref`,
  `syntax-quote`, `ns`, `in-ns`) is refused by name, where the oracle
  accepts it (and ignores it at call sites, for a special form). As values, core
  macro names answer the run-time once-expansion over the quoted form: `(def x
  when)` hands out the same answer `macroexpand-1` gives (for a core form the form
  itself -- the run-time table holds program macros only), where the oracle refuses
  a bare macro name in value position at compile time. `bound-fn`, `with-local-vars`
  and `pvalues` answer that delegate too; `bound-fn*` answers its function, capturing
  the bindings where the value is called.
- `eval`, and `resolve` of a computed symbol, run only while the program lowers (in a
  macro body and what it calls); at run time they throw an
  `UnsupportedOperationException`, where the oracle evaluates and resolves. `resolve`
  answers `nil` for a `clojure.core` var this front end lacks (the oracle's var), for a
  record or type name (the oracle's class) and for a var of a namespace built into the
  lowering (`clojure.string`); a quoted symbol resolves in the namespace the call lowers
  in, where the oracle reads `*ns*` when the call runs, and to a definition below the call
  too. A definition an `eval`'d form makes exists only while the program lowers.
- `#(...)` reads as the oracle's `(fn* [p1__N# ...] (body))` in source, under a quote and
  in `read-string`/`read`, but N restarts at each top-level form (each datum read), where
  the oracle's counter runs across the process: the parameter names differ, and two reads
  of one text answer `=` forms here. A regex literal passed to a macro reaches the
  expansion as a fresh pattern compiled from the same source, where the oracle's
  expansion holds the one `Pattern` object.
- `#=` evaluates while the program lowers, so every backend carries the answer as a
  literal. Like the oracle's read, the head applies as a function to the unevaluated
  rest, `*read-eval*` gates it, and a quote never shields one -- but the answer must
  have a literal spelling here, so a class (`#=java.lang.String`), a constructor or a
  static call, and any other value with none (an atom, a function, a host object other
  than a UUID or Date) is refused (`Can't resolve` for an unknown name,
  `Can't embed object in code` where the oracle embeds the object). A macro head is
  the oracle's arity refusal whatever the count; of two or more arguments the oracle
  runs the macro body on the shifted arguments instead. A discarded `#_` drops its
  form without evaluating it, where the oracle evaluates an `#=` inside first. A map
  or set literal refuses duplicate keys on the read forms, so two `#=` answers only
  equal once evaluated stay two members here, where the oracle refuses the second. An
  unqualified `#=(var x)` resolves in the current namespace, where the oracle throws.
- Syntax-quote qualifies every symbol but a special form, like the oracle: a core name
  spells `clojure.core/name` (one a `(:refer-clojure ...)` filter hides spells its own
  namespace instead), any other unresolved spelling the defining namespace, an alias
  head its namespace, a class head its fully qualified name. Each `x#` binds one gensym per expansion -- the oracle resolves one per
  compilation, so two expansions share its suffixes where ours differ (fresher, never
  captured). `macroexpand-1`/`macroexpand` answer the mangled data itself, so `=`
  against a quoted form holds and printing spells the oracle's lowercase. Nested syntax-quote
  evaluates its levels in the one expansion.
- A var's metadata comes from the definitions lowered above the `#'` site: a body
  lowered above a redefinition sees the older docstring until a `#'` site lowered below
  the redefinition has run, where the oracle's var shows the newest at once. `:ns` is the namespace's symbol (the oracle's
  is a Namespace object), `:file` of the entry file is the path as given (the oracle
  absolutizes it), and a var defined by anything but `def`/`defn`/`defn-`/`defmacro`
  (`defmulti`, `deftest`, a record's factory, ...) carries only `:name` and `:ns`.
  Deref of a program macro's var signals (the oracle answers its expander function);
  deref of a core macro's var answers the same once-expansion a bare core-macro
  name does. A `clojure.core` var's metadata is only `:name`, `:ns` and a macro's
  `:macro` (the oracle's also carries `:arglists`, `:doc`, `:added` and the position),
  and a core var with no value here (`#'add-classpath`) is refused.
- A namespace prints as the oracle's `#object[clojure.lang.Namespace "user"]` without the
  identity hash, and `class` of one answers `:clojure.lang.Namespace`. `the-ns` and
  `find-ns` know the namespaces the program created above the call, the libraries it
  required and the four `clj -M` loads first (`clojure.core`, `clojure.edn`,
  `clojure.java.io`, `clojure.string`); `in-ns` answers `nil` (the oracle's answers the
  namespace). `set!` and `binding` of `*ns*` change what `*ns*` reads, not the namespace the
  forms below resolve in, which `ns` and `in-ns` with a literal name decide. A compiled
  program's `*file*` is the entry file's path when it was compiled. The namespace mappings
  (`ns-publics`, `ns-map`, ...) answer what the lowering knows when the call lowers: a
  `clojure.core` var the subset lacks stays out, an import maps to a symbol of its class,
  and anything computed refuses. `alias`, `refer`, `ns-unmap` and `ns-unalias` wire while
  the program lowers, so inside a body they still reach the whole file (the oracle's run
  when the body runs); a `load-string` of a literal declares ahead like a file, so the
  same form may already name what it defines (the oracle's needs a later form). The
  standalone `(refer-clojure ...)` wires the same `(:refer-clojure ...)` filter that
  way (`:only`, `:exclude`, `:rename`, answering `nil`).
  `bounded-count` of a seq a lazy-or-strict verb realized strictly counts whole, where the
  oracle's stays lazy and stops at the bound; `print-dup` of a record or a queue prints as
  `pr` prints it, where the oracle prints its `toString`; a bit operation takes an
  `N`-suffixed literal in range as its plain integer, where the oracle refuses its `BigInt`.
- `class` answers a keyword naming the kind (`:string`, `:number`, `:keyword`, ...);
  the oracle answers host classes, which no wasm backend has. A record or deftype
  answers its tag keyword instead; a host object (interpreter and JVM) its host class.
- A [byte array](reference/byte-array.md) prints as the oracle's `#object["[B" ...]` without
  the identity hash, and `str` answers `[B` (the oracle's `[B@1b6d3586`). `aset` stores any
  integer from -128 to 127 in one, where the oracle's reflection takes only a `Byte`
  (`(aset bs 0 5)` is refused there, `(aset bs 0 (byte 5))` is not). A seq over a byte array
  holds the elements the array had when the seq was taken, where the oracle's reads the
  array as it walks.
- A narrowing cast (`byte`, `short`, `int`) answers a plain integer (g02, measured clj
  1.12.6 2026-10-13); the oracle answers `Byte`/`Short`/`Integer`. Printing, `=`,
  hashing, `compare`, `sort`, `case`, map and set keys, arithmetic (which answers plain
  integers there too), conversions and truthiness all read the oracle's values, and a byte
  array reads plain integers where the oracle reads `Byte`s. What needs the box diverges:
  a member taking `byte` takes only a `Byte` there, so `(.put bb (byte 7))` is refused
  like `(.put bb 7)` -- write a single byte through a one-element byte array instead
  (`(.put bb (byte-array [7]))` puts `[7 0]` on both); a lone `short`, `int` or `long`
  member converts its fixnum argument as the oracle converts its number (one candidate
  of the arity takes its arguments however they convert, on both).
  `instance?` of `Byte`/`Short`/`Integer` is false for a cast (and of `Long` true),
  `class` answers `:number`, and `.compareTo` reads the cast as its `Long` (the oracle
  throws handing a `Long` to `Byte.compareTo`). Boxing the casts would move the whole
  value model (`+`, `=`, printing, every `unmarshal`) on every backend for that one
  overload family, so the casts stay plain integers.
- A [typed array](reference/int-array.md) (`object-array`, `to-array`, `to-array-2d`,
  `into-array`, `int-array`, `long-array`, `double-array`, `float-array`, `short-array`,
  `char-array`, `boolean-array`) is a vector, as one `make-array` makes: `class` answers
  `:vector`, `str` and `pr` print the vector, `=` compares elements, `coll?`, `vector?`,
  `sequential?` and `counted?` are true, `aset` stores any value, and `alength` and
  `aclone` take a vector -- where the oracle answers its array classes, prints `#object`
  with the identity hash, compares by identity, takes no collection predicate, refuses a
  mistyped `aset`, and refuses a vector to `alength`/`aclone`. `to-array-2d` answers a
  vector of vectors, read through nested [aget](reference/aget.md) (several indexes over
  one is a rank error, as over any nested vector). An `aset-int`, `aset-long`,
  `aset-double`, `aset-float`, `aset-short`, `aset-boolean` or `aset-char` stores the
  value its primitive casts into any vector, answering the value itself, where the
  oracle checks the component; several indexes store through nested vectors, where the
  oracle's rows are `Object` arrays its reflection refuses. An `ints`, `longs`,
  `doubles`, `floats`, `shorts`, `chars` or `booleans` narrows any vector, where the
  oracle refuses what is no primitive array. A `float` holds a double,
  like `float`, so `(first (float-array [1/3]))` prints long where the oracle's prints
  `0.33333334`. A `short` two-argument init fills for any integer in the short range,
  where the oracle fills only for a `Short` (a `Short` is an integer here). A small `N`
  literal is a `Long`, so it takes a `Long` component in `into-array` where the oracle's
  `BigInt` refuses, and a computed `Class` object is refused as no `Class`.
- A `java.nio.charset.StandardCharsets` field or `Charset/forName` of a string literal (a
  [charset](reference/byte-array.md)) prints as the oracle's `#object` without the identity
  hash, and `class` answers its class's keyword. Only UTF-8, ISO-8859-1 and US-ASCII encode
  and decode: `.getBytes` or `String.` with another (UTF-16, say) is the oracle's
  `java.io.UnsupportedEncodingException` of its name. `Charset/forName` of anything but a
  literal, and a `Charset` a Java member answers, stay host objects (interpreter and JVM),
  `=` to a charset named in the program only by being the same object.
- A [transient](reference/transient.md) prints as the oracle's `#object` without the
  identity hash, and `str` answers its class name. A bang verb answers the transient it
  was handed, where the oracle's may answer another object (an array map's `assoc!` past
  eight entries), so a program that ignores the answer keeps every edit here and loses
  some there. `nth` past the end of a transient vector answers `nil`, as of a vector.
- A [persistent queue](reference/persistent-queue.md) prints as the oracle's `#object` without
  the identity hash, and `class` answers the type's keyword `:PersistentQueue`.
- A `clojure.lang.LispReader$StringReader` or `$RegexReader` ([read](reference/read.md)) is a
  function: `fn?` of one is true, where the oracle's is false.
- A [delay](reference/delay.md) prints as the oracle's `#object` without the identity hash, a
  failed one's `:val` the exception's report where the oracle prints its `#error` map, and `str`
  answers `clojure.lang.Delay` (the oracle's `clojure.lang.Delay@hash`).
- An instance call on a collection, keyword, symbol, ratio, atom or fn answers through the core
  functions and shares their deviations (`.getClass` answers what `class` does). A method of a
  JDK interface the oracle's class implements (`.toArray`) is refused on the wasm backends as
  `Method m taking N args is not supported for class C`, and so is, on every backend, a method
  the oracle's class may have that nothing here answers (`.reduce`, `.meta`, an atom's
  `.compareAndSet`); a method one value class has is refused that way on every receiver, where
  the oracle names it missing from another class. Such a call on the interpreter and the JVM
  reaches the read-only Java object the value crosses to Java as, whose mutators throw only
  when they would change something (`.remove` of an absent key, `.clear` of an empty
  collection answer), where the oracle's always throw. The class named for a map is an array
  map up to eight entries and a hash map past them, by size alone. `nil` is the empty list here, so a
  collection method answers on it (`(.count nil)` is `0`) where the oracle throws a
  `NullPointerException`; any other method on `nil` is one. On a record, deftype or reify,
  a name that is no protocol method and no field of a record or deftype the program defined
  is treated as on a collection; a site lowered
  before a later REPL input defines a record or deftype does not see its methods or
  fields.
- A zero-argument `(StringBuilder.)` (or `(new StringBuilder)`, `(StringBuffer.)`) is a
  string output stream on every backend, the same object as `(new java.io.StringWriter)`:
  `class` answers `:java.io.StringWriter` and it prints as one's `#object`, `(.length w)`
  answers on a `StringWriter` too (the oracle has no such method there), and `.append` of
  one value appends its `str` (nil appends `"null"`), where the oracle resolves overloads
  and refuses an ambiguous argument (`(.append sb nil)`). A one-argument construction
  pre-fills the stream with the text, an integer argument being a capacity (ignored); any
  other argument is refused as `No matching ctor found for class ...`, nil among them,
  where the oracle throws a `NullPointerException`. A one-argument `java.io.StringWriter`
  is an empty such stream instead: a number is a capacity (ignored), any other argument
  the oracle's `ClassCastException` to `Number` (nil its `NullPointerException`), whose
  message carries no module tail. `class` and `instance?` see the
  builder as the `StringWriter` it is (`(instance? CharSequence (StringBuilder. "a"))` is
  `false`). `.intern` of a string is the
  string, so only `identical?` could tell the interning apart. `java.util.Locale/US` (and
  `ROOT`, `ENGLISH`) is its `toString` text (`"en_US"`, `""`, `"en"`): `str` and `.toString`
  match the oracle, while `pr`, `class` (`:string`) and `string?` read the string.
  `.toUpperCase`/`.toLowerCase` of one argument map as the bare call whatever the argument
  is, where the oracle casts it to a `Locale`.
- A zero-argument `(java.util.HashMap.)` (or `(new java.util.HashMap)`) is the empty map
  and a one-argument construction of a map the map itself on the wasm backends, where no
  mutable map exists; `.get` and the other lookups read it, `.put` throws the oracle's
  message-less `UnsupportedOperationException` (as the read-only map Java is handed the
  value as throws it on the interpreter and the JVM), and nil builds to the oracle's
  `NullPointerException`. A two-argument construction of two numbers
  (`(java.util.HashMap. 1024 0.25)`) is the empty map too, and `.putAll` of a map stores
  its entries into the map and answers nil; on a record or a sorted map it throws the
  same `UnsupportedOperationException`. `.putAll` into a map literal stores into the
  literal's own table there, where the oracle throws. `str` and `class` read the Clojure map (`{:a 1}`, an array- or
  hash-map keyword), where the oracle spells the `HashMap`; a capacity argument answers
  itself, where the oracle builds an empty mutable map. Where the host is (the
  interpreter and the JVM) the construction stays that mutable object.
- A zero-argument `(Object.)` is a fresh uninterned symbol on the wasm backends, so a
  load-time lock builds; two constructions are never `=` or `identical?`, like two host
  objects, but one prints as a gensym where the oracle prints the object's identity.
  Where the host is it stays the host object.
- A `(java.util.Random. seed)` (or `(new java.util.Random seed)`) construction is the
  JDK's 48-bit linear congruential generator on the wasm backends, answering the
  oracle's draws bit for bit (`.nextInt`, `.nextLong`, `.nextFloat`, `.nextDouble`,
  `.nextBoolean`, `.nextGaussian`, `.setSeed`); a zero-argument construction seeds from
  host entropy. The seed and bound casts refuse as the oracle's do. Where the host is
  (the interpreter and the JVM) the construction stays the host object. `(Math/floor x)`,
  `(Math/ceil x)` and `(Math/log x)` of one argument answer the oracle's double on the
  wasm backends too, refusing as the oracle's casts do.
- `(System/getProperty k)` of a literal key is nil and `(System/getProperty k dflt)`
  its default on the wasm backends, where no properties exist (like a host without the
  property, which is what a library load reads); nil is the oracle's
  `NullPointerException`. A computed key stays the host call, which the wasm backends
  reject.
- `Long/MAX_VALUE` (and `MIN_VALUE`, the `Integer`/`Short`/`Byte` pairs) is itself on
  the wasm backends, so a load-time constant reads; any other member stays the host
  field.
- A bare class name is its `(:C%CLASS name)` designator on the wasm backends, so a
  class-keyed map builds at load; two literals of one class are equal, like two
  `forName` answers, but one prints as the list where the oracle prints the name, and
  `class?` and `str` read the designator.
- A `DateTimeFormatterBuilder` chain evaluates on the wasm backends (each builder call
  threads a marker, `toFormatter` the formatter, `ofPattern` and `ZoneId/of` their
  own), so a load-time formatter builds; parsing or formatting with one is rejected in
  the oracle's words.
- `instance?` answers a class by the oracle classes of each kind of value: a list or strict
  seq is a `clojure.lang.PersistentList`, so `IPersistentList` and `Counted` are `true` of
  `(map inc [1])` and `LazySeq` is not (the oracle's is a `LazySeq`); every two-member vector
  is a `java.util.Map$Entry` (`map-entry?`); an integer is a `Long`, `(int 1)` too, never an
  `Integer`. A `clojure.lang` class no kind is an instance of
  (`clojure.lang.PersistentQueue`) is an unknown name, where the oracle answers `false`. A
  protocol's interface (`user.P`) knows the records and deftypes defined when the site is
  lowered, so one a later REPL input defines is not an instance there; the protocol's own
  name (`P`, a var) is an unknown name, where the oracle throws a `ClassCastException`.
  A class value `class` answered tells what `class` tells: `(instance? (class 1) 1.5)` and
  `(instance? (class {}) (sorted-map))` are `true`, and a subclass is walked only for an
  exception or a runtime error, so `(instance? (class #inst "2020") ts)` of a
  `java.sql.Timestamp` is `false`. A keyword is taken for the class keyword `class` answers,
  so `(instance? :a x)` through a var answers `false`, where the oracle throws a
  `ClassCastException`; that exception's message has no module tail.
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
  `CharSequence`, the collection interfaces (`IPersistentMap`, `ISeq`, `Sequential`,
  `Iterable`, `java.util.List` ...) and overrides `Object`'s methods
  ([reify](reference/reify.md#host-interfaces)); any other interface (`IChunkedSeq`,
  `java.util.Deque` ...) is refused by name. `first`, `next` and `rest` of an `ISeq` type read
  it through its `seq`, a verb may call a method another number of times than the oracle, and
  `str` of a collection type spells its contents, `re-matcher` and `re-find` read a
  `CharSequence` type in prefixes of 16, 32, 64 ... characters (past where the oracle's
  matcher stops), and the other regex functions and `clojure.string` read it whole through its
  `length` and `charAt` once per call
  ([collection interfaces](reference/reify.md#collection-interfaces)). A type with a hash and
  an equality of its own keys a map or a set by value, every map comparing its keys as the
  oracle's hash map does, where the oracle's array map (up to eight entries) compares them
  without hashing; a map, set or sequential type is bucketed by its contents rather than its
  `hasheq` ([map keys](reference/reify.md#map-keys-and-set-members)). `sort` and `distinct`
  take a type implementing `Seqable` alone through its seq, where the oracle refuses both.
- A program-defined interface (`definterface`) joins the same rows under its own
  binary name, which `instance?` tests and a `.method` call dispatches on
  ([reify](reference/reify.md#host-interfaces)). Deviations: the name's value is the
  binary-name string (the oracle's class object), so `resolve` answers its var and
  `str` spells the name bare; redefining the interface replaces it, and instances
  made before the redefinition still satisfy it, where the oracle's are instances of
  the old class only; a wrong-count call on a `reify` names `java.lang.Object` (a
  reify's class is no host class here), and the abstract refusal of a method the body
  leaves out names the method and the interface but no receiver class.
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
  oracle rounds it to 16 significant digits first (`0.6666666666666667`). `inc'`, `dec'`,
  `+'`, `-'` and `*'` are `inc`, `dec`, `+`, `-` and `*`: integers are bignums, so nothing
  overflows, and anything but a number is refused with the oracle's class. `cast` answers
  the value when it is an instance of the class (its `instance?` test for a named class,
  the protocol tag for a computed one), `nil` of `nil`, else the oracle's refusal; a host
  class no value here has always refuses (host classes exist on no wasm backend).
- While `*unchecked-math*` is truthy (`true` or `:warn-on-boxed`), `+`, `-`, `*`, `inc`
  and `dec` wrap a 64-bit-integer sum, difference or product mod 2^64; anything else -- a
  double, ratio or out-of-range-integer operand, and the flag down -- is the plain verb.
  The flag is read when the call runs, not when it compiles: a `binding` around the call
  wraps where the oracle (which bakes the flag into the call at compile time) throws, and
  a function defined before a top-level `set!` wraps when called after it. No boxed-math
  warning is ever emitted, so `:warn-on-boxed` wraps exactly like `true`. As values
  (`+`, `apply +`, `map inc`, ...) the verbs keep the default promoting behavior on
  every flag, where the oracle's function values throw on overflow.
- `bigint` and `biginteger` answer a plain integer, and `bigdec` a plain rational (`(bigdec "1.5")`
  prints `3/2`, the oracle `1.5M`), like the `N` and `M` literals; `bigdec` of a ratio with an infinite
  decimal expansion signals, like the oracle.
- `format` renders and refuses as `java.util.Formatter` does (a computed format string parses
  when the call runs), except: `%h`, `%a` and `%t` are refused; an integer past the long range
  renders as a `BigInteger` (the oracle refuses its `BigInt` under `%d`/`%x`/`%o` and renders a
  `biginteger`);
  `int` answers a long, so `(format "%x" (int -1))` is 64 bits of `f` (the oracle's 32), and `%c`
  refuses an integer (the oracle takes an `Integer` code point).
- `upper-case`, `lower-case`, `capitalize`, `.toUpperCase`, `.toLowerCase`, the uppercase `format`
  conversions and `cl-format`'s case clauses map as the oracle's `String` methods do in a locale
  other than Turkish, Azerbaijani and Lithuanian (`ß` to `SS`, a capital sigma ending a word to
  `ς`), except in a word holding a character past the BMP, where the JDK can see a word end that
  is not there: `(lower-case "Α𝐀Σ")` is `"α𝐀ς"` here and `"α𝐀σ"` in the oracle.
- `line-seq` takes an open reader (such as a `clojure.java.io/reader`, which
  `with-open` closes), or a path, a File, a URL or a byte stream it opens, and answers
  strictly either way but over an HTTP reply, which it reads lazily (the oracle takes a
  reader only and answers lazily);
  `spit`/`slurp`/`line-seq`/`reader` run on every backend, on wasm with a `--dir` preopen
  covering the file.
- The Ring adapter (`ring.adapter.rontolisp/run-server`) puts `:content-type` and
  `:content-length` in the request map but not `:character-encoding` or
  `:ssl-client-cert`. An asynchronous handler and a second concurrent server are
  refused or replaced, and a `java.io.File` body naming no file signals (500) where Jetty
  answers an empty 200 (see [the adapter](reference/ring.md)).
- The built-in [Ring utilities](reference/ring-util.md) name a charset by a string (UTF-8,
  ISO-8859-1, US-ASCII and the JDK's aliases for them; any other is refused), have
  `ring.util.request/body-string` as a function rather than an extensible multimethod,
  and read only ASCII digits in `content-length`.
- A sorted map or set orders, prints and finds keys like the oracle's, but every verb
  copies it (an association costs the collection's size, like a hash map's); `class`
  answers `:map`/`:set`; a `subseq` or
  `rsubseq` walking from the first member that finds nothing answers `nil` (the oracle
  `()`); a test passed to `subseq` as a value is recognized by how it answers `(1 0)`,
  `(0 0)` and `(-1 0)`, where the oracle compares it with the core functions. `compare`
  orders strings by code point (the oracle by UTF-16 unit, which differs past U+FFFF).
- String indexes and lengths count Unicode code points on every backend, past the BMP
  included: `count`, `subs`, `.substring`/`.subSequence`, `.charAt`, `nth`/`get`,
  `clojure.string/index-of`/`last-index-of`, `.indexOf`/`.lastIndexOf` and `compare`
  all read that space (`.indexOf` of an `int` searches its character). The oracle counts
  UTF-16 units instead, so every astral answer differs there (`(count "a🌍b")` is 3 here
  and 4 there). A UTF-16 offset from Java, JavaScript, the LSP or another editor crosses
  the boundary converted: index arithmetic composed from one of these calls to another keeps
  working, but a foreign offset must be translated first. `lower-case` (and `.toLowerCase`)
  folds the same code-point word scan, so the astral word edge stays divergent too
  (`(lower-case "Α𝐀Σ")` is `"α𝐀ς"` here and `"α𝐀σ"` in the oracle).
- `float` answers a double, so `(float 1/3)` is `0.3333333333333333` (the oracle's Float prints
  `0.33333334`); a value past the float range still signals.
- `mod` and `rem` of a NaN or infinite dividend throw an `ArithmeticException` (the oracle: a
  `NumberFormatException`).
- `vector-of` answers an ordinary vector: a later `conj` or `assoc` stores its value as
  given, where the oracle's keeps casting, and `:float` holds doubles.
- `empty` of a list, a lazy seq or a seq answers `nil` (the oracle `()`, the empty-as-`nil` position
  of `rest`), so it carries no metadata, and of a map entry `[]` (the oracle `nil`).
- `partition-all` with a non-positive size or step signals,
  where the oracle answers an endless seq of `()`. `pmap` is `map`, run in order on the
  calling thread. `pvalues` evaluates its expressions in order on the calling
  thread and answers a realized seq (`nil` when empty, where the oracle's empty
  lazy seq prints `()`); a thread-local write stays visible where the oracle's
  pool thread refuses it, and a throwing expression stops the later ones. `take-nth` with a zero step signals, and its seq arity steps by the
  magnitude of a negative one, where the oracle's repeats the first member forever.
- Transducers are the oracle's functions over reducing functions, but an `eduction` is
  the `sequence` of its input through them, computed once (strictly over a strict input,
  lazily over a lazy one), where the oracle re-runs the transformation every time it is
  reduced; `println` prints it as that seq where the oracle prints the object. An input
  reducing through its own `CollReduce` row is reduced at the `eduction`, so its `seq`
  answers where the oracle's refuses. A `reduced` value prints as its wrapper list.
- Transactions run one at a time: `dosync` holds one lock for every transaction
  of the process, so a body never retries and its side effects run once
  (`commute` runs its function once; the oracle may run it twice). `sync` is the
  same one-lock run with its flags dropped unevaluated (a nested `sync` joins the
  running transaction like a nested `dosync`); `io!` refuses inside one with the
  oracle's `IllegalStateException` (`I/O in transaction`); `with-loading-context`
  is its body as-is. A body that
  waits for another thread's transaction never finishes, and a read outside a
  transaction may see a running one's write, where the oracle's sees only
  committed values.
- Agents have no thread pool: a send to an idle agent runs its action on the
  sender's thread before `send` returns, and one to an agent another thread is
  running joins that thread's queue; likewise `restart-agent` runs the queue kept
  behind a failure on its own thread, and an error handler runs on the thread
  running the failing action. An agent prints unreadably,
  `#<Atom ...>`, not the oracle's object; `shutdown-agents` answers `nil`, and
  `*agent*` is bound only while an action runs (`nil` outside one, where the
  oracle leaves it unbound). `(await-for 0 a)` answers `true` once the actions it
  ran finished; the oracle's races its pool and may answer `false`.
- A `defn` with no value cell validates its new function only once its definition is
  bound: where the validator refuses it, the call still reports the oracle's
  `Invalid reference state`, but later calls reach the refused definition (the oracle
  keeps the old one). A refused `defmacro` redefinition still serves its new expander
  (the call sites below it expanded with it while lowering and the table holds it),
  where the oracle keeps the old root and drops the macro.
- `swap!`, `reset!`, `compare-and-set!`, `swap-vals!` and `reset-vals!` also take a
  ref or a volatile, whose cell an atom shares (the oracle's refuse anything but an
  atom).
- `binding` rebinds only `^:dynamic` vars (anything else is refused, like the
  oracle's non-dynamic error); a `^:dynamic` `defn` is rebindable too (its calls
  go through the var while the definition stays direct); reader metadata on names
  and locals otherwise parses and drops, never affecting dispatch. `with-precision`
  binds `*math-context*` to the evaluated precision around its body (`nil` at the root,
  where the oracle binds its default context); division stays exact -- a ratio, never the
  oracle's `BigDecimal` -- so the binding scopes the extent without rounding anything.
  `bound-fn` is `bound-fn*` over `fn`: the captured creation-time bindings are
  pushed over the caller's own, so a call on another thread sees them; a var
  bound nowhere at creation stays out of the map, and a `bound-fn*` created
  under a dynamically entered `with-local-vars` extent does not see that cell.
  `with-local-vars` binds each name to a fresh var cell, unbound at the root and
  restored by leaving (an `unwind-protect` restores every cell, so a throw still
  restores); the cells print `#'ns/name` (the oracle's are unnamed),
  `alter-var-root` of one is refused, and a `recur` across the body is refused
  like the oracle's `try` boundary.
- In the REPL, a local named like a `^:dynamic` var that a later input defines binds
  that var for its extent once it is defined, so a function called there reads the
  local's value; the oracle binds a local lexically, and the function reads the var.
  In a file such a local is lexical.
- The root `with-redefs` and `alter-var-root` replace is the var's value cell, so inside
  a `binding` of a `^:dynamic` var they change that binding (the oracle changes the root
  and the binding stays). A `clojure.core` var is refused by name, and so is a
  multimethod, protocol method, record constructor or test that `alter-var-root` reaches;
  in the REPL, so is a `defn` an earlier input defined without `^:redef`.
- `locking` holds a mutex kept per value, and the lock table keeps every value it was
  handed for the program's lifetime. The `NullPointerException` of a `nil` lock names
  the local `locklocal` (the oracle names a generated one).
- `with-meta` answers a copy carrying the metadata; `assoc` (and `update` and
  `assoc-in` through it) keeps the source's metadata, like the oracle, while any
  other value derived from it (`conj`, `dissoc`, ...) starts without metadata,
  where the oracle keeps it, and a symbol carries
  none (`with-meta` answers the symbol). A `:tag` from reader metadata on a collection
  literal stays the symbol as written (`String`), where the oracle resolves the class
  (`java.lang.String`); a `{:tag Type}` on a `def` naming a record or deftype keeps the
  quoted symbol for the same reason (a type is no class here), where the oracle stores
  the class.
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
  backend, and so do the `java.util.Date`, `java.sql.Timestamp` and `java.util.UUID`
  constructions and statics; [Instants and UUIDs](reference/instants.md) lists the few
  differences (`str` of an instant answers in UTC, a host Date or UUID a Java member answers
  is `=` to one made here but prints as the host object and is another map key).
- `clojure.java.io`'s `java.io.File`, `java.net.URL`, `java.net.URI` and byte streams are
  values of this front end's own on every backend; [clojure.java.io](reference/clojure-java-io.md)
  lists the differences (three charsets, an `http:` URL read only by a program that uses
  `rontolisp:fetch` for it, a resource found on the source path rather than the class path,
  WASM's directories, a byte-array stream `input-stream` answers unwrapped).
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
  `ClassCastException` there). As in the oracle, a fn Java takes as a `Runnable`,
  `Callable`, `Comparator` or `Object`, inside a collection too, is the fn Java hands back
  (`(.comparator (java.util.TreeSet. f))` is `f`, `(.invokeAll pool [f g])` runs both), and
  where the method answers an object the fn's answer is the value's own object; the fn's Java
  object spells itself `clojure.lang.AFunction@1b6d3586`, where the oracle's names the fn's own
  class. Where the method answers a primitive the answer is cast as the oracle's adapter
  casts it, a boolean by its compiler's `(Boolean)` cast where the call resolves before it
  runs and by its reflector's truthiness where it does not: a call the oracle resolves only
  through a type hint (dropped here) or a collection literal's class
  (`(.filter (.stream [1 2]) pred)`) reads a predicate's `nil` as `false` here where the
  oracle throws. A `proxy` body's primitive answer is unboxed as the oracle's proxy class
  unboxes it.
- A value passed to Java is an object of this front end's classes that Java reads as the
  oracle's own: a vector, list, lazy seq, set, map, record or sorted collection a read-only
  `java.util` `List`, `Set` or `Map` (a vector also `RandomAccess` and `Comparable`) printing
  as Clojure prints it, a keyword, symbol or ratio an object hashing, comparing and printing
  as the oracle's (a ratio a `Number`), a deftype or reify whose body implements a Java
  interface or overrides an `Object` method, or a record whose body implements a Java
  interface, an object implementing those interfaces (a record's also its `Map`) and calling
  the type's methods, a fn the object the fn is (above), and an atom, other deftype or reify
  or other value an object equal only to itself, spelled as the oracle's `Object.toString`
  (`clojure.lang.Atom@1b6d3586`). Java hands each back as the value itself. What differs: the
  class Java sees (`getClass`, and the JDK's `ClassCastException` messages naming it; the
  front end's own cast failures name the oracle's class but lack the module tail; a reify's
  class is its namespace's `ns$reify`, where the oracle numbers each); the members are
  converted once, when the value crosses, a lazy seq realized to its end; the `toString` is
  what `str` answers here (a lazy seq and a record spell their contents); a value whose
  oracle class is no `Comparable` is one here whose `compareTo` throws the oracle's
  `ClassCastException`; Java sees no field of a deftype. A Java array a member answers is a list
  here, which converts back to an array where one is expected and nothing takes the list
  whole -- so
  `(java.util.Arrays/asList [1 2])` is a list holding the vector, where the oracle throws --
  and so does an array `make-array` makes, a vector here. A byte array crosses as the
  `byte[]` of its bytes, its own on the interpreter and the JVM alike, so an array a Java
  object keeps (`ByteBuffer/wrap`'s, a `ByteBuffer`'s `.array`) is the byte array itself. A
  `byte[]` coming back is a new byte array over the same bytes, never `identical?` to one that
  went out; so is one Java hands a `proxy`, a deftype or reify method or a fn.
- An integer receiver is called as an `Integer` when it fits one, else as a `Long`
  (the oracle's is always a `Long`): `(.getClass 1)` answers `java.lang.Integer`.
- A `_` param tag leaves that parameter to the cost rule of the `java:` surface, so
  `(^[_] Math/abs -2)` answers `2` where the oracle refuses tags that leave more than one
  overload.
- `(.thenComparing c (fn [a b] ...))` takes the fn argument as a `Comparator` and compares by
  it, where the oracle refuses a fn literal or local there at compile time (`More than one
  matching method found: thenComparing`: the `Function` overload takes it too).
- A dependency's Java class compiled against `clojure.lang` (a Clojure library's own
  class over it, such as `reitit.Trie`) cannot link here, and every call on it refuses
  with `Cannot link class C: missing class M`, naming the missing class -- catchable
  by `(catch Exception e ...)` on the interpreter and the JVM alike (on wasm every
  `java:` call is a call-time error). A class whose static initializer needs the
  missing class (a static field of its type, as in `sci.impl.FISupport`) refuses the
  same way: its `ExceptionInInitializerError` carries the missing class, so
  construction, calls and static-field reads answer the same refusal, while a
  genuinely broken initializer propagates as it did. There is no `clojure.lang` bridge, and none is
  planned: no value here is an `IPersistentMap`, so dependency code taking one could
  not read it anyway. Libraries that need one also ship a portable path (reitit's
  `clojure-trie-compiler` answers the oracle's lines), and the reader takes a
  library's `:rontolisp` branch where one names it first.
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
  keyword here, and `(reader-conditional nil false)` prints `#?()`, as one read from `#?()`
  does. In a branch not
  taken, `::alias/kw` of an unknown alias reads (the oracle refuses it); the runtime
  reader splices `#?@(:clj nil)` as nothing (the oracle refuses it) and takes `:features`
  as a hash set only.
- Records and deftypes of one simple name in two namespaces share a dispatch tag, which
  `class`, protocol dispatch and `=` read.
- A name referred from two namespaces keeps the later refer (the oracle refuses it), and
  a definition replaces a refer of its name without the oracle's warning.
