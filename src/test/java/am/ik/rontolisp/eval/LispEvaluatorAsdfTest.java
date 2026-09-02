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
	void loadSystemReadsTheExtensionAComponentClassGivesItsFiles() {
		// portableaserve's shape end to end: a cl-source-file.cl subclass and a
		// :default-component-class, so every component names NAME.cl. The extension has
		// to travel all the way to the loader -- a .lisp read here would find nothing.
		String output = run("(asdf:load-system \"acl-lib\") (print (acl-lib:greet))", Map.of(//
				"acl-lib.asd", """
						(defclass legacy-acl-source-file (cl-source-file.cl) ())
						(defmethod perform :around ((operation compile-op) (c legacy-acl-source-file))
						  (call-next-method))
						(defsystem :acl-lib
						  :default-component-class cl-source-file.cl
						  :components ((:file "package")
						               (:legacy-acl-source-file "main" :depends-on ("package"))))""", //
				"package.cl", "(defpackage :acl-lib (:use :cl) (:export :greet))", //
				"main.cl", """
						(in-package :acl-lib)
						(defun greet () "Hello from .cl")"""), List.of());
		assertThat(output).contains("\"Hello from .cl\"");
	}

	@Test
	void aDeclaredRontolispFeatureIsVisibleToTheReaderOfTheSystemsOwnComponents() {
		// The static encoding of a .asd that pushes onto *features* from an eval-when:
		// the push would happen at LOAD time and the COMPONENT files' conditionals are
		// resolved at READ time, so only a declaration can carry it across files. It
		// must reach BOTH the system's own :if-feature clauses and the component
		// sources.
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
	void findSystemAnswersAMemoizedComponentMetaobject() {
		// asdf.lisp (AsdfRuntimeLibrary): the runtime system object is a real CLOS
		// instance -- typep works, eq across calls (one object per system), the readers
		// answer, registered-systems lists the registry, *user-cache* is external nil.
		String output = run("""
				(asdf:defsystem :demo :components ((:file "main")))
				(print (typep (asdf:find-system :demo) 'asdf:system))
				(print (eq (asdf:find-system :demo) (asdf:find-system "demo")))
				(print (asdf:component-name (asdf:find-system :demo)))
				(print (mapcar (lambda (c) (asdf:component-name c))
				               (asdf:component-children (asdf:find-system :demo))))
				(print (asdf:registered-systems))
				(print asdf:*user-cache*)
				(print (asdf:find-system :absent nil))
				""", Map.of(), List.of());
		assertThat(output.trim().lines().map(String::trim)).containsExactly("T", "T", "\"demo\"", "(\"main\")",
				"(\"demo\")", "NIL", "NIL");
	}

	@Test
	void componentVersionAnswersTheDeclaredVersionStringAndNilForEveryOtherSpelling() {
		// dexador builds its User-Agent from (asdf:component-version (find-system
		// :dexador)). The value is recorded AS WRITTEN: a plain string, and nil for the
		// (:read-file-form ...) indirection nothing here evaluates.
		String output = run("""
				(asdf:defsystem :demo :version "0.9.15" :components ((:file "main")))
				(asdf:defsystem :other :version (:read-file-form "version.sexp"))
				(print (asdf:component-version (asdf:find-system :demo)))
				(print (asdf:component-version (asdf:find-system :other)))
				(print (asdf:component-version (car (asdf:component-children (asdf:find-system :demo)))))
				""", Map.of(), List.of());
		assertThat(output.trim().lines().map(String::trim)).containsExactly("\"0.9.15\"", "NIL", "NIL");
	}

	@Test
	void aDefsystemDependsOnBuiltinIsLoadedFirstAndAnnouncesItsFeatures() {
		// dexador.asd's opener. Real ASDF loads such a system while the .asd is READ, so
		// its announcement holds for this system's component files -- here the built-in
		// trivial-features shim's :unix -- and it never becomes a sideway dependency.
		String output = run("""
				(asdf:load-system "app")
				(print (which))
				(print (asdf:component-sideway-dependencies (asdf:find-system "app")))
				(print (if (member :unix *features*) :announced :absent))
				""", Map.of("app.asd", """
				(defsystem :app
				  :defsystem-depends-on ("trivial-features")
				  :components ((:file "main")))""", //
				"main.lisp", "(defun which () #+unix \"unix\" #-unix \"unannounced\")"), List.of());
		assertThat(output.trim().lines().map(String::trim)).containsExactly("\"unix\"", "NIL", ":ANNOUNCED");
	}

	@Test
	void testSystemFollowsTheInOrderToChainIntoThePerformBody() {
		// fukamachi's .asd shape reduced: test-system on the primary loads it, follows
		// the :in-order-to test-op edge into the tests system, and runs its recorded
		// :perform body with the component bound to the system metaobject.
		String output = run("(asdf:test-system \"lib\")", Map.of(//
				"lib.asd", """
						(defsystem :lib
						  :components ((:file "main"))
						  :in-order-to ((test-op (test-op "lib/tests"))))
						(defsystem "lib/tests"
						  :depends-on ("lib")
						  :components ((:file "tests"))
						  :perform (test-op (o c) (run-lib-tests (component-name c))))""", //
				"main.lisp", "(defun lib-fn () 41)", //
				"tests.lisp", """
						(defun run-lib-tests (name)
						  (print name)
						  (print (+ 1 (lib-fn))))"""), List.of());
		assertThat(output.trim().lines().map(String::trim)).containsExactly("\"lib/tests\"", "42");
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
	void aReactorSourceMayBeAHostReaderFillingOneBuffer() {
		// The read(2) shape the WASM boundary takes, written here as ordinary Lisp:
		// the caller owns ONE buffer and hands it over, the reader fills up to its
		// length and answers how many octets it wrote, 0 for end of stream. Reusing
		// the buffer is the whole memory argument for chunking, so the adapter that
		// turns (buffer, count) into a chunk is the transport's, not each host's.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defvar *octets* (list #xE3 #x81 #x82 #xE3 #x81 #x84))
				(defun host-read (buf)
				  (let ((n 0) (cap (length buf)))
				    (while (and (< n cap) *octets*)
				      (setf (aref buf n) (car *octets*))
				      (setq *octets* (cdr *octets*))
				      (setq n (+ n 1)))
				    n))
				(defun pull ()
				  (let ((buf (rontolisp::%http-reactor-buffer 4)))
				    (rontolisp::%http-reactor-chunk buf (host-read buf))))
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
	void aReactorBuffersAnOctetBodyAsTheOctetsItIs() {
		// The buffered :raw-body is a BIVALENT stream over OCTETS, so a body that
		// already arrived as octets must reach it as those octets. Decoding each
		// chunk to text and letting %http-body-stream UTF-8 encode it again is not
		// merely a second pass over the body: the decoder is lenient by construction
		// -- a byte that starts no sequence answers its own character -- so the ff fe
		// 41 a binary upload carries came back as c3 bf c3 be 41, the very double
		// encode that sent the body out of band in the first place.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun octets (&rest bs)
				  (let ((v (make-array (length bs) :element-type '(unsigned-byte 8))) (k 0))
				    (dolist (b bs) (setf (aref v k) b) (setq k (+ k 1)))
				    v))
				(defvar *chunks* (list (octets #xFF #xFE) (octets #x41)))
				(defun pull ()
				  (if *chunks*
				      (let ((c (car *chunks*))) (setq *chunks* (cdr *chunks*)) c)
				      nil))
				(defun app (env)
				  (list 200 nil
				        (list (format nil "~a"
				                      (let ((out nil))
				                        (do ((b (read-byte (getf env :raw-body) nil nil)
				                                (read-byte (getf env :raw-body) nil nil)))
				                            ((null b))
				                          (setq out (cons b out)))
				                        (nreverse out))))))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"POST\\",\\"target\\":\\"/\\"}" #'pull))
				""", Map.of(), List.of());
		assertThat(output).contains("(255 254 65)");
	}

	@Test
	void aBufferedRawBodyAnswersACharacterElementType() {
		// The buffered :raw-body is BIVALENT, and what a portable library asks it is
		// which buffer to allocate. tiny-routes' read-stream-to-string is the shape:
		// (make-array content-length :element-type (stream-element-type input-stream)),
		// read-sequence into it, write-sequence out to a string stream. Answering the
		// binary type hands that library an octet buffer it then writes to a CHARACTER
		// sink -- which signals here and on SBCL alike. Upstream a Clack :raw-body is a
		// flexi-stream, and a flexi-stream answers the CHARACTER type, so that is the
		// answer a bivalent stream owes: the byte reads stay available either way,
		// while the character one is the buffer a text sink can take.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun app (env)
				  (let ((body (getf env :raw-body)))
				    (list 200 nil
				          (list (format nil "~a|~a"
				                        (stream-element-type body)
				                        (with-output-to-string (out)
				                          (let* ((buf (make-array 8 :element-type
				                                                  (stream-element-type body)))
				                                 (n (read-sequence buf body)))
				                            (write-sequence buf out :end n))))))))
				(print (clack.handler.reactor:handle
				        #'app
				        "{\\"method\\":\\"POST\\",\\"target\\":\\"/\\",\\"body\\":\\"あい\\"}"
				        nil))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"body\\\":\\\"CHARACTER|あい\\\"");
	}

	@Test
	void aReactorSourceThatIsEmptyIsNoBodyAndFallsBackToTheEnvelope() {
		// Once the body stops riding the envelope, "is there a body at all" is a
		// question only the host can answer -- a reader answers 0 for a bodiless GET
		// -- and the answer has to reach the application as the same nil an absent
		// "body" key does, because upstream guards :raw-body with
		// (when raw-body ...). The look-ahead that decides it pushes its chunk back,
		// so an empty source also leaves the envelope's own key winning rather than
		// shadowing it.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defun empty () nil)
				(defun app (env)
				  (list 200 nil (list (if (getf env :raw-body) "a-stream" "none"))))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"GET\\",\\"target\\":\\"/\\"}" #'empty))
				(defun echo (env)
				  (list 200 nil
				        (list (with-output-to-string (out)
				                (do ((ch (read-char (getf env :raw-body) nil nil)
				                         (read-char (getf env :raw-body) nil nil)))
				                    ((null ch))
				                  (write-char ch out))))))
				(print (clack.handler.reactor:handle
				        #'echo
				        "{\\"method\\":\\"POST\\",\\"target\\":\\"/\\",\\"body\\":\\"in-band\\"}"
				        #'empty))
				""", Map.of(), List.of());
		assertThat(output).contains("\\\"body\\\":\\\"none\\\"").contains("\\\"body\\\":\\\"in-band\\\"");
	}

	@Test
	void aReactorSinkTakesTheResponseBodyOutOfTheHead() {
		// The response body leaves the head the same way the request body did: given a
		// sink, every chunk goes to it and the head carries NO "body" key, so a host can
		// tell "the body crossed out of band" from "the body is the empty string". A
		// STREAM body is forwarded chunk at a time rather than being collected first,
		// which is the whole point -- it used to reach json-stringify unresolved.
		String output = run("""
				(ql:quickload "clack-handler-reactor")
				(defvar *chunks* nil)
				(defun sink (chunk) (setq *chunks* (cons chunk *chunks*)) nil)
				(defvar *parts* '("one " "two " "three"))
				(defun part ()
				  (if *parts*
				      (let ((c (car *parts*))) (setq *parts* (cdr *parts*)) c)
				      nil))
				(defun app (env)
				  (declare (ignore env))
				  (list 200 nil (rontolisp::%stream-new #'part (lambda () nil))))
				(print (clack.handler.reactor:handle
				        #'app "{\\"method\\":\\"GET\\",\\"target\\":\\"/\\"}" nil #'sink))
				(print (reverse *chunks*))
				""", Map.of(), List.of());
		assertThat(output).doesNotContain("body").contains("(\"one \" \"two \" \"three\")");
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
