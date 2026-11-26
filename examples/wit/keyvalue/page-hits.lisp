;;;; page-hits -- one WIT interface, a different store behind it per backend.
;;;;
;;;; A page-view counter written against the real wasi:keyvalue/store: open the
;;;; store, read a counter, write it back, ask what keys are in there. Nothing in
;;;; the program below says WHERE those key-value pairs live -- that is the point.
;;;;
;;;; `rontolisp:wit-import` reads wit/keyvalue.wit and binds the interface's
;;;; functions as ordinary Lisp functions in the package kv. What each one calls
;;;; is decided separately, and differently, per backend:
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
;;;;   rontolisp page-hits.lisp -o kv.wasm --component
;;;;     + wasmtime run -S keyvalue=y ...          a WASI component -- and here the
;;;;                                               provider is the HOST: wasmtime's own
;;;;                                               wasi:keyvalue implementation, which
;;;;                                               has never heard of this program
;;;;
;;;; rontolisp itself ships NO store: it knows how to bind a provider to a WIT
;;;; interface, and nothing about what wasi:keyvalue is. Both Lisp stores below are
;;;; ordinary user code. That is the whole shape of the feature -- develop against
;;;; a fake, deploy against the real thing, and never touch the program in between.
;;;;
;;;; See README.md for the commands. (Preview 1 WASM is the one backend this does
;;;; not reach: a core import carries flat values only, and every function of this
;;;; interface returns a `result`.)

(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
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
;;;   option<u64>                  a number, or nil -- so a cursor-less call passes nil
;;;   record key-response          a keyword plist: (:keys ("/index" ...) :cursor nil)

;;; The store. Ordinary Lisp -- see memory-store.lisp, which ends in one
;;; (rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'memory-store).
;;; On the WASM backends the host IS the provider, so a wit-provide is inert there
;;; and this file's store simply goes unused.
(require :kv-memory "memory-store.lisp")

;;; ...and it is swappable. On the JVM, bind a store backed by a real Java map
;;; instead -- the same one line that would bind a Redis client. This is the only
;;; backend-specific line in the whole example, and the program never sees it.
#+rontolisp-jvm (require :kv-java "java-store.lisp")

;;; ---------------------------------------------------------------------------
;;; The program. Every line below is store-agnostic: it knows the WIT, and
;;; nothing else.
;;; ---------------------------------------------------------------------------

(defvar *requests* '("/index" "/pricing" "/index" "/docs" "/index" "/pricing"))

;;; The default store, which every wasi:keyvalue host recognizes under the empty
;;; identifier. An identifier a host does NOT recognize is the `no-such-store`
;;; error arm -- which is what the handler-case near the bottom shows.
(defvar *store* "")

;;; Read the counter for PAGE, add one, write it back. `bucket.get` answers an
;;; option, which is the value or nil -- no unwrapping ceremony, nil IS "absent".
;;; A value is a list<u8>, which crosses as a string.
(defun record-hit (bucket page)
  (let ((seen (kv:bucket-get bucket page)))
    (kv:bucket-set bucket page
                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))))

;;; `bucket.list-keys` pages, so it takes an option<u64> cursor (nil = the first
;;; page) and answers a `key-response` record -- which crosses as a keyword plist,
;;; so the keys are (getf response :keys) and :cursor is nil when there are no
;;; more pages. These stores hand back everything at once.
(defun sorted-keys (bucket)
  (sort (getf (kv:bucket-list-keys bucket nil) :keys) #'string<))

;;; `open` answers a result<bucket, error>: the ok arm IS the value, so the
;;; handle comes straight back. (The error arm would signal -- see below.)
(let ((bucket (kv:open *store*)))
  (dolist (page *requests*) (record-hit bucket page))

  (format t "~%hits per page:~%")
  (dolist (key (sorted-keys bucket))
    (format t "  ~a = ~a~%" key (kv:bucket-get bucket key)))

  (format t "~%/docs exists?      ~a~%"
          (if (kv:bucket-exists bucket "/docs") "yes" "no"))
  (kv:bucket-delete bucket "/docs")
  (format t "/docs exists now?  ~a~%"
          (if (kv:bucket-exists bucket "/docs") "yes" "no"))
  (format t "keys:              ~s~%" (sorted-keys bucket))
  (format t "/nope:             ~s~%" (kv:bucket-get bucket "/nope")))

;;; The error arm of a WIT result signals rontolisp:wit-error -- so a store's
;;; failures are caught with handler-case like any other condition, and the WIT
;;; variant that failed is the payload. No host recognizes this store identifier:
;;; the same no-such-store comes back from three completely different providers.
(handler-case (kv:open "not-a-store-anyone-has")
  (rontolisp:wit-error (e)
    (format t "bad store:         ~a~%" (rontolisp:wit-error-payload e))))

;;; Each binding is an ordinary defun, so it is an ordinary function VALUE too:
;;; #'kv:bucket-set is a first-class function, funcall takes it, mapcar maps it.
;;; Nothing about the WIT boundary leaks into the call sites.
(let ((bucket (kv:open *store*)) (set-key #'kv:bucket-set))
  (mapcar (lambda (key) (funcall set-key bucket key "seeded")) '("/a" "/b"))
  (format t "seeded:            ~s~%"
          (remove-if-not
           (lambda (key) (string= "seeded" (kv:bucket-get bucket key)))
           (sorted-keys bucket))))
