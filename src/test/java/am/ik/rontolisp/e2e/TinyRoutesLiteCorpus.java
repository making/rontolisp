package am.ik.rontolisp.e2e;

import java.util.List;

/**
 * The ONE (template, path) corpus behind the {@code tiny-routes/lite} pinning: the same
 * probe program runs once against the lite matcher on all four backends
 * ({@link TinyRoutesLiteE2eTest}) and once against the REAL tiny-routes over the real
 * cl-ppcre engine on the interpreter ({@link TinyRoutesLiteUpstreamParityTest}), and both
 * assert THIS expected output -- so "lite matches exactly as the full system does" is
 * pinned template-for-template as data, without ever co-loading the two systems (they
 * define the same packages). Widen the corpus here and both sides pick it up.
 *
 * <p>
 * What the accepted-subset cases cover, deliberately: exact templates (metacharacters
 * included -- no colon means upstream never builds a regex), the {@code ""}/{@code "*"}/
 * {@code t}/{@code nil} passthroughs, single tokens (with the empty-segment and
 * crossing-slash rejections {@code ([^/]+)} implies), multi-token segments with
 * BACKTRACKING -- including upstream's greedy token-NAME scan: {@code "/a/:x-:y"} parses
 * as the tokens {@code :X-} and {@code :Y}, adjacent, so {@code "/a/b-c-d"} binds
 * {@code (:X- "b-c-" :Y "d")} -- mid-segment tokens, a colon that starts no token (the
 * always-nil zero-token keyword matcher), token-name characters, repeated names, and the
 * route-level integration ({@code define-routes}/{@code path-parameter}/
 * {@code with-path-parameters}).
 */
final class TinyRoutesLiteCorpus {

	private TinyRoutesLiteCorpus() {
	}

	/**
	 * The probe program, without the {@code load-system} line: each concrete test
	 * prepends its own system spelling.
	 */
	static final String CORPUS = """
			(in-package :cl-user)

			(defun probe (template path)
			  (let* ((handler (tiny-routes:wrap-request-matches-path-template
			                   (lambda (req) (list :matched (getf req :path-parameters :none)))
			                   template))
			         (result (funcall handler (list :path-info path))))
			    (print (list template path result))))

			;; exact
			(probe "/plain" "/plain")
			(probe "/plain" "/nope")
			(probe "/plain" "/plain/")
			;; exact with metacharacters: no colon means no regex upstream, plain string=
			(probe "/a.b" "/a.b")
			(probe "/a.b" "/axb")
			(probe "/w+x" "/w+x")
			(probe "/w+x" "/wwx")
			;; passthroughs
			(probe "" "/anything")
			(probe "*" "/anything")
			(probe t "/anything")
			(probe nil "/anything")
			;; single token
			(probe "/users/:id" "/users/42")
			(probe "/users/:id" "/users/")
			(probe "/users/:id" "/users/a/b")
			(probe "/users/:id" "/users/42/")
			(probe "/users/:id/posts" "/users/42/posts")
			(probe "/users/:id/posts" "/users/42/nope")
			;; adjacent segments of tokens
			(probe "/:a/:b" "/x/y")
			(probe "/:a/:b" "/x/")
			(probe "/:a/:b" "/x")
			;; multi-token single segment: backtracking, upstream token-name greed (:X-)
			(probe "/a/:x-:y" "/a/b-c-d")
			(probe "/a/:x-:y" "/a/b-c")
			(probe "/a/:x-:y" "/a/bc")
			;; mid-segment token
			(probe "/files/v:version" "/files/v1")
			(probe "/files/v:version" "/files/v")
			(probe "/files/v:version" "/files/w1")
			;; a colon that starts no token (digit follows): a literal, and the
			;; keyword matcher with zero tokens never yields params, so no match ever
			(probe "/a/:1" "/a/:1")
			(probe "/a/:1" "/a/x")
			;; token name characters: underscore, digits, hyphen
			(probe "/:user_id2" "/u9")
			(probe "/:user-id" "/u9")
			;; token at both ends and repeated names
			(probe "/:x/mid/:x" "/l/mid/r")
			;; empty template segments around tokens
			(probe "/:a//:b" "/x//y")
			(probe "/:a//:b" "/x/y")

			;; the route-level integration: define-routes + path-parameter + the matchers
			(defpackage :tr-corpus (:use :cl :tiny-routes))
			(in-package :tr-corpus)

			(define-routes *app*
			  (define-get "/hello" () (ok "hello world"))
			  (define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
			  (define-get "/pair/:a/:b" (req)
			    (ok (format nil "~A+~A" (path-parameter req :a) (path-parameter req :b))))
			  (define-any "*" () (not-found "nope")))

			(defun show (res)
			  (print (list (response-status res) (response-body res))))

			(show (funcall *app* (list :request-method :get :path-info "/hello")))
			(show (funcall *app* (list :request-method :get :path-info "/users/42")))
			(show (funcall *app* (list :request-method :get :path-info "/pair/1/2")))
			(show (funcall *app* (list :request-method :post :path-info "/hello")))
			(show (funcall *app* (list :request-method :get :path-info "/zzz")))

			(with-path-parameters (id) '(:id "7") (print id))
			(print (path-parameter (list :path-parameters '(:x "1")) :y "dflt"))
			""";

	/** The output both engines must produce, one trimmed line per element. */
	static final List<String> CORPUS_EXPECTED = List.of( //
			"(\"/plain\" \"/plain\" (:MATCHED :NONE))", //
			"(\"/plain\" \"/nope\" NIL)", //
			"(\"/plain\" \"/plain/\" NIL)", //
			"(\"/a.b\" \"/a.b\" (:MATCHED :NONE))", //
			"(\"/a.b\" \"/axb\" NIL)", //
			"(\"/w+x\" \"/w+x\" (:MATCHED :NONE))", //
			"(\"/w+x\" \"/wwx\" NIL)", //
			"(\"\" \"/anything\" (:MATCHED :NONE))", //
			"(\"*\" \"/anything\" (:MATCHED :NONE))", //
			"(T \"/anything\" (:MATCHED :NONE))", //
			"(NIL \"/anything\" (:MATCHED :NONE))", //
			"(\"/users/:id\" \"/users/42\" (:MATCHED (:ID \"42\")))", //
			"(\"/users/:id\" \"/users/\" NIL)", //
			"(\"/users/:id\" \"/users/a/b\" NIL)", //
			"(\"/users/:id\" \"/users/42/\" NIL)", //
			"(\"/users/:id/posts\" \"/users/42/posts\" (:MATCHED (:ID \"42\")))", //
			"(\"/users/:id/posts\" \"/users/42/nope\" NIL)", //
			"(\"/:a/:b\" \"/x/y\" (:MATCHED (:A \"x\" :B \"y\")))", //
			"(\"/:a/:b\" \"/x/\" NIL)", //
			"(\"/:a/:b\" \"/x\" NIL)", //
			"(\"/a/:x-:y\" \"/a/b-c-d\" (:MATCHED (:X- \"b-c-\" :Y \"d\")))", //
			"(\"/a/:x-:y\" \"/a/b-c\" (:MATCHED (:X- \"b-\" :Y \"c\")))", //
			"(\"/a/:x-:y\" \"/a/bc\" (:MATCHED (:X- \"b\" :Y \"c\")))", //
			"(\"/files/v:version\" \"/files/v1\" (:MATCHED (:VERSION \"1\")))", //
			"(\"/files/v:version\" \"/files/v\" NIL)", //
			"(\"/files/v:version\" \"/files/w1\" NIL)", //
			"(\"/a/:1\" \"/a/:1\" NIL)", //
			"(\"/a/:1\" \"/a/x\" NIL)", //
			"(\"/:user_id2\" \"/u9\" (:MATCHED (:USER_ID2 \"u9\")))", //
			"(\"/:user-id\" \"/u9\" (:MATCHED (:USER-ID \"u9\")))", //
			"(\"/:x/mid/:x\" \"/l/mid/r\" (:MATCHED (:X \"l\" :X \"r\")))", //
			"(\"/:a//:b\" \"/x//y\" (:MATCHED (:A \"x\" :B \"y\")))", //
			"(\"/:a//:b\" \"/x/y\" NIL)", //
			"(200 \"hello world\")", //
			"(200 \"user 42\")", //
			"(200 \"1+2\")", //
			"(404 \"nope\")", //
			"(404 \"nope\")", //
			"\"7\"", //
			"\"dflt\"");

}
