;; Shiori: reading requests, writing responses (pages, fragments, redirects,
;; flash messages) and the middleware around every route.

;;; --- requests and responses ----------------------------------------------------

(defun param (req name)
  "The decoded value of NAME in the query string or the form body."
  (cdr (assoc name (tiny:request-get req :params) :test #'string=)))

(defun htmx-p (req) (tiny:request-header req "hx-request"))

(defun htmx-target-id (req)
  "The id of the element htmx will swap the answer into. htmx 4 names the
   target as tag#id (div#shelf); only the id is the page's contract."
  (let* ((target (tiny:request-header req "hx-target"))
         (hash (and target (position #\# target))))
    (and hash (rontolisp:url-decode (subseq target (1+ hash))))))

(defun partial-request-p (req)
  "True when htmx swaps the answer into part of the page rather than the
   whole body -- a fragment is expected."
  (equal (tiny:request-header req "hx-request-type") "partial"))

(defun positive-integer (text)
  "TEXT as a positive integer, or nil when it is not exactly one."
  (when (and (stringp text) (plusp (length text)))
    (multiple-value-bind (n end) (parse-integer text :junk-allowed t)
      (when (and n (= end (length text)) (plusp n)) n))))

(defparameter *content-security-policy*
  (format nil "~{~a~^; ~}"
          '("default-src 'self'"
            "script-src 'self' https://unpkg.com/htmx.org@4.0.1/"
            "style-src-elem 'self' https://fonts.googleapis.com"
            ;; A spine's cloth and size are custom properties in its style
            ;; attribute (spine-style).
            "style-src-attr 'unsafe-inline'"
            "font-src https://fonts.gstatic.com"
            ;; app.css masks the paper grain with a data: SVG.
            "img-src 'self' data:" "object-src 'none'" "base-uri 'none'"
            "form-action 'self'" "frame-ancestors 'none'"))
  "What a page may load and run. No inline script and no eval: an injected
   <script> or on* attribute does not run even if escaping were ever missed.
   frame-ancestors keeps the pages (their delete buttons) out of other
   sites' frames.")

(defun html (status body &rest headers)
  (tiny:make-response :status status
                      :headers (list* :content-type "text/html; charset=utf-8"
                                      :content-security-policy
                                      *content-security-policy*
                                      :x-content-type-options "nosniff"
                                      :referrer-policy "same-origin" headers)
                      :body body))

(defun flash-cookie (message)
  (format nil "shiori_flash=~a; Path=/; Max-Age=60; HttpOnly; SameSite=Lax"
          (rontolisp:url-encode message)))

(defun toast-trigger (message)
  "An HX-Trigger header value raising the page's toast event. A header is
   ISO-8859-1, so the message travels URL-encoded and the page decodes it.
   The event is raised on the body: htmx raises it after the swap, by which
   time the form that sent the request may have been swapped out."
  (format nil "{\"toast\":{\"value\":\"~a\",\"target\":\"body\"}}"
          (rontolisp:url-encode message)))

(defun redirect (req location &optional flash)
  "After a POST. A browser gets 303 + Location, so a reload does not post
   again. htmx follows a redirect inside its fetch, where it cannot see it, so it
   gets HX-Location instead and navigates itself (pushing the new URL). Either
   way the message rides in a cookie the next page shows once."
  (let ((cookie (and flash (list :set-cookie (flash-cookie flash)))))
    (if (htmx-p req)
        (tiny:make-response :status 200
                            :headers (list* :hx-location location cookie)
                            :body "")
        (tiny:make-response :status 303
                            :headers (list* :location location cookie)))))

(defun take-flash (req)
  "The flash message the previous response left, if any."
  (let ((cookies (tiny:request-header req "cookie")))
    (when cookies
      (loop for pair in (uiop:split-string cookies :separator ";")
            for trimmed = (string-left-trim " " pair)
            when
              (and (> (length trimmed) 13)
                   (string= "shiori_flash=" trimmed :end2 13))
            return (rontolisp:url-decode (subseq trimmed 13))))))

(defun page (req template title data &key (status 200) (nav template))
  "A whole page: TEMPLATE inside the layout. A flash message is shown once and
   its cookie cleared in the same response. When htmx expected a fragment -- an
   in-place update that met a missing book or an error -- the page is swapped
   in for the whole body instead of into the fragment's slot."
  (let* ((flash (take-flash req))
         (body
          (render "layout"
                  (append (list (cons :page template) (cons :title title)
                                (cons :flash flash)
                                (cons :stylesheet *stylesheet-url*)
                                (cons :script *script-url*)
                                (cons :nav-home (string= nav "home"))
                                (cons :nav-library (string= nav "library"))
                                (cons :nav-new (string= nav "new"))) data))))
    (apply #'html status body
           (append
            (and flash
                 (list
                  :set-cookie
                  "shiori_flash=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"))
            (and (partial-request-p req)
                 (list :hx-retarget "body" :hx-reswap "innerHTML"))))))

(defun error-page (req status heading message)
  (page req "error" heading
   (list (cons :status status) (cons :heading heading) (cons :message message))
   :status status
   :nav nil))

(defun not-found (req)
  (error-page req 404 "この頁は落丁しています"
              (format nil "~a という頁はありません。本棚から探してください。" (tiny:path-info req))))

;;; --- static files ----------------------------------------------------------------

(defun static (content-type text cache-control)
  (tiny:make-response :status 200
                      :headers (list :content-type content-type
                                     :cache-control cache-control
                                     :x-content-type-options "nosniff")
                      :body text))

;;; --- middleware --------------------------------------------------------------------

(defun wrap-params (handler)
  "Middleware: the query string and an url-encoded form body, decoded, as one
   alist under :params. (tiny-routes' own query middleware does not decode
   percent escapes, and leaves form bodies alone.)"
  (lambda (req)
    (funcall handler
             (tiny:request-append req
                                  :params
                                  (append
                                   (rontolisp:query-params
                                    (tiny:query-string req))
                                   (let ((type (tiny:content-type req)))
                                     (when (and type
                                            (search
                                             "application/x-www-form-urlencoded"
                                             type))
                                       (rontolisp:query-params
                                        (tiny:request-body req)))))))))

(defun same-origin-p (req)
  "False when a browser says another site sent this request. Sec-Fetch-Site
   is the browser's own word on it; a browser too old to send it still sends
   Origin, compared with Host. A request carrying neither did not come from a
   browser page (curl, a script) and has no visitor's browser to abuse."
  (let ((site (tiny:request-header req "sec-fetch-site"))
        (origin (tiny:request-header req "origin"))
        (host (tiny:request-header req "host")))
    (cond (site (member site '("same-origin" "none") :test #'string=))
          (origin (let ((authority (search "://" origin)))
                    (and authority host
                         (string= (subseq origin (+ authority 3)) host))))
          (t t))))

(defun wrap-same-origin (handler)
  "Middleware against cross-site request forgery: a page on another site can
   make the visitor's browser post here. There is no login to borrow, but the
   post still reaches an app the visitor can reach and that site cannot (on
   localhost, on a LAN). A POST from another origin gets a 403 before any
   handler runs."
  (lambda (req)
    (if (or (member (tiny:request-method req) '(:get :head :options))
            (same-origin-p req))
        (funcall handler req)
        (error-page req 403 "別のサイトからの送信です" "栞の画面から操作してください。"))))

(defun wrap-connection (handler)
  "Middleware: one connection per request, as :db, closed even when the handler
   signals. Installed post-match, so a request bound for the 404 page or the
   stylesheet opens none."
  (lambda (req)
    (with-database (db) (funcall handler (tiny:request-append req :db db)))))

(defun wrap-errors (handler)
  "Middleware: anything a handler did not foresee becomes the 500 page, and the
   condition goes to the log rather than to the visitor."
  (lambda (req)
    (handler-case (funcall handler req)
      (error (c)
        (format *error-output* "~&~a ~a: ~a~%" (tiny:request-method req)
                (tiny:path-info req) c)
        (error-page req 500 "頁を開けませんでした" "データベースに接続できるか確かめてから、もう一度開いてください。")))))
