;;;; error_log.lisp -- ../sdk-examples/error_log.lisp on the --no-gc SDK.
;;;;
;;;; A failed host call under the SDK's default: a line in the log, and the callback goes
;;;; on. /failed-call makes one, and a refused outgoing call answers NIL.

(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :error-log (:use :cl))

(in-package :error-log)

(proxy-wasm:define-filter
  (:on-request-headers (stream num-headers end-of-stream)
    (when (string= (proxy-wasm:get-header :request ":path") "/failed-call")
      ;; There is no call's reply to write to here.
      (proxy-wasm:set-buffer :call-response-body 0 1 "x"))
    (let ((pairs (proxy-wasm:make-pairs 1 32)))
      (proxy-wasm:add-pair pairs "x-outgoing"
                           (if (proxy-wasm:http-call "no-such-cluster"
                                                     '((":method" . "GET")
                                                       (":path" . "/")
                                                       (":authority" . "x")))
                               "sent"
                               "refused"))
      (proxy-wasm:send-local-response 200
                                      :headers (proxy-wasm:finish-pairs pairs)))
    :pause))
