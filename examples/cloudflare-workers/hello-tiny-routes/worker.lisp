;;; worker.lisp -- ../hello-clack with a routing library instead of one defun.
;;;
;;; Same three moves: quickload, define the application, clackup -- only the
;;; middle one changed. ../hello-clack's header explains the rest.
;;;
;;; "tiny-routes/lite" is the opt-in system whose ppcre-free path-template
;;; matcher keeps the regex engine out of the module: it takes literal
;;; characters and :name tokens, and refuses a regex-shaped template when the
;;; route is built. Sizes: ../httpbin-tiny-routes.

(ql:quickload '("clack" "clack-handler-reactor" "tiny-routes/lite"))

;; The route macros, path-parameter, ok and not-found are tiny-routes' exports.
(defpackage :hello-tiny-routes (:use :cl :tiny-routes))
(in-package :hello-tiny-routes)

;; A route answers, or returns nil to DECLINE so the next one is tried -- which
;; is all "*" needs to be the 404. ok/not-found set no headers, so
;; src/index.js's `new Response` supplies the text/plain content type.
(define-routes *app*
  (define-get "/" ()
    (ok (format nil "Hello from tiny-routes on Cloudflare Workers!~%")))
  (define-get "/hello/:name" (req)
    (ok (format nil "Hello, ~a!~%" (path-parameter req :name))))
  (define-any "*" (req)
    (not-found (format nil "no route for ~a~%" (path-info req)))))

(clack:clackup *app* :server :reactor :use-thread nil)
