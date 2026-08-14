# `uiop/os`: host identity, feature expressions and the working directory

Difficulty: Medium

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **1 / 22 (`getenv`; `os-unix-p` and `os-macosx-p` are now `not-implemented-error` stubs, not undefined functions)**.

Depends on `.todo/353` and `.todo/354`.

22 externals; three present, and two of those three are the *undefined
function* stubs `.todo/353` exists to abolish (`os-unix-p`, `os-macosx-p` --
`getenv` is real). The **19** missing:

```
ARCHITECTURE CHDIR DETECT-OS FEATUREP GETCWD GETENVP HOSTNAME
IMPLEMENTATION-IDENTIFIER IMPLEMENTATION-TYPE *IMPLEMENTATION-TYPE*
LISP-VERSION-STRING OPERATING-SYSTEM OS-COND OS-GENERA-P OS-WINDOWS-P
PARSE-FILE-LOCATION-INFO PARSE-WINDOWS-SHORTCUT READ-LITTLE-ENDIAN
READ-NULL-TERMINATED-STRING
```

## The three groups

**Answerable from what rontolisp already knows** (`os-unix-p` t, `os-windows-p`
/ `os-genera-p` nil, `operating-system` `:unix`, `implementation-type`
`:rontolisp`, `lisp-version-string` from the checked-in `Version`,
`implementation-identifier`, `architecture`, `detect-os`, `os-cond`, `getenvp`).
These are the two current stubs plus their family, and they are the reason the
item is worth doing at all: `os-unix-p` is a one-line answer that today takes a
program down.

`featurep` is a real feature-expression evaluator over `*features*` -- and
`*features*` differs per backend by design (`reader/Features`), so this is the
one name here that must be tested on all four rather than assumed.

**`getcwd` / `chdir` need a primitive that does not exist.** Nothing in
rontolisp reads or sets a working directory today. The JVM can answer `getcwd`
from `user.dir`; WASI Preview 1 and the component world have **no cwd and no
chdir** -- a WASI program has preopened directories, not a current one. So:
`getcwd` real where it can be, `chdir` `not-implemented-error` on WASM, and the
divergence written into `.kb/uiop.md` **with its reason**, so the next visitor
can tell whether it still holds (WASI may grow one). `uiop/filesystem`'s
`with-current-directory` (`.todo/358`) sits on top of this and inherits the
decision -- do not let it invent a second one.

**`hostname`** likewise: a JVM/interpreter answer, `not-implemented-error` (or
the empty string, if that is what upstream's fallback does -- check) on WASM.

**The Windows-shortcut trio** (`parse-windows-shortcut`,
`parse-file-location-info`, `read-little-endian`, `read-null-terminated-string`)
is `.lnk` parsing that upstream only uses on Windows. `read-little-endian` and
`read-null-terminated-string` are generic binary-stream readers and should be
real; the two `.lnk` parsers can be real too (they are pure stream reading) --
prefer that over a stub, since a stub here is a stub for no reason.

## Gate

`UiopCoverageTest` reports `uiop/os 22/22`, and no member of it signals
*undefined function* on any backend. `ci-spec.yaml` gains a case printing
`(uiop:os-unix-p)`, `(uiop:operating-system)` and a `featurep` expression, so
the per-backend `*features*` difference is visible in the E2E output rather than
discovered later.
