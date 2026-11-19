# Provenance, licences and naming (project-wide; written for the Java front end)

**Invariant: nothing this project ships -- a front end's library, its run-time helpers, its
tests, its documentation -- is copied or translated from a JDK or from any source whose licence
does not allow it; it is written here from the published specification, and no user surface
claims Java compatibility.** Decided 2026-11-17 for the Java front end, before its first line;
**since 2026-11-18 the rules bind the whole project** (user decision, when the Clojure front
end's regex engine was found to be a port of OpenJDK's `Pattern.java`). Not legal advice, the
working rule. The file keeps its name: the todo items and the library's headers cite it.

Why it binds hardest on what is EMBEDDED in a compiled program (`.class`, `.wasm`, the native
executable -- the libraries, the run-time helpers, the tables): its licence becomes the licence
of what a user ships. This project is Apache-2.0 and its output must stay so.

## The rules

1. **No JDK jar and no JDK class file** is bundled, embedded, extracted or read at build time to
   produce what we ship. The JDK is a tool that runs beside us, never an input to the artefact.
2. **No OpenJDK source is copied, ported or translated** -- into Java, into Common Lisp, into a
   test. OpenJDK is GPLv2 with the Classpath exception; the exception covers linking against
   its classes, not copying them. Reading its source to learn a behaviour and then writing the
   same code is the thing to avoid: learn the behaviour from the API specification, or by running
   a program under a JDK and looking at the output.
3. **No Javadoc text** in our sources, comments or `doc/`. Names, signatures and the specified
   behaviour are what an implementation has to share; the prose is theirs. Constants and
   algorithms the specification itself states (`String.hashCode`'s formula, `Random`'s
   generator) are implemented as specified.
4. **No OpenJDK test** (jtreg and the rest: GPLv2 with no exception) in the repository. The spec
   corpus (`java-spec.yaml`) is written here; a case's expected output comes from running it
   under a JDK, which is use, not copying.
5. **A port is allowed only from an implementation whose licence allows it** -- Apache-2.0,
   or a permissive licence (MIT, BSD, Sun's fdlibm permission) -- with its notice kept: the
   file's header names the origin and licence and cites its entry of
   `THIRD-PARTY-NOTICES.txt` (`Notice: THIRD-PARTY-NOTICES.txt, "<entry>"`), the entry lists
   the file and holds the licence text, and an Apache-2.0 work's NOTICE goes into `NOTICE`.
   `cli/ThirdPartyNoticesTest` holds the two directions. Default is to write it ourselves.
6. **Naming: "an experimental subset of the Java language"**, on every user surface
   (`--source-language` help, `doc/*/java/`, examples, release notes). Never "Java compatible",
   "Java SE", a logo, or a product or module name built on the word: the name is a trademark and
   compatibility is a claim reserved to those who passed the compatibility kit. Class and package
   names inside a user's program (`java.util.ArrayList`) are the API itself and stay as they are.
7. **A third-party parser or library is a separate decision** with its own licence check; the
   front end has its own lexer and parser and needs none.

## What is fine

Implementing the language from its specification; implementing `java.lang` / `java.util` under
the same names and signatures from scratch; matching observable behaviour (a `HashMap`'s
iteration order, `Double.toString`'s text) by our own code; running a JDK in tests and CI as the
oracle.

## When in doubt

A contribution whose author had the JDK's implementation open while writing the same class is
not from scratch: rewrite it from the specification. A reviewer asks where a non-obvious
algorithm came from, and the answer goes in the file's header.

## The audit (2026-11-18) and what followed

- **The table**: `.todo/artefacts/j91-license-audit/README.md` -- a read-only audit of the
  whole repository at `b61049e2d`: what was ported from OpenJDK, what was translated from
  EPL sources, what needs a notice, what is clean. The work it found is `.todo/j91`
  (OpenJDK-derived code outside the regex engine), `j92` (Clojure's EPL sources), `j93`
  (`NOTICE` and third-party notices) and `j94`; each records its outcome here when done.
  The ported code stays in git history (user decision, 2026-11-18).
- **The regex engine (j90, 2026-11-18): replaced.** `java.util.regex` of JDK 25 had been
  ported method by method into `clojure.lisp` (2026-10-11, `.todo/f43`, `f49`, `f54`: the
  parser, the matcher's nodes, the look-behind study, `Grapheme.nextBoundary`). All of it
  was deleted and one engine written in its place, `eval/regex.lisp`, shared by the Clojure
  and the Java front ends ([regex-engine.md](regex-engine.md)). How it was learned: the
  syntax, flags, class names and the replacement and split rules from the `Pattern`,
  `Matcher` and `String` API documentation; the grapheme rules from Unicode Standard Annex
  #29; everything the documentation leaves open (refusal indices, which captures survive,
  the look-behind bounds, the matcher's last end) by running patterns under a JDK and
  writing down the answers -- the probes are cases of `ClojureRegexTest`. Nobody working it
  read OpenJDK's regex sources, and the ported code was not used as the model: the new
  engine compiles a tree to closures where the port walked the JDK's node chain. Its
  Unicode tables (`regex-unicode.lisp`) are generated by `RegexUnicodeTablesTest` from a
  running JDK's public `Character` API (use, not copying; the Unicode notice is `j93`'s).
- **OpenJDK-derived code outside the regex engine (j91, 2026-11-18): replaced.** Nobody
  working it read OpenJDK's source of any of these. The old bodies were deleted, not
  edited; they were read only as far as a caller's contract had to be kept (the entry
  points, the helpers they call, the slots they use -- and, by that reading, the first
  lines of the old `exp`), and none is the model of what replaced it: each item below
  says what the new one is built from and how its shape differs. Where a JDK was asked,
  the probe's verdict is a test.
  - **fdlibm on wasm** (`WasmFdlibmRuntimeBuilder`'s `Sources`, transliterated from the JDK's
    Java port): deleted; `WasmFdlibmSources` is Sun's C fdlibm 5.3 from netlib, file by
    file, Sun's three notices in its header (rule 5). The bit-level functions (`ulp` ...
    `getExponent`) are written from StrictMath's specified behaviour, `scalb` is
    `s_scalbn.c`. What makes it not the old text again: the C's decimal constants and
    names, `__HI(x) = ...` as the C writes it, no helper the C lacks (the `ILOGB` slot is
    gone, fmod's loops are inline), pow's subnormal tail through `scalbn`. Checked:
    every literal against the C files by script, bits against `StrictMath` over four seeds
    (`.kb/transcendentals.md`).
  - **`Float.floatToFloat16`** (`WasmFloat16Compiler`, "a literal port"): deleted; the
    encode is IEEE 754's binary32 -> binary16 rounding written from the formats
    (`.kb/binary-sequence-io.md`), the NaN payload a JDK's answer. The artefact
    `482-.../Enc.java`, which held the port, is deleted.
  - **`BigInteger(String,int)`** (`%clojure-big-integer-new`): the string arm deleted and
    written from the constructor's documentation; refusal words, their order and the piece
    of the string a non-digit's refusal names are a JDK's answers. The group-width table is
    gone: the width is computed (the most digits that always fit an int), which is what the
    probe showed (`.kb/clojure-frontend.md`, "Portable number statics").
  - **`Formatter`'s checks** (`ClojureFormatLowering`, `%clojure-format-check`): the
    per-category check methods deleted on both sides; one table of refusal steps per
    conversion and one generic walk of it. WHAT is refused is the documentation's flag
    table; the ORDER and the words are a JDK's answers over all 86,016 specifiers, which
    `ClojureFormatLoweringTest` asks again of both parses. The comparison found two things
    the old code had wrong (`^` in a flags refusal of an uppercase conversion, a bare `%t`).
    No JDK method name is left.
  - **Schubfach** (`SchubfachTables`, `WasmSchubfachRuntimeBuilder`; audit row 20): the
    origin could not be told from the record (written 2026-08-17 with no source named),
    and the text cannot tell it either: the selection is, statement for statement and name
    for name, Raffaello Giulietti's own implementation
    (`github.com/c4f7fcce9cb06515/Schubfach`, `todec`, MIT, 2018-2020), which is also what
    he contributed to the JDK. It is now stated as a port of that MIT original, compared
    against it line by line, with its notice in both files' headers (rule 5); the table
    compression, the division by 10, the unsigned comparisons and the formatter are ours.
    Not rewritten from the paper: the code would be the same, since it is the paper
    author's.
  - **`Random()`'s seed** (Java library): the two constants were a JDK's and are in no
    specification; replaced by our own choice (a counter stepped by 2^64 over the golden
    ratio, mixed with the clock). Nothing observable changes.
  - **`Hashtable(Map)`'s capacity**: kept at the same value, on a new footing. The order a
    copy walks in is observable and the corpus pins it to a JDK's, so the capacity is not
    ours to choose: a probe under a JDK found, for each of 2 to 64 mappings, the one table
    size that order fits (11 up to five mappings, then two per mapping). The todo proposed
    choosing our own and documenting the difference; that would have broken
    `a-deque-an-optional-the-utilities-and-the-older-classes` for a difference no one wants.
  - **`%utf-8-malformed-length`** (prelude): deleted and written from the Unicode standard's
    Table 3-7 and its maximal-subpart practice, sharing the table with
    `%utf-8-well-formed-end`; the one departure (an encoded surrogate is one sequence) is a
    JDK's observed answer. `LispPreludeLibraryTest` pins it against the JDK decoder.
  - fdlibm and Schubfach have their `THIRD-PARTY-NOTICES.txt` entries since j93 (below).
- **Clojure's EPL sources (j92, 2026-11-18): replaced.** Clojure, ClojureScript and
  tools.deps are EPL-1.0, a weak copyleft; the audit's rows 3, 4, 5, 8, 21 and 29 were
  line-by-line translations of them, all but row 8 embedded in compiled programs. User
  decision: rewrite, not keep under an EPL notice. Nobody working it opened the EPL
  sources (no clojure jar's `.clj`, no tools.deps file, no GitHub). The old bodies were
  deleted, not edited, and read only as far as a caller's contract had to be kept (each
  item says how far); they were also RUN as black boxes beside the oracle, which is how
  "nothing observable changed" is known. What each new one is built from: the published
  documentation (the API docstrings `doc` prints, clojure.org's reference pages, the
  Common Lisp HyperSpec for what `cl-format` and the pretty printer take from it) and
  the answers of `clj` 1.12.6 to probes; where a rule is neither documented nor asked,
  it is not there.
  - **Six clojure.jar namespaces** (`clojure/{main,repl,stacktrace,data,zip}.clj`,
    `clojure/core/reducers.clj`; row 4): deleted and written again, docstrings in our own
    words. Of the old files only the `ns` forms, the names of the top-level definitions,
    the kernels they call and the lines of host interop were read (by grep). Probed:
    `clojure.zip` 122 loc shapes and refusals; `clojure.data` 86 diffs; `clojure.main`
    some 1,000 lines of `ex-triage`/`ex-str` answers over every phase, source spelling,
    frame class and method, and every namespace of clojure.jar for the "core frame"
    set; `clojure.repl`'s `doc` and `pst`; `clojure.stacktrace`'s element and trace
    printing; `clojure.core.reducers` 244 reductions and folds. The new files give the
    oracle's answer on every probe line but the documented deviations (user docs), as
    the old ones did. Shapes that differ: `demunge` scans a table of spellings left to
    right; `ex-triage` is one `case` over shared helpers and `ex-str` a word list per
    phase; the map and the sequential diff share one slot comparison; a zipper is
    written over a private `at`/`trail`/`edited` vocabulary; `fold` halves by index.
    Two things the comparison improved: `:clojure.error/path` loses repeated and
    trailing slashes like the oracle's, and `ex-str` refuses spec problems only in the
    two phases where the oracle explains them. One printed order moved: a part of a map
    diff that holds several keys lists the first map's keys, then the second's (user
    doc, `examples/clojure/bundled-namespaces.clj`'s expected line); the old file
    reached the oracle's order on that example by walking the keys the way its source
    does, and the oracle's order is its hash set's, which nothing here reproduces.
  - **The pretty printer and `cl-format`** (rows 3 and 21): the `~F ~E ~G ~$` kernel, the
    pretty writer (capture layer and layout) in `clojure.lisp` and the whole of
    `clojure/pprint.clj` (API, `simple-dispatch`, `code-dispatch`, and the `cl-format`
    compiler and executor, whose helper inventory mirrored the oracle's) were deleted and
    written again. The deleted code was read only for its entry points and kernel
    contract, and otherwise used as a second black box whose outputs, and in a few cases
    whose stream of layout marks, were compared. Built from: the float directives from
    CLHS 22.3.3 and clj's answers (a number read once into the characters of its spelling
    and the place of its point); the layout from CLHS 22.2.1 and probes against clj that
    establish each rule the file header states (a vector of marks with running column
    totals, taken mark by mark, where the old one replayed a token buffer); `cl-format`
    from CLHS 22.3 and clj's answers, refusal messages and parameter names included,
    since clj prints them (a tree of directive maps walked over a cursor); the code
    layouts from what clj prints for each kind of form at margins that force every break
    and none, written as control strings of our own `cl-format`. Checked against clj:
    12,000 generated float cases identical; 13,500 generated control strings, 8 agreeing
    with neither clj nor the old code (the order of exception classes inside a
    justification); the layout engine on targeted probes. Checked against the old code
    (itself identical to clj on a 1,500-layout sample): 9,000 generated layouts
    identical, 10,000 generated code forms with one differing (an `ns` form with a vector
    for a name under `*print-level*` prints where the old code threw; not asked of clj).
    The comparison removed two deviations: a decimal keeps all its digits under
    `~F ~E ~G`, and `~$` takes every number as a double.
  - **The type macros, `case`, `doseq`, `for` and three ClojureScript bodies** (rows 5
    and 29, and what the review of the rest of `core_macros.clj` found): the
    `reify`/`deftype`/`defrecord` expanders followed `clojure/core_deftype.clj` function
    for function and emitted its record methods as text; the `case` table helpers, the
    `doseq` step function and the `for` level emitter followed `core.clj`'s private
    helpers; `cljs_core.clj`'s `equiv-sequential`, `caching-hash` and `ArrayChunk` were
    ClojureScript's bodies (judged from the file's one commit and the callers; its source
    was not opened). All deleted and written from the macros' documentation and the
    oracle's answers. What is still the oracle's is what a program can observe and rely
    on: the expansions' shapes, the `deftype*` skeleton of a record with its method
    names, the factories' docstrings, the refusals' words. Each record method's body is
    ours, spelled from what the oracle's record answers through that method, so
    `macroexpand-1` of a `defrecord` no longer prints the oracle's text (user doc). How
    the shape differs: `case` files rows by code and merges colliding tests before it
    picks the mask; `doseq` hands each step the forms to carry on with instead of
    answering a needs-recur flag; `for` folds from the innermost level out; a record's
    iterator is a `SeqIterator` over its own seq and its `cons` is spelled in the method,
    so `records.clj`'s `RecordIterator` and `imap-cons` and their lowerings are gone.
    Checked: the class the oracle's compiler builds from our record expansion answers a
    46-line probe like its own `defrecord`; 107 shapes and 57 generated `case` tables
    print the oracle's lines but for refusal words; datascript 1.8.1 prints its oracle
    lines on all four backends. Judged clean and left: `destructure` (own
    decomposition), the small macros (their printed expansion as a template), the rest
    of `cljs_core.clj`.
  - **tools.deps' selection and root map** (rows 8 and 29; compiler only):
    `ClojureDepsGraph`'s walk had been `expand-deps`/`flatten-libs` translated function by
    function, `ClojureDepsEdn.ROOT_TEXT` the root `deps.edn` verbatim. The old class was
    read for its contract only (the `Procurer` surface, the records, the refusal words);
    its decision, exclusion, orphan and ordering methods were not. The new selection is
    built from the published reference ("Dependency Expansion", the `deps.edn`
    reference) and, for what that leaves open, from `clj` 1.12.6.1673 as a black box
    (`-Srepro -Spath`, `-Stree`): 40 constructed Maven graphs over a `file:` repository,
    33 `:local/root` trees and 1,500 generated graphs, every graph classpath identical
    here. Its shape is its own: a walk in waves (decide a depth, then read what was
    taken) over per-version routes, where the old one ran a queue of nodes and deferred
    expansions over a version map. What the answers showed is in the class's Javadoc and
    `.kb/clojure-frontend.md` "deps.edn", pinned by `ClojureDepsGraphTest` and
    `ClojureDepsAliasesTest`. The root map is stated from what the CLI answers in an empty
    directory (`-Spath`, `-A:test -Spath`, `-A:deps -Spath`, `-X:deps aliases`, the
    `clojure.basis` it hands a program) and the reference's default repositories. The
    replaced walk gave the same answer on all 1,540 graphs.
  - **`clj -X`** (`ClojureMain.exec`; not an audit row, but the record called it a port
    of the CLI's exec namespace and its Javadoc cited that namespace's private names):
    the argument reading and the map building were deleted and written from the CLI
    reference's grammar and 43 command lines asked of `clj -X` over a fixture project;
    `ClojureMainTest` pins the four answers that were new.
  - What the audit did NOT list and this item left for `.todo/j95`: below.
- **What the audit did not list (j95, 2026-11-18): judged per class.** Nobody working it
  opened tools.deps' or OpenJDK's source; the judgement is from the code's shape, its names
  and its record. Built from the deps.edn reference, the JDK's API documentation, RFC 3986
  and the answers of `clj` 1.12.6.1673 and JDK 25.
  - **`ClojureDepsProcurer`'s git checks**: the method carried the source's local names
    (`unsha`, `untag`, `canon-sha`) and its order, and the forge table its regular
    expressions verbatim. Deleted and written again: the forge table from the reference's,
    its matching from probes (any character between the parts, `ioXgithubYfoo/bar`
    included, an owner with no dot), the refusals and their order from 17 coordinates
    asked of `clj` over a `file:` repository (`.kb/clojure-frontend.md`, "deps.edn", git).
  - **Kept, names dropped**: `ClojureDepsEdn` (reading, the check walk, the merges, the
    argument map, `tool`), the rest of `ClojureDepsProcurer`, `ClojureBasis`,
    `eval/ClojureDepsRepositories`, and the `am.ik.maven` Javadoc citing how tools.deps
    builds its request: each has its own shape (records and datum walks, adapters over
    `am.ik.maven`) and states a rule `clj` answers. Where the record had only the source's
    function name, the rule was asked again: path aliases (12 trees), `tool` (5), the
    reading of an empty, `nil`, doubled and repeated-key file, and the argument map over
    300 generated alias sets against the `:argmap` of the basis `clj` writes. That
    comparison found one real difference, now fixed: a key keeps a `nil` value and a `nil`
    `:paths`/`:replace-paths` is `[]`, so `{:replace-paths nil}` empties the project's
    paths (`ClojureDepsAliasesTest`). `.kb/maven-resolver.md`'s note that `:update` was
    "read from its source" is withdrawn; its measurement stands.
  - **The Clojure HTTP client's redirects** (`clojure.lisp`, embedded): the record called the
    resolution a port of `java.net.URI.resolve` and named the client's internals
    (`RedirectFilter`, `ALLOWED_REDIRECT_HEADERS`, `newInstanceForRedirection`, `++hops`);
    the code's order is RFC 2396's, which the JDK's also follows, so the text cannot tell,
    and the record says port. Deleted and written from RFC 3986 section 5.2 (the
    reference parse of appendix B, remove_dot_segments over segments) with the JDK's
    departures from it, each learned by asking: the client's redirects against a local
    server (statuses, hops, schemes, methods, bodies, headers across origins, 60
    Locations), then `URI.resolve` over 22,703 generated pairs, which the new code answers
    and the old one missed 2,939 of. `.kb/clojure-frontend.md`, "HTTP client", lists the
    rules and the two known differences.
- **`java.net.URI`'s rules and the HTTP client (k03, 2026-11-18).** `eval/uri.lisp` holds
  j95's resolution and the Clojure client's URL scanner, moved, not rewritten; what was
  added (an opaque part, an address in brackets, a base with no scheme) and all of the
  Java subset's `java.net.http` and `java.net.URI` were written from the API
  documentation, RFC 3986 / RFC 2396's character classes and JDK 25's answers to probes
  (the builders' refusals, every character of a field name, a method and a value, 76
  references through `URI`'s getters, the redirects of a client against a local server).
  Nobody opened OpenJDK's source; the probes' answers are the expectations of
  `java-http-spec.yaml`, which the JDK leg asks again.
- **The HTTP server (k04, 2026-11-19).** `com.sun.net.httpserver` and the address
  classes of `java.net` were written from the API documentation and JDK 25's answers to
  probes (three probe servers asked by curl: the contexts a path finds, an exchange's
  refusals and their words, `Headers`' names and its CR / LF checks, `InetSocketAddress`'
  texts and hash codes, what a client sees of a handler that throws). Nobody opened
  OpenJDK's source; the answers are the expectations of `java-http-server-spec.yaml`,
  which the JDK leg asks again.
- **Notices (j93, 2026-11-18): kept with notices.** What the audit filed under "Add notices"
  and "To confirm" stays, under its licence:
  - Root `NOTICE` (rontolisp, and Maven's and Maven Resolver's Apache NOTICE lines) and
    `THIRD-PARTY-NOTICES.txt`: one entry per project (`Origin`, `License`, `Ships`: `output`
    / `tool` / `repository`, `Files`, `Use`, then the licence text), parsed by
    `cli/ThirdPartyNotices`. `LICENSE` stays the bare Apache text: an appended section would
    lower GitHub's licence match. The jar carries all three under `META-INF/` (`pom.xml`,
    build-helper `add-resource`), the native binary too (`resource-config.json`).
  - **What a user ships**: `rontolisp notices` (`cli/NoticesCommand`) prints rontolisp's
    licence, `NOTICE` and every `output` entry; `--native [--native-target P]` adds the
    runner's crates (`.kb/native-output.md`, "The Java side"), `--tool` what the jar
    or binary itself owes. A library a program loads (Quicklisp, `deps.edn`, `--java-dep`)
    carries no notice into the output and is not listed: `doc/*/compiling/licenses.md`
    states the user's obligation.
  - Settled by looking: starlark-go's `string_format` is ported (the decision chain and the
    order of the refusals are Go's), so it is noticed, not reworded. riddley's `walk.clj`
    shares `walk-exprs`' branches and `case` destructuring with 0.2.2 (13 lines verbatim):
    its docstring and `doc/*/clojure/reference/riddley.md` no longer say "from the documented
    behaviour". The WASI WIT is not Apache-2.0 (audit row 26) but the W3C Community CLA of
    the WASI repositories; `tls.wit` is wasmtime's (Apache-2.0 WITH LLVM-exception).
  - Decided: Kuzushiji-49's ShareAlike is taken to reach the trained `weights.bin`, which is
    distributed under CC BY-SA 4.0 (the example's README). The Programming Clojure probes
    (`.todo/artefacts/b16-shcloj4-inventory`): only `green-baseline.clj` held book code (12
    lines over four snippets, compared with the book's code archive); they were rewritten in
    other words with the oracle's same answers, so no book code is left to notice.
  - Vendored test libraries without licence text (row 19) got a `LICENSE` stating the
    licence their `.asd` declares with the copyright of their README or sources (none of
    those upstreams ships one); `src/wasm-component/deps/` and the keyvalue WIT got the WASI
    statement. `ThirdPartyNoticesTest` requires licence text in every `src/test/resources`
    directory whose `.asd` declares a licence.
  - Row 25: clsql's (LLGPL) and petalisp's (AGPL-3.0) near-copy `.asd` files are deleted;
    `AsdOverrides.REWRITES` edits the upstream file at load time (`.kb/asdf.md`, "Tier 2").
- **The GraalVM runtime of the native binary (j97, 2026-11-18): shipped.** Measured on the
  Oracle GraalVM 25.0.5 the release build uses (`setup-graalvm`, `distribution: graalvm`): its
  `LICENSE.txt` is the GraalVM Free Terms and Conditions, which deem the portions of a Native
  Image output "an unmodified Program" and allow redistributing it only under those terms, free
  of charge; the JDK's `legal/java.base/LICENSE` is Oracle's No-Fee Terms and Conditions (not
  GPLv2+CPE, which only a Community build would give); `legal/<module>/*.md` hold third-party
  components. Of the modules the image links, `java.base` has eight (ICU, CLDR, Unicode, the
  GCC runtime exception ...) and `jdk.incubator.vector` has SLEEF (Boost, CC-BY-4.0; Linux builds only); the
  other modules in use carry only the Oracle texts. `src/native/graalvm-notices.sh` writes
  LICENSE.txt, the java.base LICENSE and COPYRIGHT and those `.md` files from `java.home` into
  `META-INF/graalvm-notices.txt` (`-Pnative` only; the jar runs on the user's JVM), the binary
  holds it (`resource-config.json`), and `rontolisp notices --tool` prints it last. CI's
  "Verify native image" greps the two Oracle marks and one section header per `.md` the
  builder JDK ships for those modules. The `.md` set is per platform: macOS-aarch64 Oracle
  GraalVM 25.0.3/25.0.4 ships `libsleef.dylib` and `lib/static/darwin-aarch64/libsleef.a` but
  no `legal/jdk.incubator.vector/sleef.md` (OpenJDK keeps it in the Linux-only legal dir), and
  the darwin-aarch64 binary links no `Sleef_` symbol nor libsleef (`nm`, `otool -L`,
  2026-11-19), so its notices carry no SLEEF and owe none. Only licence texts are read from the JDK
  (rule 1 is about classes and jars). A module with `.md` files entering the image is added to
  the script's `MODULES`. Switching to GraalVM Community (GPLv2+CPE, no fee condition) is not
  an option: `-H:+VectorAPISupport` (pom) is Oracle GraalVM's. The release stays free of
  charge by that condition; charging for rontolisp's binary would need Oracle's terms first.
  Not covered: the web playground (`-Pweb`, Web Image) is not a release asset and was not
  audited.
