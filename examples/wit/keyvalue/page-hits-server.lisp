;;;; page-hits-server -- the page-hit counter of page-hits.lisp, but SERVED.
;;;;
;;;; page-hits.lisp counts page views in a store and exits. This one is the same
;;;; counter behind an HTTP server: every request records a hit for its own path
;;;; and answers the running tally. Two halves that belong together --
;;;; `rontolisp:http-handler` (the server) and `rontolisp:wit-import` (the store)
;;;; -- and the point of putting them together is that the state lives OUTSIDE the
;;;; program, in whatever implements the WIT.
;;;;
;;;; That is not a nicety for a served component: a wasi:http host instantiates it
;;;; AFRESH FOR EVERY REQUEST, so a global hash table reads back empty every time.
;;;; A page-hit counter simply cannot be written that way. Through the store it
;;;; can, and the very same source still runs on the interpreter and the JVM.
;;;;
;;;;   rontolisp page-hits-server.lisp             the interpreter -- a blocking
;;;;                                               server on :8080, memory-store.lisp
;;;;                                               behind the WIT
;;;;   rontolisp page-hits-server.lisp \           the JVM -- same, but java-store.lisp
;;;;     -o Server.class                           REPLACES the store with a real
;;;;                                               java.util.LinkedHashMap
;;;;   rontolisp page-hits-server.lisp \           a WASI component that EXPORTS
;;;;     -o server.wasm --component                wasi:http/incoming-handler and
;;;;     + wash dev                                IMPORTS wasi:keyvalue/store: on
;;;;                                               wasmCloud the host serves the
;;;;                                               requests and a real key-value
;;;;                                               provider holds the counts, so they
;;;;                                               survive the instance
;;;;
;;;; The same component runs under `wasmtime serve -S keyvalue=y`, where the calls
;;;; reach wasmtime's own store -- but that store is wasmtime's in-memory provider,
;;;; which it rebuilds per instance (so per REQUEST here): the tally starts over
;;;; every time. Whether a store outlives the instance is the host's business, not
;;;; the component's, and that is exactly what the WIT boundary makes swappable.
;;;;
;;;; See README.md ("Serve it") for the commands.

(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
                      :package kv)

;;; The store behind the interface, on the backends that need one -- the same two
;;; files page-hits.lisp binds, and the same one line each. On the WASM backends
;;; the host IS the provider, so a wit-provide is inert there and these stores
;;; simply go unused.
(require :kv-memory "memory-store.lisp")

#+rontolisp-jvm (require :kv-java "java-store.lisp")

;;; The default store, which every wasi:keyvalue host recognizes under the empty
;;; identifier.
(defvar *store* "")

;;; Read the counter for PAGE, add one, write it back -- the record-hit of
;;; page-hits.lisp, answering the new count. A value is a list<u8>, which crosses
;;; as a string; an absent key is nil, so the first hit starts from 0.
(defun record-hit (bucket page)
  (let ((hits
         (+ 1
            (let ((seen (kv:bucket-get bucket page)))
              (if seen (parse-integer seen) 0)))))
    (kv:bucket-set bucket page (princ-to-string hits))
    hits))

(defun sorted-keys (bucket)
  (sort (getf (kv:bucket-list-keys bucket nil) :keys) #'string<))

;;; The tally, one line per path, as the response body.
(defun report (bucket)
  (let ((body ""))
    (dolist (key (sorted-keys bucket))
      (setq body
            (concatenate 'string body
             (format nil "~a = ~a~%" key (kv:bucket-get bucket key)))))
    body))

;;; The handler: one request = one hit. Nothing in it knows where the counts live,
;;; and nothing in it is about a backend -- it is an ordinary rontolisp:http-handler
;;; whose state happens to be somebody else's.
(defun handle (env)
  (let* ((page (getf env :path-info))
         (bucket (kv:open *store*))
         (hits (record-hit bucket page)))
    (list 200 '(:content-type "text/plain")
          (list
           (format nil "~a -> ~a hit~:[s~;~]~%~%hits per page:~%~a" page hits
                   (= hits 1) (report bucket))))))

;;; On the interpreter / JVM this blocks and serves on port 8080; under --component
;;; the port is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
