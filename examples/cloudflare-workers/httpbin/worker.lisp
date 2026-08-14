;;; No library: a mini httpbin (https://httpbin.org) whose Worker adapter is
;;; written out under it, so clack never loads and only the program ships.
;;;
;;; A Worker hands over a request JavaScript has already parsed rather than a
;;; socket, so there is no server to run -- the module exports ONE function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; which src/index.js calls. The adapter converts nothing itself:
;;; rontolisp::%http-make-env and %http-normalize-response are the entry points
;;; every SERVED request also goes through, so the "?" split, the
;;; percent-decoding, the header table and the buffered :raw-body come for free.
;;; All that is left to write is the JSON envelope.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile with --no-wasi:
;;; the only thing the module imports is the request body below.

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

;;; The body does NOT ride the envelope. The host hands it over through a
;;; byte-shaped import instead -- (ptr, cap) in, "how many octets I wrote" out,
;;; 0 for end of stream -- writing into ONE buffer the module keeps and reuses
;;; for every chunk of every request. Two things follow that a JSON string
;;; cannot give: a BINARY body crosses exactly (the string boundary's decoder is
;;; non-validating), and a large upload no longer costs linear memory
;;; proportional to its own size. :async t says the host MAY suspend while it
;;; reads -- a WebAssembly.Suspending wrapper over a ReadableStream reader --
;;; and src/index.js answers synchronously, which the declaration allows.
;;;
;;; The guard names the thing itself: #+rontolisp-body-imports is present
;;; exactly where these two imports exist -- a --no-wasi wasm-GC core module
;;; built with --host-boundary=streaming, which build.sh ASKS for because the
;;; default is the in-band envelope and these endpoints echo arbitrary bodies.
;;; Every other way of running THIS FILE lacks them, each for its own reason,
;;; and the one feature covers all of them: check.lisp drives handle-request as
;;; an ordinary function on the interpreter and the JVM; a plain WASI command
;;; module's host is `wasmtime run`, which satisfies no env.* import (a
;;; declared-but-unprovided one makes it refuse to instantiate);
;;; ../httpbin-component builds this file as a component, whose host functions
;;; cross the canonical ABI instead of a core import; and --host-boundary=envelope
;;; asks for the in-band body on purpose. Every one of them keeps the envelope's
;;; own "body" key, which is what the #- half below answers with. Same
;;; endpoints, one source, every host.
#+rontolisp-body-imports
(rontolisp:wasm-import '%read-request-body
                       :from "env"
                       :as "readRequestBody"
                       :params '()
                       :returns :bytes
                       :async t)

;; The request body, in whichever shape it arrived: OCTETS pulled through the
;; import, or the envelope's own string where there is no import. The Gray body
;; stream below is bivalent over octets and takes either, so the pulled body is
;; never decoded and encoded again. The two rontolisp:: names below are the
;; transport's own, like %http-make-env: what this file writes out by hand is
;; the ENVELOPE, and neither the reused buffer nor the drain that empties it is
;; envelope work.
#+rontolisp-body-imports
(defun %body-source (req)
  (declare (ignore req))
  (rontolisp::%http-reactor-body-octets
   (lambda ()
     (let ((buf (rontolisp::%http-reactor-buffer 65536)))
       (rontolisp::%http-reactor-chunk buf (%read-request-body buf))))))

#-rontolisp-body-imports (defun %body-source (req) (gethash "body" req))

;;; The response body leaves the envelope the same way, through the mirror
;;; import: (ptr, len) OUT, "take these octets, they are the next chunk". No
;;; result -- a host cannot short-read a write, and a chunk it has not taken by
;;; the time the call returns is one it never gets, since the module reuses the
;;; memory behind it. Note the direction flip: a chunk crossing out is a :bytes
;;; PARAMETER where one crossing in is a :bytes RESULT, which is the same rule
;;; -- the caller owns the memory -- applied both ways.
#+rontolisp-body-imports
(rontolisp:wasm-import '%write-response-body
                       :from "env"
                       :as "writeResponseBody"
                       :params '(:bytes)
                       :returns :void
                       :async t)

;; The SINK. The encode is the transport's own name again: a text chunk becomes
;; UTF-8, and an (unsigned-byte 8) body is already the octets it means.
#+rontolisp-body-imports
(defun %body-sink (chunk)
  (%write-response-body (rontolisp::%http-reactor-octets chunk)))

;; T when the body was taken out of band. Every other build answers NIL and
;; never names the transport's writer at all, so nothing of it is spliced there.
#+rontolisp-body-imports
(defun %write-body (body)
  (rontolisp::%http-reactor-write (function %body-sink) body)
  t)

#-rontolisp-body-imports
(defun %write-body (body)
  (declare (ignore body))
  nil)

;;; --- the endpoints -----------------------------------------------------------

(defun read-body (stream)
  (if (null stream)
      ""
      (with-output-to-string (out)
        (do ((ch (read-char stream nil nil) (read-char stream nil nil)))
            ((null ch))
          (write-char ch out)))))

;; Parse the body as JSON when it looks like one, and fall back to null when it
;; does not parse -- which is what the real httpbin does.
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

(defun json-response (status object)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify object)))))

;; plist-hash-table and alist-hash-table give json-stringify the string-keyed
;; hash tables it serializes as objects (:method becomes "method"; an empty
;; query still renders {}), and the env :headers already is one.
(defun echo (env with-body)
  (let ((info
         (rontolisp:plist-hash-table
          (list :args (rontolisp:alist-hash-table
                       (rontolisp:query-params (getf env :query-string)))
                :headers (getf env :headers)
                :method (symbol-name (getf env :request-method))
                :path (getf env :path-info)))))
    (when with-body
      (let ((body (read-body (getf env :raw-body))))
        (setf (gethash "data" info) body)
        (setf (gethash "json" info) (body-json body))))
    (json-response 200 info)))

;; :request-method is an interned keyword, so the check is eq.
(defun endpoint (env method with-body)
  (if (eq (getf env :request-method) method)
      (echo env with-body)
      (json-response 405
                     (rontolisp:plist-hash-table
                      (list :error "method not allowed"
                            :allowed (symbol-name method))))))

;; :path-info carries the decoded path only -- the query string arrives
;; separately -- so the comparisons are exact.
(defun dispatch (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/get") (endpoint env :GET nil))
          ((string= path "/post") (endpoint env :POST t))
          ((string= path "/put") (endpoint env :PUT t))
          ((string= path "/patch") (endpoint env :PATCH t))
          ((string= path "/delete") (endpoint env :DELETE t))
          (t (json-response 404
                            (rontolisp:plist-hash-table
                             (list :error "not found" :path path)))))))

;;; --- the reactor adapter -----------------------------------------------------
;;; What `clack:clackup :server :reactor` would install. Nothing above knows it
;;; exists.

;; The headers JSON object -> the ((name . value) ...) alist the raw tuple wants.
(defun %header-alist (table)
  (if (null table)
      nil
      (let ((out nil))
        (maphash (lambda (name value) (setq out (cons (cons name value) out)))
                 table)
        (nreverse out))))

;; The response header alist -> a JSON ARRAY of [name, value]: a name may
;; repeat, and two cookies mean two Set-Cookie headers.
(defun %header-pairs (alist)
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (nreverse out)))

;; The positional tuple %http-make-env consumes. "target" is RAW -- path and
;; query still joined and still encoded, because %http-make-env owns that split
;; -- and the Host header supplies :server-name / :server-port, so the two
;; placeholders below never win when the host sends one. The body is DRAINED
;; here rather than streamed: the endpoints echo it whole, and buffering it is
;; the one thing Clack's :raw-body would do anyway.
(defun %request-tuple (req)
  (list (or (gethash "method" req) "GET") (or (gethash "target" req) "/")
        (%header-alist (gethash "headers" req))
        (rontolisp::%http-body-stream (%body-source req)) "HTTP/1.1"
        (gethash "scheme" req) "localhost" 80 (gethash "remote-addr" req) nil))

;; With a SINK the body crosses out of band and the "body" key is ABSENT -- not
;; empty: a host has to be able to tell "the body crossed" from "the body is the
;; empty string". The chunks cross BEFORE this head, because the head is the
;; return value, so a head that carries the key WINS over anything already
;; written -- which is what makes the 500 below recoverable rather than a
;; corrupt response.
(defun %envelope (status headers body out-of-band)
  (let* ((sent (and out-of-band (%write-body body)))
         (head (list :status status :headers (%header-pairs headers))))
    (rontolisp:json-stringify
     (rontolisp:plist-hash-table
      (if sent head (append head (list :body body)))))))

;; The host's entry point. It CATCHES: on a reactor an uncaught Lisp error is a
;; trap that takes the whole instance down, so answer 500 and keep serving --
;; in band, whether or not the body had a sink.
(defun handle-request (request-json)
  (handler-case (let* ((req (rontolisp:json-parse request-json))
                       (env (rontolisp::%http-make-env (%request-tuple req)))
                       (triple
                        (rontolisp::%http-normalize-response (dispatch env))))
                  (%envelope (car triple) (cadr triple) (caddr triple) t))
    (error (e)
      (%envelope 500 (list (cons "content-type" "application/json"))
                 (format nil "~a~%"
                         (rontolisp:json-stringify
                          (rontolisp:plist-hash-table
                           (list :error (format nil "~a" e))))) nil))))
