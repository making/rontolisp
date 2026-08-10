;;; worker.lisp -- a mini httpbin written the way ningle wants one written.
;;;
;;; The endpoints are ../httpbin-clack's and ../httpbin-tiny-routes': echo the
;;; request, answer a requested status, tell 405 from 404. What is deliberately
;;; NOT shared is the code, because ningle is not a spelling variant of the
;;; other two -- it is a different model, and writing it in their shape would
;;; hide every part of it worth reading:
;;;
;;;   * The application is an OBJECT and a route is an assignment. There is no
;;;     enclosing route-list form, so routes can be made in a loop, from another
;;;     file, or at run time.
;;;   * A controller returns the BODY and says everything else by mutating
;;;     ningle:*response*. The (status headers body) triple never appears.
;;;   * A controller receives the PARAMETERS, not the Clack environment -- and
;;;     by then lack/request has already decoded the query string and PARSED the
;;;     request body, so nothing here reads a stream or parses JSON.
;;;   * Declining is a matter of not matching. Returning nil is not a decline in
;;;     ningle (it answers an empty body), so the routes below are shaped so
;;;     that "no rule matched" is the only thing that can happen -- and
;;;     ningle:not-found, a METHOD on the application class, answers it.
;;;
;;; There is no size opt-in to offer the way tiny-routes has one: myway compiles
;;; every rule to a cl-ppcre scanner, so the regex engine is genuinely reachable
;;; and the tree-shaker is right to keep it. Sizes: the README beside this file.

(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

(defpackage :httpbin-ningle (:use :cl))
(in-package :httpbin-ningle)

(defvar *app* (make-instance 'ningle:app))

;;; --- answering -------------------------------------------------------------

;; ningle's process-response: a controller's return value IS the body (a string,
;; here), and status and headers are set on *response*, the response object
;; bound for the current request.

(defun respond-json (object)
  (setf (lack.response:response-headers ningle:*response*)
        (list :content-type "application/json"))
  (format nil "~a~%" (rontolisp:json-stringify object)))

(defun respond-text (text)
  (setf (lack.response:response-headers ningle:*response*)
        (list :content-type "text/plain; charset=utf-8"))
  (format nil "~a~%" text))

(defun set-status (code)
  (setf (lack.response:response-status ningle:*response*) code))

;;; --- the echo endpoint -----------------------------------------------------

;; ONE controller for every echo endpoint, because in ningle there is nothing
;; per-method left for one to do: the request arrives decoded. `args` is the
;; query string and `form` the parsed body -- for a JSON post that is the JSON
;; object, parsed by lack/request before this function ran -- and the alist
;; ningle hands the controller is those two appended, which is why `params` is
;; enough for most applications and why this one ignores it.
(defun echo (params)
  (declare (ignore params))
  (let ((request ningle:*request*))
    (respond-json
     (rontolisp:plist-hash-table
      (list :method (symbol-name (lack.request:request-method request))
            :path (lack.request:request-path-info request)
            :args (rontolisp:alist-hash-table
                   (lack.request:request-query-parameters request))
            :form (rontolisp:alist-hash-table
                   (lack.request:request-body-parameters request))
            :headers (lack.request:request-headers request))))))

;;; --- the routes ------------------------------------------------------------

;; A route is a `setf`, so the five echo endpoints are a LOOP rather than five
;; copies of one line. Each path gets two rules, and the order they are assigned
;; in is the order they are tried in:
;;
;;   1. the ONE method that path answers -> echo
;;   2. :ANY, which myway matches for every method -> 405, naming the first
;;
;; So the 405 is a route like any other, and ningle:not-found below is left with
;; only the answer it is really for: no such path at all.
(dolist (endpoint
         '(("/get" . :GET) ("/post" . :POST) ("/put" . :PUT) ("/patch" . :PATCH)
           ("/delete" . :DELETE)))
  (let ((path (car endpoint)) (allowed (cdr endpoint)))
    (setf (ningle:route *app* path :method allowed) #'echo)
    (setf (ningle:route *app* path :method :ANY)
          (lambda (params)
            (declare (ignore params))
            (set-status 405)
            (respond-json
             (rontolisp:plist-hash-table
              (list :error "method not allowed"
                    :allowed (symbol-name allowed))))))))

;; And the endpoint that answers any method it is asked with, which is what
;; :ANY says when it is not being used as a fallback.
(setf (ningle:route *app* "/anything" :method :ANY) #'echo)

;; The status endpoint. `:regexp t` is myway's other rule spelling -- the URL is
;; a REGEX rather than a template, and its capture groups arrive as :captures --
;; and it is the one that fits here: a code that is not three digits matches no
;; rule, which is a decline. A "/status/:code" template would match
;; "/status/teapot" too and leave the controller with nothing to do about it but
;; answer something.
(setf (ningle:route *app* "/status/([0-9]{3})" :regexp t)
      (lambda (params)
        (let ((code (parse-integer (first (cdr (assoc :captures params))))))
          (set-status code)
          (respond-text code))))

;; ningle's own extension point: a generic function on the application class,
;; called when no rule matched. Overriding it is how an application answers
;; that -- the default sets 404 and returns nil, i.e. an empty body.
(defmethod ningle:not-found ((app ningle:app))
  (set-status 404)
  (respond-json
   (rontolisp:plist-hash-table
    (list :error "not found"
          :path (lack.request:request-path-info ningle:*request*)))))

(clack:clackup *app* :server :reactor :use-thread nil)
