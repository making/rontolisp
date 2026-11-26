;; Relative paths that climb above the current directory, run from <dir>/cwd with
;; <dir>/up.txt beside it: a native program reaches the parent, and so must the stub.
;; The truename is absolute, so the run prints whether it is the parent, not the path.
;; Built at run time: a literal path is bundled at compile time instead.
(defun up (name) (concatenate 'string ".." "/" name))
(print (with-open-file (in (up "up.txt")) (read-line in)))
(print (equal (pathname-directory (probe-file (up "up.txt")))
              (butlast (pathname-directory (truename ".")))))
(print (probe-file (up "absent.txt")))
(with-open-file (out (up "written.txt") :direction :output :if-exists :supersede)
  (write-line "written" out))
(print (with-open-file (in (concatenate 'string "../cwd/.." "/written.txt")) (read-line in)))
(print (delete-file (up "written.txt")))
(print (probe-file (up "written.txt")))
