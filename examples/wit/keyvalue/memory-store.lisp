;;;; memory-store.lisp -- an in-memory implementation of wasi:keyvalue/store.
;;;;
;;;; rontolisp knows how to BIND a provider to a WIT interface. It does not know
;;;; what wasi:keyvalue is, and it ships no store. A store is ordinary user code,
;;;; and this file is one -- portable Lisp, nothing backend-specific, ~40 lines.
;;;;
;;;; A provider is an ordinary Lisp callable taking the bound function's Lisp
;;;; member name -- a STRING: "open", "bucket-get", ... -- and then that
;;;; function's arguments, a resource method's handle included. That is the whole
;;;; contract, so a store is just a function.
;;;;
;;;; Use it with:  (require :kv-memory "memory-store.lisp")

(provide :kv-memory)

;;; A WIT resource is an opaque integer handle. Nothing may interpret it but the
;;; provider that handed it out, so any counter will do.
(defvar *kv-buckets* (make-hash-table :test #'eql))

(defvar *kv-next-handle* 1)

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
  identifier                             ; every identifier opens a fresh bucket
  (let ((handle *kv-next-handle*))
    (setq *kv-next-handle* (+ handle 1))
    (setf (gethash handle *kv-buckets*) (make-hash-table :test #'equal))
    handle))

(defun memory-store (member &rest args)
  ;; The ok arm of a WIT result IS the return value, so nothing is wrapped:
  ;; `get` answers option<list<u8>>, which is the value string or nil, and the
  ;; three result<_, error> writers answer nil.
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
         (let ((keys nil))
           (maphash (lambda (key value) value (push key keys))
                    (kv-bucket (nth 0 args)))
           (nreverse keys)))
        (t (error 'rontolisp:wit-error :payload :other :message
                  (concatenate 'string "memory store: no such member " member)))))

;;; This is the whole binding: the interface id from the .wit, and a function.
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store)
