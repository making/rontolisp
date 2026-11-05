;;;; gateway.lisp -- an HTTP gateway filter for Envoy, on the SDK in proxy-wasm.lisp.
;;;;
;;;; Every request, in order:
;;;;
;;;;   - counts it in shared data, across every worker's VM, and hands the number
;;;;     to the upstream as `x-gateway-request`;
;;;;   - answers 403 itself for a path under a configured blocked prefix;
;;;;   - under `/guarded`, asks the authorization cluster about `x-user` with an
;;;;     outgoing HTTP call, holding the request until the reply arrives;
;;;;   - holds a request body back until it is complete, answering 422 if it holds
;;;;     the configured forbidden word (the upstream has the headers by then, and
;;;;     the refusal resets that request);
;;;;   - stamps the response, removes `x-internal-secret`, upper-cases the body under
;;;;     `/shout`, and logs one access line with the time the whole exchange took.
;;;;
;;;; A tick publishes the shared request count and the top of the bump heap as
;;;; gauges. Every option comes from the plugin configuration (the JSON in envoy.yaml).

(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :gateway (:use :cl))

(in-package :gateway)

(defclass gateway-root (proxy-wasm:root-context)
  ((blocked-prefixes :initform '() :accessor blocked-prefixes)
   (forbidden-word :initform nil :accessor forbidden-word)
   (authz-cluster :initform nil :accessor authz-cluster)
   (banner :initform "rontolisp" :accessor banner)
   (blocked-counter :initform nil :accessor blocked-counter)
   (requests-gauge :initform nil :accessor requests-gauge)
   (heap-gauge :initform nil :accessor heap-gauge)))

(defclass gateway-stream (proxy-wasm:http-context)
  ((path :initform "" :accessor stream-path)
   (method :initform "" :accessor stream-method)
   (status :initform "-" :accessor stream-status)
   (started-at :initform 0 :accessor started-at)
   (shout :initform nil :accessor shout-p)))

(defun prefix-p (prefix string)
  (and (>= (length string) (length prefix))
       (string= prefix string :end2 (length prefix))))

;;; --- configuration -------------------------------------------------------

(defmethod proxy-wasm:on-configure ((root gateway-root) configuration-size)
  (when (plusp configuration-size)
    (let ((config
           (rontolisp:json-parse
            (proxy-wasm:get-buffer :plugin-configuration))))
      (setf (blocked-prefixes root)
            (coerce (gethash "blocked_prefixes" config #()) 'list)
            (forbidden-word root) (gethash "forbidden_word" config)
            (authz-cluster root) (gethash "authz_cluster" config) (banner root)
            (gethash "banner" config "rontolisp"))
      (proxy-wasm:set-tick-period (gethash "tick_milliseconds" config 1000))))
  (setf (blocked-counter root)
        (proxy-wasm:define-metric :counter "gateway.blocked")
        (requests-gauge root)
        (proxy-wasm:define-metric :gauge "gateway.requests_seen")
        (heap-gauge root) (proxy-wasm:define-metric :gauge "gateway.heap_top"))
  (proxy-wasm:log-info "gateway: plugin ~a configured, blocking ~a"
                       (proxy-wasm:get-property "plugin_name")
                       (blocked-prefixes root))
  t)

(defmethod proxy-wasm:on-tick ((root gateway-root))
  (proxy-wasm:record-metric (heap-gauge root) (proxy-wasm:heap-top))
  (let ((count (proxy-wasm:shared-data "gateway.requests")))
    (when count
      (proxy-wasm:record-metric (requests-gauge root) (parse-integer count)))))

(defmethod proxy-wasm:make-http-context ((root gateway-root) id)
  (make-instance 'gateway-stream :id id :root root))

;;; --- the request ---------------------------------------------------------

;; Compare-and-swap, because every worker thread has a VM of its own and this is the
;; one number they share. A version that moved underneath means someone else won.
(defun next-request-number ()
  (loop
    (multiple-value-bind (value version)
        (proxy-wasm:shared-data "gateway.requests")
      (let ((next (1+ (if value (parse-integer value) 0))))
        (when (proxy-wasm:set-shared-data "gateway.requests"
                                          (princ-to-string next) version)
          (return next))))))

(defun refuse (status reason &optional headers)
  (proxy-wasm:send-local-response status
   :details "gateway"
   :body (format nil "{\"refused\":\"~a\"}~%" reason)
   :headers (append '(("content-type" . "application/json")) headers))
  :pause)

(defmethod proxy-wasm:on-request-headers
    ((stream gateway-stream) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  (let* ((root (proxy-wasm:context-root stream))
         (path (or (proxy-wasm:get-header :request ":path") ""))
         (blocked
          (find-if (lambda (prefix) (prefix-p prefix path))
                   (blocked-prefixes root))))
    (setf (stream-path stream) path (stream-method stream)
          (or (proxy-wasm:get-header :request ":method") "") (started-at stream)
          (proxy-wasm:current-time-nanos) (shout-p stream)
          (prefix-p "/shout" path))
    (proxy-wasm:set-header :request "x-gateway-request"
                           (princ-to-string (next-request-number)))
    (cond (blocked
           (proxy-wasm:increment-metric (blocked-counter root))
           (proxy-wasm:log-warn "gateway: ~a is under blocked prefix ~a" path
                                blocked)
           (refuse 403 "blocked path" '(("x-blocked-by" . "gateway"))))
          ((prefix-p "/guarded" path) (ask-authorization stream root))
          ;; Not :pause, even for a body about to be inspected: a pause here stops the
          ;; body callbacks too. ON-REQUEST-BODY holds the body back by itself.
          (t :continue))))

(defun ask-authorization (stream root)
  (let ((user (proxy-wasm:get-header :request "x-user")))
    (cond ((null user) (refuse 401 "no x-user"))
          ((proxy-wasm:http-call (authz-cluster root)
                                 (list (cons ":method" "GET")
                                  (cons ":path" (format nil "/authz/~a" user))
                                  (cons ":authority" "authz"))
                                 :timeout 2000)
           :pause)
          (t (refuse 503 "authorization is unavailable")))))

(defmethod proxy-wasm:on-http-call-response
    ((stream gateway-stream) token num-headers body-size num-trailers)
  (declare (ignore token num-headers body-size num-trailers))
  (cond ((equal (proxy-wasm:get-header :call-response ":status") "200")
         (proxy-wasm:set-header :request "x-authorized-by"
          (or (proxy-wasm:get-buffer :call-response-body) "authz"))
         (proxy-wasm:continue-request))
        (t (refuse 403 "not authorized"))))

(defmethod proxy-wasm:on-request-body
    ((stream gateway-stream) body-size end-of-stream)
  (declare (ignore body-size))
  (let ((word (forbidden-word (proxy-wasm:context-root stream))))
    (cond ((null word) :continue)
          ((not end-of-stream) :pause)
          ((search word (or (proxy-wasm:get-buffer :request-body) ""))
           (refuse 422 "forbidden word"))
          (t :continue))))

;;; --- the response --------------------------------------------------------

(defmethod proxy-wasm:on-response-headers
    ((stream gateway-stream) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  (setf (stream-status stream)
        (or (proxy-wasm:get-header :response ":status") "-"))
  (proxy-wasm:add-header :response "x-powered-by"
                         (banner (proxy-wasm:context-root stream)))
  (proxy-wasm:remove-header :response "x-internal-secret")
  (proxy-wasm:set-header :response "x-header-count"
   (princ-to-string (length (proxy-wasm:get-headers :response))))
  (when (shout-p stream)
    ;; The body is about to be rewritten, so the length the upstream stated is stale.
    (proxy-wasm:remove-header :response "content-length"))
  :continue)

(defmethod proxy-wasm:on-response-body
    ((stream gateway-stream) body-size end-of-stream)
  (declare (ignore body-size))
  (cond ((not (shout-p stream)) :continue)
        ((not end-of-stream) :pause)
        (t
         (proxy-wasm:set-buffer :response-body
                                (string-upcase
                                 (or (proxy-wasm:get-buffer :response-body)
                                     "")))
         :continue)))

(defmethod proxy-wasm:on-log ((stream gateway-stream))
  (proxy-wasm:log-info "gateway: ~a ~a -> ~a in ~a us" (stream-method stream)
   (stream-path stream) (stream-status stream)
   (floor (- (proxy-wasm:current-time-nanos) (started-at stream)) 1000)))

(proxy-wasm:set-root-context (lambda (id) (make-instance 'gateway-root :id id)))
