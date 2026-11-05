;;;; http_headers.lisp -- ../sdk-examples/http_headers.lisp on the --no-gc SDK.
;;;;
;;;; Logs both heads, and answers /hello itself.

(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :http-headers (:use :cl))

(in-package :http-headers)

(defun log-pairs (stream arrow map)
  (proxy-wasm:do-headers (name value map)
    (proxy-wasm:log-info "#~a ~a ~a: ~a" stream arrow name value)))

(proxy-wasm:define-filter
  (:on-request-headers (stream num-headers end-of-stream)
    (log-pairs stream "->" (proxy-wasm:map-type :request))
    (cond ((string= (proxy-wasm:get-header :request ":path") "/hello")
           (proxy-wasm:send-local-response 200
            :headers '(("Hello" . "World") ("Powered-By" . "proxy-wasm"))
            :body (format nil "Hello, World!~%"))
           :pause)
          (t :continue)))
  (:on-response-headers (stream num-headers end-of-stream)
    (log-pairs stream "<-" (proxy-wasm:map-type :response))
    :continue)
  (:on-log (stream) (proxy-wasm:log-info "#~a completed." stream)))
