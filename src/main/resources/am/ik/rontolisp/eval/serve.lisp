;;;; serve.lisp -- rontolisp:http-handler's HTTP glue, over wit-imported wasi:http / wasi:io.
;;;;
;;;; This is the --component implementation of the incoming-handler glue that used to be the
;;;; hand-written WAT adapter (adapter-http-server.wat). The interpreter and the JVM keep
;;;; their JDK HttpServer; Preview 1 cannot serve. It is spliced ONLY when a --component
;;;; program uses rontolisp:http-handler (eval/ServeLibrary).
;;;;
;;;; The whole of it is ordinary Lisp over the WIT bindings -- no core codegen. That is the
;;;; point of .todo/124: rontolisp:http-handler is core code that implements a host
;;;; interface, and here it re-implements itself over its own WIT pipeline, so the
;;;; hand-written WAT adapter can go. The mirror of fetch.lisp: fetch IMPORTS
;;;; wasi:http/outgoing-handler, serve EXPORTS wasi:http/incoming-handler, and both drive
;;;; wasi:http/types from Lisp.
;;;;
;;;; %serve-handle(request, response-out) is the wasi:http/incoming-handler#handle export
;;;; (two own<> handles in, nothing out). It reads the incoming-request into a request plist
;;;; (:method :path :query :headers :body), hands it to the user handler via %serve-dispatch
;;;; (a one-line bridge the serve inliner synthesizes), and writes the response plist
;;;; (:status :headers :body) back through response-outparam.set.
;;;;
;;;; Unlike the WAT adapter this reads request headers and writes response headers -- serve
;;;; on WASM grows headers here.

;; The WIT interfaces are lowered by ServeLibrary (which calls WitImportDirective.lower
;; itself), so these directives never reach WitImportInliner -- but they are written here,
;; in serve.lisp's own source, so the file reads as the program it is. Internal packages
;; (the `%` keeps them out of a user's way). It reuses fetch.wit, which already carries the
;; complete wasi:http/types + wasi:io surface (both halves).
(rontolisp:wit-import "fetch.wit" :interface "wasi:io/error@0.2.0" :package %serve-ioerr)
(rontolisp:wit-import "fetch.wit" :interface "wasi:io/streams@0.2.0" :package %serve-streams)
(rontolisp:wit-import "fetch.wit" :interface "wasi:http/types@0.2.0" :package %serve-http)

;;; --- reading the request ---

(defun %serve-method-string (m)
  ;; incoming-request.method returns the `method` variant: a keyword (:get/:post/...) for a
  ;; known method, or (:other . "FOO") for a custom one. The request plist wants an
  ;; upper-case method string, matching the interpreter/JVM backends.
  (cond ((consp m) (cdr m))
        ((eq m :get) "GET")
        ((eq m :head) "HEAD")
        ((eq m :post) "POST")
        ((eq m :put) "PUT")
        ((eq m :delete) "DELETE")
        ((eq m :connect) "CONNECT")
        ((eq m :options) "OPTIONS")
        ((eq m :trace) "TRACE")
        ((eq m :patch) "PATCH")
        (t "GET")))

(defun %serve-request-headers (request)
  ;; incoming-request.headers returns an immutable CHILD fields handle; read its entries
  ;; into a (name . value) alist (matching the interpreter/JVM) and drop it before the
  ;; parent request. fields.entries is list<tuple<field-key, field-value>> = a list of
  ;; 2-element lists.
  (let* ((fields (%serve-http:incoming-request-headers request))
         (alist (mapcar (lambda (entry) (cons (car entry) (car (cdr entry))))
                        (%serve-http:fields-entries fields))))
    (%serve-http:fields-drop fields)
    alist))

(defun %serve-read-body (request)
  ;; consume -> incoming-body -> stream -> read loop into a byte string. A request with no
  ;; body (consume's error arm, or an immediately-closed stream) yields "".
  (handler-case
      (let* ((ibody (rontolisp::%wit-result (%serve-http:incoming-request-consume request)))
             (stream (rontolisp::%wit-result (%serve-http:incoming-body-stream ibody)))
             (body (%serve-read-all stream "")))
        (%serve-streams:input-stream-drop stream)
        (%serve-http:incoming-body-drop ibody)
        body)
    (rontolisp:wit-error () "")))

(defun %serve-read-all (stream acc)
  ;; blocking-read signals rontolisp:wit-error on the stream's `closed` arm -- that is EOF.
  (let ((chunk (handler-case (%serve-streams:input-stream-blocking-read stream 4096)
                 (rontolisp:wit-error () nil))))
    (if (or (null chunk) (= (length chunk) 0))
        acc
        (%serve-read-all stream (concatenate 'string acc chunk)))))

(defun %serve-read-request (request)
  ;; Build the (:method :path :query :headers :body) request plist, splitting the wasi:http
  ;; path-with-query at the first ? into :path / :query (nil when there is none), matching
  ;; the interpreter and JVM backends. Missing path -> "/".
  (let* ((method (%serve-method-string (%serve-http:incoming-request-method request)))
         (pq (or (%serve-http:incoming-request-path-with-query request) "/"))
         (q (position #\? pq))
         (path (if q (subseq pq 0 q) pq))
         (query (if q (subseq pq (+ q 1)) nil))
         (headers (%serve-request-headers request))
         (body (%serve-read-body request)))
    (list :method method :path path :query query :headers headers :body body)))

;;; --- writing the response ---

(defun %serve-add-headers (fields headers)
  ;; Each header is a dotted (name . value) cons of strings; fields.append is the one fields
  ;; writer whose value (list<u8>) crosses as a parameter.
  (when headers
    (let ((pair (car headers)))
      (when (consp pair)
        (%serve-http:fields-append fields (car pair) (cdr pair))))
    (%serve-add-headers fields (cdr headers))))

(defun %serve-write-body (obody body)
  ;; blocking-write-and-flush accepts at most 4096 bytes per call, so chunk the body; drop
  ;; the child output-stream before finishing the parent outgoing-body (or wasi:http traps).
  (let ((ostream (rontolisp::%wit-result (%serve-http:outgoing-body-write obody))))
    (%serve-write-chunks ostream body 0)
    (%serve-streams:output-stream-drop ostream)
    (%serve-http:outgoing-body-finish obody nil)))

(defun %serve-write-chunks (ostream body i)
  (when (< i (length body))
    (let* ((remaining (- (length body) i))
           (n (if (> remaining 4096) 4096 remaining))
           (chunk (subseq body i (+ i n))))
      ;; Bail on a stream error (a disconnected client), the WAT's behaviour.
      (handler-case
          (progn
            (%serve-streams:output-stream-blocking-write-and-flush ostream chunk)
            (%serve-write-chunks ostream body (+ i n)))
        (rontolisp:wit-error () nil)))))

(defun %serve-write-response (response-out resp)
  ;; Build the outgoing-response from the handler's plist, hand it to the host BEFORE
  ;; streaming the body (setting the outparam after the writes deadlocks on any response
  ;; larger than one ~4096-byte host buffer -- the WAT's ordering), then write the body.
  (let* ((status (or (getf resp :status) 200))
         (body (or (getf resp :body) ""))
         (fields (%serve-http:fields-new)))
    (%serve-add-headers fields (getf resp :headers))
    (let ((response (%serve-http:outgoing-response-new fields)))
      (%serve-http:outgoing-response-set-status-code response status)
      (let ((obody (rontolisp::%wit-result (%serve-http:outgoing-response-body response))))
        (%serve-http:response-outparam-set response-out (cons :ok response))
        (%serve-write-body obody body)))))

;;; --- the exported handler ---

(defun %serve-handle (request response-out)
  ;; The wasi:http/incoming-handler#handle export. Read the request, run the user handler
  ;; (%serve-dispatch, synthesized by the serve inliner to call the program's handler), and
  ;; write the response. The incoming-request is dropped after the response is delivered.
  (let* ((req (%serve-read-request request))
         (resp (%serve-dispatch req)))
    (%serve-write-response response-out resp)
    (%serve-http:incoming-request-drop request)))
