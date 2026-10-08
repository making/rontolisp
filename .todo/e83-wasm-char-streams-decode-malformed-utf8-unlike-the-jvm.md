# e83. wasm: a character file stream decodes malformed UTF-8 unlike the other backends

Difficulty: Medium

A character input stream over a file holding a byte that leads no valid UTF-8 sequence
answers different characters on the WASM backends. Measured 2026-10-08:

```lisp
(let ((p (format nil "/tmp/utf8-~A" (random 1000000000))))
  (with-open-file (s p :direction :output :element-type '(unsigned-byte 8))
    (write-byte 65 s) (write-byte 233 s) (write-byte 66 s) (write-byte 255 s))
  (with-open-file (s p)
    (print (map 'list #'char-code (read-line s nil ""))))
  (delete-file p))
;; interpreter, JVM: (65 65533 66 65533)   -- U+FFFD per bad byte, Java's decoder
;; wasm, component:  (65 37055)            -- 233 taken as a 3-byte lead over 66 and 255
;; SBCL: a decoding error
```

The wasm stream decoder does not check that the bytes after a lead are continuation bytes
(`10xxxxxx`). `octets-to-string` decodes the same bytes leniently on every backend
(`.kb/characters-code-points.md`), a third answer. Clojure reads a file through these streams
(`slurp`, `clojure.java.io/reader`), where the oracle's decoder answers what the interpreter
and the JVM answer.

## Plan

1. A failing test on the four backends (ci-spec or a wasm e2e) with the bytes above.
2. Decide one rule for the stream decoders (the JVM's U+FFFD replacement is what the
   interpreter and the JVM already do) and make the wasm stream decoder follow it.
3. `.kb/character-sequence-io.md` with the rule.
