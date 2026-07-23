# Gray Streams (user-defined output streams)

rontolisp ships its own small Gray-stream extension, mirroring how real
implementations expose their native Gray support: a user class extends the
base class `rontolisp:fundamental-character-output-stream` and defines methods
on the generics `rontolisp:stream-write-string` (and
`rontolisp:stream-write-char`), and the [`write-string`](../reference/functions/write-string.md)
/ [`write-char`](../reference/functions/write-char.md) built-ins dispatch to
those methods when handed such an instance instead of a stream handle. This
works on every backend (interpreter, JVM, both WASM variants).

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

## The trivial-gray-streams shim

Portable libraries are written against
[trivial-gray-streams](https://github.com/trivial-gray-streams/trivial-gray-streams)
rather than an implementation's own protocol. rontolisp bundles a built-in
`trivial-gray-streams` ASDF system adapting the portable API onto the protocol
above (see [Systems](asdf-systems.md#built-in-shim-systems)): a class
extending `trivial-gray-streams:fundamental-character-output-stream` with
methods on `trivial-gray-streams:stream-write-char`/`-string` receives the
built-ins' writes unchanged — this is how jzon's `:stream` writer API runs.

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

- Output side only: `stream-write-char` and `stream-write-string` exist; the
  input generics of full Gray streams (`stream-read-char`, ...) do not.
- `write-char` lowers to a one-character `write-string`, so implementing
  `rontolisp:stream-write-string` is sufficient (the shim's delegating methods
  route both).
- The `format` family does not dispatch to Gray instances — write the rendered
  string with `write-string`.
- A `(write-string s instance :start ... :end ...)` call with bounding
  keywords does not dispatch the bounds to the instance.
