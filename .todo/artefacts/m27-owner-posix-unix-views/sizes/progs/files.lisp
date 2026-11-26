(with-open-file (s "l.txt" :direction :output :if-exists :supersede) (write-line "x" s))
(print (list (probe-file "l.txt") (> (file-write-date "l.txt") 0) (ensure-directories-exist "d/")))
(delete-file "l.txt")
