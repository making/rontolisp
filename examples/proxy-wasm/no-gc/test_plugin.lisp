;;;; test_plugin.lisp -- ../sdk-examples/test_plugin.lisp on the --no-gc SDK: a plugin for
;;;; a host's tests, where the request's path picks what it does.
;;;;
;;;; Every failed host call fails the plugin, as an `unwrap` in the Rust SDK does.
;;;;
;;;; Build it without --no-wasi: /stdout writes to WASI's stdout, which a --no-wasi
;;;; module drops.

(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :test-plugin (:use :cl))

(in-package :test-plugin)

(defun spin () (loop))

;; Linear memory, which is what a host's memory limit bounds.
(defun hog ()
  (let ((size (* 1024 1024)))
    (loop (proxy-wasm:store8 (+ (proxy-wasm:alloc size) (- size 1)) 1))))

(defun count-metric (name value)
  (proxy-wasm:increment-metric (proxy-wasm:define-metric :counter name) value))

;; Keeps the buffer's length unless the plugin said otherwise: the buffer is read and
;; set back in upper case, in place.
(defun upcase-buffer (buffer size)
  (proxy-wasm:with-buffer (address length buffer 0 size)
    (dotimes (i length)
      (let ((byte (proxy-wasm:load8 (+ address i))))
        (when (<= 97 byte 122) (proxy-wasm:store8 (+ address i) (- byte 32)))))
    (proxy-wasm:set-buffer-bytes buffer 0 size address length)))

;; A count no bytes follow. Not a call that can fail: the host's answer is not read.
(defun set-malformed-pairs ()
  (let ((at (proxy-wasm:alloc 4)))
    (proxy-wasm:store8 at 255)
    (proxy-wasm:store8 (+ at 1) 255)
    (proxy-wasm:store8 (+ at 2) 255)
    (proxy-wasm:store8 (+ at 3) 127)
    (proxy-wasm::%proxy-set-header-map-pairs 0 (proxy-wasm::%string at 4))))

(defun set-pairs ()
  (let ((pairs (proxy-wasm:make-pairs 256 65536)))
    (proxy-wasm:do-headers (name value :request)
      (when (not (string= name "x-gone"))
        (proxy-wasm:add-pair pairs name value)))
    (proxy-wasm:add-pair pairs "x-pairs" "set")
    (proxy-wasm:set-headers :request (proxy-wasm:finish-pairs pairs))))

(defmacro path-case (stream &body clauses)
  (let ((path (gensym "PATH")))
    `(let ((,path (path ,stream)))
       (cond
        ,@(mapcar
           (lambda (clause)
             (let ((test (car clause)))
               `(,(cond ((eq test t) t)
                        ((listp test)
                         `(or ,@(mapcar (lambda (p) `(string= ,path ,p)) test)))
                        (t `(string= ,path ,test))) ,@(cdr clause))))
           clauses)))))

(proxy-wasm:define-filter
  (:on-failure :trap)
  (:state (path 256) (configuration 1024))
  (:on-configure (root size)
    (let ((configuration
           (or (proxy-wasm:get-buffer :plugin-configuration 0 size) "")))
      (setf (configuration root) configuration)
      (not (string= configuration "refuse"))))
  (:on-request-headers (stream count end-of-stream)
    (setf (path stream) (or (proxy-wasm:get-header :request ":path") ""))
    (path-case stream ("/spin-request" (spin) :continue)
     ("/trap-request" (proxy-wasm:panic) :continue)
     ("/host" (proxy-wasm:set-header :request "host" "elsewhere") :continue)
     ("/request-edit" (proxy-wasm:set-header :request "x-plugin" "request")
      :continue)
     ("/request-end"
      (proxy-wasm:set-header :request "x-end" (if end-of-stream "yes" "no"))
      :continue) ("/request-pairs" (set-pairs) :continue)
     ("/local"
      (proxy-wasm:send-local-response 403
       :headers '(("x-plugin" . "local") ("content-length" . "1"))
       :body (format nil "denied~%")) :pause)
     (("/request-replace" "/request-stream" "/request-then-trap")
      (proxy-wasm:remove-header :request "content-length") :continue)
     ("/stdout" (format t "to stdout~%") :continue)
     ("/pairs-malformed" (set-malformed-pairs) :continue) (t :continue)))
  (:on-request-body (stream size end-of-stream)
    (path-case stream
               ("/request-read"
                (proxy-wasm:with-buffer (address length :request-body 0 size)
                  (count-metric "body.bytes;kind=request" length)) :continue)
               ("/request-replace"
                (cond ((not end-of-stream) :pause)
                      (t
                       (upcase-buffer (proxy-wasm:buffer-type :request-body)
                                      size)
                       :continue)))
               (("/request-stream" "/request-then-trap")
                (upcase-buffer (proxy-wasm:buffer-type :request-body) size)
                :continue)
               ("/local-body"
                (when end-of-stream
                  (proxy-wasm:send-local-response 451
                   :body (format nil "read it~%"))) :pause)
               ("/request-trap" (proxy-wasm:panic) :continue) (t :continue)))
  (:on-response-headers (stream count end-of-stream)
    (path-case stream ("/spin-head" (spin)) ("/trap-head" (proxy-wasm:panic))
     ("/hog-head" (hog)) ("/local-late" (proxy-wasm:send-local-response 403))
     ("/framing" (proxy-wasm:set-header :response "transfer-encoding" "x"))
     ("/smuggle"
      (proxy-wasm:set-header :response "x-plugin"
       #.(format nil "a~c~cx-smuggled: 1" (code-char 13) (code-char 10))))
     ("/edit" (proxy-wasm:add-header :response "x-plugin" "edited"))
     ("/hog-fields"
      (loop
        (proxy-wasm:add-header :response "x-plugin"
                               "a value kept by the host")))
     ("/meters"
      (dotimes (i 100) (count-metric (format nil "plugin.meters;i=~a" i) 1)))
     ("/properties"
      (let ((route (or (proxy-wasm:get-property "route_name") ""))
            (code (proxy-wasm:get-property-integer "response" "code")))
        (proxy-wasm:add-header :response "x-route" route)
        (proxy-wasm:add-header :response "x-code" (princ-to-string code))
        (proxy-wasm:add-header :response "x-configuration"
         (configuration (proxy-wasm:context-root stream)))))
     ("/outgoing"
      (proxy-wasm:add-header :response "x-outgoing"
                             (if (proxy-wasm:http-call "upstream" '()
                                                       :timeout 1000)
                                 "sent"
                                 "refused")))
     ("/replace" (proxy-wasm:remove-header :response "content-length")))
    :continue)
  (:on-response-body (stream size end-of-stream)
    (path-case stream ("/spin-body" (spin)) ("/trap-body" (proxy-wasm:panic))
               ("/hog-body" (hog))
               ("/count"
                (proxy-wasm:with-buffer (address length :response-body 0 size)
                  (count-metric "body.bytes;kind=response" length)) :continue)
               (("/replace-sized" "/replace")
                (upcase-buffer (proxy-wasm:buffer-type :response-body) size)
                (when end-of-stream
                  (proxy-wasm:set-buffer :response-body 4294967295 0 "<end>"))
                :continue) ("/hold" (if end-of-stream :continue :pause))
               ("/request-then-trap" (proxy-wasm:panic) :continue)
               ("/replace-trap"
                (proxy-wasm:set-buffer :response-body 0 size "PART")
                (proxy-wasm:panic) :continue) (t :continue))))
