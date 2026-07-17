# I/O extensions (`write`, `file-length`, `file-position`, `probe-file`, `delete-file`, `rename-file`, `directory`, `make-pathname`, `merge-pathnames`, `pathname`, `translate-logical-pathname`, `logical-pathname`)

**Status:** string I/O DONE (2026-07-05, Phase 3 unit 5 -- see
`.kb/read-load-streams.md`): `with-output-to-string`, `with-input-from-string`,
`write-string`, `write-to-string`, print-family optional stream args, and
`format` stream destinations are implemented on all three backends. The rest
(file-system, pathname, full `write`) is not implemented. Medium priority.

## What's missing

RontoLisp's I/O surface is broad: `print`, `prin1`, `princ`, `terpri`,
`fresh-line`, `read-line`, `read`, `read-char`, `read-byte`, `read-sequence`,
`read-from-string`, `parse-integer`, `write-line`, `write-string`, `write-byte`,
`write-sequence`, `write-to-string`, `open`, `close`, `streamp`,
`with-open-file`, `with-output-to-string`, `with-input-from-string`,
`prin1-to-string`, `princ-to-string`, plus the `rontolisp:` stream API
(`make-stream`, `stream-read`, `stream-write`, `stream-close`, `read-all`). What
is absent is the file-system / pathname layer and the full `write`:

### Missing string I/O

| Operator | Kind | Purpose |
|----------|------|---------|
| `with-open-stream` | Macro | Generic stream wrapper |

### Missing output functions

| Operator | Kind | Purpose |
|----------|------|---------|
| `write` | Function | Full printing with `:escape`, `:readably`, `:pretty`, etc. |

### Missing file system functions

| Operator | Kind | Purpose |
|----------|------|---------|
| `file-length` | Function | File size in bytes |
| `file-position` | Function | Get/set stream position |
| `probe-file` | Function | Check if file exists |
| `delete-file` | Function | Delete a file |
| `rename-file` | Function | Rename/move a file |
| `directory` | Function | List directory contents with wildcards |

### Missing pathname system

| Operator | Kind | Purpose |
|----------|------|---------|
| `make-pathname` | Function | Construct pathname |
| `merge-pathnames` | Function | Merge pathnames |
| `pathname` | Function | Convert to pathname |
| `pathname-host` | Function | Accessor |
| `pathname-device` | Function | Accessor |
| `pathname-directory` | Function | Accessor |
| `pathname-name` | Function | Accessor |
| `pathname-type` | Function | Accessor |
| `pathname-version` | Function | Accessor |
| `pathnamep` | Function | Pathname predicate |
| `wild-pathname-p` | Function | Check for wildcards |
| `translate-logical-pathname` | Function | Logical pathname translation |
| `logical-pathname` | Function | Create logical pathname |

### Implementation approach

**Output functions**:
1. `write` — full printing with `:escape`/`:readably`/`:case` etc.
   (`write-to-string` exists as a plain `prin1-to-string` alias; a real `write`
   would extend both.)

**File system** (JDK has `java.nio.file` for JVM; WASI has path ops for component mode):
7. `probe-file`, `delete-file`, `rename-file`, `file-length` — straightforward on JVM.
8. WASM Preview 1: limited (no directory ops). Component mode: WASI filesystem.

**Pathname** (lowest ROI for a Lisp-2 minimal implementation):
9. Full pathname system is large. Consider a string-based pathname first.

### Related

- `[[032-multiple-value-system]]` (`delete-file`, `rename-file`, `file-position` return multiple values)
- `[[031-lambda-list-extensions]]` (`write`, `file-position` use `&optional`/`&key`)
