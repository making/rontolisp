;;;; http.lisp -- rontolisp:fetch AND rontolisp:http-handler over wit-imported
;;;; wasi:http@0.3.0. ONE module for both directions: in 0.3 `client.send` (outgoing)
;;;; and `handler.handle` (incoming) share one signature in one package, and the
;;;; request/response body API is symmetric (`contents: option<stream<u8>>`,
;;;; `consume-body -> tuple<stream<u8>, future<...>>`), so the read and write halves
;;;; are shared between fetch and serve instead of the 0.2 incoming/outgoing split.
;;;;
;;;; This is the --component implementation (spliced by eval/HttpLibrary when a
;;;; --component program references rontolisp:fetch and/or uses rontolisp:http-handler;
;;;; the splice's member filter follows the reachable half, so a fetch-only program
;;;; binds no serve member and vice versa). The interpreter and the JVM keep their
;;;; java.net.http / JDK HttpServer implementations; Preview 1 has neither.
;;;;
;;;; The whole of it is ordinary Lisp over the WIT bindings -- no core codegen (a host
;;;; interface costs a .wit file, not compiler cases). The async machinery is the
;;;; canonical ABI driven through the
;;;; general wit-import path:
;;;;   - `client.send` is an `async func`, so its binding returns a FIRST-CLASS
;;;;     FUTURE (rontolisp::%subtask-future over the async-lowered call): pending
;;;;     while the subtask runs, settled by the scheduler when it returns (the
;;;;     error arm of its result re-signals rontolisp:wit-error at await -- the
;;;;     settled mapping, matching the interpreter/JVM).
;;;;   - the exported `handler.handle` is an asynchronous task: it DELIVERS the
;;;;     response mid-task through the task-return built-in (0.3's equivalent of
;;;;     0.2's response-outparam.set-before-body) and then streams the body, which
;;;;     rendezvouses with the host's eager reads.
;;;;   - bodies flow through the stream/future built-ins bound off the transparent
;;;;     type aliases at the end of http.wit's `types` interface (body-stream,
;;;;     trailers-future, transmit-future, handle-result).

;; The WIT interfaces are lowered by HttpLibrary (which calls WitImportDirective.lower
;; itself), so these directives never reach WitImportInliner -- but they are written
;; here, in http.lisp's own source, so the file reads as the program it is.
(rontolisp:wit-import "http.wit" :interface "wasi:http/types@0.3.0" :package %http)
(rontolisp:wit-import "http.wit" :interface "wasi:http/client@0.3.0" :package %http-client)

;;; --- headers (shared) ---

(defun %http-add-headers (fields headers)
  ;; Each header is a dotted (name . value) cons of strings; fields.append's value is a
  ;; field-value (list<u8>), which crosses as a byte string.
  (when headers
    (let ((pair (car headers)))
      (when (consp pair)
        (%http:fields-append fields (car pair) (cdr pair))))
    (%http-add-headers fields (cdr headers))))

(defun %http-header-alist (fields)
  ;; fields.copy-all returns list<tuple<field-name, field-value>>, a list of 2-element
  ;; lists; the request/response plists want dotted (name . value) conses, matching the
  ;; interpreter/JVM.
  (mapcar (lambda (entry) (cons (car entry) (car (cdr entry))))
          (%http:fields-copy-all fields)))

;;; --- body reading (shared): consume-body -> a first-class stream value ---

(defun %http-body-value (consume thing)
  ;; consume-body(this, res) MOVES `thing` and returns tuple<stream<u8>,
  ;; future<result<option<trailers>, error-code>>>. The pair is wrapped into a
  ;; first-class rontolisp stream (TYPE_WASI_STREAM): each rontolisp:stream-read
  ;; future settles to the next chunk (nil = EOF), and the close protocol -- run
  ;; ONCE, at EOF or an early rontolisp:stream-close -- drops the readable end and
  ;; the (unread) trailers and resolves `res` ok, reporting our side's outcome to
  ;; the host (dropping `res` instead would signal an error). rontolisp's
  ;; request/response plists carry no trailers.
  (let* ((res (%http:transmit-future-new))
         (pair (funcall consume thing (car res)))
         (stream (car pair))
         (trailers (car (cdr pair))))
    (rontolisp::%wasi-stream-new
     ;; One built-in read per call: the next chunk, nil = EOF, or -- when the host
     ;; reports the read in flight -- a PENDING future the scheduler settles (the
     ;; stream runtime passes it through, so the task keeps running meanwhile).
     (lambda () (%http:body-stream-read stream))
     (lambda ()
       (%http:body-stream-drop-readable stream)
       (%http:trailers-future-drop-readable trailers)
       (%http:transmit-future-write (cdr res) :ok)))))

;;; --- body writing (shared): stream the bytes, close, resolve the trailers ---

(defun %http-write-body (writable body)
  ;; The synchronous stream.write built-in blocks until the peer has taken the bytes
  ;; (rendezvous), so one call carries the whole body; dropping the writable end is the
  ;; end-of-stream signal.
  (when (> (length body) 0)
    (%http:body-stream-write writable body))
  (%http:body-stream-drop-writable writable))

;;; --- fetch (outgoing): build request -> async send -> read the response ---

(defun %fetch-scheme-keyword (url colon)
  ;; A scheme length of 5 is https, anything else http (`https://` puts the colon at
  ;; index 5, `http://` at 4). The `scheme` variant's cases are HTTP / HTTPS.
  (if (= colon 5) :HTTPS :HTTP))

(defun %fetch-method-variant (method)
  ;; The options plist carries the method as a string ("GET", "post"); wasi:http wants
  ;; the `method` variant. Case-insensitive, defaulting to GET, matching the
  ;; interpreter/JVM.
  (let ((m (if (stringp method) (string-upcase method) "GET")))
    (cond ((string= m "GET") :get)
          ((string= m "HEAD") :head)
          ((string= m "POST") :post)
          ((string= m "PUT") :put)
          ((string= m "DELETE") :delete)
          ((string= m "OPTIONS") :options)
          ((string= m "PATCH") :patch)
          (t (error 'rontolisp:wit-error :payload :other
                    :message (concatenate 'string "fetch: unsupported method: " m))))))

(defun %fetch-send (url options)
  ;; scheme://authority/path -- the scheme's colon is the first colon. Returns the
  ;; send future with the request already fully in flight: the async-lowered send
  ;; starts the subtask immediately, and the body / trailers writes below rendezvous
  ;; with the host's eager reads before this function returns.
  (let ((colon (position #\: url)))
    (when (null colon)
      (error 'rontolisp:wit-error :payload :other
             :message (concatenate 'string "fetch: no scheme in URL: " url)))
    (let* ((rest (subseq url (+ colon 3)))
           (slash (position #\/ rest))
           (authority (if slash (subseq rest 0 slash) rest))
           (path (if slash (subseq rest slash) "/"))
           (body (getf options :body))
           (fields (%http:fields-new)))
      (%http-add-headers fields (getf options :headers))
      (let* ((bodypair (if body (%http:body-stream-new) nil))
             (trailers (%http:trailers-future-new))
             (reqpair (%http:request-new fields
                                         (if bodypair (car bodypair) nil)
                                         (car trailers)
                                         nil))
             (req (car reqpair)))
        (%http:request-set-method req (%fetch-method-variant (getf options :method)))
        (%http:request-set-scheme req (%fetch-scheme-keyword url colon))
        (%http:request-set-authority req authority)
        (%http:request-set-path-with-query req path)
        (let ((future (%http-client:send req)))
          (when bodypair
            (%http-write-body (cdr bodypair) body))
          (%http:trailers-future-write (cdr trailers) (cons :ok nil))
          (%http:transmit-future-drop-readable (car (cdr reqpair)))
          future)))))

(defun %fetch-read-response (response)
  ;; The send future settled to the response resource; build the
  ;; (:status :body :headers) plist -- :body is a first-class stream (drain it with
  ;; (rontolisp:await (rontolisp:read-all body)), matching the interpreter/JVM).
  ;; consume-body moves the response, so the headers are read first (the WIT
  ;; guarantees previously acquired headers stay valid).
  (let* ((status (%http:response-get-status-code response))
         (rheaders (%http:response-get-headers response))
         (headers (%http-header-alist rheaders))
         (body (%http-body-value (function %http:response-consume-body) response)))
    (%http:fields-drop rheaders)
    (list :status status :body body :headers headers)))

(rontolisp:async-defun %fetch-run (send-future)
  ;; Awaits the in-flight send -- a REAL suspension when the response has not
  ;; arrived (the scheduler resumes this frame on the subtask's completion) -- and
  ;; reads the response. A transport failure re-signals rontolisp:wit-error at the
  ;; await, rejecting the fetch future, so it surfaces at the CALLER's await.
  (let ((response (rontolisp:await send-future)))
    (%fetch-read-response response)))

(defun rontolisp:fetch (url &rest options)
  ;; Returns a future immediately (the request is already in flight); await it for the
  ;; (:status :body :headers) plist. A request that cannot even be started -- a
  ;; malformed URL, an unsupported method -- returns nil rather than a future; a
  ;; transport failure signals rontolisp:wit-error at await time, matching the
  ;; interpreter/JVM.
  (handler-case
      (%fetch-run (%fetch-send url (if options (car options) nil)))
    (rontolisp:wit-error () nil)))

;;; --- serve (incoming): read the request, dispatch, deliver, stream the body ---

(rontolisp:async-defun %http-drain (s)
  ;; Drain a stream response body into one string -- a private read-all:
  ;; http.lisp must stay self-contained (the prelude splice is a separate,
  ;; later pass, and library splices must not depend on each other's order).
  (let ((acc "")
        (chunk (rontolisp:await (rontolisp:stream-read s))))
    (while chunk
      (setq acc (concatenate 'string acc chunk))
      (setq chunk (rontolisp:await (rontolisp:stream-read s))))
    acc))

(defun %serve-method-string (m)
  ;; request.get-method returns the `method` variant: a keyword (:get/:post/...) for a
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

(defun %serve-read-request (request)
  ;; Build the (:method :path :query :headers :body) request plist, splitting
  ;; path-with-query at the first ? into :path / :query (nil when there is none),
  ;; matching the interpreter and JVM backends -- :body is a first-class stream
  ;; there too. consume-body moves the request, so everything else is read first.
  (let* ((method (%serve-method-string (%http:request-get-method request)))
         (pq (or (%http:request-get-path-with-query request) "/"))
         (q (position #\? pq))
         (path (if q (subseq pq 0 q) pq))
         (query (if q (subseq pq (+ q 1)) nil))
         (rheaders (%http:request-get-headers request))
         (headers (%http-header-alist rheaders))
         (body (%http-body-value (function %http:request-consume-body) request)))
    (%http:fields-drop rheaders)
    (list :method method :path path :query query :headers headers :body body)))

(rontolisp:async-defun %serve-handle (request)
  ;; The handler.handle export body (an asynchronous task). Read the request (its
  ;; :body stays a lazy stream), run the user handler (%serve-dispatch, synthesized
  ;; by the serve inliner) -- awaiting its future, so the handler itself may be an
  ;; async-defun -- drain a stream response body (a proxied fetch :body passes
  ;; straight through), DELIVER the response through task.return -- only then can
  ;; the host start reading the contents stream -- and stream the body after it
  ;; (the rendezvous order verified on wasmtime 46). The request body's close
  ;; protocol runs even when the handler never read it (stream-close is idempotent).
  (let* ((req (%serve-read-request request))
         (resp (rontolisp:await (%serve-dispatch req)))
         (status (or (getf resp :status) 200))
         (body-val (or (getf resp :body) ""))
         (body (if (rontolisp:streamp body-val)
                   (rontolisp:await (%http-drain body-val))
                   body-val))
         (fields (%http:fields-new)))
    (%http-add-headers fields (getf resp :headers))
    (rontolisp:stream-close (getf req :body))
    (let* ((bodypair (%http:body-stream-new))
           (trailers (%http:trailers-future-new))
           (rpair (%http:response-new fields (car bodypair) (car trailers)))
           (response (car rpair)))
      (%http:response-set-status-code response status)
      (%http:handle-result-task-return (cons :ok response))
      (%http-write-body (cdr bodypair) body)
      (%http:trailers-future-write (cdr trailers) (cons :ok nil))
      (%http:transmit-future-drop-readable (car (cdr rpair)))
      nil)))
