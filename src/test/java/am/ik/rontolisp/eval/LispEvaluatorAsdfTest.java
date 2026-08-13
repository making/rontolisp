package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter side of the limited ASDF subset: {@code asdf:defsystem} as a special
 * form and {@code asdf:load-system} as a runtime function over the {@code load}
 * machinery, with {@code .asd} files served by an in-memory {@link SourceLoader}.
 */
class LispEvaluatorAsdfTest {

	private static SourceLoader loaderOf(Map<String, String> files) {
		return path -> {
			String src = files.get(path);
			if (src == null) {
				throw new java.io.FileNotFoundException(path);
			}
			return src;
		};
	}

	private String run(String source, Map<String, String> files, List<String> systemPath) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSourceLoader(loaderOf(files));
		evaluator.setSystemPath(systemPath);
		for (LispVal expr : LispReader.readAllFromString(source)) {
			evaluator.eval(expr);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void loadSystemLoadsComponentsInDependencyOrder() {
		String output = run("(asdf:load-system \"my-lib\") (print (my-lib:greet \"world\"))", Map.of(//
				"my-lib.asd", """
						(defsystem :my-lib
						  :components ((:file "main" :depends-on ("package"))
						               (:file "package")))""", //
				"package.lisp", "(defpackage :my-lib (:use :cl) (:export :greet))", //
				"main.lisp", """
						(in-package :my-lib)
						(defun greet (name) (concatenate 'string "Hello, " name))"""), List.of());
		assertThat(output).contains("\"Hello, world\"");
	}

	@Test
	void aDeclaredRontolispFeatureIsVisibleToTheReaderOfTheSystemsOwnComponents() {
		// The static encoding of a .asd that pushes onto *features* from an eval-when:
		// the push would happen at LOAD time and the conditionals are resolved at READ
		// time, so only a declaration can reach them (.todo/181). It must reach BOTH the
		// system's own :if-feature clauses and the component sources.
		String output = run("(asdf:load-system \"feat-lib\") (print (feat-lib-mode))", Map.of(//
				"feat-lib.asd", """
						(defsystem :feat-lib
						  :rontolisp-features (:feat-lib-fancy)
						  :serial t
						  :components ((:file "main")
						               (:file "fancy" :if-feature :feat-lib-fancy)))""", //
				"main.lisp", """
						(defun feat-lib-mode ()
						  #+feat-lib-fancy :fancy
						  #-feat-lib-fancy :plain)""", //
				"fancy.lisp", "(print :fancy-component-loaded)"), List.of());
		assertThat(output).contains(":FANCY-COMPONENT-LOADED").contains(":FANCY");
	}

	@Test
	void aDeclaredRontolispFeatureDoesNotLeakToAnotherSystem() {
		// The declaration is recorded per system and each loader widens its own base
		// set, so a dependency parsed from its own .asd never inherits it.
		String output = run("(asdf:load-system \"feat-app\") (print (feat-dep-mode))", Map.of(//
				"feat-app.asd", """
						(defsystem :feat-app
						  :rontolisp-features (:feat-lib-fancy)
						  :depends-on ("feat-dep")
						  :components ((:file "app")))""", //
				"app.lisp", "(defun feat-app-mode () :app)", //
				"feat-dep.asd", """
						(defsystem :feat-dep
						  :components ((:file "dep")))""", //
				"dep.lisp", """
						(defun feat-dep-mode ()
						  #+feat-lib-fancy :leaked
						  #-feat-lib-fancy :clean)"""), List.of());
		assertThat(output).contains(":CLEAN");
	}

	@Test
	void loadSystemResolvesBuiltinUsocketWithoutAnAsdFile() {
		// "usocket" is a built-in system (BuiltinSystems): no usocket.asd is looked
		// up -- the empty loader would throw FileNotFoundException if it were.
		String output = run("""
				(asdf:load-system "usocket")
				(print usocket:*wildcard-host*)
				(print (usocket:socket-stream 42))
				""", Map.of(), List.of());
		assertThat(output).contains("\"0.0.0.0\"").contains("42");
	}

	@Test
	void findSystemAnswersABuiltinSystemBeforeItIsLoaded() {
		// lack's find-package-or-load probes (asdf:find-system name nil) and loads on
		// a hit: this is the route by which (clackup app :server :rontolisp) resolves
		// the clack-handler-rontolisp backend at run time, under the DOTTED spelling
		// find-package-or-load derives from the package name.
		String output = run("""
				(print (asdf:find-system "clack.handler.rontolisp" nil))
				(print (asdf:find-system "no-such-system" nil))
				""", Map.of(), List.of());
		assertThat(output).contains("\"clack.handler.rontolisp\"").contains("NIL");
	}

	@Test
	void loadSystemResolvesTheClackHandlerShimAndRegistersItsPackage() {
		// The shim carries its own defpackage (NOT seeded in PackageRegistry: a
		// pre-seeded package would short-circuit lack's find-package probe and skip
		// the load), so find-package answers nil before and the package after; the
		// interned RUN then names the shim's exported function.
		String output = run("""
				(print (find-package "CLACK.HANDLER.RONTOLISP"))
				(asdf:load-system "clack.handler.rontolisp")
				(print (find-package "CLACK.HANDLER.RONTOLISP"))
				(print (fboundp (intern "RUN" (find-package "CLACK.HANDLER.RONTOLISP"))))
				""", Map.of(), List.of());
		assertThat(output).containsSubsequence("NIL", ":CLACK.HANDLER.RONTOLISP", "T");
	}

	@Test
	void theReactorHandlerShimRunsAClackApplicationOverTheJsonEnvelope() {
		// clack-handler-reactor is the handler backend for a HOST-DRIVEN REACTOR:
		// no socket, so its entry point is `handle` (a JSON request string in, a JSON
		// response string out) rather than clackup's `run`. It converts nothing
		// itself -- it rides %http-make-env / %http-normalize-response -- so what this
		// pins is that an ordinary Clack application sees what Clack promises: the
		// RAW target split into a percent-decoded :path-info and a :query-string, and
		// the response lowered back into the envelope.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun app (env)
				  (list 200 '(:content-type "text/plain")
				        (list (format nil "~a ~a ~a"
				                      (getf env :request-method)
				                      (getf env :path-info)
				                      (getf env :query-string)))))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"GET\\",\\"target\\":\\"/%67et?a=1\\"}"))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"status\\\":200")
			.contains("GET /get a=1")
			.contains("[[\\\"content-type\\\",\\\"text/plain\\\"]]");
	}

	@Test
	void theReactorHandlerShimAnswersAHeaderlessResponseAsAnEmptyJsonArray() {
		// A Clack response may carry no headers at all (tiny-routes' (ok "x") builds
		// one), and the envelope's headers must still cross as [] -- an empty LIST
		// would stringify as JSON false, which the Headers constructor on the
		// JavaScript side rejects. %header-pairs answers a vector for exactly this.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun app (env) (declare (ignore env)) (list 200 nil (list "bare")))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"GET\\",\\"target\\":\\"/\\"}"))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"headers\\\":[]").doesNotContain("\\\"headers\\\":false");
	}

	@Test
	void theReactorHandlerShimAnswers500RatherThanLettingAnErrorEscape() {
		// On a reactor an uncaught error is a trap that takes the whole instance
		// down, so the transport catches -- as every other rontolisp transport does
		// with a handler error.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun app (env) (declare (ignore env)) (error "boom"))
				(print (clack.handler.reactor:handle #'app "{\\"target\\":\\"/\\"}"))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"status\\\":500").contains("boom");
	}

	@Test
	void theReactorHandlerShimServesClackupThroughDispatch() {
		// (clackup app :server :reactor) resolves this backend and applies
		// RUN -- which is what the two calls below stand in for, so the test needs no
		// download of clack itself. A reactor owns no socket, so run stores the
		// application and returns nil; DISPATCH is what the host calls instead of
		// connecting, and on the interpreter it is called directly (the WASM backends
		// reach the same function through the compiler-synthesized export).
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun app (env)
				  (list 200 '(:content-type "text/plain")
				        (list (format nil "~a ~a" (getf env :request-method)
				                      (getf env :path-info)))))
				(print (clack.handler.reactor:run #'app :port 8080))
				(print (clack.handler.reactor:dispatch
				        "{\\"method\\":\\"GET\\",\\"target\\":\\"/hi\\"}"))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"status\\\":200").contains("GET /hi");
	}

	@Test
	void theReactorHandlerShimTakesTheBodyOutOfBandAsAPullSource() {
		// The transport takes a request HEAD and a BODY SOURCE, and a Clack
		// application must not be able to tell which shape the host had: a pull thunk
		// -- what a host that streams an upload passes -- reaches the application as
		// the same buffered :raw-body an in-band "body" string does, because Clack's
		// :raw-body is a synchronous stream either way.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defvar *chunks* '("he" "llo"))
				(defun pull ()
				  (if *chunks*
				      (let ((c (car *chunks*))) (setq *chunks* (cdr *chunks*)) c)
				      nil))
				(defun app (env)
				  (list 200 nil
				        (list (with-output-to-string (out)
				                (do ((ch (read-char (getf env :raw-body) nil nil)
				                         (read-char (getf env :raw-body) nil nil)))
				                    ((null ch))
				                  (write-char ch out))))))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"POST\\",\\"target\\":\\"/\\"}" #'pull))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"body\\\":\\\"hello\\\"");
	}

	@Test
	void aReactorPullSourceMayHandOverOctetsAndACodePointMaySpanTwoChunks() {
		// A chunk is a string OR an (unsigned-byte 8) vector -- the shape a
		// byte-shaped host boundary reads into a reusable buffer. The host doing the
		// cutting knows nothing about code points, so the sequence left open by one
		// chunk has to be carried into the next: both characters below straddle the
		// boundary, and decoding the chunks independently would answer four
		// malformed ones.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun octets (&rest bs)
				  (let ((v (make-array (length bs) :element-type '(unsigned-byte 8))) (k 0))
				    (dolist (b bs) (setf (aref v k) b) (setq k (+ k 1)))
				    v))
				(defvar *chunks* (list (octets #xE3 #x81 #x82 #xE3) (octets #x81 #x84)))
				(defun pull ()
				  (if *chunks*
				      (let ((c (car *chunks*))) (setq *chunks* (cdr *chunks*)) c)
				      nil))
				(defun app (env)
				  (list 200 nil
				        (list (with-output-to-string (out)
				                (do ((ch (read-char (getf env :raw-body) nil nil)
				                         (read-char (getf env :raw-body) nil nil)))
				                    ((null ch))
				                  (write-char ch out))))))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"POST\\",\\"target\\":\\"/\\"}" #'pull))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"body\\\":\\\"あい\\\"");
	}

	@Test
	void theReactorDispatcherAnswersAPortableStreamRawBodyByDefault() {
		// The reactor's OWN default is rontolisp's asynchronous stream -- the
		// http-handler directive's default, and what makes the portable
		// (await (read-all (getf env :raw-body))) drain work on a reactor too. Clack's
		// backends opt into :buffered at registration; nothing else does.
		String output = run("""
				(rontolisp:async-defun app (env)
				  (list 200 nil
				        (list (rontolisp:await
				               (rontolisp:read-all (getf env :raw-body))))))
				(rontolisp::%http-reactor-register #'app)
				(print (rontolisp::%http-reactor-dispatch
				        "{\\"method\\":\\"POST\\",\\"target\\":\\"/\\",\\"body\\":\\"streamed\\"}"))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"body\\\":\\\"streamed\\\"");
	}

	@Test
	void quickloadResolvesBuiltinUsocketWithoutDownloading() {
		// A built-in system short-circuits before the QuicklispClient is even
		// created, so no network or cache is touched.
		String output = run("""
				(ql:quickload :usocket)
				(print (usocket:socket-stream 7))
				""", Map.of(), List.of());
		assertThat(output).contains("7");
	}

	@Test
	void dependsOnBuiltinUsocketLoadsTheShimFirst() {
		String output = run("(asdf:load-system \"net-lib\") (print (net-lib:stream-of 9))", Map.of(//
				"net-lib.asd", """
						(defsystem :net-lib
						  :depends-on ("usocket")
						  :components ((:file "net")))""", //
				"net.lisp", """
						(defpackage :net-lib (:use :cl) (:export :stream-of))
						(in-package :net-lib)
						(defun stream-of (s) (usocket:socket-stream s))"""), List.of());
		assertThat(output).contains("9");
	}

	@Test
	void dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals() {
		// clack.asd hard-depends on "swank", whose real .asd is a program the
		// defsystem-as-data front-end cannot read -- without the stub system the
		// dependency sends quickload after the slime tarball. stop-server is a nil
		// no-op (clack calls it whenever a swank port was recorded); create-server
		// signals, because a caller reaching it explicitly asked for a remote REPL.
		String output = run("""
				(asdf:load-system "repl-lib")
				(print (repl-lib:stop 4005))
				(print (handler-case (swank:create-server :port 4005)
				         (error (c) :signaled)))
				""", Map.of(//
				"repl-lib.asd", """
						(defsystem :repl-lib
						  :depends-on ("swank")
						  :components ((:file "repl")))""", //
				"repl.lisp", """
						(defpackage :repl-lib (:use :cl) (:export :stop))
						(in-package :repl-lib)
						(defun stop (port) (swank:stop-server port))"""), List.of());
		assertThat(output).contains("NIL").contains(":SIGNALED");
	}

	@Test
	void loadSystemAcceptsAComputedNameAtRuntime() {
		String output = run("""
				(defvar *sys* "my-lib")
				(asdf:load-system *sys*)
				(print (f))""", Map.of("my-lib.asd", "(defsystem :my-lib :components ((:file \"main\")))", //
				"main.lisp", "(defun f () 42)"), List.of());
		assertThat(output).contains("42");
	}

	@Test
	void loadSystemIsIdempotent() {
		String output = run("""
				(asdf:load-system :my-lib)
				(asdf:load-system :my-lib)""",
				Map.of("my-lib.asd", "(defsystem :my-lib :components ((:file \"main\")))", //
						"main.lisp", "(print \"loaded\")"),
				List.of());
		// The component file runs once: the second load-system is a no-op.
		assertThat(output.split("loaded", -1)).hasSize(2);
	}

	@Test
	void loadSystemLoadsDependencySystemsFirst() {
		String output = run("(asdf:load-system :app)", Map.of(//
				"app.asd", "(defsystem :app :depends-on (:base) :components ((:file \"app\")))", //
				"base.asd", "(defsystem :base :components ((:file \"base\")))", //
				"base.lisp", "(print \"base\")", //
				"app.lisp", "(print \"app\")"), List.of());
		assertThat(output.indexOf("base")).isLessThan(output.indexOf("app"));
	}

	@Test
	void loadSystemSearchesTheSystemPath() {
		String output = run("(asdf:load-system :lib)", Map.of(//
				"registry/lib.asd", "(defsystem :lib :components ((:file \"main\")))", //
				"registry/main.lisp", "(print \"from registry\")"), List.of("registry"));
		assertThat(output).contains("from registry");
	}

	@Test
	void componentFilesResolveAgainstTheAsdDirectory() {
		String output = run("(asdf:load-system :lib)", Map.of(//
				"registry/lib.asd", """
						(defsystem :lib
						  :components ((:module "src" :components ((:file "main")))))""", //
				"registry/src/main.lisp", "(print \"nested\")"), List.of("registry"));
		assertThat(output).contains("nested");
	}

	@Test
	void inlineDefsystemRegistersForALaterLoadSystem() {
		String output = run("""
				(asdf:defsystem :inline-sys :components ((:file "main")))
				(asdf:load-system :inline-sys)""", Map.of("main.lisp", "(print \"inline\")"), List.of());
		assertThat(output).contains("inline");
	}

	@Test
	void loadSystemDoesNotLeakTheCurrentPackageToTheCaller() {
		// A component file's (in-package :my-lib) must be scoped to the load: after
		// asdf:load-system returns, *package* is restored to the caller's package
		// (cl-user), like Common Lisp's load binding *package* dynamically.
		String output = run("""
				(asdf:load-system :my-lib)
				(princ *package*)""", Map.of(//
				"my-lib.asd", """
						(defsystem :my-lib
						  :components ((:file "package") (:file "main" :depends-on ("package"))))""", //
				"package.lisp", "(defpackage :my-lib (:use :cl) (:export :greet))", //
				"main.lisp", """
						(in-package :my-lib)
						(defun greet () 1)"""), List.of());
		assertThat(output).isEqualTo("CL-USER");
	}

	@Test
	void aFunctionDefinedAfterALoadResolvesInTheCallersPackage() {
		// The end-to-end shape of examples/net/http-handler-cl-who.lisp: a top-level
		// defun
		// AFTER the load, handed to a caller by unqualified quoted symbol, must resolve
		// (the leaked package would define/quote it under the loaded system's package).
		String output = run("""
				(asdf:load-system :my-lib)
				(defun handle () 42)
				(print (funcall (symbol-function 'handle)))""", Map.of(//
				"my-lib.asd", """
						(defsystem :my-lib
						  :components ((:file "package") (:file "main" :depends-on ("package"))))""", //
				"package.lisp", "(defpackage :my-lib (:use :cl) (:export :greet))", //
				"main.lisp", """
						(in-package :my-lib)
						(defun greet () 1)"""), List.of());
		assertThat(output).contains("42");
	}

	@Test
	void missingSystemNamesTheTriedPathsAndTheRegistryOptions() {
		assertThatThrownBy(() -> run("(asdf:load-system :nope)", Map.of(), List.of("registry")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("system 'nope' not found")
			.hasMessageContaining("--system-path");
	}

	@Test
	void circularSystemDependencyIsAHardError() {
		assertThatThrownBy(() -> run("(asdf:load-system :a)", Map.of(//
				"a.asd", "(defsystem :a :depends-on (:b))", //
				"b.asd", "(defsystem :b :depends-on (:a))"), List.of()))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Circular system :depends-on");
	}

	@Test
	void asdNotDefiningTheRequestedSystemIsAHardError() {
		assertThatThrownBy(() -> run("(asdf:load-system :lib)",
				Map.of("lib.asd", "(defsystem :something-else :components nil)"), List.of()))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("does not define system 'lib'");
	}

}
