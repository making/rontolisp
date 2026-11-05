;;;; http_body.lisp -- ../sdk-examples/http_body.lisp on the --no-gc SDK.
;;;;
;;;; Holds a response body until it is complete, and replaces it if it says "secret".

(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :http-body (:use :cl))

(in-package :http-body)

(proxy-wasm:define-filter
  (:on-response-headers (stream num-headers end-of-stream)
    ;; The body may change length, and this is the last moment to say so.
    (proxy-wasm:remove-header :response "content-length")
    :continue)
  (:on-response-body (stream body-size end-of-stream)
    (cond ((not end-of-stream) :pause)
          (t
           (proxy-wasm:with-buffer (address size :response-body 0 body-size)
             (when (proxy-wasm:search-bytes "secret" address size)
               (proxy-wasm:set-buffer :response-body 0 body-size
                (format nil "Original message body (~a bytes) redacted.~%"
                        body-size))))
           :continue))))
