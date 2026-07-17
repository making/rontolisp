# Readtable and printing control (`*readtable*` + the reader-macro API, `peek-char`/`unread-char`/`write-char`, output flushing, the `*print-*` / stream variables, `pprint`)

**Status:** mostly not implemented. Low priority — readtable customization and
pretty printing are advanced features. Done since: `read-char`
(`LispNames.READ_CHAR`, in the `PackageRegistry` CL symbols;
`doc/en/reference/functions/read-char.md`), and the string-stream macros
`with-input-from-string` / `with-output-to-string` (see #36).

## What's missing

### Readtable system

| Operator | Purpose |
|----------|---------|
| `*readtable*` | Current readtable |
| `readtablep` | Predicate |
| `readtable-case` | Case mode (`:upper`, `:infer`, `:preserve`, `:normalize`) |
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

| Variable | Purpose |
|----------|---------|
| `*print-level*` | Max nesting depth |
| `*print-length*` | Max list elements |
| `*print-circle*` | Show circular structure |
| `*print-array*` | Print arrays fully |
| `*print-base*` | Integer base |
| `*print-case*` | `:upper`, `:downcase`, `:capitalize` |
| `*print-escape*` | Use escape sequences |
| `*print-readably*` | Readable output |
| `*print-gensym*` | Print gensyms specially |
| `*print-right-margin*` | Right margin |
| `*print-lines*` | Max lines |

### Pretty printing

| Operator | Purpose |
|----------|---------|
| `pprint` | Pretty print |
| `pprint-linear` | Linear block |
| `pprint-tabular` | Tabular block |
| `pprint-indent` | Indent |
| `pprint-newline` | Newline |
| `pprint-fill` | Fill block |
| `pprint-tab` | Tab |
| `pprint-logical-block` | Logical block |
| `pprint-dispatch` | Custom dispatch |
| `set-pprint-dispatch` | Set dispatch table |

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
