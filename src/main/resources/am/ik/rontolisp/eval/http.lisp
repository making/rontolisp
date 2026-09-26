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
(rontolisp:wit-import "http.wit"
                      :interface "wasi:http/types@0.3.0"
                      :package %http)
(rontolisp:wit-import "http.wit"
                      :interface "wasi:http/client@0.3.0"
                      :package %http-client)

;;; --- headers (shared) ---

(defun %http-add-headers (fields headers)
  ;; Each header is a dotted (name . value) cons of strings; fields.append's value is a
  ;; field-value (list<u8>), which crosses as a byte string.
  (when headers
    (let ((pair (car headers)))
      (when (consp pair) (%http:fields-append fields (car pair) (cdr pair))))
    (%http-add-headers fields (cdr headers))))

(defun %http-header-alist (fields)
  ;; fields.copy-all returns list<tuple<field-name, field-value>>, a list of 2-element
  ;; lists; the request/response plists want dotted (name . value) conses, matching the
  ;; interpreter/JVM.
  (mapcar (lambda (entry) (cons (car entry) (car (cdr entry))))
          (%http:fields-copy-all fields)))

(defun %http-field-insert (pair sorted)
  ;; Stable: a field goes AFTER those of its own name already placed, so the values of
  ;; one field keep their wire order.
  (if (and sorted (not (string< (car pair) (car (car sorted)))))
      (cons (car sorted) (%http-field-insert pair (cdr sorted)))
      (cons pair sorted)))

(defun %http-sorted-fields (alist)
  ;; A fetched reply's :headers in ascending name order, what the interpreter, the JVM
  ;; and a --native runner answer; the host hands the fields over in wire order.
  (let ((sorted nil))
    (dolist (pair alist sorted)
      (setq sorted (%http-field-insert pair sorted)))))

;;; --- body reading (shared): consume-body -> a first-class stream value ---

(defun %http-body-value (consume thing finish)
  ;; consume-body(this, res) MOVES `thing` and returns tuple<stream<u8>,
  ;; future<result<option<trailers>, error-code>>>. The pair is wrapped into a
  ;; first-class rontolisp stream: each rontolisp:stream-read future settles to the
  ;; next chunk, nil at the end.
  ;;
  ;; The release -- run ONCE, at the end or at an early rontolisp:stream-close --
  ;; drops the readable end and the trailers and resolves `res` ok, reporting our
  ;; side's outcome to the host (dropping `res` instead would signal an error);
  ;; rontolisp's plists carry no trailers. FINISH is the body owner's part of it:
  ;; nil for a served request's body, and for a fetched reply's the closure
  ;; %fetch-read-response makes, called with the trailers future and whether the
  ;; body reached its END (nil for an early close), which answers the transfer's
  ;; failure -- signalled here, so at the await of the read that found the end --
  ;; or nil.
  ;;
  ;; The end is found HERE, not by the stream runtime: a read that answers nil at
  ;; once, or that the host reports in flight (a PENDING future the scheduler
  ;; settles, so the task keeps running meanwhile), goes through %http-body-next,
  ;; which awaits it and releases at the end -- the same release, and the same
  ;; failure, whichever way the end arrives. A chunk that is there at once is
  ;; answered as it is.
  (let* ((res (%http:transmit-future-new))
         (pair (funcall consume thing (car res)))
         (stream (car pair))
         (trailers (car (cdr pair)))
         (live t)
         (release
          (lambda (whole)
            (when live
              (setq live nil)
              (%http:body-stream-drop-readable stream)
              (let ((failure (if finish (funcall finish trailers whole) nil)))
                (%http:trailers-future-drop-readable trailers)
                (%http:transmit-future-write (cdr res) :ok)
                (when failure
                  (error 'rontolisp:wit-error
                         :payload failure
                         :message (concatenate 'string
                                               "the body's transfer failed: "
                                               (%prin1-piece failure))))))))
         (next
          (lambda ()
            (if live
                (let ((chunk (%http:body-stream-read stream)))
                  (if (and chunk (not (rontolisp:futurep chunk)))
                      chunk
                      (%http-body-next chunk release)))
                nil))))
    (rontolisp::%stream-new next (lambda () (funcall release nil)))))

(rontolisp:async-defun %http-body-next (pending release)
  ;; A read that found the end (nil) or is in flight (a pending future): the chunk it
  ;; settles to, or -- at the end -- nil once the body is released. (Not a parameter
  ;; named `read`: the library splices find their triggers by the symbols a program
  ;; spells, and that one pulls in the prelude's reader.)
  (let ((chunk (rontolisp:await pending)))
    (if chunk
        chunk
        (progn
          (funcall release t)
          nil))))

;;; --- body writing (shared): stream the bytes, close, resolve the trailers ---

(defun %http-write-body (writable body)
  ;; The synchronous stream.write built-in blocks until the peer has taken the bytes
  ;; (rendezvous), so one call carries the whole body; dropping the writable end is the
  ;; end-of-stream signal.
  (when (> (length body) 0) (%http:body-stream-write writable body))
  (%http:body-stream-drop-writable writable))

;;; --- fetch (outgoing): build request -> async send -> read the response ---

(defun %fetch-scheme-keyword (url colon)
  ;; A scheme length of 5 is https, anything else http (`https://` puts the colon at
  ;; index 5, `http://` at 4). The `scheme` variant's cases are HTTP / HTTPS.
  (if (= colon 5) :HTTPS :HTTP))

(defun %fetch-method-variant (method)
  ;; The options plist carries the method as a string ("GET", "post"); wasi:http wants
  ;; the `method` variant. Case-insensitive, defaulting to GET, matching the
  ;; interpreter/JVM -- and like theirs, any other method (or a :method that is not a
  ;; string) signals at the fetch CALL, which validates its options before anything
  ;; starts.
  (unless (or (null method) (stringp method))
    (error "fetch: :method must be a string, got ~s" method))
  (let ((m (if method (string-upcase method) "GET")))
    (cond ((string= m "GET") :get)
          ((string= m "HEAD") :head)
          ((string= m "POST") :post)
          ((string= m "PUT") :put)
          ((string= m "DELETE") :delete)
          ((string= m "OPTIONS") :options)
          ((string= m "PATCH") :patch)
          (t (error "fetch: unsupported method: ~a" m)))))

(defun %fetch-user-agent-set-p (headers)
  ;; Whether the caller's alist already names the user-agent field. HTTP field names
  ;; are case-insensitive, so ("user-agent" . "x") is the caller setting it just as
  ;; much as ("User-Agent" . "x") is; only the ABSENT case takes the default below.
  ;; The name comes from the generated %http-user-agent-header, like the value: the
  ;; header fetch adds is declared once, in compiler/FetchResponseShape.
  (when headers
    (let ((pair (car headers)))
      (if (and (consp pair) (stringp (car pair))
               (string-equal (car pair) (%http-user-agent-header)))
          t
          (%fetch-user-agent-set-p (cdr headers))))))

(defun %fetch-fields (headers)
  ;; The request's header fields: the caller's alist, and the one header we add on the
  ;; caller's behalf. Without it a caller-silent request goes out with NO user-agent at
  ;; all -- the java.net.http backends write their own, so the same program sent a
  ;; different request here, and an origin that rejects agent-less traffic answered it
  ;; with a 4xx. A field the host refuses (fields.append's error arm) releases the
  ;; fields before the failure goes on.
  (let ((fields (%http:fields-new)))
    (handler-case (progn
                    (%http-add-headers fields headers)
                    (unless (%fetch-user-agent-set-p headers)
                      (%http:fields-append fields (%http-user-agent-header)
                                           (%http-default-user-agent))))
      (error (c)
        (%http:fields-drop fields)
        (error c)))
    fields))

(defun %fetch-send (url options method)
  ;; scheme://authority/path -- the scheme's colon is the first colon. Answers
  ;; (send-future . transmission): the request in flight -- the async-lowered send
  ;; starts the subtask at once, and the body / trailers writes below rendezvous with
  ;; the host's eager reads before this returns -- and the readable end of the future
  ;; request.new answered for the request's transmission.
  ;;
  ;; That readable end stays OPEN until the reply body is released
  ;; (%fetch-body-finish): wasmtime (49) keeps the connection's I/O task alive only
  ;; while the future it resolves has a reader. Dropped here, as it used to be, the
  ;; connection was aborted as soon as the response head was in, and the reply body
  ;; ended with whatever had arrived with the head -- "first-" of a reply that
  ;; pauses, the first 8 KiB of a large one -- reading as whole.
  (let ((colon (position #\: url)))
    (when (null colon)
      (error 'rontolisp:wit-error
             :payload :other
             :message (concatenate 'string "fetch: no scheme in URL: " url)))
    (let* ((rest (subseq url (+ colon 3)))
           (slash (position #\/ rest))
           (authority (if slash (subseq rest 0 slash) rest))
           (path (if slash (subseq rest slash) "/"))
           (body (getf options :BODY))
           (fields (%fetch-fields (getf options :HEADERS)))
           (bodypair (if body (%http:body-stream-new) nil))
           (trailers (%http:trailers-future-new))
           (reqpair
            (%http:request-new fields (if bodypair (car bodypair) nil)
                               (car trailers) nil))
           (req (car reqpair))
           (transmission (car (cdr reqpair))))
      (handler-case (progn
                      (%http:request-set-method req method)
                      (%http:request-set-scheme req
                       (%fetch-scheme-keyword url colon))
                      (%http:request-set-authority req authority)
                      (%http:request-set-path-with-query req path))
        (error ()
          ;; A URL the host refuses to request (a space in its path): release what
          ;; was made -- the request takes the body's and the trailers' readable ends
          ;; with it -- then fail, naming the URL, since the setters' error arms carry
          ;; nothing.
          (%http:request-drop req)
          (when bodypair (%http:body-stream-drop-writable (cdr bodypair)))
          (%http:transmit-future-drop-readable transmission)
          (error 'rontolisp:wit-error
                 :payload :other
                 :message (concatenate 'string
                                       "fetch: not a URL the host can request: "
                                       url))))
      (let ((future (%http-client:send req)))
        (when bodypair (%http-write-body (cdr bodypair) body))
        (%http:trailers-future-write (cdr trailers) (cons :ok nil))
        (cons future transmission)))))

(defun %fetch-body-finish (trailers whole transmission)
  ;; A fetched reply body's part of the release. At the END it reads the trailers
  ;; future, which is where wasi:http reports a transfer that failed mid-body -- the
  ;; stream itself just ends, exactly like a body that arrived whole -- and answers
  ;; its error-code, the failure the release signals; without the look a reply cut
  ;; short by the network read as a short body, on this backend alone. An early close
  ;; does not look: the caller gave the rest up. The request's transmission future
  ;; closes last, whichever way.
  (let ((failure
         (if whole
             (let ((result (%http:trailers-future-read trailers)))
               (if (and (consp result) (eq (car result) :error))
                   (cdr result)
                   (progn
                     (when (and (consp result) (cdr result))
                       (%http:fields-drop (cdr result)))
                     nil)))
             nil)))
    (%http:transmit-future-drop-readable transmission)
    failure))

(defun %fetch-read-response (response transmission)
  ;; The send future settled to the response resource; build the response plist
  ;; through %http-response-plist (generated from the http-plist WIT record, so the
  ;; shape matches the interpreter/JVM by construction) -- :body is a first-class
  ;; stream (drain it with (rontolisp:await (rontolisp:read-all body))).
  ;; consume-body moves the response, so the headers are read first (the WIT
  ;; guarantees previously acquired headers stay valid).
  (let* ((status (%http:response-get-status-code response))
         (rheaders (%http:response-get-headers response))
         (headers (%http-sorted-fields (%http-header-alist rheaders)))
         (body
          (%http-body-value (function %http:response-consume-body) response
                            (lambda (trailers whole)
                              (%fetch-body-finish trailers whole
                                                  transmission)))))
    (%http:fields-drop rheaders)
    (%http-response-plist status headers body)))

(rontolisp:async-defun %fetch-run (url options method)
  ;; Builds and sends the request, awaits its head -- a REAL suspension while the
  ;; response has not arrived (the scheduler resumes this frame on the subtask's
  ;; completion) -- and reads the response. Whatever fails in here rejects the future
  ;; rontolisp:fetch answered, so it signals at the CALLER's await: a request the host
  ;; refuses to build as much as a transport failure (rontolisp:wit-error), the
  ;; interpreter's timing.
  (let* ((sent (%fetch-send url options method))
         (response
          (handler-case (rontolisp:await (car sent))
            (error (c)
              (%http:transmit-future-drop-readable (cdr sent))
              (error c)))))
    (%fetch-read-response response (cdr sent))))

(defun rontolisp:fetch (url &rest options)
  ;; Returns a future at once, the request already in flight; await it for the
  ;; (:status :headers :body) plist. The options are validated HERE, so an
  ;; unsupported method signals at the call (a literal one is a compile error);
  ;; everything after that fails the future and signals at the await, as on every
  ;; other backend.
  (let ((opts (if options (car options) nil)))
    (%fetch-run url opts (%fetch-method-variant (getf opts :METHOD)))))

;;; --- serve (incoming): read the request, dispatch, deliver, stream the body ---

(defun %serve-method-keyword (m)
  ;; request.get-method returns the `method` variant: a payload-less keyword for a
  ;; known method, or (:other . "FOO") for a custom one. The lifted case name reads
  ;; UPCASED on the component backend (:GET/:POST/...), which is exactly the Clack
  ;; :request-method value, so only the custom arm needs work.
  (if (consp m) (intern (string-upcase (cdr m)) :keyword) m))

;; %serve-request-body -- the request body in the shape the directive asked for
;; -- is SYNTHESIZED by the serve inliner (HttpLibrary), beside %serve-dispatch:
;; the :raw-body mode is a compile-time constant, and synthesizing the matching
;; body (pass the lazy stream through, or drain and wrap it) is what keeps a
;; default-mode component free of the buffered-body machinery -- no Gray class,
;; no UTF-8 encoder -- instead of carrying it behind a runtime flag.

(rontolisp:async-defun %serve-handle (request)
  ;; The handler.handle export body (an asynchronous task). Read the transport
  ;; facts, hand them to the SHARED server model (rontolisp::%http-serve-request in
  ;; http-server.lisp, which builds the Clack environment, runs the handler --
  ;; awaiting its future, so the handler itself may be an async-defun -- and
  ;; normalizes its Clack response), then DELIVER the response through task.return
  ;; -- only then can the host start reading the contents stream -- and stream the
  ;; body after it (the rendezvous order verified on wasmtime 46).
  ;;
  ;; The request body is the one thing decided at compile time: by default it stays
  ;; a LAZY stream (nothing is buffered; the handler awaits it), and under
  ;; :raw-body :buffered it is drained here so the environment can carry the
  ;; synchronously readable stream a Clack application needs. Its close protocol
  ;; runs either way, even when the handler never read it (stream-close is
  ;; idempotent).
  (let* ((method (%serve-method-keyword (%http:request-get-method request)))
         (target (or (%http:request-get-path-with-query request) "/"))
         (rheaders (%http:request-get-headers request))
         (headers (%http-header-alist rheaders))
         (stream
          (%http-body-value (function %http:request-consume-body) request nil)))
    (%http:fields-drop rheaders)
    (let* ((body (rontolisp:await (%serve-request-body stream)))
           ;; The raw tuple %http-make-env consumes. wasi:http@0.3.0 exposes no peer
           ;; address on the request resource at all, so :remote-addr / :remote-port
           ;; are nil here while the interpreter and the JVM carry the real peer; the
           ;; scheme and the protocol are likewise not readable from the pruned
           ;; import block. See .kb/http-server.md for the re-evaluation trigger.
           ;; script-name "": wasi:http hands over a whole authority, so the
           ;; application is root-mounted by construction.
           (raw
            (list method target headers body "HTTP/1.1" "http" nil 80 nil nil
                  ""))
           (res
            (rontolisp:await
             (rontolisp::%http-serve-request (function %serve-dispatch) raw)))
           (fields (%http:fields-new)))
      (%http-add-headers fields (car (cdr res)))
      (rontolisp:stream-close stream)
      (let* ((bodypair (%http:body-stream-new))
             (trailers (%http:trailers-future-new))
             (rpair (%http:response-new fields (car bodypair) (car trailers)))
             (response (car rpair)))
        (%http:response-set-status-code response (car res))
        (%http:handle-result-task-return (cons :ok response))
        (%http-write-body (cdr bodypair) (car (cdr (cdr res))))
        (%http:trailers-future-write (cdr trailers) (cons :ok nil))
        (%http:transmit-future-drop-readable (car (cdr rpair)))
        nil))))
