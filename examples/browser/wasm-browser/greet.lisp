;; greet.lisp -- reads one line from stdin and prints a greeting.
;; The JavaScript shim feeds the text from a <textarea> as stdin, so this
;; shows how to pass input from the browser into the WASM program.

(let ((name (read-line)))
  (if (and name (> (length name) 0))
      (format t "Hello, ~a! Your name has ~a character(s).~%"
              name (length name))
      (format t "Hello, anonymous visitor!~%")))
