;; The tiny-routes flavour of httpbin-clack.lisp: the application is COMPOSED
;; out of routes and middleware. `pipe` threads the route table through
;; wrap-request-body (the raw body, read) and wrap-query-parameters (the query
;; string, parsed), and the JSON group through wrap-response-content-type -- so
;; the echo handlers neither drain a stream nor set a header. A route answers or
;; returns nil to DECLINE, which is how a wrong method reaches the catch-all and
;; how /status/:code refuses a bad code.
;;
;; "tiny-routes/lite" is the ppcre-free opt-in system; the full "tiny-routes"
;; runs this file unchanged and costs a regex engine.
;;
;; Run (the first run downloads clack/lack/tiny-routes into ~/.rontolisp/quicklisp):
;;   rontolisp examples/net/httpbin-tiny-routes.lisp
;;   rontolisp examples/net/httpbin-tiny-routes.lisp -o HttpbinTinyRoutes.class && \
;;     java -cp rontolisp-exec.jar:. HttpbinTinyRoutes
;;   rontolisp examples/net/httpbin-tiny-routes.lisp -o httpbin-tiny-routes.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
;;       httpbin-tiny-routes.wasm
;; Preview 1 has no incoming TCP: the program compiles, clackup fails at run
;; time. Under --component the host owns the socket, so :port is ignored.
;;
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post
;;   curl http://127.0.0.1:8080/status/418

(ql:quickload '("clack" "tiny-routes/lite"))

(defpackage :httpbin-tiny-routes (:use :cl :tiny-routes))
(in-package :httpbin-tiny-routes)

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
                       (request-get req :query-parameters))
                :headers (request-headers req)
                :method (symbol-name (request-method req))
                :path (path-info req)))))
    (when with-body
      (let ((body (request-body req "")))
        (setf (gethash "data" info) body)
        (setf (gethash "json" info) (body-json body))))
    (ok (json info))))

;; Nothing claimed the request. The table is not a second dispatch: it is what
;; tells a known path that DECLINED on its method (405, naming the one that
;; works) from a path no route has (404).
(defparameter *endpoints*
  '(("/get" . :GET) ("/post" . :POST) ("/put" . :PUT) ("/patch" . :PATCH)
    ("/delete" . :DELETE)))

(defun no-route (req)
  (let* ((path (path-info req))
         (allowed (cdr (assoc path *endpoints* :test #'string=))))
    (if allowed
        (method-not-allowed
         (json
          (rontolisp:plist-hash-table
           (list :error "method not allowed" :allowed (symbol-name allowed)))))
        (not-found
         (json
          (rontolisp:plist-hash-table (list :error "not found" :path path)))))))

;;; --- the routes --------------------------------------------------------------

;; Each route answers the one method it names and declines every other, so the
;; catch-all at the bottom is reached by both a wrong method and an unknown path.
(define-routes *json-routes*
  (define-get "/get" (req) (echo req nil))
  (define-post "/post" (req) (echo req t))
  (define-put "/put" (req) (echo req t))
  ;; tiny-routes has no define-patch; matching the method is all the other
  ;; macros add over define-any, and that matcher is exported.
  (wrap-request-matches-method (define-any "/patch" (req) (echo req t)) :patch)
  (define-delete "/delete" (req) (echo req t))
  (define-any "*" (req) (no-route req)))

(define-routes *routes*
  ;; The one endpoint that does not answer JSON, so it sets its own header and
  ;; stays outside the group. A :code that is not a number declines.
  (define-get "/status/:code" (req)
    (let ((code (parse-integer (path-parameter req :code) :junk-allowed t)))
      (when code
        (make-response :status code
                       :headers '(:content-type "text/plain; charset=utf-8")
                       :body (format nil "~a~%" code)))))
  (pipe *json-routes* (wrap-response-content-type "application/json")))

(defparameter *app* (pipe *routes* (wrap-request-body) (wrap-query-parameters)))

(clack:clackup *app* :server :rontolisp :port 8080 :use-thread nil)
