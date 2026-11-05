;;;; http_body.lisp -- the Rust SDK's http_body example, on the SDK in ../proxy-wasm.lisp.
;;;;
;;;; Holds a response body until it is complete, and replaces it if it says "secret".

(require :proxy-wasm "../proxy-wasm.lisp")

(defpackage :http-body (:use :cl))

(in-package :http-body)

(proxy-wasm:define-filter
  ;; A failure takes the VM down, as an `unwrap` in the Rust SDK does.
  (:on-failure :trap)
  (:on-response-headers (stream num-headers end-of-stream)
    ;; The body may change length, and this is the last moment to say so.
    (proxy-wasm:remove-header :response "content-length")
    :continue)
  (:on-response-body (stream body-size end-of-stream)
    (cond ((not end-of-stream) :pause)
          (t
           (proxy-wasm:with-buffer (address size :response-body 0 body-size)
             (when (proxy-wasm:search-bytes "secret" address size)
               (proxy-wasm:set-buffer
                :response-body (format nil
                                "Original message body (~a bytes) redacted.~%"
                                body-size))))
           :continue))))
