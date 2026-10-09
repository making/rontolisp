# e90. read-line ends a line at a lone CR on the interpreter and the JVM, not on wasm

Difficulty: Low

`read-line` over a character file stream answers different lines for a carriage return that
no line feed follows. Measured 2026-10-09:

```lisp
(with-open-file (out "cr.dat" :direction :output :if-exists :supersede
                     :element-type '(unsigned-byte 8))
  (dolist (b '(97 13 98 10 99)) (write-byte b out)))
(with-open-file (in "cr.dat")
  (print (loop for l = (read-line in nil) while l collect l)))
;; interpreter, JVM:  ("a" "b" "c")          -- BufferedReader.readLine's \r terminator
;; wasm, component:   ("a<CR>b" "c")         -- only LF ends a line, a CR before it is stripped
;; SBCL:              ("a<CR>b" "c")
```

`RontoCharFileReader.readLine`, `RontoIoFileStream.readLine` and the JVM's plain
`BufferedReader` take `\r` alone as a terminator; wasm `_read_line` ends at LF and strips one
CR before it (`.kb/read-load-streams.md`, "`read-line`, `read-char`, `peek-char`").

## Plan

1. A failing ci-spec case with the bytes above.
2. Decide the rule (SBCL and wasm: LF only, one CR before it stripped) and make the
   interpreter and JVM readers follow it; check a string input stream the same way.
3. `.kb/read-load-streams.md` with the rule.
