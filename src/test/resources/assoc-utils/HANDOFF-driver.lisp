;; Reproduction driver for .todo/86 assoc-utils integration handoff.
;; Run: java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
;;        src/test/resources/assoc-utils/HANDOFF-driver.lisp \
;;        --system-path src/test/resources/assoc-utils
(asdf:load-system :assoc-utils)
(format t "loaded~%")
(let ((a (list (cons "name" "eitaro") (cons "loc" "vienna"))))
  (format t "aget=~A~%" (assoc-utils:aget a "name"))
  (format t "keys=~A~%" (assoc-utils:alist-keys a))
  (format t "plist=~A~%" (assoc-utils:alist-plist a)))
