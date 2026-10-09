# f08. A buffered request body's read-char takes #xF8-#xFF as a four-octet lead

Difficulty: Low

`.kb/http-server.md` gives the lenient UTF-8 rule: a byte that leads no valid sequence
(`#xF8`-`#xFF`, a stray continuation) answers its own character (`%http-utf8-length`,
`%http-utf8-decode-octets`). The `:buffered` body's `read-char` does not: both the Gray class's
`stream-read-char` (`http-server.lisp`) and the interpreter's `HttpRequestBodyStream.decodeAt` /
`decodedLength` take any byte from `#xF0` up as a four-octet lead.

Measured 2026-10-09 on the interpreter and the JVM:

```lisp
(defvar *b* (rontolisp::%http-body-stream
             (make-array 5 :element-type '(unsigned-byte 8) :initial-contents '(255 254 65 195 169))))
(print (loop for c = (read-char *b* nil nil) while c collect (char-code c)))
;; => (2089027 169), a code past char-code-limit; the rule answers (255 254 65 233),
;;    as rontolisp:octets-to-string of the same octets does ("ÿþAé")
```

A Ring handler's `.read` of a binary request body shows it (`f09`).

## Plan

1. A ci-spec case (beside `http-buffered-body-stream`) reading that body with `read-char` and
   `read-line`, failing on the four backends.
2. `#xF0 <= b < #xF8` for the four-octet arm in both decoders; `decodedLength` to match.
