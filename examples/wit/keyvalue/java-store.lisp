;;;; java-store.lisp -- the SAME wasi:keyvalue/store interface, backed by a real
;;;; Java map instead of a Lisp hash table.
;;;;
;;;; This is the point of the whole exercise: page-hits.lisp does not change, and
;;;; does not know this file exists. It calls (kv:bucket-set b "/index" "3"); what
;;;; that lands in is decided here, by one rontolisp:wit-provide.
;;;;
;;;; java: interop is reflection, so this file runs on the JVM (and the JVM-hosted
;;;; interpreter) only -- which is exactly why page-hits.lisp requires it under
;;;; #+rontolisp-jvm. A real deployment would swap java.util.LinkedHashMap for a
;;;; Redis client or a JDBC connection; nothing else in this file would change,
;;;; and nothing at all in the program would.
;;;;
;;;; Use it with:  (require :kv-java "java-store.lisp")

(provide :kv-java)

(defvar *java-buckets* (java:new "java.util.HashMap"))

(defvar *java-next-handle* 500)

(defun java-bucket (handle)
  (let ((bucket (java:call *java-buckets* "get" handle)))
    (if (null bucket)
        (error 'rontolisp:wit-error :payload :no-such-store
               :message "java store: not an open bucket handle")
        bucket)))

(defun java-store (member &rest args)
  (cond ((string= member "open")
         (let ((handle *java-next-handle*))
           (setq *java-next-handle* (+ handle 1))
           (java:call *java-buckets* "put" handle (java:new "java.util.LinkedHashMap"))
           (format t ";; [java store] open ~s -> handle ~a~%" (nth 0 args) handle)
           handle))
        ((string= member "bucket-get")
         (java:call (java-bucket (nth 0 args)) "get" (nth 1 args)))
        ((string= member "bucket-set")
         (format t ";; [java store] set ~a = ~a~%" (nth 1 args) (nth 2 args))
         (java:call (java-bucket (nth 0 args)) "put" (nth 1 args) (nth 2 args))
         nil)
        ((string= member "bucket-delete")
         (format t ";; [java store] delete ~a~%" (nth 1 args))
         (java:call (java-bucket (nth 0 args)) "remove" (nth 1 args))
         nil)
        ((string= member "bucket-exists")
         (if (java:call (java-bucket (nth 0 args)) "containsKey" (nth 1 args)) t nil))
        ((string= member "bucket-list-keys")
         ;; java.util.LinkedHashMap keeps insertion order, so the keys come back
         ;; in the order they were first written.
         (java:call (java:call (java-bucket (nth 0 args)) "keySet") "toArray"))
        (t (error 'rontolisp:wit-error :payload :other :message
                  (concatenate 'string "java store: no such member " member)))))

;;; Bound AFTER the memory store, so this one wins: rontolisp:wit-provide replaces
;;; whatever was bound for the interface before it.
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'java-store)
