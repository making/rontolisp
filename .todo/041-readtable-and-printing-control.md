# Readtable and printing control (`*readtable*` + the reader-macro API, the remaining character I/O, the stream COLUMN, `pprint` layout)

Difficulty: High (the stream column touches every write primitive on four
backends; the readtable API needs a readtable-driven reader in the frontend AND
in the three embedded read runtimes)

**Status:** the printing half is done except for LAYOUT; the READTABLE half is
still a set of no-op stubs. `.kb/pretty-printer.md` owns what is real and what is
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
`:upcase` stub, todo-195), and **`*print-case*` (2026-08-15, all four backends,
`.kb/pretty-printer.md`)** -- the piece rove needed (`.todo/372` row 12).

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
- **`write-to-string` keywords + `write`'s missing keywords** -- MEASURED
  2026-09-01 on the installed interpreter binary, correcting the "full set minus
  `:case`" this bullet used to claim: `write` ACCEPTS `:stream` `:escape`
  `:readably` `:pretty` `:circle` `:right-margin` `:lines` `:miser-width`
  `:pprint-dispatch` and REJECTS `:length` `:level` `:gensym` `:case` `:base`
  `:radix` `:array` with `Unknown keyword argument`. All seven rejected
  keywords have a printer variable that already exists and already holds the
  right default, so the gap is the argument list, not the renderer -- except
  that binding those variables must then also CHANGE the output, which today it
  does not for `*print-length*` / `*print-level*` / `*print-base*` /
  `*print-radix*` / `*print-gensym*` / `*print-array*` (see the row below).
  `write-to-string` still takes one argument
  (`.kb/pretty-printer.md` has both reasons: the `with-output-to-string` lowering
  flips the WASM EH gate, and adding `:case` to the prelude `write` would make
  every `write` user MENTION `*print-case*` and so pull the case renderer into
  modules that never bind it). **Consumer (2026-08-15, `~W` `.todo/381`)**: the
  `format` directive `~W` is `write` of its argument and renders as
  `prin1-to-string` on both paths for the same reason -- it DOES honor
  `*print-case*` now (it lowers to `prin1-to-string`, which the case seam
  rewrites), but still not `*print-escape*` / `*print-readably*`; whatever teaches
  `write-to-string` to read them owes `~W` the same read (`.kb/format.md` carries
  the trigger, including the `injectMvSpillGlobal` scan the static path needs
  before it can read a printer variable).
- **Six printer variables are settable but inert** (measured 2026-09-01, same
  run): `*print-length*` `*print-level*` `*print-base*` `*print-radix*`
  `*print-gensym*` `*print-array*` all read back the value bound to them and
  none of them changes what the printer emits.

  ```lisp
  (let ((*print-length* 3)) (print '(1 2 3 4 5 6)))    ; SBCL: (1 2 3 ...)
  (let ((*print-level* 2))  (print '(1 (2 (3 (4))))))  ; SBCL: (1 (2 #))
  (let ((*print-base* 16))  (print 255))               ; SBCL: FF
  (let ((*print-base* 16) (*print-radix* t)) (print 255)) ; SBCL: #xFF
  (let ((*print-gensym* nil)) (print (list (gensym "G"))))  ; SBCL: (G114)
  (let ((*print-array* nil)) (print #(1 2 3)))         ; SBCL: #<(SIMPLE-VECTOR 3) ...>
  ```

  `*print-case*` / `*print-escape*` / `*print-readably*` / `*print-pretty*` do
  change the text, as this file's header says -- so the seam exists and these
  six are not on it. `*print-length*` and `*print-level*` are the two worth
  doing first: they are the only defence a REPL or a debugger has against a
  circular or enormous structure, and they cost one depth counter and one
  element counter in the same walk. Do them with the `write` keywords above,
  which is where a caller reaches for them.
- **`*print-case*` inside a container whose rendering is a runtime form** -- a
  symbol nested in a structure, a CLOS instance, a hash table or an array of rank
  != 1 keeps its stored spelling (`%print-cased` walks symbols, conses and
  vectors). Re-evaluate when such a rendering becomes reachable from Lisp: the
  walk gains a branch, nothing else moves.
- **`pprint-linear` / `pprint-tabular` / `pprint-fill` / `pprint-pop` /
  `pprint-exit-if-list-exhausted`** -- not defined; the first three are layout the
  column would decide, the last two are `pprint-logical-block` iteration.
- **The remaining print-control variables** -- `*print-base*` / `*print-radix*`
  (integer printing), `*print-length*` / `*print-level*` (truncation),
  `*print-circle*` (circle detection), `*print-gensym*`, `*print-array*`. Each
  holds the value the printer behaves as, so only a non-default binding is
  inert. `*print-base*` is the natural next one: it wants the same seam
  `*print-case*` uses, applied to the integer leaf instead of the symbol one.

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
