;;; ningle: the application is a CLOS OBJECT and a route is an assignment, so
;;; routes can be added anywhere -- in a loop, from another file, at run time.
;;; A controller returns the BODY and says the rest by mutating ningle:*response*
;;; (the response object bound for this request); the 404 is ningle:not-found, a
;;; METHOD on the application class, not a route at the bottom of a list.

(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

(defvar *app* (make-instance 'ningle:app))

;; A controller does not have to be a function: a bare value IS one.
(setf (ningle:route *app* "/")
      (format nil "Hello from ningle on Cloudflare Workers!~%"))

;; A :name token binds one path segment into the parameter alist.
(setf (ningle:route *app* "/hello/:name")
      (lambda (params) (format nil "Hello, ~a!~%" (cdr (assoc :name params)))))

(defmethod ningle:not-found ((app ningle:app))
  (setf (lack.response:response-status ningle:*response*) 404)
  (format nil "no route for ~a~%"
          (lack.request:request-path-info ningle:*request*)))

(clack:clackup *app* :server :reactor :use-thread nil)
