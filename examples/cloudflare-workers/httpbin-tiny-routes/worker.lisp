;;; tiny-routes: a mini httpbin COMPOSED out of routes and middleware. `pipe`
;;; threads the route table through wrap-request-body (the raw body, read) and
;;; wrap-query-parameters (the query string, parsed), and the JSON group through
;;; wrap-response-content-type -- so the echo handlers neither drain a stream nor
;;; set a header. A route answers or returns nil to DECLINE, which is how a wrong
;;; method reaches the catch-all and how /status/:code refuses a bad code.
;;; `tiny` is the library's own nickname, so nothing has to be imported.
;;;
;;; "tiny-routes/lite" is the opt-in system whose ppcre-free path-template
;;; matcher keeps the regex engine out of the module.

(ql:quickload '("clack" "clack-handler-reactor" "tiny-routes/lite"))

;;; --- the handlers ------------------------------------------------------------

(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

(defun json (object) (format nil "~a~%" (rontolisp:json-stringify object)))

;; The echo document. Everything it reports is already on the request, so this
;; only shapes JSON: plist-hash-table turns tiny-routes' query plist and this
;; plist into the string-keyed hash tables json-stringify renders as objects.
(defun echo (req with-body)
  (let ((info
         (rontolisp:plist-hash-table
          (list :args (rontolisp:plist-hash-table
                       (tiny:request-get req :query-parameters))
                :headers (tiny:request-headers req)
                :method (symbol-name (tiny:request-method req))
                :path (tiny:path-info req)))))
    (when with-body
      (let ((body (tiny:request-body req "")))
        (setf (gethash "data" info) body)
        (setf (gethash "json" info) (body-json body))))
    (tiny:ok (json info))))

;; Nothing claimed the request. The table is not a second dispatch: it is what
;; tells a known path that DECLINED on its method (405, naming the one that
;; works) from a path no route has (404).
(defparameter *endpoints*
  '(("/get" . :GET) ("/post" . :POST) ("/put" . :PUT) ("/patch" . :PATCH)
    ("/delete" . :DELETE)))

(defun no-route (req)
  (let* ((path (tiny:path-info req))
         (allowed (cdr (assoc path *endpoints* :test #'string=))))
    (if allowed
        (tiny:method-not-allowed
         (json
          (rontolisp:plist-hash-table
           (list :error "method not allowed" :allowed (symbol-name allowed)))))
        (tiny:not-found
         (json
          (rontolisp:plist-hash-table (list :error "not found" :path path)))))))

;;; --- the routes --------------------------------------------------------------

;; Each route answers the one method it names and declines every other, so the
;; catch-all at the bottom is reached by both a wrong method and an unknown path.
(tiny:define-routes *json-routes*
  (tiny:define-get "/get" (req) (echo req nil))
  (tiny:define-post "/post" (req) (echo req t))
  (tiny:define-put "/put" (req) (echo req t))
  ;; tiny-routes has no define-patch; matching the method is all the other
  ;; macros add over define-any, and that matcher is exported.
  (tiny:wrap-request-matches-method
   (tiny:define-any "/patch" (req) (echo req t)) :patch)
  (tiny:define-delete "/delete" (req) (echo req t))
  (tiny:define-any "*" (req) (no-route req)))

;; The one endpoint that does not answer JSON, so it is its own group with its
;; own content type. A :code that is not a number declines.
(defparameter *status-route*
  (tiny:pipe (tiny:define-get "/status/:code" (req)
               (let ((code
                      (parse-integer (tiny:path-parameter req :code)
                                     :junk-allowed t)))
                 (when code
                   (tiny:make-response :status code
                                       :body (format nil "~a~%" code)))))
             (tiny:wrap-response-content-type "text/plain; charset=utf-8")))

(tiny:define-routes *routes*
  *status-route*
  (tiny:pipe *json-routes*
             (tiny:wrap-response-content-type "application/json")))

(defparameter *app*
  (tiny:pipe *routes* (tiny:wrap-request-body) (tiny:wrap-query-parameters)))

(clack:clackup *app* :server :reactor :use-thread nil)
