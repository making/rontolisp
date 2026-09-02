# Readtable and printing control (`*readtable*` + the reader-macro API, the remaining character I/O, the stream COLUMN, `pprint` layout)

Difficulty: High (the stream column touches every write primitive on four
backends; the readtable API needs a readtable-driven reader in the frontend AND
in the three embedded read runtimes)

**Status:** the printing half is done except for LAYOUT (and the two `~W`
reads); the READTABLE half is still a set of no-op stubs. `.kb/pretty-printer.md` owns what is real and what is
not -- every printer-control variable holds the value the printer actually
behaves as, and `*print-escape*` / `*print-readably*` / `*print-pretty*` /
`*print-case*` are the four that change the text when bound.

Landed since this item was written: `read-char`, `peek-char` (+ `%peek-char`),
`write-char`, `listen` (interpreter/JVM only -- no WASM implementation outside a
`--component` socket stream), `force-output` / `finish-output` / `clear-output`,
`with-input-from-string` / `with-output-to-string`, every standard stream
variable (`*standard-output*` / `*standard-input*` / `*error-output*` are real
designators, `.kb/standard-output-redirect.md`; `*trace-output*` / `*debug-io*` /
`*query-io*` / `*terminal-io*` ride the printer-variable table), the whole
printer surface of `.todo/248` (`write`, `pprint`, the pprint DISPATCH tables,
`pprint-logical-block` / `pprint-newline` / `pprint-indent` / `pprint-tab`, the
format logical block `~<...~:>` and `~/name/`), `readtable-case` (a constant
`:upcase` stub, todo-195), **`*print-case*` (2026-08-15, all four backends,
`.kb/pretty-printer.md`)** -- the piece rove needed (`.todo/372` row 12) -- and
**(2026-09-02) `*print-length*` / `*print-level*` / `*print-gensym*` /
`*print-base*` / `*print-radix*` on the same walk, `write`'s full keyword set and
`write-to-string`'s keywords**, the pieces Practical Common Lisp's `ppme` needed
(`.kb/asdf.md`, "The _Practical Common Lisp_ book corpus", chapter 8): the seven
`write` keywords the measurement below listed as
rejected are accepted and all but `:array` change the text, SBCL-identical on all
four backends. The cost that used to argue against it is measured in
`.kb/pretty-printer.md` (a `write` user's `.wasm` grows by ~33 KB; everything else
is byte-identical).

## Still open

- **A column on the stream** -- the one change that turns `*print-right-margin*` /
  `*print-miser-width*` / `*print-lines*`, `pprint-newline`'s three conditional
  kinds, `pprint-indent`/`pprint-tab`, `~_`/`~i` and justification's `:mincol`
  padding from no-ops into the real thing, all at once. One field on the stream
  object plus a write-through update in every primitive that writes. See the
  re-evaluation trigger in `.kb/pretty-printer.md`.
- **The printing operators consulting `*print-pprint-dispatch*`** -- wants the
  `%print-object-str` / `%print-cased` seam (`.kb/clos.md`,
  `.kb/pretty-printer.md`) widened once more; today an entry fires only where the
  program calls the entry function itself. The `*print-case*` pass showed the
  shape: one shared prelude renderer under the print-object route, gated on a
  surface fact so every other program stays byte-identical.
- **The two `~W` reads** -- the `format` directive `~W` is `write` of its
  argument and renders as `prin1-to-string` on both paths: it honors
  `*print-case*` and now the truncation/base variables too (they all ride the
  `prin1-to-string` rewrite), but still not `*print-escape*` / `*print-readably*`,
  and `~@W` binds neither `*print-level*` nor `*print-length*` to nil.
  `.kb/format.md` carries the trigger; the shape is
  `LispMacroExpander.mentionsPrinterVariable`, which already counts a
  `write-to-string` keyword as a mention for the `injectMvSpillGlobal` scan the
  static path needs before it can read a printer variable -- a `~W` in a format
  string is the same kind of Pass-2 read.
- **`#'write-to-string` as a value on the compile paths** takes one argument (the
  `BuiltinFunctionWrappers` defun), so `(apply #'write-to-string (list x :length
  1))` ignores its keywords there and answers `"(1 2 3)"`; the interpreter's
  wrapper honors them. `.kb/pretty-printer.md` has the reason a keyword-taking
  wrapper is not worth it; re-evaluate if a library reaches for that shape.
- **`*print-array*` nil and `*print-circle*` t** stay inert (SBCL prints
  `#<(SIMPLE-VECTOR 3) {addr}>` and `#1=` labels); accepted and bound by `write`.
- **A printer-control variable inside a container whose rendering is a runtime
  form** -- a symbol nested in a structure, a CLOS instance, a hash table or an
  array of rank != 1 keeps its stored spelling and such a container's elements
  are never truncated or re-based (`%print-cased` walks symbols, conses and
  rank-1 general vectors). Re-evaluate when such a rendering becomes reachable
  from Lisp: the walk gains a branch, nothing else moves.
- **`pprint-linear` / `pprint-tabular` / `pprint-fill` / `pprint-pop` /
  `pprint-exit-if-list-exhausted`** -- not defined; the first three are layout the
  column would decide, the last two are `pprint-logical-block` iteration.

### Readtable system

| Operator | State |
|----------|-------|
| `*readtable*` | exists, always nil (an opaque token: there is no readtable OBJECT) |
| `copy-readtable` | a nil-returning no-op (arguments still evaluated) |
| `set-dispatch-macro-character` | a t-returning no-op |
| `readtable-case` | a constant `:upcase` stub (todo-195) |
| `readtablep` | MISSING |
| `set-macro-character` / `get-macro-character` | MISSING |

Custom readtables need the readtable threaded through the reader -- and the
current reader has a fixed configuration in FOUR places: the frontend
`LispReader` plus the embedded read runtimes of the JVM and both WASM backends.
The one known user syntax in the loadable set, ironclad's `#N@(...)` s-box
literal, is native in `LispLexer` instead. Deferred deliberately; a real
readtable is its own project, and nothing in the loadable library set has
needed one.

### Character I/O

| Operator | State |
|----------|-------|
| `read-char` / `peek-char` / `write-char` / `fresh-line` / `listen` | DONE (`listen`: interpreter + JVM) |
| `unread-char` | MISSING (wants a one-character pushback slot per input stream) |
| `read-char-no-hang` | MISSING |
| `clear-input` | MISSING (`clear-output` exists) |

## Related

- `[[038-symbol-and-package-extensions]]` (symbol printing)
