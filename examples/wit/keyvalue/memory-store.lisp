;;;; memory-store.lisp -- an in-memory implementation of wasi:keyvalue/store.
;;;;
;;;; rontolisp knows how to BIND a provider to a WIT interface. It does not know
;;;; what wasi:keyvalue is, and it ships no store. A store is ordinary user code,
;;;; and this file is one -- portable Lisp, nothing backend-specific, ~50 lines.
;;;;
;;;; A provider is an ordinary Lisp callable taking the bound function's Lisp
;;;; member name -- a STRING: "open", "bucket-get", ... -- and then that
;;;; function's arguments, a resource method's handle included. That is the whole
;;;; contract, so a store is just a function.
;;;;
;;;; It implements the SAME wit/keyvalue.wit that the component's host (wasmtime's
;;;; own wasi:keyvalue) implements -- which is why page-hits.lisp prints the same
;;;; thing against either one, down to the no-such-store error arm.
;;;;
;;;; Use it with:  (require :kv-memory "memory-store.lisp")

(provide :kv-memory)

;;; A WIT resource is an opaque integer handle. Nothing may interpret it but the
;;; provider that handed it out, so any counter will do.
(defvar *kv-buckets* (make-hash-table :test #'eql))

;;; The handle each store identifier was opened under, so re-opening a store hands
;;; back the same bucket rather than a fresh empty one (the host's rule).
(defvar *kv-open-stores* (make-hash-table :test #'equal))

(defvar *kv-next-handle* 1)

;;; The identifiers this store recognizes. Like every wasi:keyvalue host, it
;;; answers the default store under the empty identifier and raises no-such-store
;;; for anything else -- the WIT says so, so a store that means it must too.
(defvar *kv-identifiers* '(""))

(defun kv-bucket (handle)
  ;; An unknown handle is the error arm of every one of the resource's methods --
  ;; and the settled WIT mapping says an error arm SIGNALS a condition, on every
  ;; backend. So that is what a provider does, and the payload is the WIT variant.
  (let ((bucket (gethash handle *kv-buckets*)))
    (if (null bucket)
        (error 'rontolisp:wit-error :payload :no-such-store
               :message "memory store: not an open bucket handle")
        bucket)))

(defun kv-open (identifier)
  (if (not (member identifier *kv-identifiers* :test #'string=))
      (error 'rontolisp:wit-error :payload :no-such-store
             :message (concatenate 'string "memory store: no such store " identifier))
      ;; One bucket per identifier, opened once and re-handed out after that, so
      ;; two opens of the same store see each other's writes (the host's rule).
      (let ((existing (gethash identifier *kv-open-stores*)))
        (if existing
            existing
            (let ((handle *kv-next-handle*))
              (setq *kv-next-handle* (+ handle 1))
              (setf (gethash handle *kv-buckets*) (make-hash-table :test #'equal))
              (setf (gethash identifier *kv-open-stores*) handle)
              handle)))))

(defun memory-store (member &rest args)
  ;; The ok arm of a WIT result IS the return value, so nothing is wrapped:
  ;; `get` answers option<list<u8>>, which is the value string or nil; the three
  ;; result<_, error> writers answer nil; and `list-keys` answers the record
  ;; key-response, which is a keyword plist.
  (cond ((string= member "open")
         (kv-open (nth 0 args)))
        ((string= member "bucket-get")
         (gethash (nth 1 args) (kv-bucket (nth 0 args))))
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) (kv-bucket (nth 0 args))) (nth 2 args))
         nil)
        ((string= member "bucket-delete")
         (remhash (nth 1 args) (kv-bucket (nth 0 args)))
         nil)
        ((string= member "bucket-exists")
         (if (gethash (nth 1 args) (kv-bucket (nth 0 args))) t nil))
        ((string= member "bucket-list-keys")
         ;; The cursor (arg 1, an option<u64>) is nil here and stays nil: this
         ;; store hands back every key at once, so there is never a next page.
         (let ((keys nil))
           (maphash (lambda (key value) value (push key keys))
                    (kv-bucket (nth 0 args)))
           (list :keys (nreverse keys) :cursor nil)))
        (t (error 'rontolisp:wit-error :payload :other :message
                  (concatenate 'string "memory store: no such member " member)))))

;;; This is the whole binding: the interface id from the .wit, and a function.
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'memory-store)
