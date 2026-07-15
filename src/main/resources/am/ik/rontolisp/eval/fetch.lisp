;;;; fetch.lisp -- rontolisp:fetch, over wit-imported wasi:http / wasi:io.
;;;;
;;;; This is the --component implementation of the built-in `rontolisp:fetch`. The
;;;; interpreter and the JVM keep their java.net.http one; Preview 1 has no fetch. It is
;;;; spliced ONLY when a --component program references rontolisp:fetch (eval/FetchLibrary),
;;;; so a program that never fetches is byte-identical to one built before this existed.
;;;;
;;;; The whole of it is ordinary Lisp over the WIT bindings -- no core codegen. That is the
;;;; point of .todo/124: fetch is core code that implements a host interface, and here it
;;;; re-implements itself over its own WIT pipeline, so the hand-written WAT adapter can go.
;;;;
;;;; The promise API is preserved without making fetch synchronous. wasi:http's
;;;; outgoing-handler.handle returns a future-incoming-response WITHOUT blocking -- the
;;;; request is in flight the moment it returns -- so `(fetch url)` builds the request, sends
;;;; it, and returns `(then future #'read-response)` immediately. Only `await` of that promise
;;;; blocks (pollable.block), and the promise runtime memoizes the settled value, so two
;;;; fetches overlap and a double-await is free.

;; The WIT interfaces are lowered by FetchLibrary (which calls WitImportDirective.lower
;; itself), so these directives never reach WitImportInliner -- but they are written here,
;; in fetch.lisp's own source, so the file reads as the program it is. Internal packages
;; (the `%` keeps them out of a user's way).
(rontolisp:wit-import "fetch.wit" :interface "wasi:io/poll@0.2.0" :package %fetch-poll)
(rontolisp:wit-import "fetch.wit" :interface "wasi:io/error@0.2.0" :package %fetch-ioerr)
(rontolisp:wit-import "fetch.wit" :interface "wasi:io/streams@0.2.0" :package %fetch-streams)
(rontolisp:wit-import "fetch.wit" :interface "wasi:http/types@0.2.0" :package %fetch-http)
(rontolisp:wit-import "fetch.wit" :interface "wasi:http/outgoing-handler@0.2.0" :package %fetch-outgoing)

;;; --- URL parsing (scheme / authority / path-with-query) ---

(defun %fetch-scheme-keyword (url colon)
  ;; A scheme length of 5 is https, anything else http -- the same positional rule the WAT
  ;; adapter used (`https://` puts the colon at index 5, `http://` at 4). The `scheme`
  ;; variant's cases are HTTP / HTTPS, so the keyword is upper-case.
  (if (= colon 5) :HTTPS :HTTP))

;;; --- method mapping ---

(defun %fetch-method-variant (method)
  ;; The options plist carries the method as a string ("GET", "post"); wasi:http wants the
  ;; `method` variant. Case-insensitive, defaulting to GET, matching the interpreter/JVM.
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

;;; --- sending (non-blocking): build the request, hand it to the host, return the future ---

(defun %fetch-add-headers (fields headers)
  ;; Each header is a dotted (name . value) cons of strings; fields.append is the one fields
  ;; writer whose value (list<u8>) crosses as a parameter.
  (when headers
    (let ((pair (car headers)))
      (%fetch-http:fields-append fields (car pair) (cdr pair)))
    (%fetch-add-headers fields (cdr headers))))

(defun %fetch-send (url options)
  ;; scheme://authority/path -- the scheme's colon is the first colon (WASM has no `search`
  ;; and no `:start`, so split with subseq off that colon rather than scanning for "://").
  (let ((colon (position #\: url)))
    (when (null colon)
      (error 'rontolisp:wit-error :payload :other
             :message (concatenate 'string "fetch: no scheme in URL: " url)))
    (let* ((rest (subseq url (+ colon 3)))
           (slash (position #\/ rest))
           (authority (if slash (subseq rest 0 slash) rest))
           (path (if slash (subseq rest slash) "/"))
           (body (getf options :body))
           (fields (%fetch-http:fields-new)))
      (%fetch-add-headers fields (getf options :headers))
      (let ((req (%fetch-http:outgoing-request-new fields)))
        (%fetch-http:outgoing-request-set-method req (%fetch-method-variant (getf options :method)))
        (%fetch-http:outgoing-request-set-scheme req (%fetch-scheme-keyword url colon))
        (%fetch-http:outgoing-request-set-authority req authority)
        (%fetch-http:outgoing-request-set-path-with-query req path)
        ;; A request body must be taken BEFORE handle consumes the request.
        (let ((obody (if body (%fetch-http:outgoing-request-body req) nil)))
          (let ((future (%fetch-outgoing:handle req nil)))
            (when obody
              (%fetch-write-body obody body))
            future))))))

(defun %fetch-write-body (obody body)
  (let ((ostream (%fetch-http:outgoing-body-write obody)))
    (%fetch-streams:output-stream-blocking-write-and-flush ostream body)
    ;; The output-stream is a child of the outgoing-body: drop it before finishing the
    ;; parent, or wasi:http traps ("resource has children").
    (%fetch-streams:output-stream-drop ostream)
    (%fetch-http:outgoing-body-finish obody nil)))

;;; --- reading (blocking): only reached when the promise is awaited ---

(defun %fetch-read-all (stream acc)
  ;; blocking-read signals rontolisp:wit-error on the stream's `closed` arm -- that is EOF.
  (let ((chunk (handler-case (%fetch-streams:input-stream-blocking-read stream 4096)
                 (rontolisp:wit-error () nil))))
    (if (or (null chunk) (= (length chunk) 0))
        acc
        (%fetch-read-all stream (concatenate 'string acc chunk)))))

(defun %fetch-header-alist (fields)
  ;; fields.entries returns list<tuple<field-key, field-value>>, a list of 2-element lists;
  ;; the response plist wants dotted (name . value) conses, matching the interpreter/JVM.
  (mapcar (lambda (entry) (cons (car entry) (car (cdr entry))))
          (%fetch-http:fields-entries fields)))

(defun %fetch-read-response (future)
  ;; Any wit-error along the way -- a transport failure, an unreachable host -- yields nil,
  ;; the nil-on-failure convention of this backend (an interpreter/JVM fetch signals at await
  ;; time instead; here await simply returns nil).
  (handler-case (%fetch-read-response-1 future)
    (rontolisp:wit-error () nil)))

(defun %fetch-read-response-1 (future)
  (%fetch-poll:pollable-block (%fetch-http:future-incoming-response-subscribe future))
  ;; get returns option<result<result<incoming-response, error-code>, _>>: the outer option
  ;; is ready (we blocked), and the two nested results unwrap through %wit-result, whose
  ;; error arm signals -- caught above and turned into nil.
  (let* ((response (rontolisp::%wit-result
                     (rontolisp::%wit-result (cdr (%fetch-http:future-incoming-response-get future)))))
         (status (%fetch-http:incoming-response-status response))
         (headers (%fetch-header-alist (%fetch-http:incoming-response-headers response)))
         (ibody (rontolisp::%wit-result (%fetch-http:incoming-response-consume response)))
         (istream (rontolisp::%wit-result (%fetch-http:incoming-body-stream ibody)))
         (text (%fetch-read-all istream "")))
    (list :status status :body text :headers headers)))

;;; --- the built-in ---

(defun rontolisp:fetch (url &rest options)
  ;; Returns a promise immediately (the request is already in flight); await it for the
  ;; (:status :body :headers) plist. A request that cannot even be started -- a malformed
  ;; URL, an unsupported method -- returns nil rather than a promise.
  (handler-case
      (let ((future (%fetch-send url (if options (car options) nil))))
        (rontolisp:then future (lambda (f) (%fetch-read-response f))))
    (rontolisp:wit-error () nil)))
