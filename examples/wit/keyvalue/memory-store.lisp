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

;;; THE DATA lives under the store's IDENTIFIER, not under a handle: a handle is a
;;; REFERENCE to a store, never the store itself, so dropping one does not take the
;;; store with it. That distinction is the whole point of the two tables below, and
;;; it is what `bucket-drop` is here to make visible.
;;;
;;; (One measured difference from wasmtime's own `-S keyvalue=y` provider, which is
;;; an in-memory convenience rather than a real store: it hands each `open` an
;;; INDEPENDENT snapshot, so a write through one bucket is invisible to a later open
;;; there. A store that means it -- this one, wasmCloud's, a redis -- shares.)
(defvar *kv-stores* (make-hash-table :test #'equal))

;;; A WIT resource is an opaque integer handle. Nothing may interpret it but the
;;; provider that handed it out, so any counter will do; this one maps the handles
;;; still outstanding to the store each one refers to. `bucket-drop` deletes an
;;; entry HERE and nothing else.
(defvar *kv-handles* (make-hash-table :test #'eql))

(defvar *kv-next-handle* 1)

;;; The identifiers this store recognizes. Like every wasi:keyvalue host, it
;;; answers the default store under the empty identifier and raises no-such-store
;;; for anything else -- the WIT says so, so a store that means it must too.
(defvar *kv-identifiers* '(""))

(defun kv-bucket (handle)
  ;; An unknown handle -- never opened, or already dropped -- is the error arm of
  ;; every one of the resource's methods, and the settled WIT mapping says an error
  ;; arm SIGNALS a condition, on every backend. So that is what a provider does, and
  ;; the payload is the WIT variant.
  (let ((identifier (gethash handle *kv-handles*)))
    (if (null identifier)
        (error 'rontolisp:wit-error :payload :no-such-store
               :message "memory store: not an open bucket handle")
        (gethash identifier *kv-stores*))))

(defun kv-open (identifier)
  (if (not (member identifier *kv-identifiers* :test #'string=))
      (error 'rontolisp:wit-error :payload :no-such-store
             :message (concatenate 'string "memory store: no such store " identifier))
      ;; Every open hands out a FRESH handle -- what a real host does, each one an
      ;; owned resource the guest gives back on its own -- onto the one store the
      ;; identifier names, created on first sight.
      (let ((handle *kv-next-handle*))
        (setq *kv-next-handle* (+ handle 1))
        (if (null (gethash identifier *kv-stores*))
            (setf (gethash identifier *kv-stores*) (make-hash-table :test #'equal)))
        (setf (gethash handle *kv-handles*) identifier)
        handle)))

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
        ((string= member "bucket-drop")
         ;; A resource is released by its interface's `drop`, which WIT declares no
         ;; function for -- rontolisp spells it `<resource>-drop` and dispatches it
         ;; here like any other member. Dropping a handle RELEASES THE REFERENCE, it
         ;; does not delete the store: the data is keyed by identifier, and the next
         ;; open sees it all. A provider with nothing to release just answers nil.
         (remhash (nth 0 args) *kv-handles*)
         nil)
        (t (error 'rontolisp:wit-error :payload :other :message
                  (concatenate 'string "memory store: no such member " member)))))

;;; This is the whole binding: the interface id from the .wit, and a function.
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'memory-store)
