# A displaced array cannot view a string

Difficulty: High

`(make-array n :element-type 'character :displaced-to s :displaced-index-offset k)`
signals `MAKE-ARRAY expects an array` on every backend when `s` is a string.
Displacement is a view over a `LispArray` only, and a string is its own value
type here (a code-point buffer, `.kb/characters-code-points.md`) -- on the JVM a
Java `String`, on WASM a UTF-8 byte string. None of those can be aliased by the
existing view.

It is a common idiom, because it is how a portable library takes a substring
without copying. cl-ppcre's `nsubseq` is exactly that, and it is on the path of
every `regex-replace` / `regex-replace-all` with a FUNCTION replacement and of
`:sharedp t` on `scan-to-strings` / `register-groups-bind` / `do-scans`. That
whole surface signalled until `eval/ClPpcreSharedSubseq` rewrote the one
definition to copy -- which is what cl-ppcre itself does when the caller did not
ask to share, so nothing is lost but the saved copy. The rewrite is a local
answer for one library; the gap is general.

## What NOT to do

Do not make `make-array :displaced-to` answer a COPY for a string target. It
would make this call work and silently stop every other library's displacement
from aliasing, which is the one thing displacement is for.

## What to implement

A string VIEW: a string value that shares another string's storage with an
offset and a length, so `(setf (char view 0) ...)` is visible through the
target and `array-displacement` answers the target and the offset. Per backend:

- **interpreter** (`LispString`): the buffer is already an `int[]`; the view
  needs a target reference + offset + length beside the existing fill-pointer
  fields, and every read/write path routed through them.
- **JVM**: a Lisp string is an immutable Java `String` with framing quotes, so
  a mutable shared view needs the mutable char-vec representation (`_strv`) as
  its target, not the `String` -- decide whether a view over a literal is an
  error or forces the literal into a char vec.
- **WASM**: UTF-8 bytes, so a code-point offset is not a byte offset; the view
  has to carry a byte offset resolved once, and the three shared accessors
  (`_str_char_count` / `_str_char_at` / `_str_char_byte_offset`) have to respect
  it. A write that changes a character's UTF-8 WIDTH cannot be done in place at
  all -- decide that case before starting.

## Definition of done

```lisp
(let* ((s (copy-seq "abcdef"))
       (v (make-array 3 :element-type 'character :displaced-to s
                        :displaced-index-offset 1)))
  (setf (char v 0) #\X)
  (list v s (multiple-value-list (array-displacement v))))
```
answers `("Xcd" "aXcdef" (<s> 1))` byte-identically on all four backends, and
`ClPpcreSharedSubseq` is retired with its test.
