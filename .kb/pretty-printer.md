# The printer entry points and the pretty-printer subset

**Invariant: every CL printer-control variable EXISTS and holds the value the printer
actually behaves as, and the pretty-printing operators produce the text a wide enough
line holds -- identically on the interpreter, the JVM and both WASM GC backends.** What
they do NOT do is change the LAYOUT: no rontolisp stream carries a column, so nothing
wraps.

Pinned by `LispEvaluatorTest.evalWriteAndPprintDispatch` /
`evalPprintLogicalBlock`, the `esrap-enablement-language-group` ci-spec case (all four
backends end to end) and `EsrapE2eTest`, whose parse-error report is the real consumer.

Landed with `.todo/248` (esrap), which is also the only library in the loadable set that
uses any of it.

## What is real

- **`write`** -- the printer entry point, a `LispPreludeLibrary` defun. CL defines its
  keywords as BINDINGS of the printer control variables around one print, and that is
  literally the expansion: `(let ((*print-escape* escape) ...) (write-string (if ... )))`.
  Only `:escape` / `:readably` change the text (they pick between `prin1-to-string` and
  `princ-to-string`, the two conversions every backend has); the others are inert because
  the variable they bind is inert. **`write-to-string` did NOT gain the same keywords**:
  it is a per-backend primitive with its own compiler case, and the
  `with-output-to-string` the obvious lowering would want is exactly what must not appear
  in a shared source -- it flips the WASM exception-handling gate (see `.kb/format.md` on
  `~/name/`). Spell it `(with-output-to-string (s) (write x :stream s ...))`;
  `.todo/041` owns closing the gap.
- **`pprint`** -- a fresh line then `write` with `:escape t :pretty t`, returning no
  values.
- **The pprint DISPATCH tables** -- `copy-pprint-dispatch` / `set-pprint-dispatch` /
  `pprint-dispatch` over a real entry list with real `typep` matching and real priority
  ordering. A table is a one-element LIST holding its entries, so `set-pprint-dispatch`
  can mutate a table it was handed (`rplaca`) -- which is the whole point of the
  `(copy-pprint-dispatch)` + `set-pprint-dispatch` idiom esrap builds its result printer
  with. `pprint-dispatch` answers `#'%pprint-dispatch-default` (a `(stream object)`
  `write`) and `nil` when nothing matches, as CL specifies.
- **`pprint-logical-block`** -- a `LispMacroExpander` lowering (`expandPprintLogicalBlock`,
  in `CL_MACROS`, wired into the evaluator + both compilers + `FreeVarAnalyzer`): writes
  the prefix, runs the body, writes the suffix. CL's non-list rule is honored -- an ATOM
  is printed with `write` and the body is skipped -- which is what makes the macro safe to
  wrap around a value that may or may not be a list.
- **`pprint-newline` `:mandatory`** -- a real line break (gated on `*print-pretty*`), and
  so is the format directive `~:@_`.
- **`~<...~>` / `~<...~:>`** -- see `.kb/format.md`. The SECTION rules are real (a
  justification's `~;` segments consume arguments in turn; a logical block's first section
  is the prefix, its last the suffix when there are three, and a block without `@` takes
  ONE argument -- a list -- as its whole argument list).

## What a stream with no column cannot do, and the re-evaluation trigger

**Every conditional line break is a no-op** -- `pprint-newline` with `:linear` / `:fill` /
`:miser`, and the format directives `~_` / `~:_` / `~@_`; so is `pprint-indent`,
`pprint-tab` and `~i`. Deciding one needs the stream's current column, and a rontolisp
stream is an opaque integer handle with no column (`.kb/standard-output-redirect.md`);
`format`'s own `~&`/`~t` only approximate it by scanning the string built SO FAR, which a
logical block cannot do because the block is not the whole output. Consequently
`*print-right-margin*`, `*print-miser-width*` and `*print-lines*` are accepted and
ignored, and a justification never pads to `:mincol`.

**Re-evaluate when a stream gains a column.** That is one field on the stream object plus
a write-through update in every primitive that writes; with it, `pprint-newline` and the
`~_` family become one shared "does the rest fit before the margin" test and every
variable above starts working. Nothing in the loadable library set has needed it: esrap's
report is designed to read the same unwrapped, which is why its expected text in
`EsrapE2eTest` is byte-identical to SBCL's apart from character NAMES (below).

**The ordinary printing operators do NOT consult `*print-pprint-dispatch*`.** `princ` /
`prin1` / `print` / `~A` / `~S` are a per-backend primitive on the hottest path, and the
one hook above them -- `%print-object-str`, the `print-object` seam (`.kb/clos.md`) -- is
gated on the program defining a method and only sees the value the operator is HANDED,
not one nested inside a printed list. A dispatch table entry therefore fires only where
the program calls the entry function itself, which is what esrap does
(`(funcall (pprint-dispatch x) stream x)` under a rebound table). Re-evaluate together
with the column: both want the same seam.

**`char-name` answers nil for a graphic character**, which is CL. SBCL additionally
returns the Unicode NAME (`DIGIT_ZERO` for `#\0`), an extension; that is the only
difference between esrap's parse-error report here and on SBCL.

## The printer-control variables

`LispMacroExpander.PRINTER_MODE_VARS` is the single table: name -> global value. The
interpreter seeds it in `Environment.createGlobal` plus `LispEvaluator`'s special-variable
set (they are BOUND, not merely read, and only a proclaimed-special name gets a dynamic
binding); the compile paths get a top-level `(defvar name value)` from
`injectMvSpillGlobal` for each one the program MENTIONS, which runs after
`expandTopLevelDefinitions` so a reference the expansion itself created is in view.

| variable | value | honored? |
| --- | --- | --- |
| `*print-escape*` | `t` | yes -- picks prin1 vs princ, and the `print-object` route binds it |
| `*print-readably*` | `nil` | yes -- forces escaping |
| `*print-pretty*` | `t` | yes -- gates the MANDATORY line break |
| `*print-circle*` | `nil` | no (the printer does no circle detection) |
| `*print-right-margin*` / `*print-miser-width*` / `*print-lines*` | `nil` | no (no column) |
| `*print-length*` / `*print-level*` | `nil` | the value IS the behavior (no truncation) |
| `*print-base*` | `10` | the value IS the behavior |
| `*print-radix*` | `nil` | the value IS the behavior |
| `*print-case*` | `:upcase` | the value IS the behavior (`.kb/reader-case-upcase.md`) |
| `*print-array*` / `*print-gensym*` | `t` | the value IS the behavior |
| `*print-pprint-dispatch*` | a fresh empty table | entries and lookup, but see above |

Every default is what the printer ACTUALLY does, so a program that only READS one sees
the truth; binding one to a non-default value is what has no effect. `*print-level*` /
`*print-length*` are not decoration -- esrap's `print-object` on a parse result binds
both, so they have to exist on the compile paths or the module does not compile.

The four remaining standard STREAM variables (`*trace-output*`, `*debug-io*`,
`*query-io*`, `*terminal-io*`) ride the same table with the `t` designator
`*standard-output*` already holds. esrap's rule tracing formats to `*trace-output*` from
inside a closure, which is what forced them: a captured free variable must be a declared
global.
