package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL tiny-routes v0.1.1
 * sources (vendored unmodified under {@code src/test/resources/tiny-routes}, BSD-3-Clause
 * -- the released Quicklisp layout) load via {@code asdf:load-system} and route requests
 * on ALL FOUR backends. It is the piece between {@code clack:clackup}
 * ({@code .kb/clack.md}) and an application with routes: request/response plists,
 * {@code define-get}/{@code define-post}/{@code define-routes}, the middleware
 * combinators and a {@code :id}-style path-template matcher over cl-ppcre.
 *
 * <p>
 * The exercise is transport-free -- it calls the composed handler with hand-built request
 * plists -- so the same output holds on WASM Preview 1, which has no incoming TCP. The
 * SERVING half (the same routes behind {@code clack:clackup}, answered over real HTTP) is
 * a leg of {@link ClackE2eTest}, which already carries the three-backend caveat.
 *
 * <p>
 * What it pins beyond the library's own API: the LOOP anaphoric {@code it} read outside
 * {@code cl-user} ({@code tiny:routes}, the dispatch function every application goes
 * through, is {@code (loop for handler in handlers when (funcall handler request) return
 * it)} inside {@code (in-package :tiny-routes)}), and {@code uiop:if-let} /
 * {@code uiop:with-deprecation}, which {@code tiny-routes.lisp} and {@code response.lisp}
 * spell -- the second of them at LOAD time, around the six deprecated response
 * constructors the exercise calls.
 *
 * <p>
 * Its one dependency is cl-ppcre, vendored beside it; {@code uiop} is the built-in shim.
 * The companion system {@code tiny-routes-middleware-cookie} depends on cl-cookie (and so
 * on quri / local-time / proc-parse), which is more than this hermetic driver vendors: it
 * is covered by {@link ClackE2eTest}'s tiny-routes leg, over a live {@code ql:quickload}.
 */
class TinyRoutesE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "tiny-routes")
		.toAbsolutePath()
		.toString();

	private static final String CL_PPCRE_DIR = Path.of("src", "test", "resources", "cl-ppcre")
		.toAbsolutePath()
		.toString();

	// The routes are read inside :tr-demo, not cl-user: a routing library is used from
	// the application's own package, and that is where the library's own `it` anaphor
	// and every qualified name have to keep working.
	private static final String EXERCISE = """
			(asdf:load-system :tiny-routes)

			(defpackage :tr-demo (:use :cl :tiny-routes))
			(in-package :tr-demo)

			(define-routes *app*
			  (define-get "/hello" () (ok "hello world"))
			  (define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
			  (define-get "/search" (req)
			    (ok (format nil "q=~A" (getf (request-get req :query-parameters) :|q|))))
			  (define-post "/echo" (req) (ok (format nil "echo:~A" (request-body req))))
			  (define-put "/put" () (created "/put" "made"))
			  (define-route (req)
			    (when (ppcre:scan "^/v[0-9]+/ping$" (path-info req)) (ok "pong")))
			  (define-any "*" () (not-found "nope")))

			(defparameter *handler* (pipe *app* (wrap-request-body) (wrap-query-parameters)))

			(defun env (method path &optional (query ""))
			  (list :request-method method :request-uri path :path-info path
			        :url-scheme "http" :query-string query))

			(defun show (res)
			  (print (list (response-status res) (response-body res) (response-headers res))))

			(show (funcall *handler* (env :get "/hello")))
			(show (funcall *handler* (env :get "/users/42")))
			(show (funcall *handler* (env :get "/search" "q=lisp&n=2")))
			(show (funcall *handler* (env :get "/v2/ping")))
			(show (funcall *handler* (env :put "/put")))
			(show (funcall *handler* (env :get "/zzz")))
			;; The method matcher declines a POST to a GET-only route, so it falls through
			;; to the catch-all.
			(show (funcall *handler* (env :post "/hello")))
			;; wrap-request-body reads the Clack :raw-body stream up to :content-length.
			(with-input-from-string (in "abc")
			  (show (funcall *handler*
			                 (append (env :post "/echo") (list :content-length 3 :raw-body in)))))

			;; The request/response predicates (deftype + satisfies) and make-request's
			;; &key &allow-other-keys defaults.
			(print (list (requestp (env :get "/hello")) (requestp '(:a 1))
			             (responsep (ok "x")) (responsep '(999 nil nil))))
			(print (make-request :request-uri "/a" :request-method :post :extra 7))
			(with-request (request-method (path path-info) (missing :nope "dflt")) (env :get "/w")
			  (print (list request-method path missing)))
			(with-path-parameters (id) '(:id "7") (print id))

			;; The response combinators, and the six DEPRECATED constructors -- the
			;; uiop:with-deprecation block response.lisp wraps them in.
			(show (clone-response (ok "x") :status 202))
			(show (funcall (wrap-response-content-type (define-get "/ct" () (ok "y")) "text/plain")
			               (env :get "/ct")))
			(show (funcall (wrap-response-status (define-get "/st" () (ok "z")) 418) (env :get "/st")))
			(show (status-response (ok "z") 418))
			(show (header-response (ok "z") :x-a "1"))
			(show (headers-response (ok "z") (list :x-b "2")))
			(show (body-response (ok "z") "b"))
			(show (headers-response-append (ok "z") :x-c "3"))
			(show (body-mapper-response (ok "z") #'string-upcase))

			;; util: the RFC 1123 date of a fixed universal time (no clock in the output).
			(print (rfc-1123-date 3960000000))
			""";

	private static final List<String> EXPECTED = List.of("(200 \"hello world\" NIL)", "(200 \"user 42\" NIL)",
			"(200 \"q=lisp\" NIL)", "(200 \"pong\" NIL)", "(201 \"made\" (:LOCATION \"/put\"))", "(404 \"nope\" NIL)",
			"(404 \"nope\" NIL)", "(200 \"echo:abc\" NIL)", "(T NIL T NIL)",
			// merge-plists' (setf (getf ...)) PREPENDS an indicator the defaults do not
			// carry, so :extra leads -- upstream's order, not an alphabetical one.
			"(:EXTRA 7 :REQUEST-URI \"/a\" :REQUEST-METHOD :POST :PATH-INFO \"/a\" :URL-SCHEME \"http\")",
			"(:GET \"/w\" \"dflt\")", "\"7\"", "(202 \"x\" NIL)", "(200 \"y\" (:CONTENT-TYPE \"text/plain\"))",
			"(418 \"z\" NIL)", "(418 \"z\" NIL)", "(200 \"z\" (:X-A \"1\"))", "(200 \"z\" (:X-B \"2\"))",
			"(200 \"b\" NIL)", "(200 \"z\" (:X-C \"3\"))", "(200 \"Z\" NIL)", "\"Fri, 27 Jun 2025 08:00:00 GMT\"");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(CL_PPCRE_DIR);
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "TinyRoutesProgram";
	}

}
