;;;; http-reactor.lisp -- the ONE host-driven-reactor transport, shared by both
;;;; Clack handler backends: the reactor leg of clack-handler-rontolisp
;;;; (#+rontolisp-reactor, i.e. --no-wasi / --no-gc) and the explicit
;;;; clack-handler-reactor backend delegate here, so the two cannot drift and a
;;;; program that mixes them still stores ONE application.
;;;;
;;;; A reactor owns no socket: the host has already parsed the request and
;;;; calls a function -- the synthesized handle-request wasm-export on the WASM
;;;; backends (eval/HttpReactorInliner), %http-reactor-dispatch directly on the
;;;; interpreter and the JVM. There is no re-implementation of the server model
;;;; here: %http-reactor-handle builds the raw tuple and rides the same
;;;; backend-free rontolisp::%http-make-env / %http-normalize-response entry
;;;; points every other transport meets in (http-server.lisp), so it inherits
;;;; the percent-decoding, the "?" split, the header lowercasing and
;;;; comma-joining, the Host split, the content-length parsing and the buffered
;;;; :raw-body, and CANNOT drift from what a served request sees. What is left
;;;; is the JSON envelope, and that is all this file is.
;;;;
;;;; The envelope, both directions (the host speaks JSON because the other side
;;;; of a reactor boundary is normally JavaScript):
;;;;
;;;;   in   {"method": "GET",            ; defaults to "GET"
;;;;         "target": "/path?a=1",      ; RAW -- path and query still joined and
;;;;                                     ;   still encoded. %http-make-env owns
;;;;                                     ;   that split; a pre-split path would
;;;;                                     ;   leave :query-string nil.
;;;;         "headers": {"host": "..."}, ; content-length MUST be here for a body:
;;;;                                     ;   lack/request parses nothing without
;;;;                                     ;   it, and a chunked request carries
;;;;                                     ;   none, so the host sets it from the
;;;;                                     ;   bytes it actually read.
;;;;         "body": "...",              ; already read; "" or absent for none
;;;;         "scheme": "https",          ; optional, defaults to "http"
;;;;         "remote-addr": "203.0.113.7"}  ; optional, Clack's :remote-addr
;;;;
;;;;   out  {"status": 200,
;;;;         "headers": [["content-type", "application/json"]],  ; an ARRAY of
;;;;                                     ;   pairs, not an object: a name may
;;;;                                     ;   repeat, and an application that sets
;;;;                                     ;   two cookies answers two Set-Cookie
;;;;                                     ;   headers. The host feeds it to the
;;;;                                     ;   Headers constructor as is.
;;;;         "body": "..."}
;;;;
;;;; :remote-port is always nil (a reactor host exposes no peer port), and
;;;; :server-name / :server-port come from the Host header.
;;;;
;;;; %http-reactor-handle CATCHES: on a reactor an uncaught Lisp error is a trap
;;;; that takes the whole instance down, and the host would have to throw the
;;;; instance away. It answers 500 with the condition's report instead, which is
;;;; what every other rontolisp transport does with a handler error. Consequence:
;;;; a program that loads this library compiles in EH mode on the WASM backends.
;;;;
;;;; Unlike http-server.lisp this library is NOT self-contained: it names
;;;; rontolisp:json-parse / json-stringify / plist-hash-table (the JsonLibrary
;;;; splice picks the call sites up downstream) and http-server.lisp's
;;;; %http-make-env / %http-normalize-response / %http-body-stream (the
;;;; HttpServerLibrary splice runs after this one).

(defvar rontolisp::%http-reactor-app nil)

(defun rontolisp::%http-reactor-register (app)
  ;; The one application store, shared by both handler backends' run. A
  ;; function rather than a bare setq at the call sites so the interpreter's
  ;; lazy library load triggers on the FIRST touch, whichever entry point that
  ;; is.
  (setq rontolisp::%http-reactor-app app)
  nil)

(defun rontolisp::%http-reactor-header-alist (table)
  ;; The headers JSON object -> the ((name . value) ...) alist the raw tuple
  ;; wants. nil (no headers) passes straight through.
  (if (null table)
      nil
      (let ((out nil))
        (maphash (lambda (name value) (setq out (cons (cons name value) out)))
                 table)
        (nreverse out))))

(defun rontolisp::%http-reactor-header-pairs (alist)
  ;; The response header alist -> a JSON array of [name, value]. See the
  ;; envelope note above for why this is not an object. A VECTOR, not a list:
  ;; a response without headers (tiny-routes' (ok "...") builds one) must
  ;; reach the host as [], and json-stringify renders an empty LIST as false,
  ;; which the Headers constructor on the other side rejects.
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (coerce (nreverse out) 'vector)))

(defun rontolisp::%http-reactor-request-tuple (req)
  ;; The positional raw tuple %http-make-env consumes:
  ;;   (method request-uri header-alist body server-protocol url-scheme
  ;;    local-name local-port remote-addr remote-port)
  ;; The Host header supplies :server-name / :server-port, so the two
  ;; placeholders below never win when the host sends one.
  (list (or (gethash "method" req) "GET") (or (gethash "target" req) "/")
        (rontolisp::%http-reactor-header-alist (gethash "headers" req))
        (rontolisp::%http-body-stream (gethash "body" req)) "HTTP/1.1"
        (gethash "scheme" req) "localhost" 80 (gethash "remote-addr" req) nil))

(defun rontolisp::%http-reactor-envelope (status headers body)
  (rontolisp:json-stringify
   (rontolisp:plist-hash-table
    (list :status status
          :headers (rontolisp::%http-reactor-header-pairs headers)
          :body body))))

(defun rontolisp::%http-reactor-handle (app request-json)
  "Run the Clack application APP against the JSON request REQUEST-JSON and
answer the JSON response. See the envelope in this file's header."
  (handler-case (let* ((req (rontolisp:json-parse request-json))
                       (env
                        (rontolisp::%http-make-env
                         (rontolisp::%http-reactor-request-tuple req)))
                       (triple
                        (rontolisp::%http-normalize-response
                         (funcall app env))))
                  (rontolisp::%http-reactor-envelope (car triple)
                                                     (car (cdr triple))
                                                     (car (cdr (cdr triple)))))
    (error (e)
      (rontolisp::%http-reactor-envelope 500
       (list (cons "content-type" "application/json"))
       (format nil "~a~%"
               (rontolisp:json-stringify
                (rontolisp:plist-hash-table
                 (list :error (format nil "~a" e)))))))))

(defun rontolisp::%http-reactor-dispatch (request-json)
  "Run the application the handler backend stored against the JSON request
REQUEST-JSON and answer the JSON response. The host's entry point: on the WASM
backends the synthesized wasm-export calls this, on every other backend the
host calls it directly."
  (rontolisp::%http-reactor-handle rontolisp::%http-reactor-app request-json))
