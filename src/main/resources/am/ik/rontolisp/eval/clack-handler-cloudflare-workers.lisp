;; clack.handler.cloudflare-workers: the Clack handler backend for a HOST-DRIVEN
;; REACTOR -- a Cloudflare Worker, and any other embedding where the host has
;; already parsed the request and calls an exported function instead of handing
;; the program a socket. Satisfies the built-in ASDF system
;; "clack-handler-cloudflare-workers" (and its dotted alias
;; "clack.handler.cloudflare-workers", the spelling lack's find-package-or-load
;; derives from the package name).
;; Like clack-handler-rontolisp the package is NOT seeded in PackageRegistry, so
;; this shim carries its own defpackage (the leaf-module pattern).
;;
;; What it provides is HANDLE, not RUN: there is no socket to bind, so clackup
;; has nothing to do here (see the run stub at the bottom, and .todo/281). The
;; user exports one function and calls handle from it:
;;
;;   (ql:quickload "clack-handler-cloudflare-workers")
;;   (rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
;;   (defun handle-request (json)
;;     (clack.handler.cloudflare-workers:handle #'app json))
;;
;; and APP is an ordinary Clack application -- the environment plist in, the
;; (status headers body) list out -- unchanged from the one that runs on
;; hunchentoot, on woo, under `wasmtime serve` or on the JVM.
;;
;; There is no re-implementation of Clack here either. Since the
;; rontolisp:http-handler cutover rontolisp's own server protocol IS Clack's,
;; and both halves of that boundary are backend-free entry points in
;; http-server.lisp: rontolisp::%http-make-env turns a positional raw tuple into
;; the environment, rontolisp::%http-normalize-response turns whatever the
;; application returned into the (status header-alist body-string) triple. So
;; this shim inherits the percent-decoding, the "?" split, the header
;; lowercasing and comma-joining, the Host split, the content-length parsing and
;; the buffered :raw-body, and CANNOT drift from what a served request sees.
;; What is left is the JSON envelope, and that is all this file is.
;;
;; The envelope, both directions (the host speaks JSON because the other side of
;; a reactor boundary is normally JavaScript):
;;
;;   in   {"method": "GET",            ; defaults to "GET"
;;         "target": "/path?a=1",      ; RAW -- path and query still joined and
;;                                     ;   still encoded. %http-make-env owns
;;                                     ;   that split; a pre-split path would
;;                                     ;   leave :query-string nil.
;;         "headers": {"host": "..."}, ; content-length MUST be here for a body:
;;                                     ;   lack/request parses nothing without
;;                                     ;   it, and a chunked request carries
;;                                     ;   none, so the host sets it from the
;;                                     ;   bytes it actually read.
;;         "body": "...",              ; already read; "" or absent for none
;;         "scheme": "https",          ; optional, defaults to "http"
;;         "remote-addr": "203.0.113.7"}  ; optional, Clack's :remote-addr
;;
;;   out  {"status": 200,
;;         "headers": [["content-type", "application/json"]],  ; an ARRAY of
;;                                     ;   pairs, not an object: a name may
;;                                     ;   repeat, and an application that sets
;;                                     ;   two cookies answers two Set-Cookie
;;                                     ;   headers. The host feeds it to the
;;                                     ;   Headers constructor as is.
;;         "body": "..."}
;;
;; :remote-port is always nil (a reactor host exposes no peer port), and
;; :server-name / :server-port come from the Host header.
;;
;; handle CATCHES: on a reactor an uncaught Lisp error is a trap that takes the
;; whole instance down, and the host would have to throw the instance away. It
;; answers 500 with the condition's report instead, which is what every other
;; rontolisp transport does with a handler error.

(defpackage :clack.handler.cloudflare-workers
  (:use :cl)
  (:export :handle :run :stop))

(defun clack.handler.cloudflare-workers::%header-alist (table)
  ;; The headers JSON object -> the ((name . value) ...) alist the raw tuple
  ;; wants. nil (no headers) passes straight through.
  (if (null table)
      nil
      (let ((out nil))
        (maphash (lambda (name value) (setq out (cons (cons name value) out)))
                 table)
        (nreverse out))))

(defun clack.handler.cloudflare-workers::%header-pairs (alist)
  ;; The response header alist -> a JSON array of [name, value]. See the
  ;; envelope note above for why this is not an object.
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (nreverse out)))

(defun clack.handler.cloudflare-workers::%request-tuple (req)
  ;; The positional raw tuple %http-make-env consumes:
  ;;   (method request-uri header-alist body server-protocol url-scheme
  ;;    local-name local-port remote-addr remote-port)
  ;; The Host header supplies :server-name / :server-port, so the two
  ;; placeholders below never win when the host sends one.
  (list (or (gethash "method" req) "GET") (or (gethash "target" req) "/")
   (clack.handler.cloudflare-workers::%header-alist (gethash "headers" req))
   (rontolisp::%http-body-stream (gethash "body" req)) "HTTP/1.1"
   (gethash "scheme" req) "localhost" 80 (gethash "remote-addr" req) nil))

(defun clack.handler.cloudflare-workers::%envelope (status headers body)
  (rontolisp:json-stringify
   (rontolisp:plist-hash-table
    (list :status status
          :headers (clack.handler.cloudflare-workers::%header-pairs headers)
          :body body))))

(defun clack.handler.cloudflare-workers:handle (app request-json)
  "Run the Clack application APP against the JSON request REQUEST-JSON and
answer the JSON response. See the envelope in this file's header."
  (handler-case (let* ((req (rontolisp:json-parse request-json))
                       (env
                        (rontolisp::%http-make-env
                         (clack.handler.cloudflare-workers::%request-tuple
                          req)))
                       (triple
                        (rontolisp::%http-normalize-response
                         (funcall app env))))
                  (clack.handler.cloudflare-workers::%envelope (car triple)
                   (car (cdr triple)) (car (cdr (cdr triple)))))
    (error (e)
      (clack.handler.cloudflare-workers::%envelope 500
       (list (cons "content-type" "application/json"))
       (format nil "~a~%"
               (rontolisp:json-stringify
                (rontolisp:plist-hash-table
                 (list :error (format nil "~a" e)))))))))

;; clackup's protocol, present so (clack:clackup app :server
;; :cloudflare-workers) fails with a sentence rather than with "undefined
;; function RUN". A reactor owns no socket, so there is nothing for run to start
;; and nothing to stop. Making clackup itself work here is .todo/285 -- and
;; clackup ALREADY runs on a --no-wasi reactor when the caller passes
;; :silent t :debug nil; what is missing is the compiler-synthesized export.
(defun clack.handler.cloudflare-workers:run (app &rest ignored)
  (declare (ignore app ignored))
  (error
   "clack.handler.cloudflare-workers: clackup cannot run on a host-driven reactor -- there is no socket to bind. Export a function and call clack.handler.cloudflare-workers:handle from it instead."))

(defun clack.handler.cloudflare-workers:stop (server)
  (declare (ignore server))
  nil)
