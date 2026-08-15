# Gray Streams (user-defined streams)

rontolisp ships its own small Gray-stream extension, mirroring how real
implementations expose their native Gray support: a user class extends one of
the `rontolisp:fundamental-*-stream` base classes and defines methods on the
`rontolisp:stream-*` generics, and the stream-taking built-ins dispatch to
those methods when handed such an instance instead of a stream handle. This
works on every backend (interpreter, JVM, both WASM variants).

The base classes form the CL-shaped hierarchy: `fundamental-stream` at the
root, `fundamental-input-stream` / `fundamental-output-stream` below it, and
`fundamental-character-input-stream` / `fundamental-character-output-stream` /
`fundamental-binary-input-stream` / `fundamental-binary-output-stream` as the
leaves (all in the `rontolisp` package).

| built-in | dispatches to |
| --- | --- |
| `write-char` | `rontolisp:stream-write-char` |
| `write-string`, `format` (stream destination) | `rontolisp:stream-write-string` |
| `princ`, `prin1`, `print` | `rontolisp:stream-write-string` of the rendered text (`print` then `stream-terpri`) |
| `terpri` | `rontolisp:stream-terpri` (default method writes a newline through `stream-write-char`) |
| `fresh-line` | `rontolisp:stream-fresh-line` (default method: `stream-terpri` unless `stream-start-line-p`) |
| `write-line` | `rontolisp:stream-write-string` then `rontolisp:stream-terpri` |
| `force-output` / `finish-output` / `clear-output` | `rontolisp:stream-force-output` / `-finish-output` / `-clear-output` (default methods answer `nil`) |
| `close` | answers `t` -- see below |
| `write-byte` | `rontolisp:stream-write-byte` |
| `read-byte` | `rontolisp:stream-read-byte` |
| `read-char` | `rontolisp:stream-read-char` |
| `read-line` | `rontolisp:stream-read-line` (default method loops `stream-read-char`) |
| `listen` | `rontolisp:stream-listen` (default method answers `nil`) |
| `read-sequence` / `write-sequence` | `rontolisp:stream-read-sequence` / `-write-sequence` (default methods loop the element generics) |
| `file-position` | `rontolisp:stream-file-position`; the two-argument form calls the `(setf rontolisp:stream-file-position)` writer generic |

A character output stream defines **`stream-write-char` or `stream-write-string`
-- either one is enough**. Each has a default method written in terms of the
other, so the rest of the output protocol composes out of whichever you wrote.
(Defining neither is the one broken shape: the two defaults then call each
other.)

Two more generics have no built-in of their own but are what the line-oriented
operators consult: `rontolisp:stream-line-column` answers the stream's current
column, or `nil` (the default) for a stream that tracks none, and
`rontolisp:stream-start-line-p` answers from it. A stream with no column cannot
tell whether it is at the start of a line, so `fresh-line` on it always writes
the newline. `rontolisp:stream-advance-to-column` rounds out the protocol for a
program that calls it directly.

Closing a Gray stream answers `t` and does nothing else -- there is nothing to
release. A stream that DOES hold something writes CL's own spelling, a method on
`close` itself:

```lisp
(defclass closing-stream (rontolisp:fundamental-character-output-stream)
  ((acc :initform "") (openp :initform t)))
(defmethod rontolisp:stream-write-char ((s closing-stream) c)
  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
  c)
(defmethod close ((s closing-stream) &key abort)
  (declare (ignore abort))
  (setf (slot-value s 'openp) nil)
  t)
(let ((s (make-instance 'closing-stream)))
  (write-string "bye" s)
  (list (close s) (slot-value s 'openp))) ; => (T NIL)
```

Such a method dispatches on every backend. A program that defines one owns
`close` outright: the Gray default steps aside for it.

On the read side the methods answer the keyword `:eof` at end of stream; the
built-ins translate that through the usual `eof-error-p` / `eof-value`
contract. `stream-read-line` answers a partial last line as that line — `:eof`
means "no characters left at all".

```lisp
(defclass upcase-stream (rontolisp:fundamental-character-output-stream)
  ((acc :initform "")))
(defmethod rontolisp:stream-write-string ((s upcase-stream) str)
  (setf (slot-value s 'acc)
        (concatenate 'string (slot-value s 'acc) (string-upcase str)))
  str)
(let ((s (make-instance 'upcase-stream)))
  (write-string "hello" s)
  (write-char #\! s)
  (slot-value s 'acc)) ; => "HELLO!"
```

A `stream-write-char`-only stream that tracks its column, so `fresh-line` can
tell whether it has to break the line:

```lisp
(defclass column-stream (rontolisp:fundamental-character-output-stream)
  ((acc :initform "") (col :initform 0)))
(defmethod rontolisp:stream-write-char ((s column-stream) c)
  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
  (setf (slot-value s 'col) (if (char= c #\Newline) 0 (+ (slot-value s 'col) 1)))
  c)
(defmethod rontolisp:stream-line-column ((s column-stream)) (slot-value s 'col))
(let ((s (make-instance 'column-stream)))
  (princ "one" s)
  (fresh-line s)      ; column 3 -> writes the newline
  (fresh-line s)      ; column 0 -> writes nothing
  (write-line "two" s)
  ;; newlines shown as / so the whole answer fits on one line
  (substitute #\/ #\Newline (slot-value s 'acc))) ; => "one/two/"
```

A binary input stream with the `file-position` protocol:

```lisp
(defclass byte-source (rontolisp:fundamental-binary-input-stream)
  ((items :initarg :items) (pos :initform 0)))
(defmethod rontolisp:stream-read-byte ((s byte-source))
  (let ((items (slot-value s 'items)) (pos (slot-value s 'pos)))
    (if (>= pos (length items))
        :eof
        (progn (setf (slot-value s 'pos) (+ pos 1)) (nth pos items)))))
(defmethod rontolisp:stream-file-position ((s byte-source)) (slot-value s 'pos))
(defmethod (setf rontolisp:stream-file-position) (position (s byte-source))
  (setf (slot-value s 'pos) position))
(let ((in (make-instance 'byte-source :items (list 10 20 30))))
  (read-byte in)                          ; 10
  (file-position in)                      ; 1
  (file-position in 0)
  (list (read-byte in) (read-byte in nil :done))) ; => (10 20)
```

## The trivial-gray-streams shim

Portable libraries are written against
[trivial-gray-streams](https://github.com/trivial-gray-streams/trivial-gray-streams)
rather than an implementation's own protocol. rontolisp bundles a built-in
`trivial-gray-streams` ASDF system adapting the portable API onto the protocol
above (see [Systems](asdf-systems.md#built-in-shim-systems)): the
`trivial-gray-streams` package mirrors every base class (plus
`trivial-gray-stream-mixin`) and every generic, including
`stream-read-sequence` / `stream-write-sequence` `(stream sequence start end
&key)`, `stream-file-position` with its `(setf ...)` writer, and the output
family `stream-line-column` / `stream-start-line-p` / `stream-terpri` /
`stream-fresh-line` / `stream-advance-to-column` / `stream-force-output` /
`stream-finish-output` / `stream-clear-output` — this is how
jzon's `:stream` writer API runs, and the class shape fast-io and
circular-streams define loads unchanged. The defaults are the same ones the
rontolisp protocol has, so a portable class that defines only
`trivial-gray-streams:stream-write-char` still answers every operator above.

```lisp
(asdf:load-system "trivial-gray-streams")

(defclass upcase-stream (trivial-gray-streams:fundamental-character-output-stream)
  ((acc :initform "")))
(defmethod trivial-gray-streams:stream-write-string
    ((s upcase-stream) str &optional start end)
  (declare (ignore start end))
  (setf (slot-value s 'acc)
        (concatenate 'string (slot-value s 'acc) (string-upcase str)))
  str)
(defmethod trivial-gray-streams:stream-write-char ((s upcase-stream) c)
  (trivial-gray-streams:stream-write-string s (string c))
  c)
(let ((s (make-instance 'upcase-stream)))
  (write-string "hello" s)
  (write-char #\! s)
  (slot-value s 'acc)) ; => "HELLO!"
```

## Limits

- `rontolisp:stream-unread-char` and `rontolisp:stream-advance-to-column` exist
  as protocol generics but no built-in dispatches to them (`unread-char` is not
  a built-in, and `format`'s `~T` does not consult the column); `peek-char` does
  not dispatch to Gray instances either.
- The read generics return primary values only: `stream-read-line` has no
  `(values line missing-newline-p)` pair — `:eof` is the whole EOF signal.
- `listen` on a Gray instance works on the interpreter and the JVM; the
  Preview 1 WASM backend rejects any `listen` call at compile time (a
  pre-existing platform limit, Gray or not).
- A `(write-string s instance :start ... :end ...)` call with bounding
  keywords does not dispatch the bounds to the instance.
- Dispatch happens at the built-in call sites: a first-class
  `(funcall #'read-byte instance)` does not dispatch on the compiled backends.
