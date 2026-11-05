;;;; http_headers.lisp -- the Rust SDK's http_headers example, on the SDK in ../proxy-wasm.lisp.
;;;;
;;;; Logs both heads, and answers /hello itself.

(require :proxy-wasm "../proxy-wasm.lisp")

(defpackage :http-headers (:use :cl))

(in-package :http-headers)

(defun log-pairs (stream arrow pairs)
  (dolist (pair pairs)
    (proxy-wasm:log-info "#~a ~a ~a: ~a" (proxy-wasm:context-id stream) arrow
                         (car pair) (cdr pair))))

(defmethod proxy-wasm:on-request-headers
    ((stream proxy-wasm:http-context) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  (log-pairs stream "->" (proxy-wasm:get-headers :request))
  (cond ((equal (proxy-wasm:get-header :request ":path") "/hello")
         (proxy-wasm:send-local-response 200
          :headers '(("Hello" . "World") ("Powered-By" . "proxy-wasm"))
          :body (format nil "Hello, World!~%"))
         :pause)
        (t :continue)))

(defmethod proxy-wasm:on-response-headers
    ((stream proxy-wasm:http-context) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  (log-pairs stream "<-" (proxy-wasm:get-headers :response))
  :continue)

(defmethod proxy-wasm:on-log ((stream proxy-wasm:http-context))
  (proxy-wasm:log-info "#~a completed." (proxy-wasm:context-id stream)))
