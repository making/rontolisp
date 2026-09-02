# `format`: two renderings, one directive set

`format` has TWO implementations of the same directive set, and they must not drift:

| control string | who renders it | where |
|---|---|---|
| a literal (`(format nil "~a" x)`) | `LispMacroExpander.expandFormat` -- parses at COMPILE time and lowers to string pieces (`FmtParser` -> `FmtOp`s -> `%string-concat` / `princ` calls); no interpreter reaches the output | `macro/LispMacroExpander.java` |
| a runtime value | `%fmt-render`, a Lisp-source interpreter over the control string, injected once per program | `macro/format-render.lisp` (+ `format-render-slash.lisp` / `-stub.lisp`) + `macro/FormatRenderer.java` |

**The invariant: the same control string and arguments render to the same text
whichever path renders them, on all four backends.** Pinned by
`FormatRendererTest.staticAndRuntimeRenderingAgree` (a table run through BOTH
paths) and the `format-runtime-control-string` ci-spec case. Add a directive to
one path and you add a row to that table.

Two implementations is a deliberate cost: the literal path exists so an ordinary
`(format t "...")` compiles to concatenation, with no control-string parsing and
no renderer in the emitted artifact. Merging them would put the renderer into
every program that formats anything.

## What reaches the runtime renderer

Six ways in -- all of them funnel into `(%fmt-render control arguments)`:

1. a computed control expression (cl-who's `escape-string` binds its control to
   a local; cl-postgres carries the server's message);
2. `#'format` as a first-class value -- the `BuiltinFunctionWrappers.formatWrapper`
   body (compile path) and the `format` `LispFunction` in `LispEvaluator`
   (interpreter) both call the renderer, so `(apply #'format ...)` renders the
   same way everywhere;
3. `~?` / `~@?` (the inner control is data by definition);
4. a literal control the static parser DECLINES (`UnsupportedOperationException`
   -- justification `~<`, an argument-divergent `~[` nested in a composite, `~t`,
   `~p`): `expandFormat` falls back rather than failing the compile, so a library
   carrying such a directive on a cold branch still builds;
5. a condition's `format-control` slot -- `%format-condition` (see
   `.kb/error-handling.md`) renders a string control through the renderer;
6. `(error/warn/signal/cerror <computed datum> args...)` -- a datum that is a
   STRING at run time is a format control and the arguments after it are its
   format arguments, so the object-designator expansion's string arm renders it
   (`.kb/error-handling.md`, todo-220).

## What the LITERAL path lowers to (and the two things it does NOT emit)

A `t` destination lowers to a sequence of print calls, a `nil` one to
`%string-concat` pieces (`formatOutputForms` / `opsToPieces`). Two rules keep that
lowering from hiding its own constants, and both are shared by all four backends:

- **A self-evaluating literal argument is NOT bound to a `__format_arg` temp.**
  The temps exist so that an argument is evaluated exactly once, left to right,
  even when a directive reads one position twice (`~:*`, a conditional's re-parsed
  remainder). A literal needs neither guarantee, so `formatArgExprs` substitutes it
  into the lowering directly. **The renderer gate calls the same function**, so the
  shape it predicts and the shape the expansion builds cannot diverge -- the same
  rule the gate already follows for the parser itself.
- **The radix directives answer a NON-NUMBER as if by `~A`** (CLHS 22.3.2): `~D`,
  `~B`, `~O`, `~X` and `~R` given something that is not an integer print it in
  `~A` format and decimal base. The RUNTIME renderer always did (`%fmt-dec` /
  `%fmt-radix` each open with their own guard); the STATIC expansion did not, and
  died inside the digit loop with `Expected integer` -- so the two paths disagreed
  on exactly the arguments the guard exists for. `radixIntegerExpr` and
  `decimalExpr` now close with the same `(if (integerp x) DIGITS
  (%princ-piece x))` (`numberp` for `~:D`/`~@D`, which is the arm that has an
  expansion at all -- plain `~D` was already that conversion). Found through
  cl-unicode, which spells a Hangul syllable name
  `(format nil "HANGUL SYLLABLE ~X" (compute-hangul-name cp))` over a STRING.
- **Every piece renders through `%princ-piece` / `%prin1-piece`, never through the
  public `princ-to-string` / `prin1-to-string`.** The piece names are the same
  print-object-routing conversion WITHOUT the mutable-result wrap the public names
  finish with on the compile paths (`.kb/string-write-runtime.md`, "The fourth
  round"): a piece is consumed by `%string-concat` or written to the destination and
  never reaches the program, and spelling it with the public name had measured 17-80%
  on the whole string-building family.
  `LispMacroExpanderTest.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer`
  pins the spelling.
- **A piece that IS a `%princ-piece` / `%prin1-piece` call prints straight to the
  destination.** A plain `~a` / `~d` lowers to `(%princ-piece x)` and `~s` to
  `(%prin1-piece x)`; under a `t` destination `formatOutputForms` would have
  wrapped that in `(princ ...)`, which by the definition of those two functions
  ("the text `princ`/`prin1` would print") is exactly `(princ x)` / `(prin1 x)`.
  `printPiece` emits that instead, so no intermediate string is built at run time.

Together they are what puts a literal in FRONT of a backend's literal fold instead
of behind a variable reference and a wrapping call: `(format t "Hello, ~a!~%"
"World")` went 5,031 -> 694 bytes of wasm at `--optimize`, `(format t "~s~%" "...")`
6,609 -> 651 (`.kb/optimize-dead-code-elimination.md`, "the print family's literal
fold"). The second rule pays off for a COMPUTED argument too -- it is one string
allocation per `~a` that no longer happens on any backend.

`printPiece` matches the two-element call SHAPE only, so a padded or composite piece
(`~10a` wraps its piece in `padExpr`, `~:a` in an `if`) keeps building its string, as
it must -- the wrapper consumes the text, not the destination.

## `~F` / `~$` lower to ONE call, and must keep doing so (todo-286)

`decimalFloatExpr` emits exactly `(%fixed-decimal value places int-digits plus-p)`.
The primitive is `compiler/FixedDecimal` in the interpreter, `_fixdec` on the JVM
(`JvmNumericRuntimeBuilder`) and `_fixed_dec` on WASM
(`WasmFixedDecimalRuntimeBuilder`), and the RUNTIME renderer's `%fmt-fixed` is now a
`numberp` guard in front of the same call -- one renderer, so the two paths cannot
disagree about a digit.

**What it replaced, and why the shape is the point.** The directive used to expand
INLINE into eight ordinary Lisp forms -- scale by `10^d`, `round` to an integer,
`princ-to-string` it, then punch in a decimal point with `subseq` and
`%string-concat`. That is a reasonable fixed-decimal renderer to WRITE in Lisp and a
bad one to inline into a caller: every generic operation in it was emitted with its
full i31 / bignum / bigint / ratio / float ladder **at every site**, and its `round`
dragged the bignum-capable integer path into a program whose only number is a float.
Measured on `size-report/programs/pi_approx` at `--optimize` the day it landed:
**17,012 -> 5,356 bytes** (-68.5%), of which the caller's own body was 8,607 ->
1,113 and the 22 runtime functions only that chain reached went away. `~,2F` cost
the same as `~,15F`, which is how you can tell it was the shape and not the digit
count. (The same program is 3,540 today; the rest came from
`.kb/wasm-shared-coercion.md`, which took the type ladders out of the operands the
directive still evaluates.)

**The algorithm is a cross-backend contract, not an implementation detail.** Every
step is chosen so four backends can reproduce it bit for bit: `10^places` by repeated
multiplication (WASM has no `pow`), `Math.rint` = `f64.nearest` = round half to even,
and `(long)` of a double = `i64.trunc_sat_f64_s` = SATURATING -- which is why a
magnitude past `2^63` renders as `Long.MAX_VALUE`'s digits (`~,2F` of `1e30` is
`92233720368547758.07`), exactly as the `round`-based expansion did. `places` and
`int-digits` are clamped to `[0, FixedDecimal.MAX_DIGITS]` so a computed `~v,vF`
cannot ask a backend for an unbounded digit buffer. Pinned by `FixedDecimalTest` (the
contract) and by the `~f` / `~$` rows of
`FormatRendererTest.staticAndRuntimeRenderingAgree` (the two paths).

**A `%fixed-decimal` piece goes out through `write-string`, not `princ`.** It is a
string by construction, and `princ` of a value whose type the compiler cannot see has
to keep the generic printer reachable -- on the WASM GC backend that is the float
printer and several KB of runtime (see "The float printer" below), in a program that
no longer prints a float anywhere. `write-string` tracks the output column the same way, so a following `~&`
is unaffected. The `write-string` sites additionally skip the character-vector
normalizer for an argument that CANNOT be one
(`compiler/StringValuedForms.certainlyString`; `_charvec_to_str` plus the
`_charvec_p` shape test it calls is 708 bytes of WASM) -- that set is closed and conservative on purpose, since answering true for
something that can also answer a character vector drops a normalization the semantics
need. (`princ` of a certainly-string form now takes the same shortcut by itself, so
the spelling here is no longer load-bearing for size -- it stays because `%fixed-decimal`
IS a string operation. `.kb/optimize-dead-code-elimination.md`, "the print family's
static-TYPE shortcut".)

**It retired a real drift.** The two paths used to scale by different powers of ten
-- the static one by a `long` `pow10` that OVERFLOWS past `10^18`, the renderer by
`(expt 10.0 places)` -- so `(format nil "~,25F" 3.14159)` answered
`0.0000004997949179834153984` with the control inline and
`0.0000009223372036854775807` through a variable. Nothing pinned it because the
agreement table carried no large-`places` row; it does now, and the primitive is why
one answer is possible.

**Re-evaluation trigger:** if a future `~F` grows a case the primitive cannot
express, add it TO the primitive rather than reintroducing an inline arm --
`LispMacroExpanderTest.aFixedDecimalDirectiveIsOneCallAndNotAnInlinedScaleRoundSliceExpansion`
fails the moment `round` / `princ-to-string` / `subseq` / `%string-concat` reappear in
the lowering, and that failure is the whole finding above coming back.

## Injection (compile path) and lazy load (interpreter)

`LispMacroExpander.expandTopLevelDefinitions` appends `FormatRenderer.defuns()`
at BOTH of its exits (`withFormatRenderer`), the definition-splicing one and the
early "nothing to splice" fast path -- the plainest runtime-control programs take
the fast path. The gate scans the program for (a) a renderer call already present
(the condition-report runtime injected just above, a spliced library), (b)
`#'format`, (c) a `(format ...)` call whose control the static path will decline
-- decided by running the SAME `FmtParser` the expansion runs, so the gate and
the expansion cannot answer differently -- and (d) a signal designator with a
computed datum and arguments after it (`signalRendersRuntimeControl`, repeating
`expandSignalDesignatorInner`'s case split, because the expansion it predicts has
not run yet). (d) is what carries the renderer for a `(warn ctrl a b)`: only
`error` also injects `%error-runtime`, whose body the (a) scan would have seen.

It has to be a scan of the pre-expansion program: expression expansion happens
per form much later (Pass 2) and cannot add a top-level defun. A program with no
way in carries none of the renderer.

The interpreter cannot inject top-level defuns at all, so `LispEvaluator`
evaluates the same forms into the global environment on the first resolution of
a `%FMT-` name (`ensureFormatRendererLoaded`, the `%condition-report-str`
pattern).

### The `~/name/` arm is injected SEPARATELY (todo-261)

`%fmt-user-function` and the designator resolution under it live in their own
resource, `format-render-slash.lisp`, and `withFormatRenderer` appends either it
or `format-render-slash-stub.lisp` -- never both, never neither, since
`%fmt-directive`'s `#\/` arm calls the name unconditionally.

**Why**: that arm resolves a function out of a CONTROL STRING -- runtime data --
which is precisely the condition under which `--optimize`'s funcall-dispatch gate
has to keep every function dispatchable (`.kb/optimize-dead-code-elimination.md`).
The renderer is spliced into every program that formats a computed control, so ONE
directive's arm was holding the gate open for every library program: with it
split out, a `(ql:quickload "split-sequence")` module went 619,722 -> 234,745
(**-62%**). Since the 2026-08-08 gate split (an `intern`/`find-symbol` no longer
bails by itself), the arm's presence is signalled to the gate by NAME:
`FormatRenderer.FUNCTION_DESIGNATOR` (`%fmt-function-designator`, defined only by
the real arm, never by the stub) is in `RuntimeNameProducers`' trigger set, so
injecting the arm still keeps every function dispatchable -- by design, not as a
side effect of the operators inside it.

**The gate**: `FormatRenderer.namesFunctionDesignator` -- does any STRING LITERAL
in the program (post-splice, post-prune) spell a `~/name/` directive -- plus
`--dynamic`, whose contract is that any name resolves at run time. A control
string is runtime data, so this is the only honest question the source can
answer; a control ASSEMBLED at run time out of pieces that never spell the
directive gets the stub, which signals (the `.kb/clack.md` call-time-error
policy). Compile-path only: the interpreter loads the real arm always, having a
live symbol table and nothing to dead-code eliminate.

`FormatRenderer.functionDesignatorNames` is the ONE scanner behind both the gate
and `LibraryDefunPruner`'s "a `~/name/` is a function reference" rule -- shared so
that "the pruner kept this function" and "the arm that calls it was injected"
cannot disagree.

**The stub's message must contain no TILDE.** A signalled condition carries the
rendered text in `format-control`, and printing the condition renders that text
AGAIN as a control string (`%format-condition`), so spelling the directive in the
message made reporting the error re-enter the stub -- outside the `handler-case`
that caught the first one, i.e. a trap. Pinned by
`JvmLispCompilerTest.formatUserFunctionDirectiveSignalsWhenTheCompileNeverSawTheDirective`
(and its WASM twin), which reads the message back through `~a` of the condition.

## Why the renderer is Lisp source

`FormatRenderer` reads `format-render.lisp` with the real `LispReader`. That is
the reason the `macro` package sits ABOVE `reader` in the dependency order (see
CLAUDE.md): an expander pass may BUILD the AST it injects by reading Lisp instead
of assembling `LispCons` nodes in Java. Before that the renderer was a
hand-assembled lambda inlined at each call site, and it understood `~~ ~% ~a ~s
~d ~x ~c` only -- every other directive was emitted LITERALLY while its argument
was still consumed, so the tail of a mixed control string came out shifted
(todo-216). A full directive set was not writable in that form.

The definitions are many small defuns on purpose: one emitted WASM function body
must not grow without bound (`.kb/wasm-function-body-size.md`). The resource
needs a `resource-config.json` entry to survive native-image
(`NativeImageResourceConfigTest` enforces it).

Cost, measured 2026-07-31 on a three-`format` program: a program that does NOT
reach the renderer is byte-identical to a build that never knew about it (both
backends, verified by the stash dance). One that does grows by ~114 KB of wasm
(316 KB -> 430 KB), which is the whole directive set in one place and is not
tree-shakeable -- every arm is reachable from `%fmt-render`, since the control
string is only known at run time. The gate is what keeps every other program at
zero. The `~/name/` arm is the one exception, and it is separated at INJECTION
time rather than by the shaker (the section above): what made that arm worth
separating was never its size, it was the funcall-dispatch gate it held open.

## The argument list is a materialized VECTOR, not a list the renderer indexes

**Invariant: inside the renderer `all` is not the argument list -- it is the pair
`(list <the list as given> <a vector of it, or nil>)` that `%fmt-args` builds once
per rendering LEVEL, and every read of it goes through `%fmt-arg` / `%fmt-count`.
A read the vector cannot serve falls back to the very `(nth i list)` /
`(length list)` the renderer used to make, so no answer moves.**

Measured 2026-08-31. The renderer read its arguments with `(nth i all)` per
directive, and `%fmt-iterate-list` / `%fmt-iterate-args` called `(length items)`
TWICE per pass -- so `(format nil "~{~a~}" <n-element list>)` was quadratic in `n`
on every path that reaches the renderer (the interpreter always; the compilers
whenever the control string is computed).

**A cursor was the wrong shape here, and that is the finding.** The four
directives that reposition the argument pointer -- `~*` forward, `~:*` backward,
`~n@*` absolutely, plus `~?` / `~{` recursing with their own -- make the access
pattern genuinely RANDOM, and a monotone cons cursor (the shape the rest of the
`elt`-per-element family took, `.kb/seq-coerce-runtime.md`) cannot serve it
without a re-seed whose cost is the walk it was meant to remove. One O(n) pass up
front buys O(1) reads for the whole level instead, which is what a random pattern
wants. Measured first: at n = 2000 the two `(length items)` calls per pass were
57% (wasm-GC) to 78% (JVM) of the whole call, the `nth` 13-19%, and the renderer's
own per-pass cost 3-5%.

- `%fmt-args` walks the argument once. **Only a PROPER list is materialized**, and
  only when it is long enough to pay for the vector (8 elements today, a cost
  threshold and nothing else -- the two paths answer identically). A dotted list,
  a non-sequence, a string, a vector and a short list all get `(list x nil)` and
  keep the walk.
- `%fmt-arg` consults the vector only for an index INSIDE it; a negative index and
  one past the end fall through to `(nth i list)`, which is what reproduces NIL
  past the end and whatever `nth` does with a negative index. `%fmt-count`
  likewise falls back to `(length x)`, so `(format nil "~{~a~}" 5)` still renders
  nothing because `(length 5)` is 0.
- The pair is built again for each NESTED level -- `~{`'s one list argument,
  `~:{`'s and `~:@{`'s per-pass sublist, a logical block's list argument, `~?`'s
  argument list through `%fmt-render` -- so a sublist is materialized when it is
  visited and not before.

**The iteration directives also collect their pieces and join them once.**
`%fmt-iterate-list` / `%fmt-iterate-args` grew one accumulator with
`(%fmt-cat acc piece)` per pass, which rebuilds the whole text every pass and is
quadratic in the OUTPUT even after the reads are O(1). `%fmt-join` halves the
piece list until one string is left: O(n log n) characters copied, no mutable
buffer (the renderer has none on any backend), and the same `%fmt-cat` underneath.

### The ladder

`(format nil "~{~a~}" <n-element list>)`, Apple M4 Max, one locked acquisition,
before and after in the same run, each row its own `defun`. **ms per call.**

| n | 250 | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: | ---: |
| JVM `.class` before | 0.285 | 0.880 | 3.47 | 12.62 | **54.8** |
| JVM `.class` **after** | 0.028 | 0.055 | 0.071 | 0.145 | **0.306** |
| WASM p1 before | 0.340 | 1.115 | 5.53 | 49.3 | **169.3** |
| WASM p1 **after** | 0.137 | 0.269 | 0.549 | 1.103 | **2.231** |
| `--component` before / after | 0.343/0.136 | 1.125/0.270 | 5.61/0.549 | 49.3/1.108 | 169.8/**2.225** |
| interpreter before | 5.76 | 12.08 | 26.6 | 64.4 | **164.9** |
| interpreter **after** | 5.86 | 11.68 | 23.4 | 46.7 | **93.2** |

**179x on the JVM and 76x on wasm-GC at n = 4000**, and every after-row DOUBLES
per doubling of n where every before-row quadrupled -- the wasm ladder reads
0.137 / 0.269 / 0.549 / 1.103 / 2.231, which is the class. `~:{` over 2,000
sublists moves with it: 14.06 -> **0.40** (JVM), 59.6 -> **2.38** (wasm p1),
113.1 -> **91.5** (interpreter). The interpreter's residue is the tree-walking
evaluator's per-node cost over the renderer's own `defun`s -- it is LINEAR now
(5.86 / 11.68 / 23.4 / 46.7 / 93.2 doubles cleanly) where it was not.

### What it costs

A short argument list pays one extra walk, one cons and a `%fmt-arg` call per
read for a vector it never builds. Each row in its own `defun`, ms per call:

| row | interpreter | JVM | WASM p1 |
| --- | --- | --- | --- |
| `(format nil "~a ~a" 1 2)`, computed control | 0.0398 -> 0.0425 (+7%) | 0.0003 -> 0.0003 | 0.0008 -> 0.0010 |
| the same with 8 arguments (the threshold) | 0.1435 -> 0.1545 (+8%) | 0.0005 -> 0.0006 | 0.0030 -> 0.0034 |
| `(format nil "~{~a~}" <8-element list>)` | 0.221 -> 0.243 (+10%) | 0.0010 -> 0.0009 | 0.0050 -> 0.0052 |
| `(mapcar #'1+ ...)` -- untouched, the control | 0.0500 -> 0.0500 | 0.0250 -> 0.0250 | 0.0250 -> 0.0250 |

7-10% on the interpreter for a short `format`, nothing measurable on the compile
paths, and the control row is identical to three digits on all three -- which is
what says the rest of the table is the change and not the harness.

### Proving the answers did not move

11,248 comparisons, zero divergence: the same generated program run on the jar
built from the parent commit and the jar built from this one, output diffed byte
for byte per backend. 2,510 cases x 4 backends -- a deterministic LCG over 79
control strings x 35 argument sets, with the full cross product of every
iteration and repositioning directive, argument lists of 0, 1, 2, 3, 7, 8, 9, 12,
20 and 40 elements (straddling the threshold in both directions), sublists,
strings, vectors, characters, floats, nils and non-sequences -- plus 604 cases x 2
backends for the shapes that trap uncatchably on wasm before this change and
after it (a string or a vector where a directive indexes a sequence, a dotted
argument list, a sublist of non-lists, `~/name/` against the stub). The three
`~{`-family sites, `%fmt-value`, `%fmt-recursive`, `%fmt-plural`, the `~[` arms,
`%fmt-escape`, `~#`, `~v`, the logical block and `format-render-slash.lisp`'s
`~/name/` arm all read through the pair.

**Re-evaluation trigger:** the pair is built by `%fmt-args` and read by
`%fmt-arg` / `%fmt-count` and by NOTHING else. A new directive arm that reads
`all` must use those three; spelling `(nth i all)` again would read the wrapper
cons rather than an argument, and the renderer never signals, so it would answer
quietly wrong text. The `%fmt-cat`-per-character accumulation inside `%fmt-run`
itself is still quadratic in the CONTROL STRING's length -- irrelevant at the
length control strings have, and the reason the iteration loops (whose
accumulator grows with the DATA) were the ones changed.

## Deliberate divergences, and why

- **The renderer never signals.** A malformed control, an unknown directive, an
  unterminated `~{`, a missing argument all render as text (`NIL` for the missing
  argument). The literal path signals the same problems at EXPANSION time, which
  is a compile-time diagnostic. Reason: a runtime control usually arrives with
  the data being reported -- a condition report must not fail while reporting.
  Do not "fix" this by signalling; it would put a crash inside the error path.
- **The renderer never signals -- with ONE exception, the absent `~/name/` arm.**
  That stub is not bad input to a working renderer, it is a capability the
  artifact does not contain, and rendering the directive as text would drop a
  report's payload with nothing for the user to search for. See the arm's section
  above; the tension with the rule right above is deliberate and bounded to that
  one case.
- **`~t`, `~p`, `~<...~>` and `~/name/` are renderer-only.** The static path
  declines them and falls back, so all four work either way; the fallback is what
  makes that acceptable. If the static path ever grows them, drop them from this
  list, not from the renderer. The `staticAndRuntimeRenderingAgree` table carries
  rows for the logical-block family anyway, so a future static implementation has
  to match the renderer rather than invent its own answer.
- **`~<...~>` is JUSTIFICATION, `~<...~:>` a LOGICAL BLOCK, and the closing
  directive is what decides.** The SECTION rules are real: a justification's `~;`
  segments consume arguments in turn; a logical block's first section is the
  prefix (a `~@;` separator makes it a per-line prefix, the same text without line
  breaks) and, with three sections, the last is the suffix, neither consuming an
  argument; a block WITHOUT `@` takes one argument -- a LIST -- as its whole
  argument list, which is why esrap's
  `(format s "~2@T~<~@;~A~:>" (list line))` prints the line and not the list. What
  does NOT happen is the LAYOUT: no padding to `:mincol`, no wrapping at the right
  margin, and only the MANDATORY conditional newline (`~:@_`, gated on
  `*print-pretty*`) breaks a line -- deciding the other three needs the stream's
  current column. `~i` is inert for the same reason. Full reasoning and the
  re-evaluation trigger: `.kb/pretty-printer.md`.
- **`~/name/` resolves the name as if by `find-symbol`, INTERNAL spelling first.**
  `:` and `::` are equivalent in this directive (CLHS 22.3.5.4), and a library
  rarely exports the function it names in one, so `%fmt-function-designator` tries
  `find-symbol`'s answer, then `PKG::NAME`, then `PKG:NAME`, picking the first that
  is `fboundp`. It also opens and closes its string stream by hand rather than with
  `with-output-to-string`: the WASM exception-handling gate scans the program for a
  `with-*` form, and the renderer is spliced into every program that formats a
  computed control -- one `with-output-to-string` here would put a tag section into
  modules that catch nothing. **A `~/name/` is a function REFERENCE, and the only
  trace of one**: `LibraryDefunPruner.formatFunctionNames` scans string literals for
  it, or the tree-shaker would delete the very function the report calls.
- **`~r` without a radix prints decimal digits.** English cardinals/ordinals are
  not implemented on either path.
- **`~W` renders as `prin1`, and never reads `*print-escape*`.** CL defines it as
  `write` of the argument under the CURRENT printer variables -- i.e. `princ` when
  `*print-escape*` is nil -- and both paths render `prin1-to-string` unconditionally
  instead. The reason is that the static path expands in Pass 2, AFTER the scan
  that decides which printer-mode `defvar`s a compiled program gets
  (`injectMvSpillGlobal`), so a `*print-escape*` read there would need its own scan
  trigger the way `print-unreadable-object` and the `write-to-string` keywords
  (`LispMacroExpander.mentionsPrinterVariable`, 2026-09-02) have one -- that helper
  is the shape to extend when this is done. (`write-to-string` itself no longer
  answers the same way: with `:escape` / `:readably` it lowers to the escape-picking
  conditional, `.kb/pretty-printer.md`.) Its modifiers bind variables of which
  `~@W`'s two now change text (`*print-level*`/`*print-length*` nil would UNDO an
  enclosing truncation) but are still not bound here, so all three spellings are the
  one call, and it takes no prefix parameters; `.todo/041` owns both reads. It DOES honor `*print-case*` (2026-08-15,
  `.todo/041`): the case seam rewrites the `prin1-to-string` it lowers to, on both
  renderings, so a `~W` under a `:downcase` binding prints lower case like every other
  printing operator -- the gap left is the escape pair. **Re-evaluation trigger**: when
  the printer entry points honor the control variables past `write`'s own keywords, `~W`
  has to consult `*print-escape*`/`*print-readably*` exactly as `write` does -- and the
  static path then owes the scan trigger above. Pinned by the `~w` rows of
  `FormatRendererTest.staticAndRuntimeRenderingAgree` and the
  `format-directive-write` ci-spec case (all four backends).
- **`~&` measures the column from the text rendered so far** (an empty
  accumulator counts as the start of a line), because the renderer answers a
  string and cannot see the stream's column. The literal path's `t` destination
  uses the real column. Same approximation the literal path already documents for
  a nil destination.
- **`~x`/`~o`/`~b`/`~r` answer UPPERCASE digits** on both paths, as Common Lisp
  does. The old cut-down fallback answered lowercase, which is why cl-who's
  numeric entity (`&#x~x;`) changed case when the renderer landed
  (`ClWhoE2eTest`).
- **A signal's runtime control renders EAGERLY, into the message** -- the
  condition a handler sees carries the rendered text in `format-control` and nil
  `format-arguments`, exactly as the literal-control designator has always built
  it. `.kb/error-handling.md` ("the string designator renders eagerly") has the
  reason and the one observable consequence; the point here is that BOTH paths
  deviate the same way, so the two spellings of one signal cannot drift.


## The float printer: one Schubfach selection on all four backends (todo-431)

Free-format float text (`print`/`princ`/`prin1`, `princ-to-string`, `format ~A`/`~S`,
the array printers, JSON output through the jzon shim) is **byte-identical on all four
backends**: the shortest decimal that reads back as the same IEEE value, chosen exactly
as `Double.toString` chooses it (Schubfach: fewest digits that round-trip, refined to a
two-digit minimum, closest to the value, ties to the even significand), spelled with
Java's notation thresholds (plain for decimal exponent -3..6, scientific otherwise)
and CL's lowercase exponent marker (`1.0e10`). The authority is
`am.ik.rontolisp.FloatText` (root package): `doubleText` = `Double.toString` + the
`E -> e` rewrite, `singleText` = `Float.toString` likewise.

- **Interpreter**: `LispDouble.print()` / `Environment.displayString`/`printString`
  call `FloatText`.
- **JVM**: the emitted `_lispToString` / `_lispToDisplayString` call
  `Double.toString` then `String.replace("E", "e")` -- the same text by definition.
- **WASM GC + `--no-gc`**: a hand-emitted Schubfach (`WasmSchubfachRuntimeBuilder`,
  shared bodies parameterized only by function indices and addresses): `_f64_dec`
  selects digits+exponent, `_dec_fmt` renders the spelling, with `_schub_umulhi` /
  `_schub_g` / `_schub_rop` beneath. The 617-entry 126-bit power table is compressed
  to a **~755-byte blob** (`SchubfachTables`): every 27th entry exactly, a `5^j`
  multiplier table, and a 2-bit correction per entry **derived at emit time by
  comparing a bit-exact replay of the runtime recomposition against the exact
  BigInteger value** -- the build fails if a correction ever leaves 2 bits, so the
  compressed table cannot drift from the exact one. `SchubfachTables` also carries a
  Java mirror of the precise u64 instruction sequence the wasm bodies perform;
  `SchubfachTablesTest` pins that mirror against `FloatText` over millions of values
  (exhaustive tiny denormals included -- the region where the JDK's two-digit
  refinement differs from a naive shortest selection), and the per-backend corpus
  tests + the `ieee-float-shortest-round-trip-printing` ci-spec case pin the
  transcription.

**Single-float width**: scalars are all `double-float` (no single scalar exists), but a
packed `#f(...)` array element prints at its **f32** width (`FloatText.singleText`,
`_f32_dec` on wasm, a transient `Float`/`TYPE_F32BOX` box on the JVM/GC print path) so
`#f(0.1)` round-trips instead of showing the widened double's 17 digits. `aref` still
answers the widened double, whose own text is the f64 spelling of that value -- the
width lives in the array, not in the scalar.

**Size**: the printer runtime measured on a program printing one computed f64 at
`--optimize`: `_dec_fmt` 915 B, `_f32_dec` 589 B, `_f64_dec` 524 B, `_schub_g` 304 B,
`_schub_umulhi` 106 B, `_print_f64_no_nl` 90 B, `_schub_rop` 71 B,
`_print_f32_no_nl` 62 B, `_write_dec` 23 B -- ~2.7 KB of code plus the 755-byte table,
vs the 379-byte (and wrong) digit extraction it replaced. The trade is recorded in
`size-report/`; correctness wins. The f32 half rides along whenever the generic
printer is reachable (its arm lives in `_print_val`/`_princ_val` like every other
type's); a program whose only prints are literals folds them to static text
(`WasmLiteralPrint` now folds floats too) and carries none of this.

**What this deliberately does not touch**: `~F`/`~$`/`~E` keep their own fixed-decimal
renderers above (a different question -- a REQUESTED digit count, not the shortest),
and `read`/`parse` stay on the eisel-lemire/parseDouble side.
