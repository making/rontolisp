;; Loads the REAL tiny-routes (BSD 3-Clause, Johnny Ruiz) via asdf:load-system
;; and routes requests through it. Run with:
;;   rontolisp examples/asdf/tiny-routes-demo.lisp --system-path src/test/resources/tiny-routes:src/test/resources/cl-ppcre
;; tiny-routes targets Clack, and this demo is deliberately TRANSPORT-FREE: it
;; calls the composed handler with hand-built request plists, so it runs on all
;; four backends -- WASM Preview 1 has no incoming TCP. To serve the same routes
;; over HTTP, hand the composed handler to clack:clackup (see
;; doc/en/guides/clack.md); that works on the interpreter, the JVM and the WASM
;; component.
;; The demo uses with-input-from-string, whose expansion is an unwind-protect, so
;; BOTH wasm run commands need -W exceptions=y.

(asdf:load-system :tiny-routes)

;; An application uses the library from its own package; :tiny is tiny-routes'
;; own nickname.
(defpackage :tiny-routes-demo (:use :cl :tiny-routes))
(in-package :tiny-routes-demo)

;; define-routes binds a handler that tries each route in turn and answers with
;; the first non-nil response. Each define-VERB is itself a handler: a lambda
;; wrapped in the method matcher and the path-template matcher, so its arguments
;; are the path template, the request lambda list and a body.
(define-routes *app*
  (define-get "/hello" () (ok "hello world"))
  ;; A :name segment in the template binds a path parameter.
  (define-get "/users/:id" (req)
    (ok (format nil "user ~A" (path-parameter req :id))))
  ;; wrap-query-parameters below parses the query string; its keys are interned
  ;; VERBATIM, so "q" is the |q| keyword, not :Q.
  (define-get "/search" (req)
    (ok (format nil "q=~A" (getf (request-get req :query-parameters) :|q|))))
  ;; wrap-request-body below reads the request body stream into a string.
  (define-post "/echo" (req) (ok (format nil "echo:~A" (request-body req))))
  (define-put "/put" () (created "/put" "made"))
  ;; A route with no template at all: match on anything you like.
  (define-route (req)
    (when (ppcre:scan "^/v[0-9]+/ping$" (path-info req)) (ok "pong")))
  ;; "*" matches every path and :any every method, so this is the fallback.
  (define-any "*" () (not-found "nope")))

;; pipe threads the handler through middleware left to right.
(defparameter *handler*
  (pipe *app* (wrap-request-body) (wrap-query-parameters)))

;; The Clack request environment, as a server would hand it over.
(defun env (method path &optional (query ""))
  (list :request-method method
        :request-uri path
        :path-info path
        :url-scheme "http"
        :query-string query))

(defun show (res)
  (format t "~A ~A ~A~%" (response-status res) (response-body res)
          (response-headers res)))

(show (funcall *handler* (env :get "/hello")))
(show (funcall *handler* (env :get "/users/42")))
(show (funcall *handler* (env :get "/search" "q=lisp&n=2")))
(show (funcall *handler* (env :get "/v2/ping")))
(show (funcall *handler* (env :put "/put")))
(show (funcall *handler* (env :get "/zzz")))
;; The method matcher declines a POST to a GET-only route, so it falls through.
(show (funcall *handler* (env :post "/hello")))

;; A request WITH a body: :raw-body is the stream, :content-length its size.
(with-input-from-string (in "abc")
  (let ((request
         (append (env :post "/echo") (list :content-length 3 :raw-body in))))
    (show (funcall *handler* request))))

;; Response combinators, as middleware and directly. A route is an ordinary
;; value, so it can be named and wrapped on its own.
(defparameter *ct-route* (define-get "/ct" () (ok "y")))

(defparameter *typed* (wrap-response-content-type *ct-route* "text/plain"))

(show (funcall *typed* (env :get "/ct")))
(show (clone-response (ok "x") :status 202))
