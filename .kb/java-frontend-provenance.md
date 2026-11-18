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
   or a permissive licence (MIT, BSD, Sun's fdlibm permission) -- with its notice kept and the
   origin stated in the file's header and in `NOTICE`. Default is to write it ourselves.
   Since j91 two ports stand on it: fdlibm (Sun's C) and Schubfach (Giulietti's own, MIT).
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
  - `NOTICE` entries for fdlibm and Schubfach are `j93`'s.
