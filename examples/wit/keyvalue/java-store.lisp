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

;;; The DATA lives under the store's identifier; a handle is only a reference TO a
;;; store, so dropping one does not take the store with it (memory-store.lisp makes
;;; the same distinction, and for the same reason).
(defvar *java-stores* (java:new "java.util.HashMap"))

(defvar *java-handles* (java:new "java.util.HashMap"))

(defvar *java-next-handle* 500)

;;; The identifiers this store recognizes -- the same rule the real host follows:
;;; the default store under the empty identifier, no-such-store for anything else.
(defvar *java-identifiers* '(""))

(defun java-bucket (handle)
  ;; An unknown handle -- never opened, or already dropped.
  (let ((identifier (java:call *java-handles* "get" handle)))
    (if (null identifier)
        (error 'rontolisp:wit-error
               :payload :no-such-store
               :message "java store: not an open bucket handle")
        (java:call *java-stores* "get" identifier))))

(defun java-open (identifier)
  (if (not (member identifier *java-identifiers* :test #'string=))
      (error 'rontolisp:wit-error
       :payload :no-such-store
       :message (concatenate 'string "java store: no such store " identifier))
      ;; A fresh handle per open, onto the one store the identifier names.
      (let ((handle *java-next-handle*))
        (setq *java-next-handle* (+ handle 1))
        (if (null (java:call *java-stores* "get" identifier))
            (java:call *java-stores* "put" identifier
                       (java:new "java.util.LinkedHashMap")))
        (java:call *java-handles* "put" handle identifier)
        (format t ";; [java store] open ~s -> handle ~a~%" identifier handle)
        handle)))

(defun java-store (member &rest args)
  (cond ((string= member "open") (java-open (nth 0 args)))
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
         (if (java:call (java-bucket (nth 0 args)) "containsKey" (nth 1 args))
             t
             nil))
        ((string= member "bucket-list-keys")
         ;; java.util.LinkedHashMap keeps insertion order, so the keys come back
         ;; in the order they were first written. The cursor (arg 1) stays nil:
         ;; this store hands back every key at once, so there is no next page --
         ;; and the key-response record crosses as a keyword plist.
         (list :keys (java:call (java:call (java-bucket (nth 0 args)) "keySet")
                                "toArray")
               :cursor nil))
        ((string= member "bucket-drop")
         ;; Releasing the reference, not the store: the LinkedHashMap stays, and the
         ;; next open sees every key still in it.
         (format t ";; [java store] drop handle ~a~%" (nth 0 args))
         (java:call *java-handles* "remove" (nth 0 args))
         nil)
        (t
         (error 'rontolisp:wit-error
                :payload :other
                :message (concatenate 'string "java store: no such member "
                                      member)))))

;;; Bound AFTER the memory store, so this one wins: rontolisp:wit-provide replaces
;;; whatever was bound for the interface before it.
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'java-store)
