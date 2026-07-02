# I/O extensions (`with-input-from-string`, `with-output-to-string`, `write`, `write-string`, `write-to-string`, `file-length`, `file-position`, `probe-file`, `delete-file`, `rename-file`, `directory`, `make-pathname`, `merge-pathnames`, `pathname`, `translate-logical-pathname`, `logical-pathname`)

**Status:** not implemented. Medium priority — extends file and string I/O.

## What's missing

RontoLisp has `print`, `prin1`, `princ`, `terpri`, `fresh-line`, `read-line`, `read`, `read-from-string`, `parse-integer`, `write-line`, `open`, `close`, `with-open-file`, `prin1-to-string`, `princ-to-string`. The following I/O operators are absent:

### Missing string I/O

| Operator | Kind | Purpose |
|----------|------|---------|
| `with-input-from-string` | Macro | Read from string as stream: `(with-input-from-string (s "1 2 3") (read s))` |
| `with-output-to-string` | Macro | Write to string: `(with-output-to-string (s) (princ "hello" s))` |
| `with-open-stream` | Macro | Generic stream wrapper |

### Missing output functions

| Operator | Kind | Purpose |
|----------|------|---------|
| `write` | Function | Full printing with `:escape`, `:readably`, `:pretty`, etc. |
| `write-string` | Function | Write string to stream |
| `write-to-string` | Function | Write object to string |
| `format` (output to stream) | Macro | Partially done; `format nil` and `format t` work |

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
| `merge-pathnames` | Function | Default pathname merging |

### Implementation approach

**String I/O** (highest ROI):
1. `with-input-from-string` — macro wrapping a string-backed stream (like `with-open-file` pattern).
2. `with-output-to-string` — macro wrapping a string-builder stream.
3. Both backends already have `read` and print functions; they just need stream abstraction.

**Output functions**:
4. `write-string` — write string to stream (or `*standard-output*`).
5. `write-to-string` — `write` + capture to string.
6. `write` — full printing with `:escape`/`:readably`/`:case` etc.

**File system** (JDK has `java.nio.file` for JVM; WASI has path ops for component mode):
7. `probe-file`, `delete-file`, `rename-file`, `file-length` — straightforward on JVM.
8. WASM Preview 1: limited (no directory ops). Component mode: WASI filesystem.

**Pathname** (lowest ROI for a Lisp-2 minimal implementation):
9. Full pathname system is large. Consider a string-based pathname first.

### Related

- `[[32-multiple-value-system]]` (`delete-file`, `rename-file`, `file-position` return multiple values)
- `[[31-lambda-list-extensions]]` (`write`, `file-position` use `&optional`/`&key`)
