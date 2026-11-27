# Provenance, licences and naming (project-wide; written for the Java front end)

**Invariant: nothing this project ships -- a front end's library, its run-time helpers, its
tests, its documentation -- is copied or translated from a JDK or from any source whose licence
does not allow it; it is written here from the published specification, and no user surface
claims Java compatibility.** Decided for the Java front end, before its first line; **the
rules bind the whole project**. Not legal advice, the working rule. The file keeps its name:
the todo items and the library's headers cite it.

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

8. **No recalled implementation knowledge.** A model's or a person's memory of how the JDK,
   Clojure, JUnit or AssertJ implements a class -- private names, internal fields, an
   algorithm's structure, "such a class is usually built this way" -- is their source read
   at second hand and falls under rule 2. Every behaviour traces to a public specification
   (Javadoc, JLS, an RFC: section named) or to the author's own probe of the implementation,
   kept with its output beside the work; a behaviour with neither is not implemented and is
   reported.

## What is fine

Implementing the language from its specification; implementing `java.lang` / `java.util` under
the same names and signatures from scratch; matching observable behaviour (a `HashMap`'s
iteration order, `Double.toString`'s text) by our own code; running a JDK in tests and CI as the
oracle.

## A library a program uses (k24, 2026-11-19)

A library read from a program's source path is the program's INPUT, as its own files are:
compiled into what that user ships, under the library's licence, which
`doc/*/compiling/licenses.md` leaves to the user. It is not this project's source, and none
of it is copied here: the fixture of the corpus is a library written here, and the YAVI
measurement held programs and stand-in classes written here plus `sed` lines. YAVI is Apache-2.0 and
has the owner this project has (the user: YAVI may be changed where a change is right for
YAVI too); that lifts no rule above, which are about the JDK and about what is embedded.

### SLF4J and Commons Logging, and the WIT of `wasi:logging` (m14, 2026-11-25)

SLF4J (`org.slf4j:slf4j-api`, `slf4j-jdk14`, MIT) and Commons Logging
(`commons-logging:commons-logging`, Apache-2.0) are a program's dependencies, compiled from
their published sources jars by `--java-source-dep`; nothing of theirs is in the repository.
The java-spec case resolves them from Maven Central as a run of tests resolves JUnit; the
JDK leg runs their published jars. The lines a census refusal named were read to learn
which JDK member they call; the JDK classes added for them are written from the API
documentation and JDK 25's answers to probes. The source a record names through SLF4J is
matched against what JDK 25 prints, not against the binding's code. Of Commons Logging
(m17), `LogFactory`'s choice and `Jdk14Logger.log` were read to learn which names it looks
up and that it hands `logp` a method from its stack; what the records say is matched against
JDK 25's output.

`wasi:logging` (`github.com/WebAssembly/wasi-logging`) carries no licence file and GitHub
detects none (checked 2026-11-25), so its WIT is not copied: `eval/logging.wit` and the test
host's `host.wit` are written here, the interface's names and types only -- what an
implementation has to share, as rule 3 treats a signature -- with none of the proposal's
text. The test host's trimmed `wasi:io` and `wasi:cli` 0.2.0 members are written the same way
(WASI's own text is under the W3C CLA, "WASI" in `THIRD-PARTY-NOTICES.txt`).

### GraalVM's reachability metadata (m03, 2026-11-25)

The format a program and a library declare their reflection in is GraalVM's
(`reachability-metadata.json`, `reflect-config.json`). It was learned from GraalVM's published
documentation of it (the Reachability Metadata page) and from what `native-image` 25.0.5.1 and a
JDK print for probes written here;
nobody read GraalVM's sources, and the reader and its JSON parser were written here. A program's
or a library's metadata is its input, as its sources are. The shared repository
(`oracle/graalvm-reachability-metadata`, CC0-1.0) is read only by the user's switch, from
the user's own download; nothing of it is in the repository, and the test's repository is a
directory written by the test. Counting its entries read the release's zip, the user's
`~/.m2` copy.

## A library under another's names (l32, 2026-11-22)

`webmvc-fn-lite/` has the names and the signatures of Spring Framework's classes (Apache-2.0)
and behaves as they do, by the user's decision; its text was written here. It is treated as
rule 5 treats a port: each file's header says which class it stands for and cites
"Spring Framework" in `THIRD-PARTY-NOTICES.txt`, and the module's jar carries Spring
Framework's notice (`META-INF/NOTICE`). It is in neither the rontolisp jar nor a program
compiled without it, so the root `NOTICE` does not name it
([webmvc-fn-lite.md](webmvc-fn-lite.md), "Provenance"). Its surfaces say what it is not:
Spring Framework, or a product of its authors.

## A test API under its own names (l81, 2026-11-24; the user's decision: l93)

`rontolisp test FILE.java` compiles a file's tests against JUnit Jupiter
(`org.junit.jupiter`), AssertJ (`org.assertj.core`) and opentest4j (`org.opentest4j`): the
test sources are the ones javac compiles against the real jars, so the names and the
signatures have to be theirs ([java-frontend.md](java-frontend.md), "Tests of a program").

- **How the classes were learned**: the names, signatures and elements from the published
  API documentation; what a check says and throws by running the libraries under a JDK and
  reading the output (probes, and the JDK
  leg of `JavaSpecE2eTest`'s run of tests, which IS the libraries: use, not copying).
  Nobody read the sources of JUnit, AssertJ or opentest4j. AssertJ's sources jar was put on
  a source path, and its `import` lines of a JDK's classes listed, only to MEASURE whether
  the front end compiles it (l81, l86: a copy with the package renamed by `sed`, so that the
  library's part would not answer); what came back was the front end's own refusals and
  warnings, file names, counts, the census's method names, the probes' output, the sizes and
  the IR's functions by name and length. A rule of the language it showed missing was found
  from the front end's diagnostics and AssertJ's published signatures (`javap` of the jar's
  public API), and pinned by programs written here.
- **AssertJ and opentest4j (Apache-2.0): in the library with their notices**, as
  `webmvc-fn-lite/` treats Spring Framework ("A library under another's names" above; the
  user's decision, 2026-11-24; they hold no text of AssertJ's and carry the entry anyway).
  Each file's header says which class it stands for and cites its entry of
  `THIRD-PARTY-NOTICES.txt`, `Ships: output` since a compiled run of tests carries the
  classes it names. Neither project ships a NOTICE file, so `NOTICE` names neither.
  `ThirdPartyNoticesTest` requires the citation of every class of the library outside a
  JDK's packages and the subset's own (`rontolisp/`).
- **JUnit Jupiter (EPL-2.0): not in the rontolisp jar** (the user's decision, 2026-11-24). Rule
  5 allows a notice entry for Apache-2.0 or a permissive licence only, and its annotation types
  and assertions are not carried on the JDK's footing ("What is fine") either, for the safe
  side. A run of tests compiles the real JUnit Jupiter from the program's own dependency
  (`--java-source-dep`), which its user takes under EPL-2.0 as for a run on a JDK
  (`doc/*/compiling/licenses.md`), and a run without it says how to give it. JUnit's sources are
  given to the front end, not read: what comes back is its refusals, file names, counts and the
  census's method names; what else is needed is asked of JUnit running under a JDK. The runner
  (`JavaTests`, `TestRun`, `TestSources`) calls JUnit's API and carries none of it; nothing of
  the JUnit Platform or of the Jupiter engine is carried.
- **The Clojure front end's `clojure.core` (EPL-1.0's names): not the same treatment** (judged
  by the session that did l93, not decided by the user). JUnit could leave the jar because a
  program brings the real one and the front end compiles it. `clojure.core` is the language a
  Clojure program is written in, which the front end implements as Common Lisp functions and
  lowerings (`clojure.lisp`, `Clojure*Lowering`); clojure.jar's own is the language defined over
  its run time, the JVM classes of `clojure.lang`, which is what the front end replaces (a
  judgement, not measured): taking its names out is taking the front end out. Its names and
  documented behaviour are what an implementation of a language shares, as `java.lang`'s are the
  Java subset's ("What is fine"); every body is written here ("The Clojure front end's
  libraries" below). Where the question does reach is the namespaces of clojure.jar the front
  end has as Clojure source of its own (`clojure.zip`, `clojure.data`, `clojure.walk`,
  `clojure.pprint` and the rest under `clojure/lib/clojure/`): their real sources are in
  clojure.jar, `org.clojure/clojure`, which every project's root map names and the front end
  answers itself (`ClojureBuiltinLibs`), as JUnit's are in the program's dependency. Measured
  2026-11-25 ([clojure-frontend.md](clojure-frontend.md), "The jar's own files, measured"): the
  jar's file runs as the front end's own on all four backends for five of the eighteen (`zip`,
  `template`, `stacktrace`, `uuid`, `data`); the other thirteen fail, differ or are refused,
  being written against `clojure.lang` and host interop -- the judgement above, measured for the
  namespaces beside `clojure.core`. clojure.jar's sources were given to the front end, not read
  (JUnit's rule above): what came back is refusals, output and sizes. **Decided (user,
  2026-11-25): all eighteen stay the front end's own**, on `clojure.core`'s footing. Moving the
  four that could leave (`uuid` is read for every `#uuid`, so it stays either way) would put
  clojure.jar's EPL-1.0 code into a compiled program that names one, fetch a 4.2 MB jar, refuse
  them where nothing resolves (playground, embedder), and still leave fourteen under Clojure's
  names.

## Trademarks (2026-11-26)

Names of other projects' languages, products and services appear on user surfaces and are
theirs. `NOTICE`, `README.md` and `doc/*/compiling/licenses.md` ("Trademarks") carry one
statement: the named owners' marks, and that rontolisp is not affiliated with, endorsed by or
sponsored by them.

- **An owner is named only where the owner's own page says so** (Oracle's trademark credit
  line, `openjdk.org/legal/openjdk-trademark-notice.html`, Broadcom's `spring.io/trademarks`,
  the ASF's mark list, Cloudflare's trademark page, Google's, Apple's and the Linux
  Foundation's lists, `postgresql.org/about/policies/trademarks/`). Anything not confirmed
  that way (Clojure, WebAssembly, GraalVM at 2026-11-26: no owner's page found or readable)
  goes in "may be trademarks of their respective owners". A name not confirmed as a mark is
  neither listed as one nor declared free of one. Add a name to the statement when a user
  surface starts to depend on it, with the page that names its owner.
- **No surface claims "compatible with", "certified", "official" or "drop-in" for a name that
  is a mark** (rule 6 for Java). What the code shares is names and signatures, said as that
  (`webmvc-fn-lite`: "the names and the signatures of Spring Framework's classes", "not Spring
  Framework"). Library APIs described as "API-compatible subset" (ASDF, Quicklisp) or
  "-compatible" (usocket, jzon, Perl, Optima) and Scheme's "SICP-compatibility names" are
  descriptions of a library's or a book's interface, kept as written.

**Contributions** are taken under the Developer Certificate of Origin 1.1
(`CONTRIBUTING.md`, text unchanged from developercertificate.org). `.github/workflows/dco.yaml`
requires a `Signed-off-by:` trailer on every non-merge commit of a pull request; direct pushes
are not checked.

## When in doubt

A contribution whose author had the JDK's implementation open while writing the same class is
not from scratch: rewrite it from the specification. A reviewer asks where a non-obvious
algorithm came from, and the answer goes in the file's header.

## Where the code comes from

### The evidence tables (m35, 2026-11-25)

Every file of the Java library had a row of evidence -- the specification, a probe, or the
library's own choice -- in a per-class table (removed from the tree with the other
measurement records on 2026-11-26, kept in the project's private history). A constant or name
that no specification or probe fixes (an unobservable constant, a private name) is chosen here
and stated as the library's own in its file (`MathContext`'s 31, `PriorityQueue`'s doubling,
`store`, `chars`, `afterCr`), or taken from a probe the file cites (the delay thread's
`ForkJoinPool.commonPool-delayScheduler`, a daemon of priority 5). A JDK's private members are
not asked by reflection; a serialized form is read through `ObjectStreamClass` (it is the
published Serialized Form). An observable behaviour equal to a JDK's is carried by a probe.

### Written here from specifications and probes

Nobody read OpenJDK's source of any of these; a JDK probe's verdict is a test.

- **The regex engine**: `eval/regex.lisp`, shared by the Clojure and Java front ends
  ([regex-engine.md](regex-engine.md)), is written from the `Pattern`, `Matcher` and
  `String` API documentation (syntax, flags, class names, replacement and split rules),
  Unicode Standard Annex #29 (graphemes), and a JDK's answers to probes for what the
  documentation leaves open (cases of `ClojureRegexTest`). It compiles a tree to closures.
  Its Unicode tables (`regex-unicode.lisp`) are generated by `RegexUnicodeTablesTest` from a
  running JDK's public `Character` API (use, not copying).
- **fdlibm on wasm**: `WasmFdlibmSources` is Sun's C fdlibm 5.3 from netlib, file by file,
  Sun's three notices in its header (rule 5): the C's decimal constants and names,
  `__HI(x) = ...` as the C writes it, no helper the C lacks, pow's subnormal tail through
  `scalbn`; `scalb` is `s_scalbn.c`. The bit-level functions (`ulp` ... `getExponent`) are
  written from StrictMath's specified behaviour. Checked: every literal against the C files
  by script, bits against `StrictMath` (`.kb/transcendentals.md`).
- **`Float.floatToFloat16`** (`WasmFloat16Compiler`): IEEE 754's binary32 -> binary16
  rounding written from the formats (`.kb/binary-sequence-io.md`); the NaN payload is a
  JDK's answer.
- **`BigInteger(String,int)`** (`%clojure-big-integer-new`): written from the constructor's
  documentation; the refusal words, their order and the piece of the string named are a
  JDK's answers; the group width is computed (the most digits that always fit an int), not
  a table (`.kb/clojure-frontend.md`, "Portable number statics").
- **`Formatter`'s checks** (`ClojureFormatLowering`, `%clojure-format-check`): one table of
  refusal steps per conversion and one generic walk. WHAT is refused is the documentation's
  flag table; the ORDER and the words are a JDK's answers over all 86,016 specifiers
  (`ClojureFormatLoweringTest`).
- **Schubfach** (`SchubfachTables`, `WasmSchubfachRuntimeBuilder`): statement for statement
  Raffaello Giulietti's own implementation (`github.com/c4f7fcce9cb06515/Schubfach`, `todec`,
  MIT, 2018-2020). Stated as a port of that MIT original, its notice in both files' headers
  (rule 5); the table compression, the division by 10, the unsigned comparisons and the
  formatter are ours.
- **`Random()`'s seed** (Java library): a JDK's constants are in no specification; ours is a
  counter stepped by 2^64 over the golden ratio, mixed with the clock.
- **`Hashtable(Map)`'s capacity**: the order a copy walks in is observable and the corpus
  pins it to a JDK's, so the capacity is not ours to choose: it is the one table size a JDK
  probe found that order fits for each of 2 to 64 mappings (11 up to five mappings, then two
  per mapping).
- **`%utf-8-malformed-length`** (prelude): written from the Unicode standard's Table 3-7 and
  its maximal-subpart practice, sharing the table with `%utf-8-well-formed-end`; the one
  departure (an encoded surrogate is one sequence) is a JDK's observed answer
  (`LispPreludeLibraryTest`).
- fdlibm and Schubfach have their `THIRD-PARTY-NOTICES.txt` entries ("Notices" below).

### The Clojure front end's libraries

Clojure, ClojureScript and tools.deps are EPL-1.0, a weak copyleft: nothing is translated from
them, and their sources are not opened (no clojure jar's `.clj`, no tools.deps file, no
GitHub). What the front end's libraries are built from: the published documentation (the API
docstrings `doc` prints, clojure.org's reference pages, the Common Lisp HyperSpec for what
`cl-format` and the pretty printer take from it) and the answers of `clj` 1.12.6 to probes;
**where a rule is neither documented nor asked, it is not there.**

- **Six clojure.jar namespaces** (`clojure/{main,repl,stacktrace,data,zip}.clj`,
  `clojure/core/reducers.clj`): docstrings in our own words. A map diff's key order is the
  first map's keys then the second's, not the oracle's hash-set order (user doc).
- **The pretty printer and `cl-format`**: the `~F ~E ~G ~$` kernel, the pretty writer and
  the whole of `clojure/pprint.clj` from CLHS 22.3.3 (float directives), 22.2.1 (layout) and
  22.3 (`cl-format`) and clj's answers, refusal messages and parameter names included, since
  clj prints them; the code layouts from what clj prints at margins that force every break
  and none, written as control strings of our own `cl-format`.
- **The type macros, `case`, `doseq`, `for` and the ClojureScript bodies of
  `cljs_core.clj`** (`equiv-sequential`, `caching-hash`, `ArrayChunk`): from the macros'
  documentation and the oracle's answers. What is the oracle's is what a program can observe
  and rely on: the expansions' shapes, the `deftype*` skeleton of a record with its method
  names, the factories' docstrings, the refusals' words. Each record method's body is ours,
  so `macroexpand-1` of a `defrecord` does not print the oracle's text (user doc).
- **tools.deps' selection and root map** (`ClojureDepsGraph`, `ClojureDepsEdn.ROOT_TEXT`;
  compiler only): the selection is built from the published reference ("Dependency
  Expansion", the `deps.edn` reference) and, for what that leaves open, `clj` 1.12.6.1673 as
  a black box (`-Srepro -Spath`, `-Stree`); `ClojureDepsGraphTest`,
  `ClojureDepsAliasesTest`, `.kb/clojure-frontend.md` "deps.edn". The root map is stated from
  what the CLI answers in an empty directory and the reference's default repositories.
- **`clj -X`** (`ClojureMain.exec`): from the CLI reference's grammar and command lines asked
  of `clj -X` (`ClojureMainTest`).
- **`ClojureDepsProcurer`'s git checks**: the forge table from the reference's, its matching
  and the refusals from probes of `clj`. `ClojureDepsEdn`, the rest of
  `ClojureDepsProcurer`, `ClojureBasis`, `eval/ClojureDepsRepositories` and the
  `am.ik.maven` Javadoc on how tools.deps builds its request each state a rule `clj`
  answers. Built from the deps.edn reference, the JDK's API documentation, RFC 3986 and the
  answers of `clj` 1.12.6.1673 and JDK 25.
- **The Clojure HTTP client's redirects** (`clojure.lisp`, embedded): from RFC 3986 section
  5.2 (appendix B's parse, remove_dot_segments) with the JDK's departures from it, each
  learned by asking (`.kb/clojure-frontend.md`, "HTTP client").

### Notices

What rontolisp takes from another project stays under that project's licence:

- Root `NOTICE` (rontolisp, and Maven's and Maven Resolver's Apache NOTICE lines) and
  `THIRD-PARTY-NOTICES.txt`: one entry per project (`Origin`, `License`, `Ships`: `output`
  / `tool` / `repository`, `Files`, `Use`, then the licence text), parsed by
  `cli/ThirdPartyNotices`. `LICENSE` stays the bare Apache text: an appended section would
  lower GitHub's licence match. The jar carries all three under `META-INF/` (`pom.xml`,
  build-helper `add-resource`), the native binary too (`resource-config.json`).
- **What a user ships**: `rontolisp notices` (`cli/NoticesCommand`) prints rontolisp's
  licence, `NOTICE` and every `output` entry; `--native [--native-target P]` adds the
  runner's crates (`.kb/native-output.md`, "The Java side"), `--tool` what the jar or binary
  itself owes. A library a program loads (Quicklisp, `deps.edn`, `--java-dep`) carries no
  notice into the output and is not listed: `doc/*/compiling/licenses.md` states the
  user's obligation.
- starlark-go's `string_format` is ported (the decision chain and the order of the refusals
  are Go's), so it is noticed, not reworded. riddley's `walk.clj` shares `walk-exprs`'
  branches and `case` destructuring with 0.2.2 (13 lines verbatim): its docstring and
  `doc/*/clojure/reference/riddley.md` do not say "from the documented behaviour". The WASI
  WIT is not Apache-2.0 but the W3C Community CLA of the WASI repositories; `tls.wit` is
  wasmtime's (Apache-2.0 WITH LLVM-exception).
- Kuzushiji-49's ShareAlike is taken to reach the trained `weights.bin`, distributed under
  CC BY-SA 4.0 (the example's README).
- A vendored test library without licence text carries a `LICENSE` stating the licence its
  `.asd` declares with the copyright of its README or sources; `src/wasm-component/deps/`
  and the keyvalue WITs (test host, `examples/wit/keyvalue/wit/`) carry the WASI statement.
  `ThirdPartyNoticesTest` requires a licence FILE (`LICENSE*`, `LICENCE*`, `COPYING*`,
  `COPYRIGHT*`) in every `src/test/resources` directory holding an `.asd` but its own demos
  (`OWN_FIXTURES`). The check it replaced (licence text anywhere, and only where the `.asd`
  declared a licence) let six libraries in without one.
- When upstream ships no licence file, the `LICENSE` copies the text of a source header
  (cl-mustache, esrap, iterate, parse-number) or the README's statement (assoc-utils). A
  library with a file its own maintainer calls of unclear licence is not vendored
  (cl-utilities' `split-sequence.lisp`, removed with `ClUtilitiesE2eTest` 2026-11-26).
- A port of an Apache-2.0 example or model code into `examples/` (wasmCloud templates, the
  proxy-wasm Rust SDK's `http_body`/`http_headers`, transformers' Qwen3.5 / LFM2 layers) has an
  entry like any other port; an example that loads a ported directory carries that directory's
  licence too (`examples/browser/hiragana/LICENSE.md`).
- A copyleft `.asd` (clsql's LLGPL, petalisp's AGPL-3.0) is not copied into the
  repository; `AsdOverrides.REWRITES` edits the upstream file at load time (`.kb/asdf.md`,
  "Tier 2").

### The GraalVM runtime of the native binary: shipped

Oracle GraalVM 25.0.5, as the release build uses (`setup-graalvm`, `distribution: graalvm`):
its `LICENSE.txt` is the GraalVM Free Terms and Conditions, which deem the portions of a Native
Image output "an unmodified Program" and allow redistributing it only under those terms, free
of charge; the JDK's `legal/java.base/LICENSE` is Oracle's No-Fee Terms and Conditions (not
GPLv2+CPE, which only a Community build would give); `legal/<module>/*.md` hold third-party
components. Of the modules the image links, `java.base` has eight (ICU, CLDR, Unicode, the
GCC runtime exception ...) and `jdk.incubator.vector` has SLEEF (Boost, CC-BY-4.0; Linux
builds only); the other modules in use carry only the Oracle texts.

- `src/native/graalvm-notices.sh` writes LICENSE.txt, the java.base LICENSE and COPYRIGHT and
  those `.md` files from `java.home` into `META-INF/graalvm-notices.txt` (`-Pnative` only;
  the jar runs on the user's JVM), the binary holds it (`resource-config.json`), and
  `rontolisp notices --tool` prints it last. CI's "Verify native image" (job
  `native-image-verify`) greps the two Oracle marks and one section header per `.md` the
  builder JDK ships for those modules. A module with `.md` files entering the image is added
  to the script's `MODULES`. Only licence texts are read from the JDK (rule 1 is about
  classes and jars).
- The `.md` set is per platform: macOS-aarch64 Oracle GraalVM ships `libsleef` but no
  `legal/jdk.incubator.vector/sleef.md`, and the darwin-aarch64 binary links no `Sleef_`
  symbol nor libsleef (`nm`, `otool -L`, 2026-11-19), so its notices carry no SLEEF and owe
  none.
- GraalVM Community (GPLv2+CPE, no fee condition) is not an option: `-H:+VectorAPISupport`
  (pom) is Oracle GraalVM's. The release stays free of charge by that condition; charging for
  rontolisp's binary would need Oracle's terms first. Not covered: the web playground
  (`-Pweb`, Web Image) is not a release asset and was not audited.

### The Java library (k03 on) and Unicode normalization

**The footing of every entry below, unless it says more**: written from the API
documentation and JDK 25's answers to probes (the item's artefact directory held the
probes; removed from the tree on 2026-11-26, kept in the history); nobody opened OpenJDK's source; what the documentation does not state (the words,
order and exception of each refusal, hash codes, texts, iteration orders, which of several
candidates a JDK uses) was read off the answers, and the answers are lines of the spec corpus
(`java-spec.yaml` unless named), which the JDK leg asks again. A pgjdbc or
`com.ongres.scram` sources jar (BSD-2-Clause) named below was read only for which members it
calls or implements, nothing of it copied. CLDR's or Unicode's data "as a running JDK
writes it" is under the Unicode entry of `THIRD-PARTY-NOTICES.txt`. A hash code marked "our
own" is a number of our own, not a JDK's.

- **k03 `java.net.URI`'s rules and the HTTP client**: `eval/uri.lisp` holds the RFC 3986
  resolution and the URL scanner it shares with the Clojure client; the rest from RFC 3986 / RFC
  2396's character classes too. Corpus `java-http-spec.yaml`.
- **k04 the HTTP server** (`com.sun.net.httpserver`, `java.net`'s address classes): corpus
  `java-http-server-spec.yaml`.
- **k05 `java.time.Duration`, the client's time limits**: the runner's timeouts (`wire.rs`)
  are Rust's `connect_timeout` and socket read timeouts.
- **k08 the field updaters, `invokeAll` with a limit, `join(Duration)`**; also which abstract
  method javac names of a class leaving several out.
- **k12 `java.util.stream`, the collectors, the summary statistics**: the summation of
  doubles is Kahan's, as published, the last compensation subtracted (the one of four
  candidates giving a JDK's bits).
- **k35 `java.time`** (dates, times, instants, zones, clocks, fields, units): the calendar's
  arithmetic is our own formulation over the day counted from 0000-01-01; the ISO-8601 forms
  are read by one scanner written here. `LocalDate.plusDays` is checked against the grid;
  `ValueRange.hashCode` is our own.
- **k42 the regions of the tz database**: `zone-rules.txt` (embedded in a program that names
  a region) is the tz database's data, public domain: `JavaZoneRulesTest` asks a running
  JDK's public `java.time.zone` for every zone's transitions, yearly rules and standard
  offsets (use, not copying: no `tzdb.dat`, no file of a JDK and none of the tz database is
  read) and writes them in an encoding made here. Entry "tz database"; the version is the
  file's `tzdb` line; [java-frontend.md](java-frontend.md), "java.time: the regions", says
  how a newer one is taken.
- **k43 the formatter of `java.time`**: `Parsed` has the
  name a JDK's refusals show; the words of months, days, eras and half days are CLDR's.
- **k52 what the formatter left** (weeks, quarters, Julian days, `java.time.chrono`): the quarters' words and field names
  CLDR's.
- **k57 zone names in words, localized forms, a day's period**
  (and `JavaZoneRulesTest`'s walk of every
  zone's names): the patterns, names and periods' words CLDR's.
- **k70 what k57 left** (`DecimalStyle`,
  `java.text.Format`, `Chronology.resolveDate`, `AbstractChronology`).
- **k77 the localized pattern of a requested template**: all the
  rule beyond the template's letters was read off the answers; the skeletons' patterns are
  CLDR's.
- **k78 a calendar of the program's own**: which members are
  defaults was read by reflection off a running JDK.
- **k37 `java.util.Date` and `java.sql`'s dates**: the calendar is our own day count per
  calendar (`rontolisp.internal.Dates`). `Timestamp`'s split of an instant is floor
  division, checked against the grid.
- **k47 the sorted collections** (`TreeMap`, `TreeSet`, the navigable interfaces, their
  `Collections` wrappers, `ConcurrentSkipListMap`/`Set`, `EnumSet`, `EnumMap`): the tree is
  Adelson-Velsky and Landis's (1962), balanced by heights, where a JDK's is red-black; the
  skip list is that tree under a monitor. `AbstractSet.removeAll` and `Map.remove` /
  `replace` of a value are the documentation's own statements. `Random`'s `nextBytes` and
  `nextFloat()` are the algorithms the documentation states; its draws between bounds the
  streams' (k26) and, for bounds too far apart, the one of five candidate formulas giving a
  JDK's bits.
- **k55 the sequenced collections**:
  the defaults' stated implementations from the documentation.
- **k60 `Collections`' collections, `List.of`'s refusals, a bridge's cast, `PriorityQueue`**: `PriorityQueue` is the binary heap
  of Williams (1964) with Floyd's construction (1964), as textbooks have them; which of equal
  elements goes where was read off a JDK's answers to seeded runs compared with a heap
  written beside it.
- **k71 a raw argument, `Stream.toList()`, the digits**:
  also the JLS (18.5.2, 15.12.2.6, 4.5.1); the zeros of the decimal digits are Unicode's.
- **k82 `copyOf`, surrogate halves, `chars()`**.
- **k85 `Formatter`, `Formattable`, `%g` `%a` `%t`**.
- **k72 `StringJoiner`, `StringTokenizer`, `BitSet`, `Scanner`, `Readable`, `Buffer`,
  `CharBuffer`, hexadecimal floats**:
  `BitSet.hashCode` the documented formula.
- **k88 the buffers**.
- **k86 `File`, the file streams, `RandomAccessFile`, `Path`, `Files`, the strict codecs**: `File.hashCode` the formula the
  documentation states for a Unix host; a strict decoder counts the Unicode Standard's
  maximal subpart.
- **l67 a file's attributes from the host's `stat`**: the
  WASI side is wasmtime's answers to WAT probes of `path_filestat_get` and
  `path_filestat_set_times` (`.kb/read-load-streams.md`).
- **m19 links made and read, the basic view**:
  the WASI side is wasmtime's answers to a WAT probe of `path_symlink` and `path_readlink`
  (`wasi/gen.py`, `wasi/run.sh`).
- **k98 `Socket`, `ServerSocket`, `javax.net`'s factories**: pgjdbc read for members.
  Corpus `java-socket-spec.yaml`.
- **l14 a connect's limit, a bound socket, half-close, an accept's deadline**: the runner's
  connect with no wait is `socket2`'s (MIT / Apache-2.0, linked through tokio already) and
  POSIX's `connect`, `poll` and `SO_ERROR` as their manual pages state them. Corpus
  `java-socket-spec.yaml`.
- **l96 the socket options, `SocketOption`, `StandardSocketOptions`**: the runner's options
  are `socket2`'s setters (MIT / Apache-2.0). Corpus `java-socket-spec.yaml`.
- **k99 `MessageDigest`, `Mac`, PBKDF2's `SecretKeyFactory`, `SecureRandom`**: the
  digests from RFC 1321 (MD5) and FIPS 180-4 (SHA-1, SHA-2), HMAC from RFC 2104, PBKDF2 from
  RFC 8018, their constants the standards' own; the names and aliases through the public
  `Provider` API. `SecretKeySpec.hashCode` our own. pgjdbc and `com.ongres.scram` read for
  members.
- **l18 SHA-3, SHAKE, MD2**:
  FIPS 202 (rho's offsets and iota's constants computed by the standard's Algorithms 2, 5
  and 6, not typed), RFC 1319 (its permutation S as the RFC prints it, the step 2 of its
  published correction). What the probes show of a JDK's late provider choice is stated as
  answers, not as the JDK's mechanism.
- **l17 `java.util.Base64`**: the
  alphabets and padding RFC 4648's, the lines RFC 2045's; `Compare.java` runs the library's
  own file renamed beside the JDK's class. pgjdbc and `com.ongres.scram` read for members.
- **l08 a `Locale` of any language**:
  also BCP 47 (RFC 5646: the grammar, its grandfathered tags with their registry's preferred
  values) and the Unicode Standard's special casing conditions (Turkish, Azerbaijani,
  Lithuanian); `compare/Compare.java` runs the library's class renamed beside a JDK's; the
  languages and the regions' weeks CLDR's. Corpora `java-spec.yaml` and
  `java-http-server-spec.yaml`.
- **k89 reflection in full, references, the weak map**:
  `getMethods`' algorithm, the texts `toString` specifies and `Modifier`'s values from the
  documentation; the weak map's bucket spread (the exclusive-or of the hash code shifted by
  0, 4, 7, 12, 16, 19, 20, 24 and 27) was found from keys of single-bit hash codes, its walks
  and growth checked against a model.
- **l64 reflection over the JDK's classes**: `jdk-members.txt` is read off a running JDK 25
  by `JavaJdkMembersTest` through the public `java.lang.reflect` API: names, signatures,
  modifiers and the types thrown of the public and protected members, which an
  implementation shares ("What is fine"); no class file, no private member and no prose of a
  JDK's. `getMethods()` and `getFields()` are the API's published algorithms over that table.
  The access rules are the API documentation's, their words and order JDK 25's answers.
- **l65 `java.lang.reflect.Array`, `java.lang.invoke`**:
  `MethodType.hashCode` as specified; JLS 15.12.3 for a signature polymorphic call. JUnit's
  sources were given to the front end, not read (l93's rule).
- **l66 generic types, records, parameters, member annotations**:
  `Annotation`'s `equals` and `hashCode` as specified; JVMS 4.7.9.1 for a signature's text;
  JLS 9.6.4.1 for `@Target`, 9.7.5 for a container. The JDK's signatures and annotations in
  `jdk-members.txt` are read by `JavaJdkMembersTest` through the public API
  (`getGeneric*`, `getDeclaredAnnotations`, an annotation's `toString`), as l64's are.
- **m30 annotated types, a type variable's annotations, `@Target` checked**: the interfaces'
  members as JDK 25's reflection lists them (`AnnotatedType.getAnnotatedOwnerType` a default);
  JLS 9.6.4.1 and 9.7.4; the texts, equality, owners, receivers, the parameters' alignment,
  which declaration carries a `TYPE_USE` annotation, where javac writes a declaration's own
  ahead of a qualified name and javac's three refusals: JDK 25's answers to probes of our own.
- **l00 `java.sql`'s interfaces, `DriverManager`, the `SQLException` family, `javax.sql`**: the
  interfaces (`java.sql`, `javax.sql`, `javax.transaction.xa`'s two,
  `javax.xml.transform`'s two) were written by a script of ours from the member signatures
  of JDK 25's API documentation pages (modifiers, type parameters, types, parameter names,
  `throws` clauses, in the pages' order) and their constant values page; no prose of them. A
  default member's body is the answer a probe measured. The classes are hand-written from
  the signatures and the probes. pgjdbc read for which members it implements and calls.
- **l20 what l00 left: `SQLData`, `SQLInput`, `SQLOutput`, `SQLPermission`,
  `java.security`'s permissions, `javax.naming`'s references**: the interfaces by
  l00's script from the API documentation pages and their constant values page; the classes
  by hand from the signatures and the probes.
- **l79 `Calendar`, `GregorianCalendar`, `TimeZone`, `SimpleTimeZone`**: in two
  sessions: one wrote `spec.md` (53 rules) and its probes from the documentation and JDK
  25's answers alone, another the code from them and thirteen probes of its own for what the
  rules left open (rules 54-66). `rules.md` maps each rule to the members answering it
  and lists what is refused for want of a rule.
- **l78 `java.util.logging`**:
  the names of the methods javac makes of lambdas were read off `javap -p` and the sources a
  logger reported over probe programs, not off javac's source.
- **k74 a `CopyOnWriteArrayList`'s part, a stale part, `removeRange`**.
- **k58 computed resource names, `Properties`, `ResourceBundle`, `ServiceLoader`**: a
  `Properties`' map is the map the probes match. The scan of the libraries' class files
  (`Scan.java`) reads third-party class files as data and copies nothing.
- **k69 `java.net.URL`, a resource's URL, the loaders above the program's**:
  `URL.hashCode` our own.
- **m78 `java.net`'s cookies and a client's cookie handler**: RFC 2965 3.3.2 for the
  embedded dot; the date an Expires is read as, the version a header is, the port list,
  the domain matching and every refusal's words from JDK 25's answers. The in-memory
  store's name is what `getClass()` answered; its index of the cookies, and the order of
  its answers, are ours (`deviations.md`). The epoch day of a date is our own arithmetic.
- **k29 Unicode normalization** (`eval/unicode-normalization.lisp`): written from Unicode
  Standard Annex #15 and the Unicode Standard's sections 3.11 (decomposition, canonical
  ordering, canonical composition) and 3.12 (Hangul by arithmetic); nobody opened OpenJDK's
  or ICU's source. Its tables are generated by `UnicodeNormalizationTablesTest` from a
  running JDK's public `java.text.Normalizer` (use, not copying: no data file of a JDK or of
  ICU is read, and the Unicode Character Database's files are not either), under the Unicode
  entry. `java.text.Normalizer` itself is from the API documentation
  ([unicode-normalization.md](unicode-normalization.md)).
