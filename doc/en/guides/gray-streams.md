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
| `write-string`, `write-char`, `format` (stream destination) | `rontolisp:stream-write-string` (`write-char` lowers to a one-character write) |
| `write-byte` | `rontolisp:stream-write-byte` |
| `read-byte` | `rontolisp:stream-read-byte` |
| `read-char` | `rontolisp:stream-read-char` |
| `read-line` | `rontolisp:stream-read-line` (default method loops `stream-read-char`) |
| `listen` | `rontolisp:stream-listen` (default method answers `nil`) |
| `read-sequence` / `write-sequence` | `rontolisp:stream-read-sequence` / `-write-sequence` (default methods loop the element generics) |
| `file-position` | `rontolisp:stream-file-position`; the two-argument form calls the `(setf rontolisp:stream-file-position)` writer generic |

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
&key)` and `stream-file-position` with its `(setf ...)` writer — this is how
jzon's `:stream` writer API runs, and the class shape fast-io and
circular-streams define loads unchanged.

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

- `rontolisp:stream-unread-char` exists as a protocol generic but no built-in
  dispatches to it (`unread-char` is not a built-in); `peek-char` does not
  dispatch to Gray instances either.
- The read generics return primary values only: `stream-read-line` has no
  `(values line missing-newline-p)` pair — `:eof` is the whole EOF signal.
- `listen` on a Gray instance works on the interpreter and the JVM; the
  Preview 1 WASM backend rejects any `listen` call at compile time (a
  pre-existing platform limit, Gray or not).
- A `(write-string s instance :start ... :end ...)` call with bounding
  keywords does not dispatch the bounds to the instance.
- Dispatch happens at the built-in call sites: a first-class
  `(funcall #'read-byte instance)` does not dispatch on the compiled backends.
