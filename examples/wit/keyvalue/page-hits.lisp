;;;; page-hits -- one WIT interface, a different store behind it per backend.
;;;;
;;;; A tiny page-view counter written against wasi:keyvalue/store: open a bucket,
;;;; read a counter, write it back, ask what keys are in there. Nothing in the
;;;; program below says WHERE those key-value pairs live -- that is the point.
;;;;
;;;; `rontolisp:wit-import` reads wit/keyvalue.wit and binds the interface's
;;;; functions as ordinary Lisp functions in the package kv. What each one calls
;;;; is decided separately, by whoever binds a provider:
;;;;
;;;;   rontolisp page-hits.lisp                    the interpreter -- memory-store.lisp
;;;;                                               is the provider, a portable Lisp
;;;;                                               hash-table store
;;;;   rontolisp page-hits.lisp -o PageHits.class  the JVM -- java-store.lisp is
;;;;                                               required too, and REPLACES it with
;;;;                                               a real java.util.LinkedHashMap
;;;;                                               (the ";; [java store]" lines below
;;;;                                               are the proof that the calls land
;;;;                                               there)
;;;;
;;;; rontolisp itself ships NO store: it knows how to bind a provider to a WIT
;;;; interface, and nothing about what wasi:keyvalue is. Both stores below are
;;;; ordinary user code. That is the whole shape of the feature -- develop against
;;;; a fake, deploy against the real thing, and never touch the program in between.
;;;;
;;;; See README.md for the commands and for what does NOT work (--component and
;;;; --no-gc reject wit-import; a result/option-bearing interface like this one
;;;; does not cross the Preview 1 WASM import boundary either).

(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0"
                      :package kv)

;;; The names that just appeared, and where they come from:
;;;
;;;   kv:open              open: func(identifier: string) -> result<bucket, error>
;;;   kv:bucket-get        bucket.get, with the handle as the first argument
;;;   kv:bucket-set        bucket.set
;;;   kv:bucket-delete     bucket.delete
;;;   kv:bucket-exists     bucket.exists
;;;   kv:bucket-list-keys  bucket.list-keys
;;;
;;; A `resource bucket`'s method `get` binds as `bucket-get` taking the handle
;;; first, so `b.get(key)` in WIT is `(kv:bucket-get b key)` here. The handle
;;; itself is opaque -- an integer you pass back, never one you interpret.
;;;
;;; The types come across as rontolisp's settled WIT mapping:
;;;
;;;   result<bucket, error>        the bucket handle -- and the ERROR arm signals
;;;                                rontolisp:wit-error, which handler-case catches
;;;   option<list<u8>>             the value string, or nil when the key is absent
;;;   list<u8>                     a string (bytes, one per character)
;;;   result<list<string>, error>  a list of strings

;;; The store. Ordinary Lisp -- see memory-store.lisp, which ends in one
;;; (rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store).
(require :kv-memory "memory-store.lisp")

;;; ...and it is swappable. On the JVM, bind a store backed by a real Java map
;;; instead -- the same one line that would bind a Redis client. This is the only
;;; backend-specific line in the whole example, and the program never sees it.
#+rontolisp-jvm
(require :kv-java "java-store.lisp")

;;; ---------------------------------------------------------------------------
;;; The program. Every line below is store-agnostic: it knows the WIT, and
;;; nothing else.
;;; ---------------------------------------------------------------------------

(defvar *requests*
  '("/index" "/pricing" "/index" "/docs" "/index" "/pricing"))

;;; Read the counter for PAGE, add one, write it back. `bucket.get` answers an
;;; option, which is the value or nil -- no unwrapping ceremony, nil IS "absent".
;;; A value is a list<u8>, which crosses as a string.
(defun record-hit (bucket page)
  (let ((seen (kv:bucket-get bucket page)))
    (kv:bucket-set bucket page
                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))))

(defun sorted-keys (bucket)
  (sort (kv:bucket-list-keys bucket) #'string<))

;;; `open` answers a result<bucket, error>: the ok arm IS the value, so the
;;; handle comes straight back. (The error arm would signal -- see the bottom.)
(let ((bucket (kv:open "page-hits")))
  (dolist (page *requests*)
    (record-hit bucket page))

  (format t "~%hits per page:~%")
  (dolist (key (sorted-keys bucket))
    (format t "  ~a = ~a~%" key (kv:bucket-get bucket key)))

  (format t "~%/docs exists?      ~a~%" (if (kv:bucket-exists bucket "/docs") "yes" "no"))
  (kv:bucket-delete bucket "/docs")
  (format t "/docs exists now?  ~a~%" (if (kv:bucket-exists bucket "/docs") "yes" "no"))
  (format t "keys:              ~s~%" (sorted-keys bucket))
  (format t "/nope:             ~s~%" (kv:bucket-get bucket "/nope")))

;;; The error arm of a WIT result signals rontolisp:wit-error -- so a store's
;;; failures are caught with handler-case like any other condition, and the WIT
;;; variant that failed is the payload. Here the handle is not one any store ever
;;; handed out: no-such-store.
(handler-case
    (kv:bucket-get 42 "/index")
  (rontolisp:wit-error (e)
    (format t "bad handle:        ~a~%" (rontolisp:wit-error-payload e))))

;;; Each binding is an ordinary defun, so it is an ordinary function VALUE too:
;;; #'kv:bucket-set is a first-class function, funcall takes it, mapcar maps it.
;;; Nothing about the WIT boundary leaks into the call sites.
(let ((bucket (kv:open "second-bucket"))
      (set-key #'kv:bucket-set))
  (mapcar (lambda (key) (funcall set-key bucket key "seeded"))
          '("a" "b"))
  (format t "second bucket:     ~s~%" (sorted-keys bucket)))
