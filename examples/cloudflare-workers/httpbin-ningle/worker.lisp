;;; ningle: a mini httpbin on an application OBJECT. Four things make it ningle
;;; rather than a second route table:
;;;
;;;   * a route is an ASSIGNMENT, so the five echo endpoints are a loop;
;;;   * a controller returns the BODY and says the rest by mutating
;;;     ningle:*response* -- the (status headers body) triple never appears;
;;;   * a controller receives the PARAMETERS, and by then lack/request has
;;;     decoded the query string and PARSED the body, so nothing here reads a
;;;     stream or parses JSON;
;;;   * declining means NOT MATCHING (returning nil answers an empty body), so
;;;     every miss lands on ningle:not-found, a METHOD on the application class.

(ql:quickload '("clack" "clack-handler-reactor" "ningle"))

(defvar *app* (make-instance 'ningle:app))

;;; --- answering ---------------------------------------------------------------

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

;;; --- the echo endpoint -------------------------------------------------------

;; ONE controller for every echo endpoint: per method there is nothing left to
;; do. `args` is the query string and `form` the parsed body -- for a JSON post
;; that is the JSON object itself -- and the alist ningle hands the controller
;; is those two appended, which is why this one ignores it.
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

;;; --- the routes --------------------------------------------------------------

;; Rules are tried in the order they were assigned, so each path gets two: the
;; ONE method it answers, then :ANY for the 405. That leaves not-found with only
;; the answer it is really for.
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

;; :ANY used as itself rather than as a fallback.
(setf (ningle:route *app* "/anything" :method :ANY) #'echo)

;; myway's other rule spelling: a REGEX, whose capture groups arrive as
;; :captures. It fits because a code that is not three digits then matches no
;; rule at all -- where a "/status/:code" template would match "/status/teapot"
;; and leave the controller with nothing good to answer.
(setf (ningle:route *app* "/status/([0-9]{3})" :regexp t)
      (lambda (params)
        (let ((code (parse-integer (first (cdr (assoc :captures params))))))
          (set-status code)
          (respond-text code))))

(defmethod ningle:not-found ((app ningle:app))
  (set-status 404)
  (respond-json
   (rontolisp:plist-hash-table
    (list :error "not found"
          :path (lack.request:request-path-info ningle:*request*)))))

(clack:clackup *app* :server :reactor :use-thread nil)
