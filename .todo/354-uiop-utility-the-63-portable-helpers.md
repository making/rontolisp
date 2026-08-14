# `uiop/utility`: the 63 portable helpers everything else is built on

Difficulty: Medium

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **7 / 68 (`emptyp`, `first-char`, `last-char`, `split-string`, `if-let`, plus `not-implemented-error` and `parameter-error`, which landed with the skeleton because every other item signals through them)**.

Depends on `.todo/353` (sub-package skeleton + `UiopCoverageTest`). **Take this
one first of the twelve**: `strcat` / `ensure-list` / `nest` are what upstream's
other sub-packages are written in. (`not-implemented-error` and `parameter-error`
also live here and every other item signals through them, so they landed with the
skeleton rather than waiting for this item.)

`uiop/utility` has 68 portable externals; 7 are present (`emptyp`,
`first-char`, `last-char`, `split-string`, `if-let`, and the two the skeleton
landed: `not-implemented-error` / `parameter-error`). The other **61**:

```
ACCESS-AT ACCESS-AT-COUNT APPENDF BASE-STRING-P BOOLEAN-TO-FEATURE-EXPRESSION
CALL-FUNCTION CALL-FUNCTIONS CALL-WITH-MUFFLED-CONDITIONS CHARACTER-TYPE-INDEX
+CHARACTER-TYPES+ COERCE-CLASS COMPATFMT +CR+ +CRLF+ EARLIER-TIMESTAMP
EARLIEST-TIMESTAMP ENSURE-FUNCTION ENSURE-GETHASH ENSURE-LIST
FIND-STANDARD-CASE-SYMBOL FROB-SUBSTRINGS LATER-TIMESTAMP LATEST-TIMESTAMP
LATEST-TIMESTAMP-F LENGTH=N-P LEXICOGRAPHIC< LEXICOGRAPHIC<= +LF+
LIST-TO-HASH-SET LOAD-UIOP-DEBUG-UTILITY MATCH-ANY-CONDITION-P
MATCH-CONDITION-P +MAX-CHARACTER-TYPE-INDEX+ NEST +NON-BASE-CHARS-EXIST-P+
PARSE-BODY REDUCE/STRCAT
REGISTER-HOOK-FUNCTION REMOVE-PLIST-KEY REMOVE-PLIST-KEYS SIMPLE-STYLE-WARNING
STANDARD-CASE-SYMBOL-NAME STRCAT STRING-ENCLOSED-P STRING-PREFIX-P
STRINGS-COMMON-ELEMENT-TYPE STRING-SUFFIX-P STRIPLN STYLE-WARN
SYMBOL-TEST-TO-FEATURE-EXPRESSION TIMESTAMP*< TIMESTAMP< TIMESTAMP<=
TIMESTAMPS< TIMESTAMPS-EARLIEST TIMESTAMPS-LATEST UIOP-DEBUG
*UIOP-DEBUG-UTILITY* WHILE-COLLECTING WITH-MUFFLED-CONDITIONS
WITH-UPGRADABILITY
```

Nothing here touches the filesystem, the OS or the network, so all 63 run on
all four backends. Most bodies can come across near-verbatim from
`utility.lisp`.

## What needs a decision rather than a transcription

- **`with-upgradability`** wraps every upstream definition and exists so ASDF
  can redefine itself in place. rontolisp has no image to upgrade: expand it to
  `progn`. Say so in `.kb/uiop.md` -- it is a semantic choice, not an omission.
- **`not-implemented-error` / `parameter-error`** are the two conditions
  `.todo/353` makes the house style. Their `:report` must name the operation
  and, for the first, why it cannot work here (no subprocesses, no image dump)
  -- the message is the whole value of the item.
- **The character-type quartet** (`+character-types+`,
  `+max-character-type-index+`, `character-type-index`,
  `+non-base-chars-exist-p+`, `base-string-p`,
  `strings-common-element-type`) is about `base-char` vs `character`.
  rontolisp has one character type; answer consistently (one type, index 0,
  `+non-base-chars-exist-p+` true) rather than approximating SBCL's answers.
- **`match-condition-p` / `with-muffled-conditions`** need a condition-type
  designator match and `handler-bind` + `muffle-warning`. Check
  `.kb/error-handling.md` for what `muffle-warning` does on the compile paths
  before writing the body; if the restart is not there on some backend, that is
  a finding for this item, not a follow-up.
- **`uiop-debug` / `load-uiop-debug-utility` / `*uiop-debug-utility*`** load a
  developer's personal debug file at read time. Define them; the load arm is a
  `not-implemented-error` where there is no filesystem.
- **`parse-body`** is what a macro-writing library calls to split docstring and
  declarations. Get the `declare`/docstring ordering exactly right -- it is the
  one function here whose subtle bug shows up as someone else's macro
  misbehaving.

## Gate

`UiopCoverageTest` reports `uiop/utility 68/68`. A new
`LispEvaluatorTest` block covers the string family, the timestamp family and
`access-at`; `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` cover
`strcat` / `string-prefix-p` / `nest` / `while-collecting` (the four with real
codegen shape). One `ci-spec.yaml` case exercises the string helpers end to end.
