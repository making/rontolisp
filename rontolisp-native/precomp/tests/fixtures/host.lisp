;; What the stub hands the module: arguments, environment, stdin, a relative path
;; (against the preopened current directory), an absolute path (against the preopened
;; root), output without a trailing newline, and the exit status.
(print (uiop:command-line-arguments))
(print (uiop:getenv "RLNATIVE_TEST"))
(print (read-line))
(with-open-file (out "relative.txt" :direction :output :if-exists :supersede)
  (write-line "relative" out))
(print (with-open-file (in "relative.txt") (read-line in)))
;; Built at run time: a literal absolute path is bundled at compile time instead.
(defvar *absolute* (concatenate 'string (uiop:getenv "RLNATIVE_DIR") "/absolute.txt"))
(with-open-file (out *absolute* :direction :output :if-exists :supersede)
  (write-line "absolute" out))
(print (with-open-file (in *absolute*) (read-line in)))
(print (not (null (probe-file *absolute*))))
(format t "~%no newline at the end")
(uiop:quit 7)
