;;; tiny-routes: an application is COMPOSED. `define-routes` builds a handler
;;; that tries each route and takes the first non-nil answer, so returning nil
;;; DECLINES and "*" is the 404; `ok` / `not-found` name the status instead of
;;; spelling the triple; and `pipe` threads the whole table through middleware
;;; -- here the one that gives every response its content type.
;;;
;;; "tiny-routes/lite" is the opt-in system whose ppcre-free path-template
;;; matcher keeps the regex engine out of the module. It takes literal
;;; characters and :name tokens, and refuses a regex-shaped template.

(ql:quickload '("clack" "clack-handler-reactor" "tiny-routes/lite"))

(defpackage :hello-tiny-routes (:use :cl :tiny-routes))
(in-package :hello-tiny-routes)

(define-routes *routes*
  (define-get "/" ()
    (ok (format nil "Hello from tiny-routes on Cloudflare Workers!~%")))
  (define-get "/hello/:name" (req)
    (ok (format nil "Hello, ~a!~%" (path-parameter req :name))))
  (define-any "*" (req)
    (not-found (format nil "no route for ~a~%" (path-info req)))))

(defparameter *app*
  (pipe *routes* (wrap-response-content-type "text/plain; charset=utf-8")))

(clack:clackup *app* :server :reactor :use-thread nil)
