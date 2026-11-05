;;;; http_body.lisp -- the Rust SDK's http_body example, on the SDK in ../proxy-wasm.lisp.
;;;;
;;;; Holds a response body until it is complete, and replaces it if it says "secret".

;; A failure takes the VM down, as an `unwrap` in the Rust SDK does.
(eval-when (:compile-toplevel) (pushnew :proxy-wasm-trap *features*))

(require :proxy-wasm "../proxy-wasm.lisp")

(defpackage :http-body (:use :cl))

(in-package :http-body)

(defmethod proxy-wasm:on-response-headers
    ((stream proxy-wasm:http-context) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  ;; The body may change length, and this is the last moment to say so.
  (proxy-wasm:remove-header :response "content-length")
  :continue)

(defmethod proxy-wasm:on-response-body
    ((stream proxy-wasm:http-context) body-size end-of-stream)
  (cond ((not end-of-stream) :pause)
        (t
         (when (search "secret" (or (proxy-wasm:get-buffer :response-body) ""))
           (proxy-wasm:set-buffer
            :response-body (format nil
                            "Original message body (~a bytes) redacted.~%"
                            body-size)))
         :continue)))
