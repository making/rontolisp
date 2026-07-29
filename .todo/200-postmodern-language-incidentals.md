# Language incidentals on the postmodern path (format, features, floats, streams)

Grab-bag of small-to-medium gates hit by postmodern proper, each too small for
its own file but each a real blocker at a named line. Probed 2026-07-28 unless
marked "verify". Blocks `.todo/202-postmodern-non-mop-milestone.md`.

**Status 2026-07-29**: everything below is either DONE or reduced to ONE
remaining work item (the string/character stream group in "Streams / io") plus
the pre-existing `.todo/181`. All DONE items hold on **all four backends** and
are pinned by the `postmodern-language-incidentals` ci-spec case plus
`LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`
cases named per item.

## FORMAT (extends `.todo/001-advanced-format-directives.md`)

- ~~`~^` inside `~{...~}`~~ -- DONE by `.todo/195`; re-verified here.
- ~~`"\\~C~V,V,'0R"` (`json-encoder.lisp` unicode escape writer)~~ -- VERIFIED
  ALREADY WORKING: `~V` parameter-from-argument (twice), the `'0` character
  prefix parameter and `~R` radix mode with min-width/padchar all answer
  correctly. No change was needed.
- ~~`"deallocate ~:@(~S~)"` (`prepare.lisp`)~~ -- VERIFIED ALREADY WORKING.
- ~~`deftable.lisp` `\!foreign`/`\!unique` nesting~~ -- **DONE**. The blocker was
  not nesting as such (the `FmtParser` was already recursive, and `~@[...~]`
  around a `~{~^~}` already worked); it was `requireEqualConsumption`, which
  rejected a runtime-selected `~[` whose clauses consume DIFFERENT numbers of
  arguments. That is exactly the `~:[NOT DEFERRABLE~;DEFERRABLE INITIALLY
  ~:[IMMEDIATE~;DEFERRED~]~]` tail of both constraint strings, and the whole
  control string was silently emitted VERBATIM (the runtime-renderer fallback
  does not know `~:[`, so it echoed the directives).
  Replaced by `FmtParser.clauseExprs`: when the clauses diverge, the REMAINDER
  of the enclosing control string is re-parsed once per clause and folded into
  that clause, so each branch continues from its own argument position -- which
  is what CL's argument pointer does. A branch needing more arguments than were
  supplied folds to a call that signals only if selected, instead of failing the
  whole expansion. Fan-out is bounded by `MAX_REMAINDER_EXPANSIONS`.
  Two documented residual limits (`doc/{en,ja}/reference/macros/format.md`): a
  `~&` in a distributed tail is approximated from that branch's literal text
  (as in any composite body), and an argument-divergent `~[` nested inside
  ANOTHER composite directive (`~(`/`~{`) still falls back.

## `*features*` pushes by `eval-when` must be visible to the reader mid-file

STILL OPEN -- owned by
`.todo/181-features-pushes-are-invisible-to-the-reader-and-to-the-compile-path.md`.
`postmodern/json-encoder.lisp:500` remains its first hard consumer.

**However the urgency dropped**: the float-lattice premise below turned out to
be wrong, so the branch this `eval-when` selects is the CORRECT one even with
the push invisible. json-encoder is therefore no longer a *correctness* victim
of 181, only a fidelity one. The Tier-4 form-rewrite fallback is not needed.

## ~~`subtypep` float lattice is dishonest~~ -- FALSE PREMISE, no change

`(subtypep 'single-float 'double-float)` returns `T`, and that is **correct**.
rontolisp has exactly ONE float format: `1.0s0`/`1.0f0`/`1.0d0`/`1.0l0` all read
to the same value, `type-of` answers `FLOAT` for each, and a float is `typep` of
all four names. CLHS explicitly permits an implementation to have as few as one
distinct float format, in which case the four names denote the same type. So
`canonicalSubtypeName` collapsing them is honest, and json-encoder's `eval-when`
probe correctly selects `:cl-json-only-one-float-type`. **Do not "fix" the
lattice tables.** The re-evaluation trigger (what would make this wrong again --
a distinct single-float landing) is written into `.kb/declarations-type-checks.md`.

One real but minor gap noted there and left open: `subtypep` returns ONE value,
not CL's `(values result certain-p)`. Every known consumer uses only the primary.

## Streams / io -- THE ONE REMAINING WORK ITEM

- ~~`with-output-to-string` binding `*standard-output*`~~ -- DONE by `.todo/195`;
  re-verified here.
- **`make-string-output-stream` / `get-output-stream-string` DO NOT EXIST** as
  public names. The machinery is all there -- `%make-string-output-stream`,
  `%make-string-input-stream`, `%string-stream-contents` are `CL_INTERNALS`
  primitives on all four backends behind `with-output-to-string`
  (`.kb/read-load-streams.md`) -- only the standard names are unexposed.
  `execute-file.lisp` needs them at `:11`, `:219`, `:426` (a
  `make-string-output-stream` call as a `defstruct` slot `:initform`, which does
  work once the name resolves) and `:208`, `:334`, `:472`.
  **Caution when exposing them**: CL's `get-output-stream-string` RETURNS the
  accumulated string AND CLEARS the stream. `%string-stream-contents` does not
  clear (`with-output-to-string` fetches once, then closes), so a straight alias
  would be silently wrong for any caller that reads twice. postmodern happens to
  read each stream exactly once, so the alias would pass -- which is precisely
  the kind of latent gap this repo's working principles warn about. Either add a
  clear step (interpreter = `StringWriter.getBuffer().setLength(0)`; JVM = the
  same in `_stringStreamContents`; WASM = reset the negative-handle record's
  chunk list head) or, if that is deferred, write the divergence AND its reason
  into `.kb/read-load-streams.md`.
- **`peek-char` DOES NOT EXIST** (`The function PEEK-CHAR is undefined`).
  `execute-file.lisp`'s lexer needs it.
- **`read-char` at EOF is not a typed `end-of-file`**. `end-of-file` IS a
  registered condition class -- `(handler-case (error 'end-of-file)
  (end-of-file () ...))` catches -- but `read-char` on an exhausted string
  stream signals a plain error whose message is `READ-CHAR: end of file`, which
  only `(error () ...)` catches. `execute-file.lisp` wraps its whole lexer loop
  in `(handler-case ... (end-of-file (e) ...))`, so it would never fire.
  The 3-argument `(read-char s nil :eof)` eof-value form DOES work.
  Fixing this means signalling the real condition class from the read family on
  all four backends; check `read-line`/`read` for the same gap while there.
- `make-synonym-stream` (`config.lisp:224`) is still missing -- it is where
  `.todo/202` says the load currently stops, and it belongs with this group.

## ~~Misc verifications~~ -- ALL DONE

- ~~`getf` with a default~~ -- **DONE**. Was a hard arity error
  (`GETF expects 2 arguments, got 3`). `expandGetf` now binds the optional
  DEFAULT in the `do` INIT list, the interpreter takes 2-3 arguments, and the
  first-class `#'getf` wrapper moved to `binaryOptionalThird` (the nil dispatch
  is exact here: an omitted default and an explicit nil one both answer nil).
  One trap worth keeping: `getf` is a FUNCTION in CL, so the default is
  evaluated hit OR miss. Putting it in the `do` RESULT position (the obvious
  spot) makes the compile paths skip it on a hit and silently diverge from the
  interpreter, which evaluates arguments eagerly -- caught by the
  `evalGetfDefault` test, which pins that a side-effecting default fires either
  way.
- ~~`#.` read-time eval inside a normal source file~~ -- VERIFIED WORKING.
  ~~`rassoc-if`~~ -- was UNDEFINED, now **DONE** (`LispNames` +
  `PackageRegistry.CL_FUNCTIONS` + `expandRassocIf` + interpreter +
  `Jvm/WasmExprCompiler` + a `BuiltinFunctionWrappers` entry + doc page).
- ~~`#\Backspace`, `#\Return`, `#\Tab`, literal form-feed in `#\`~~ -- VERIFIED.
- ~~`case` over characters incl. `#\\`; `(declare (type character ...))`~~ -- VERIFIED.
- ~~`mapc #'funcall` over hook lists; `notany`; `cl-ppcre:create-scanner` with
  `:whitespace-char-class`~~ -- VERIFIED WORKING (the cl-ppcre slice covers both
  the string and the keyword argument form).
- ~~`string-trim`~~ -- a STRING bag worked; a LIST bag
  (`'(#\Space #\Tab)`, which is what `execute-file.lisp` uses at 5 sites) was a
  hard type error. **DONE** via one shared normalizer,
  `LispMacroExpander.normalizeCharBag`, applied by both compilers to
  `string-trim`/`string-left-trim`/`string-right-trim`: a literal bag folds to a
  string constant at expansion time (so existing programs stay byte-identical),
  any other bag gets `(coerce bag 'string)`. The interpreter widens the same way
  in `Environment.charBagString`.
- ~~`eval-when` around a defun needed AT MACROEXPANSION time~~ -- VERIFIED
  WORKING: the defun is visible to the expander when the macro call site expands.
- ~~Toplevel `(let ...)` / `(labels ...)` wrapping `defun`s~~ -- VERIFIED on the
  COMPILE paths too (JVM + both WASM), not just the interpreter. Note when
  writing such a probe: the compilers evaluate multi-argument call forms
  RIGHT-TO-LEFT (`.todo/014`), so sequence the calls through `let*` or the
  counter values read back reversed and look like a hoisting bug that is not one.
