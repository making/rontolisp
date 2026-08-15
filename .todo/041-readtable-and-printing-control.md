# Readtable and printing control (`*readtable*` + the reader-macro API, `peek-char`/`unread-char`/`write-char`, output flushing, the `*print-*` / stream variables, `pprint`)

**Status:** the READTABLE half is not implemented; the printing half largely is.
Low priority — readtable customization is an advanced feature. Done since:
`read-char` (`LispNames.READ_CHAR`, in the `PackageRegistry` CL symbols;
`doc/en/reference/functions/read-char.md`), the string-stream macros
`with-input-from-string` / `with-output-to-string` (see #36), and — with
`.todo/248` (esrap) — the whole printer surface below: `write`, `pprint`, the
pprint DISPATCH tables, `pprint-logical-block` / `pprint-newline` /
`pprint-indent` / `pprint-tab`, every `*print-*` control variable and the four
remaining standard stream variables (`*trace-output*` / `*debug-io*` /
`*query-io*` / `*terminal-io*`), plus the format logical block `~<...~:>` and
`~/name/`. **`.kb/pretty-printer.md` owns what is real and what is not**: every
variable holds the value the printer actually behaves as, but no stream carries a
COLUMN, so nothing wraps and every conditional line break is a no-op. That one
missing field is what is left of the "pretty printing" item here.

## Still open after `.todo/248`

- **A column on the stream** — the one change that turns `*print-right-margin*` /
  `*print-miser-width*` / `*print-lines*`, `pprint-newline`'s three conditional
  kinds, `pprint-indent`/`pprint-tab`, `~_`/`~i` and justification's `:mincol`
  padding from no-ops into the real thing, all at once. See the re-evaluation
  trigger in `.kb/pretty-printer.md`.
- **The printing operators consulting `*print-pprint-dispatch*`** — wants the same
  seam (`%print-object-str`, `.kb/clos.md`) widened; today an entry fires only
  where the program calls the entry function itself.
- **`write-to-string` keywords** — `write` takes the full set, `write-to-string`
  still takes one argument (`.kb/pretty-printer.md` has the reason). **Consumer
  (2026-08-15, `~W` `.todo/381`)**: the `format` directive `~W` is `write` of its
  argument, and it renders as `prin1-to-string` on both paths for the same reason
  — so whatever teaches `write-to-string` to read `*print-escape*` /
  `*print-readably*` owes `~W` the same read (`.kb/format.md` carries the
  re-evaluation trigger, including the `injectMvSpillGlobal` scan the static path
  needs before it can read a printer variable).
- **`pprint-linear` / `pprint-tabular` / `pprint-fill` / `pprint-pop` /
  `pprint-exit-if-list-exhausted`** — not defined; the first three are layout the
  column would decide, the last two are `pprint-logical-block` iteration.

## What's missing

### Readtable system

| Operator | Purpose |
|----------|---------|
| `*readtable*` | Current readtable |
| `readtablep` | Predicate |
| `readtable-case` | DONE (2026-07-28, todo-195): a constant-`:upcase` stub on every backend |
| `set-dispatch-macro-character` | Dispatch macro |
| `set-macro-character` | Single-character macro |
| `get-macro-character` | Query macro char |
| `copy-readtable` | Copy readtable |

### Character I/O

| Operator | Purpose |
|----------|---------|
| `unread-char` | Push back character |
| `peek-char` | Look ahead |
| `listen` | Check if input available |
| `read-char` (done) | Already implemented |
| `read-char-no-hang` | Non-blocking read |
| `write-char` | Write single char |
| `fresh-line` (done) | Already implemented |

### Stream flushing

| Operator | Purpose |
|----------|---------|
| `finish-output` | Flush and wait |
| `force-output` | Flush without waiting |
| `clear-input` | Clear input buffer |

### Standard stream variables

| Variable | Purpose |
|----------|---------|
| `*standard-output*` | Default output stream |
| `*error-output*` | Error output |
| `*query-io*` | Query I/O |
| `*debug-io*` | Debug I/O |
| `*terminal-io*` | Terminal I/O |
| `*trace-output*` | Trace output (for `trace`, `time`) |
| `*standard-input*` | Default input stream |

### Print control variables

All of these EXIST since todo-248, each holding the value the printer actually
behaves as; only `*print-escape*` / `*print-readably*` / `*print-pretty*` change
anything when BOUND (`.kb/pretty-printer.md` has the table).

| Variable | Purpose |
|----------|---------|
| `*print-level*` | Max nesting depth (nil = no truncation, which is the behavior) |
| `*print-length*` | Max list elements (nil = no truncation, which is the behavior) |
| `*print-circle*` | Show circular structure (no circle detection) |
| `*print-array*` | Print arrays fully (t = the behavior) |
| `*print-base*` | Integer base (10 = the behavior) |
| `*print-case*` | `:upcase` = the behavior. **Consumer (2026-08-15, rove `.todo/372`)**: `(let ((*print-case* :downcase)) (princ-to-string name))` is how rove names every test (`deftest` expansion, `run*` patterns) -- SBCL prints `add-test`, we print `ADD-TEST`, so a rove report differs from every other implementation's line for line. `:downcase`/`:capitalize` in `princ`/`prin1`/`~A`/`~S`/`write-to-string`/`princ-to-string`, all four backends, is the piece rove needs |
| `*print-escape*` | DONE (honored) |
| `*print-readably*` | DONE (honored) |
| `*print-gensym*` | Print gensyms specially (t = the behavior) |
| `*print-right-margin*` | Right margin (inert: no column) |
| `*print-lines*` | Max lines (inert: no column) |

### Pretty printing

| Operator | Purpose |
|----------|---------|
| `pprint` | DONE (todo-248) |
| `pprint-linear` | Linear block |
| `pprint-tabular` | Tabular block |
| `pprint-indent` | DONE (todo-248; a no-op, no column) |
| `pprint-newline` | DONE (todo-248; only `:mandatory` breaks a line) |
| `pprint-fill` | Fill block |
| `pprint-tab` | DONE (todo-248; a no-op, no column) |
| `pprint-logical-block` | DONE (todo-248; never wraps) |
| `pprint-dispatch` | DONE (todo-248) |
| `set-pprint-dispatch` | DONE (todo-248) |

### Implementation approach

**Print control** (highest ROI):
1. `*standard-output*`, `*standard-input*`, `*error-output*` — stream variables.
2. `*print-base*` — affect integer printing.
3. `*print-case*` — affect symbol/string case in output.
4. `write-char` — character-level output (`read-char` is done).
5. `finish-output`, `force-output` — stream flushing.

**Readtable** (deferred):
6. Custom readtables require threading the readtable through the reader.
7. The current reader has a fixed configuration.

**Pretty printing** (lowest priority):
8. Full pretty printing is a large system.

### Related

- `[[036-io-extensions]]` (stream I/O)
- `[[038-symbol-and-package-extensions]]` (symbol printing)
