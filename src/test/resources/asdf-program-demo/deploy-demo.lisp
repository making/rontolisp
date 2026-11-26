(defpackage #:deploy-demo
  (:use #:cl)
  (:export #:main))

(in-package #:deploy-demo)

;; A :deploy hook runs while the image builds and writes next to the executable;
;; a :boot hook runs when the executable starts.
(deploy:define-hook (:deploy greeting) (directory)
  (with-open-file (out (merge-pathnames "greeting.txt" directory)
                       :direction :output
                       :if-exists :supersede)
    (write-line "hello from the data directory" out)))

(deploy:define-hook (:boot announce) ()
  (format t "booted~%"))

(defun main ()
  (format t "deployed ~a~%" (deploy:deployed-p))
  (with-open-file (in (merge-pathnames "greeting.txt" (deploy:data-directory)))
    (format t "~a~%" (read-line in))))
