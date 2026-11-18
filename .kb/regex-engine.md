# The regular-expression engine (`eval/regex.lisp`, `eval/RegexLibrary`)

**Invariant: one engine, written here, answers every front end that has patterns. It is Common
Lisp source spliced into a compiled program (`regex.lisp`, its Unicode tables
`regex-unicode.lisp`), written from the `java.util.regex.Pattern` API documentation and from a
JDK's answers as an oracle; no JDK source was read for it and none of the engine it replaced was
kept.** Since 2026-11-18 (j90). Before: a port of OpenJDK's `Pattern.java` in `clojure.lisp`
(2026-10-11, `.todo/f43`), which rule 2 of [java-frontend-provenance.md](java-frontend-provenance.md)
forbids; it was deleted, not refactored (it stays in git history, the user's decision).

## Where it sits

- `eval/RegexLibrary`: gate `%REGEX-` ([source-language.md](source-language.md), "The run-time
  libraries' name gate"). The interpreter loads it on the first resolution of a
  `rontolisp::%regex-` function; `CompileFrontend.expand` splices it right OUTSIDE
  `ClojureLibrary` and `JavaLibrary` (their libraries are what name it), and
  `LibraryDefunPruner` drops what a program does not reach. `RontoPlayground` splices it the
  same place. A program of two languages gets it once.
- Clojure: `clojure.lisp`, "Regular expressions" -- the pattern and matcher values, the `re-*`
  verbs, `clojure.string`'s pattern arms, the `Matcher` methods, the matcher over a
  CharSequence type ([clojure-frontend.md](clojure-frontend.md), "Regex").
- Java: `java.util.regex.Pattern`, `Matcher`, `MatchResult`, `PatternSyntaxException` as Java
  source of the library, their `native` members the engine's functions
  (`JavaIntrinsics.NATIVES`), `String.matches` / `replaceAll` / `replaceFirst` / `split`
  ([java-frontend.md](java-frontend.md), "The library").

**Decided: Lisp in a shared run-time library, not Java source lowered for both.** The Clojure
front end cannot lower the Java library (its values are not Java's: a string is shared, a
vector, a boolean and `null` are not), and the Java library's own measured cost is against it
(an `int` kept in range by a mask on every operation, structure slots: `.kb/java-frontend.md`,
"Measured costs"). Lisp is what both already splice.

## The design (this file's own)

- **Parser**: recursive descent over the pattern's characters to a tree of lists
  (`(:char code mode)`, `(:set test)`, `(:seq ..)`, `(:alt ..)`, `(:group i body)`,
  `(:rep body min max kind)`, ...). `\Q..\E` is rewritten to escaped literals first. The state
  is one simple vector (cursor, flags, groups, names, the providers).
- **Compiler**: the tree becomes closures `(STATE I) -> boolean`, each made holding the closure
  that continues the match, so a sequence allocates nothing while it runs and a failure is a
  return. A class is a test function of a code.
- **State of a search**: one simple vector -- input, length, the groups' bounds, the counters
  and positions the loops own. A pattern is immutable and shared; a matcher owns its state.
- **Three kinds of repetition**: of one character (a counted run, no recursion per character);
  of a body that matches one way (iterations taken whole, giving back is dropping one); of
  any other body (count and iteration start in the state, the body continuing with the step,
  every way tried). A greedy unbounded loop of the third kind, outside any other loop and
  look-behind in a pattern with no back reference, remembers the positions it could not
  continue from, so `(a|aa)*c` and `(x+x+)+y` stay polynomial.
- **Providers**: the engine never names its Unicode tables. A pattern is compiled with up to
  three functions -- `%regex-unicode-provider` (`\p`, `\P`, the U flag's classes),
  `%regex-category-provider` (`\b`'s word characters past ASCII), `%regex-grapheme-provider`
  (`\X`, `\b{g}`) -- and one it was not given is refused as `unsupported regex`. So the pruner
  alone decides what a program carries: only the front end's entry that names a provider
  reaches its table. This replaced three `ClojureArms` families.
- **No function value is called with more than two arguments** (matchers `(st i)`, tests
  `(code)`, the refusal function `(description index)`, the providers). Measured 2026-11-18: a
  three- and a four-argument `funcall` made every wasm program carry `_dispatch_3` /
  `_dispatch_4` and the wide forms of every dispatchable builtin (`ASSOC` 248 -> 924 B,
  `REDUCE` 125 -> 3,876 B, the `SUBSTITUTE` family whole): 539,385 -> 452,713 B in a program
  with one pattern once they were gone.
- **No variable is named after a function the wasm compiler scans for** (`set` made
  `programUsesSymbol(.., SET)` true, which brings the eval and apply runtimes), and no call
  takes `:test` / `:start` / `:end` (the prelude's sequence functions grow with the keywords
  a program uses): 598,330 -> 539,385 B for the two together.

## What was read from the documentation and what was measured

The syntax, the flags, the classes and their names, the quantifier kinds, replacement and
split are the `Pattern` / `Matcher` / `String.split` documentation's. What it leaves open was
measured by running patterns under JDK 25 (never by reading its source); the 1,100 probes are
kept (`src/test/resources/regex-probes.txt`, run by `ClojureRegexTest` against the JDK
running it), and a comment in `regex.lisp` says "measured" where a rule is one:

- **Refusals**: the description and index of each `PatternSyntaxException`. The index is the
  cursor after the character read last, minus one; a read past the end moves it too.
- **Captures the oracle keeps**: a group's bounds are set where its body ends and given back
  only when what follows fails; a sub-match (a lookahead's, an atomic group's, one iteration
  of a one-way body) keeps what it set. In a loop over a one-way body the body's group follows
  the last iteration kept and the groups inside keep the furthest one (`((\w)\w)*\w\w` over
  `abcde` is `["abcd" "ab" "c"]`); an empty iteration past the minimum is not taken (`()*`
  leaves the group unset), a possessive loop takes it. A possessive loop over a group takes
  each iteration's first success whatever the body (`(?:a|ab){2}+c` does not match `abac`).
  `\R` under a quantifier is taken whole.
- **Look-behind**: tried from the nearest start to the farthest. The bounds are counted as the
  oracle counts them -- 32-bit integers that wrap, an unbounded count the largest of them, the
  alternatives' greatest starting below zero, what follows alternatives counted from zero --
  so the same bodies are refused (`Look-behind group does not have an obvious maximum
  length`), one unbounded repetition of one character reaches back to the start, and two wrap
  the bound so the look-behind never matches (`(?<=a+b+)c`).
- **Classes**: `&&` with nothing after it, or with a lone `&` later, answers as the oracle's
  bookkeeping does (a table of the single characters below 256 that stays the same table
  after a `&&`); at either end of a range `\v` is the vertical tab; in comments mode an `&`
  before skipped space is dropped.
- **Case**: `i` folds ASCII; `iu` compares upcased, then upcased and downcased; a single
  character with no other case compares exactly, a run of two or more folds every character
  (`(?iu)aß` takes `aẞ`, `(?iu)ß` does not).
- **Matcher**: `find` starts where the last match ended, one further after an empty one; a
  failed search keeps the last end and the next `find` starts there; `\G` holds at the last
  end as the search began, at the start for the first search since a reset. The last end is
  moved by each sub-match of a search (and left there when it fails), which shows in the next
  `find` and in `\b{g}`: the oracle's `\b{g}` asks whether the one cluster from the last end
  reaches past the position, and throws `StringIndexOutOfBoundsException` where the last end
  is the input's end. Counted runs of one character move it, a greedy unbounded one does not.
  `(?m)^` never holds at the end, an empty input's start included.
- **Unicode**: the property names are the documentation's; which prefix and spelling a name
  takes was measured (`Is` + a binary property in any case, a category or a script; `In` + a
  block; `sc=` / `blk=` / `gc=`; under U a POSIX name is the binary property). `\b` without U
  takes a non-spacing mark as a word character when its base, past other marks, is a letter
  or digit of any script. Grapheme clusters are Unicode Standard Annex #29's rules (GB1 to
  GB999, GB9c) written from the annex.
- **Tables** (`regex-unicode.lisp`): general category and 24 `Character` predicates, script,
  block, grapheme break type and conjunct class per code point, generated by
  `RegexUnicodeTablesTest` from the running JDK's public `Character` API and by asking its
  `\X`, pinned to the shipped file.

## Deviations (documented in `doc/*/clojure/reference/regex.md`, `doc/*/java/deviations.md`)

- Positions count code points. Where the oracle steps between the halves of a surrogate pair
  (after an empty match before a supplementary character) its finds, and a `\b{g}` there,
  differ; `ClojureRegexTest` leaves such cases out by the oracle's own answer.
- A look-behind holding a supplementary literal under `(?i)` never matches in the oracle (it
  counts one UTF-16 unit); here it matches.
- `(?c)` (CANON_EQ): a class or a property under it is `unsupported regex`; so is `\N{name}`
  in a pattern made at run time (the Clojure lowering resolves a literal's names through
  `Character.codePointOf` while lowering; the Java front end has no table).
- The index of an unclosed class at the very end of a comments-mode pattern (the oracle's
  cursor stands one further after skipped space than after a skipped comment) was not
  pinned down: 1 case in 34,000 random patterns.

## Measured (2026-11-18, against the port it replaced, same machine)

Random differential against `java.util.regex` of JDK 25 on fresh seeds, every find, group and
refusal message compared: 34,000 plain patterns 1 mismatch (the unclosed-class index above),
9,000 Unicode patterns 0, 5,000 grapheme patterns 2 (both the code point deviation). The
seeds `ClojureRegexTest` pins: 0.

Wasm P1 / component / JVM class, bytes, and a loop's best of three, seconds:

| | port | this engine |
|---|---|---|
| `(println (re-find #"a+" "caab"))` | 386,096 / 409,345 / 659,649 | 415,700 / 439,221 / 656,687 |
| six patterns (`re-seq`, `re-matches`, `replace`, `split`, two `re-find`) | 425,889 / 449,171 / 717,798 | 471,722 / 495,019 / 710,612 |
| the six, 3,000 times, each literal compiled every time: interpreter / JVM / wasm / component | 8.1 / 0.89 / 0.37 / 0.36 | 7.3 / 0.92 / 0.37 / 0.37 |

So: the same time on the compiled backends, a tenth less on the interpreter, a JVM class the
same size, a wasm module 8 to 11 % larger. The engine's own functions are 121 KB of wasm
where the port's were 92 KB: a closure per node costs more code than a walk over an opcode
tree (a `svref`, a `setf` of one or a `+` is 100 to 250 bytes there). What was done for size:
the two rules under "The design" (-146 KB together), and helpers for the cold paths -- the
parser's cursor, the look-behind study, the loops' bookkeeping (-22 KB of the engine alone).

The Java front end, wasm: `"..".split("\\s+")` 251,783 B (the engine, no table);
`split(" ")`, a constant text, 66,664 B (no engine; the splitter it replaced made 79,448);
a pattern computed at run time 317,388 B (every table).

## Pins

- `ClojureRegexTest` (the interpreter against `java.util.regex`): curated cases, the seeded
  random corpora (2,000 plain, 1,500 Unicode, 1,500 grapheme), the loop memo, matcher scripts
  (`find` / `find(int)` / `lookingAt` / `matches`), the matcher over a CharSequence type
  reading a prefix, the unsupported refusals.
- `RegexUnicodeTablesTest`: the shipped tables are the running JDK's; every run and name
  decoded.
- `clojure-spec.yaml` (`a-regex-*`, `regex-literals-*`, `re-*`, `backslash-g-*`,
  `a-matcher-answers-the-java-matcher-methods`) and `java-spec.yaml`
  (`regular-expressions-run-on-the-engine-the-front-ends-share`): four backends, the oracle's
  lines.
- `ClojureLibraryTest#aProgramCarriesTheRegexEnginesTablesOnlyWhereAPatternMayNameWhatReadsThem`,
  `ClojureArmsTest#aRegexLiteralIsParsedByTheEntryItsSourceCallsFor`.
