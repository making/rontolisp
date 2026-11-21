;; What probe-file answers of a file and a directory no one may read, and of the usual.
(%make-directories "closed/")
(format t "~s ~s ~s ~s ~s~%" (%probe-file "locked.txt") (%probe-file "ro") (%probe-file "nothing") (%probe-file "probe.lisp") (%probe-file "probe.lisp/x"))
