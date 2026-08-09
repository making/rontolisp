;;; worker.lisp -- ../hello-clack with ningle instead of one defun.
;;;
;;; Same three moves: quickload, define the application, clackup -- only the
;;; middle one changed. ../hello-clack's header explains the rest.
;;;
;;; ningle is the other shape a Clack router can take: not a list of handler
;;; functions but a CLOS application object you hang routes on, so the
;;; application is `(make-instance 'ningle:app)` and each route is a `setf`.
;;; Sizes: the README beside this file.

(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

(defpackage :hello-ningle (:use :cl))
(in-package :hello-ningle)

(defvar *app* (make-instance 'ningle:app))

;; A controller does not have to be a function: a bare value IS one, and ningle
;; answers it as the body. `src/index.js`'s `new Response` supplies the
;; text/plain content type, since nothing here sets a header.
(setf (ningle:route *app* "/")
      (format nil "Hello from ningle on Cloudflare Workers!~%"))

;; A :name token in the template binds one path segment; the controller reads it
;; out of the parameter alist, keyed by the keyword.
(setf (ningle:route *app* "/hello/:name")
      (lambda (params) (format nil "Hello, ~a!~%" (cdr (assoc :name params)))))

;; The 404 is a METHOD, not a route: ningle's own extension point, called when
;; no rule matched. `*response*` is the response object for THIS request, so
;; setting its status is how a controller answers with anything but 200.
(defmethod ningle:not-found ((app ningle:app))
  (setf (lack.response:response-status ningle:*response*) 404)
  (format nil "no route for ~a~%"
          (lack.request:request-path-info ningle:*request*)))

(clack:clackup *app* :server :reactor :use-thread nil)
