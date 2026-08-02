package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JvmLispCompilerTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode) throws Exception {
		// mirror the CLI pipeline's prelude splice (equalp/string</read-all) so the
		// prelude-backed built-ins resolve like they do in a real compile
		return compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	// JSON tests pre-process with JsonLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private String compileAndRunJson(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.JsonLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	// linalg tests pre-process with LinalgLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private String compileAndRunLinalg(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	// URL tests pre-process with UrlLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private String compileAndRunUrl(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.UrlLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	// usocket tests pre-process with UsocketLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private String compileAndRunUsocket(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.UsocketLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	private String compileAndRun(List<LispVal> program) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	// warn writes its "WARNING: ..." line to standard ERROR, which compileAndRun drops.
	private String compileAndRunCapturingErr(String lispCode) throws Exception {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			compileAndRun(lispCode);
		}
		finally {
			System.setErr(oldErr);
		}
		return err.toString().trim();
	}

	@Test
	void compileFindClassReturnsAnEqStableClassMetaobject() throws Exception {
		// The generated metaobject runtime memoizes per canonical name, so the answer is
		// eq across calls; slot 0 = name, slot 1 = direct-superclass metaobjects, slot 4
		// = finalized-p (the %obj-ref index contract of the seeded MOP layouts).
		assertThat(compileAndRun("""
				(defclass fc-animal () ((legs :initarg :legs :accessor fc-legs :type integer)))
				(defclass fc-dog (fc-animal) ((name :initarg :name)))
				(let ((c (find-class 'fc-dog)))
				  (print (list (eq c (find-class 'fc-dog))
				               (%obj-ref c 0)
				               (eq (car (%obj-ref c 1)) (find-class 'fc-animal))
				               (%obj-ref c 4))))
				""")).isEqualTo("(T FC-DOG T T)");
	}

	@Test
	void compileFindClassMetaobjectCarriesEffectiveSlotDefinitions() throws Exception {
		assertThat(compileAndRun("""
				(defclass fc-box () ((w :initarg :w :accessor fc-w :type integer) (h :initform 2)))
				(let* ((c (find-class 'fc-box))
				       (slots (%obj-ref c 3)))
				  (print (list (length slots)
				               (mapcar (lambda (s) (%obj-ref s 0)) slots)
				               (%obj-ref (car slots) 1)
				               (%obj-ref (car slots) 3)
				               (%obj-ref (car slots) 4)
				               (%obj-ref (car (cdr slots)) 2))))
				""")).isEqualTo("(2 (W H) (:W) INTEGER (FC-W) 2)");
	}

	@Test
	void compileFindClassUnknownSignalsUnlessErrorpNil() throws Exception {
		assertThat(compileAndRun("""
				(print (list (find-class 'fc-no-such nil)
				             (handler-case (find-class 'fc-no-such) (error (e) :signaled))))
				""")).isEqualTo("(NIL :SIGNALED)");
	}

	@Test
	void compileFindClassAnswersForSeededConditionClasses() throws Exception {
		assertThat(compileAndRun("""
				(let ((c (find-class 'type-error)))
				  (print (list (%obj-ref c 0) (%obj-ref (car (%obj-ref c 1)) 0))))
				""")).isEqualTo("(TYPE-ERROR ERROR)");
	}

	@Test
	void compileSetfSlotValueWithARuntimeSlotName() throws Exception {
		// The write-side twin of the runtime-name slot-value read: postmodern's
		// dao-from-fields writes every column through a runtime slot name.
		assertThat(compileAndRun("""
				(defclass rs-pt () ((x :initarg :x :initform 0) (y :initform 1)))
				(let ((p (make-instance 'rs-pt))
				      (n 'y))
				  (print (list (setf (slot-value p n) 42) (slot-value p 'y) (slot-value p n))))
				""")).isEqualTo("(42 42 42)");
	}

	@Test
	void compileMakeInstanceAsAFirstClassFunction() throws Exception {
		// (apply #'make-instance class initargs) -- the postmodern make-dao idiom: a
		// runtime class (metaobject or name) routes through the generated
		// %mop-make-instance dispatch, whose arms widen to every registered class when
		// the program takes make-instance as a value.
		assertThat(compileAndRun("""
				(defclass fv-pt () ((x :initarg :x :initform 0)))
				(defclass fv-line () ((n :initarg :n)))
				(let ((a (apply #'make-instance (list (find-class 'fv-pt) :x 7)))
				      (b (funcall #'make-instance 'fv-line :n 3)))
				  (print (list (slot-value a 'x) (slot-value b 'n) (typep a 'fv-pt) (typep b 'fv-line))))
				""")).isEqualTo("(7 3 T T)");
	}

	@Test
	void compileCloserMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators() throws Exception {
		// The closer-mop system is spliced by the compile-time LoadInliner pass (cli),
		// mirroring the CLI pipeline; the shim serves BOTH generations, exactly like the
		// interpreter
		// (LispEvaluatorTest#closerMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators).
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "closer-mop")
				(defclass cmm-p () ((name :initarg :name) (age :initarg :age :type integer)))
				(let ((c (find-class 'cmm-p)))
				  (print (list (closer-mop:classp c)
				               (closer-mop:classp 42)
				               (closer-mop:class-name c)
				               (closer-mop:class-finalized-p c)
				               (mapcar #'closer-mop:slot-definition-name (closer-mop:class-slots c))
				               (mapcar #'closer-mop:slot-definition-type (closer-mop:class-slots c))
				               (mapcar #'closer-mop:slot-definition-initargs (closer-mop:class-slots c))
				               (mapcar #'closer-mop:slot-definition-name
				                       (closer-mop:class-slots (class-of (make-instance 'cmm-p :name "x")))))))
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))
			.isEqualTo("(T NIL CMM-P T (NAME AGE) (T INTEGER) ((:NAME) (:AGE)) (NAME AGE))");
	}

	@Test
	void compileAllocateInstanceAnswersAnAllSlotsUnboundInstance() throws Exception {
		// The generated allocate-instance runtime (injected when referenced) resolves a
		// metaobject OR a name designator to the per-class construction arm; every
		// slot starts unbound, mirroring the interpreter built-in.
		assertThat(compileAndRun("""
				(defclass ai-pt () ((x :initarg :x :initform 7) (y :initarg :y)))
				(let ((p (allocate-instance (find-class 'ai-pt))))
				  (print (list (typep p 'ai-pt)
				               (slot-boundp p 'x)
				               (slot-boundp p 'y)
				               (progn (setf (slot-value p 'x) 10) (slot-value p 'x))
				               (let ((q (allocate-instance 'ai-pt))) (slot-boundp q 'x)))))
				""")).isEqualTo("(T NIL NIL 10 NIL)");
	}

	@Test
	void compileAllocateInstanceRejectsNonClosClasses() throws Exception {
		assertThat(compileAndRun("""
				(defclass ai-ok () ((v :initarg :v)))
				(defstruct ai-node value)
				(print (list (handler-case (allocate-instance (find-class 'integer)) (error (e) :signaled))
				             (handler-case (allocate-instance 'ai-node) (error (e) :signaled))
				             (typep (allocate-instance (find-class 'ai-ok) :v 1 :junk 2) 'ai-ok)))
				""")).isEqualTo("(:SIGNALED :SIGNALED T)");
	}

	@Test
	void compileDefclassMetaclassRunsTheClassDefinitionProtocol() throws Exception {
		// The postmodern dao-class shape on the compile path, mirroring
		// LispEvaluatorTest#defclassMetaclassRunsTheClassDefinitionProtocol: the
		// MopProtocol defaults and the %mop-make-instance/%register-class-metaobject
		// runtime are injected by the :metaclass gate, the driver call runs at program
		// start in top-level order, and find-class/class-of answer the driver-built
		// metaclass instance from then on.
		assertThat(compileAndRun("""
				(defvar *direct-column-slot* nil)
				(defclass mc-dao-class (standard-class)
				  ((direct-keys :initarg :keys :initform nil :accessor mc-direct-keys)
				   (table-name)))
				(defclass mc-direct-column-slot (closer-mop:standard-direct-slot-definition)
				  ((col-type :initarg :col-type :accessor mc-column-type)))
				(defclass mc-effective-column-slot (closer-mop:standard-effective-slot-definition)
				  ((direct-slot :initform *direct-column-slot* :reader mc-slot-column)))
				(defmethod closer-mop:validate-superclass ((class mc-dao-class) (super standard-class)) t)
				(defmethod shared-initialize :before ((class mc-dao-class) slot-names
				                                      &key table-name &allow-other-keys)
				  (if table-name
				      (setf (slot-value class 'table-name) (car table-name))
				      (slot-makunbound class 'table-name)))
				(defmethod closer-mop:direct-slot-definition-class ((class mc-dao-class) &rest initargs
				                                                    &key col-type &allow-other-keys)
				  (if col-type (find-class 'mc-direct-column-slot) (call-next-method)))
				(defmethod closer-mop:compute-effective-slot-definition ((class mc-dao-class) name dsds)
				  (let ((*direct-column-slot* (find-if (lambda (s) (typep s 'mc-direct-column-slot)) dsds)))
				    (call-next-method)))
				(defmethod closer-mop:effective-slot-definition-class ((class mc-dao-class) &rest initargs)
				  (if *direct-column-slot* (find-class 'mc-effective-column-slot) (call-next-method)))
				(defvar *mc-finalized* nil)
				(defmethod closer-mop:finalize-inheritance :after ((class mc-dao-class))
				  (setq *mc-finalized* (cons (%obj-ref class 0) *mc-finalized*)))
				(defclass mc-user ()
				  ((id :col-type integer :initarg :id :accessor mc-user-id)
				   (note :initarg :note :initform "n/a"))
				  (:metaclass mc-dao-class)
				  (:table-name "users")
				  (:keys id))
				(print (list (let ((c (find-class 'mc-user)))
				               (list (%obj-ref c 0)
				                     (typep c 'mc-dao-class)
				                     (eq (class-of c) (find-class 'mc-dao-class))
				                     (slot-value c 'table-name)
				                     (mc-direct-keys c)
				                     *mc-finalized*
				                     (%obj-ref c 4)))
				             (let ((u (make-instance 'mc-user :id 7)))
				               (list (mc-user-id u) (slot-value u 'note) (eq (class-of u) (find-class 'mc-user))))
				             (mapcar (lambda (s)
				                       (list (%obj-ref s 0)
				                             (if (typep s 'mc-effective-column-slot)
				                                 (mc-column-type (mc-slot-column s))
				                                 :plain)))
				                     (%obj-ref (find-class 'mc-user) 3))))
				"""))
			.isEqualTo("((MC-USER T T \"users\" (ID) (MC-USER) T) (7 \"n/a\" T) ((ID INTEGER) (NOTE :PLAIN)))");
	}

	@Test
	void compileDefclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling() throws Exception {
		// Mirrors
		// LispEvaluatorTest#defclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling
		// on the compile path: the :before's reset of an initarg-supplied slot must not
		// survive the re-fill, on the defclass driver's %mop-make-instance AND on the
		// widened #'make-instance-as-value dispatch.
		assertThat(compileAndRun("""
				(defclass mcb-meta (standard-class)
				  ((ks :initarg :keys :initform nil :reader mcb-ks)
				   (table-name)))
				(defmethod shared-initialize :before ((c mcb-meta) slot-names
				                                      &key table-name &allow-other-keys)
				  (setf (slot-value c 'ks) nil)
				  (if table-name
				      (setf (slot-value c 'table-name) (car table-name))
				      (slot-makunbound c 'table-name)))
				(defclass mcb-user () ((id)) (:metaclass mcb-meta) (:keys id) (:table-name "users"))
				(print (let ((c (find-class 'mcb-user))
				             (m (apply #'make-instance (list 'mcb-meta :name 'raw :keys '(k)))))
				         (list (mcb-ks c) (slot-value c 'table-name) (mcb-ks m))))
				""")).isEqualTo("((ID) \"users\" (K))");
	}

	@Test
	void compileInterceptsDefinitionTimeMethodConstruction() throws Exception {
		// The build-dao-methods idiom on the compile path, mirroring
		// LispEvaluatorTest#compileInterceptsDefinitionTimeMethodConstruction. The
		// program routes through UserMacroExpander (the CLI pipeline): the :metaclass
		// defclass activates the pass, its macro-time evaluation runs the
		// class-definition protocol, the (compile nil `(lambda () ,code)) inside the
		// finalize-inheritance :after hook is intercepted, and the folded method
		// definitions are spliced after the defclass -- where the nested defmethods
		// register statically and their bodies close over the let*/labels lexicals.
		// At run time the driver re-runs the hook and the generated compile runtime
		// answers a no-op for the already-spliced construction.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defclass ce-tbl-class (standard-class)
				  ((table-name :initform nil)))
				(defmethod shared-initialize :before ((class ce-tbl-class) slot-names
				                                      &key table-name &allow-other-keys)
				  (if table-name (setf (slot-value class 'table-name) (car table-name)) nil))
				(defgeneric ce-row-tag (obj))
				(defgeneric ce-fetch-row (type key))
				(defun ce-eval (code)
				  (funcall (compile nil (list 'lambda nil code))))
				(defun ce-build-methods (class)
				  (ce-eval
				   `(let* ((tname (slot-value ,class 'table-name)))
				      (labels ((prefix (s) (concatenate 'string tname ":" s)))
				        (defmethod ce-row-tag ((object ,class))
				          (prefix "row"))
				        (defmethod ce-fetch-row ((type (eql (class-name ,class))) key)
				          (prefix key))))))
				(defmethod closer-mop:finalize-inheritance :after ((class ce-tbl-class))
				  (ce-build-methods class))
				(defclass ce-user ()
				  ((id :initarg :id))
				  (:metaclass ce-tbl-class)
				  (:table-name "users"))
				(print (list (ce-row-tag (make-instance 'ce-user :id 1))
				             (ce-fetch-row 'ce-user "k7")))
				"""));
		assertThat(compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))
			.isEqualTo("(\"users:row\" \"users:k7\")");
	}

	@Test
	void compileCloserCommonLispPackageServesTheDaoPackageShape() throws Exception {
		// (:use :closer-common-lisp) implies cl, and the closer-mop members inherit
		// through the re-export -- the postmodern DAO package shape on the compile
		// path (shim spliced by LoadInliner, like the closer-mop test above).
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "closer-mop")
				(defpackage :ccl-probe (:use :closer-common-lisp))
				(in-package :ccl-probe)
				(defclass ccl-pt () ((x :initarg :x) (y :initarg :y)))
				(let ((c (find-class 'ccl-pt)))
				  (print (list (classp c)
				               (mapcar #'slot-definition-name (class-slots c))
				               (c2cl:class-name c)
				               (car (c2cl:list 1 2)))))
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))
			.isEqualTo("(T (X Y) CCL-PROBE::CCL-PT 1)");
	}

	@Test
	void compileAndRunHandlerCaseCatchesTypedErrorByClass() throws Exception {
		assertThat(compileAndRun("""
				(define-condition hc-err (error) ((v :initarg :v :reader hc-err-v)))
				(print (handler-case (error 'hc-err :v 7)
				         (hc-err (e) (list :caught (hc-err-v e)))))
				""")).isEqualTo("(:CAUGHT 7)");
	}

	@Test
	void compileAndRunHandlerCaseCatchesPlainErrorAsError() throws Exception {
		assertThat(compileAndRun("""
				(print (handler-case (error "boom ~a" 1)
				         (error (e) (list :caught (simple-condition-format-control e)))))
				""")).isEqualTo("(:CAUGHT \"boom 1\")");
	}

	@Test
	void compileAndRunHandlerCaseCatchesErrorFromCalledFunction() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-thrower () (error "deep"))
				(print (handler-case (hc-thrower) (error (e) :caught)))
				""")).isEqualTo(":CAUGHT");
	}

	@Test
	void compileAndRunHandlerCaseDispatchesByHierarchyAndClauseOrder() throws Exception {
		assertThat(compileAndRun("""
				(define-condition hc-sub (parse-error) ())
				(print (handler-case (error 'hc-sub)
				         (warning (w) :warning)
				         (parse-error (e) :parse)
				         (error (e) :error)))
				""")).isEqualTo(":PARSE");
	}

	@Test
	void compileAndRunHandlerCaseRethrowsUnmatchedToOuterHandler() throws Exception {
		assertThat(compileAndRun("""
				(define-condition hc-warn2 (warning) ())
				(print (handler-case
				           (handler-case (error 'hc-warn2)
				             (error (e) :inner))
				         (warning (w) :outer)))
				""")).isEqualTo(":OUTER");
	}

	@Test
	void compileAndRunHandlerCaseUnmatchedErrorAborts() {
		assertThatThrownBy(() -> compileAndRun("(handler-case (error \"boom\") (warning (w) :w))"))
			.hasRootCauseMessage("boom");
	}

	@Test
	void compileAndRunHandlerCaseNoErrorClauseReceivesValue() throws Exception {
		assertThat(compileAndRun("(print (handler-case (+ 1 2) (error (e) :err) (:no-error (v) (list :ok v))))"))
			.isEqualTo("(:OK 3)");
	}

	@Test
	void compileAndRunRestartCaseNormalCompletionReturnsPrimaryValues() throws Exception {
		assertThat(compileAndRun("(print (restart-case (+ 1 2) (retry () :retried)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (multiple-value-list (restart-case (values 1 2) (retry () nil))))"))
			.isEqualTo("(1 2)");
	}

	@Test
	void compileAndRunHandlerBindInvokesKeywordRestartAcrossFunctions() throws Exception {
		// The postmodern prepare.lisp shape: the restart is ESTABLISHED in one
		// function and INVOKED (by keyword name, with an argument) from a
		// handler-bind handler running in another, before unwinding.
		assertThat(compileAndRun("""
				(defun rs-f ()
				  (restart-case (progn (error "boom") :not-reached)
				    (:reconnect (x) (list :reconnected x))))
				(print (handler-bind ((error (lambda (c) (invoke-restart :reconnect 42))))
				         (rs-f)))
				""")).isEqualTo("(:RECONNECTED 42)");
	}

	@Test
	void compileAndRunHandlerBindDecliningHandlerFallsThroughToHandlerCase() throws Exception {
		assertThat(compileAndRun("""
				(print (let ((log nil))
				         (handler-case
				             (handler-bind ((error (lambda (c) (setq log (cons :seen log)))))
				               (error "boom"))
				           (error (e) (cons :caught log)))))
				""")).isEqualTo("(:CAUGHT :SEEN)");
	}

	@Test
	void compileAndRunHandlerBindReceivesTypedConditionWithSlots() throws Exception {
		assertThat(compileAndRun("""
				(define-condition hb-err (error) ((v :initarg :v :reader hb-err-v)))
				(print (handler-bind ((hb-err (lambda (c) (invoke-restart :use (hb-err-v c)))))
				         (restart-case (error 'hb-err :v 7)
				           (:use (x) (list :slot x)))))
				""")).isEqualTo("(:SLOT 7)");
	}

	@Test
	void compileAndRunFindRestartReturnsObjectAndGoLeavesClauseIntoTagbody() throws Exception {
		// The postmodern transaction.lisp shape: find-restart with a condition
		// argument returns a first-class restart object, invoke-restart on the
		// object transfers to the clause, and the clause body (go start) re-enters
		// the enclosing tagbody -- a lexical, same-function go.
		assertThat(compileAndRun("""
				(defun rs-retry (c)
				  (let ((r (find-restart 'retry-me c)))
				    (if (null r) :none (invoke-restart r))))
				(print (handler-bind ((error (lambda (c) (rs-retry c))))
				         (let ((n 0))
				           (tagbody start
				             (restart-case
				                 (progn (setq n (+ n 1)) (when (< n 3) (error "again")))
				               (retry-me () (go start))))
				           n)))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunRestartsDisappearOutsideTheirExtent() throws Exception {
		assertThat(compileAndRun("(print (progn (restart-case 1 (gone () nil)) (find-restart 'gone)))"))
			.isEqualTo("NIL");
		assertThat(compileAndRun("(print (handler-case (invoke-restart :nope) (error (e) :no-restart)))"))
			.isEqualTo(":NO-RESTART");
	}

	@Test
	void compileAndRunComputeRestartsListsInnermostFirstAndRestartNameReads() throws Exception {
		assertThat(compileAndRun("""
				(print (restart-case
				           (restart-case (mapcar (function restart-name) (compute-restarts))
				             (aaa () nil)
				             (bbb () nil))
				         (ccc () nil)))
				""")).isEqualTo("(AAA BBB CCC)");
	}

	@Test
	void compileAndRunRestartCasePassesFiveArguments() throws Exception {
		// The postmodern roles.lisp shape: a restart taking 5 arguments.
		assertThat(compileAndRun("""
				(print (handler-bind ((error (lambda (c) (invoke-restart :five 1 2 3 4 5))))
				         (restart-case (error "x")
				           (:five (a b c d e) (list a b c d e)))))
				""")).isEqualTo("(1 2 3 4 5)");
	}

	@Test
	void compileAndRunNestedHandlerBindLayersInnerDeclinesOuterInvokes() throws Exception {
		// The prepare.lisp shape: nested handler-bind layers around one
		// restart-case; the inner cluster's handler declines (returns), the outer
		// cluster's handler invokes the restart.
		assertThat(compileAndRun("""
				(print (let ((log nil))
				         (handler-bind ((error (lambda (c) (invoke-restart :reconnect))))
				           (handler-bind ((error (lambda (c) (setq log (cons :inner-saw log)))))
				             (restart-case (error "conn lost")
				               (:reconnect () (cons :reconnected log)))))))
				""")).isEqualTo("(:RECONNECTED :INNER-SAW)");
	}

	@Test
	void compileAndRunHandlerSignalingInsideHandlerDoesNotSeeOwnCluster() throws Exception {
		assertThat(compileAndRun("""
				(print (handler-case
				           (handler-bind ((error (lambda (c) (error "inner"))))
				             (error "outer"))
				         (error (e) (simple-condition-format-control e))))
				""")).isEqualTo("\"inner\"");
	}

	@Test
	void compileAndRunRestartBindInvokesFunctionAtInvocationPoint() throws Exception {
		assertThat(compileAndRun("""
				(print (let ((hit nil))
				         (restart-bind ((poke (lambda (v) (setq hit v))))
				           (invoke-restart 'poke 9)
				           hit)))
				""")).isEqualTo("9");
	}

	@Test
	void compileAndRunWithSimpleRestartReturnsNilAndT() throws Exception {
		assertThat(compileAndRun("""
				(print (handler-bind ((error (lambda (c) (invoke-restart 'skip))))
				         (multiple-value-list (with-simple-restart (skip "Skip it") (error "x")))))
				""")).isEqualTo("(NIL T)");
	}

	@Test
	void compileAndRunCerrorEstablishesContinueRestart() throws Exception {
		assertThat(compileAndRun("""
				(print (handler-bind ((error (lambda (c) (continue))))
				         (list :after (cerror "Continue." "problem"))))
				""")).isEqualTo("(:AFTER NIL)");
	}

	@Test
	void compileAndRunSignalRunsHandlerBindHandlersAndReturnsNil() throws Exception {
		assertThat(compileAndRun("""
				(print (let ((log nil))
				         (handler-bind ((condition (lambda (c) (setq log :ran))))
				           (signal "s"))
				         log))
				""")).isEqualTo(":RAN");
	}

	@Test
	void compileAndRunMuffleWarningAbortsWarnOutput() throws Exception {
		assertThat(compileAndRun("""
				(print (handler-bind ((warning (lambda (w) (muffle-warning))))
				         (list :done (warn "noise"))))
				""")).isEqualTo("(:DONE NIL)");
	}

	@Test
	void compileAndRunHandlerCaseCatchesSignal() throws Exception {
		assertThat(compileAndRun(
				"(print (handler-case (progn (signal \"quiet\") :not-raised) (condition (c) :raised))) (print (signal \"quiet\"))"))
			.isEqualTo(":RAISED\nNIL");
	}

	@Test
	void compileAndRunHandlerCaseRunsUnwindProtectCleanupBeforeHandler() throws Exception {
		assertThat(compileAndRun("""
				(let ((log nil))
				  (print (handler-case
				             (unwind-protect (error "boom") (setq log (cons :cleaned log)))
				           (error (e) (cons :caught log)))))
				""")).isEqualTo("(:CAUGHT :CLEANED)");
	}

	@Test
	void compileAndRunHandlerCaseReturnExitsProtectedRegion() throws Exception {
		// A return inside the protected form exits the loop, not the handler; the
		// handler depth is restored through the UnwindScope cleanup channel, so a
		// later unhandled signal still falls through to nil.
		assertThat(compileAndRun("""
				(print (dolist (x '(1 2 3))
				         (handler-case (when (= x 2) (return :done)) (error (e) :err))))
				(print (signal "after"))
				""")).isEqualTo(":DONE\nNIL");
	}

	@Test
	void compileAndRunIgnoreErrors() throws Exception {
		assertThat(compileAndRun("(print (ignore-errors (error \"boom\"))) (print (ignore-errors (+ 1 2)))"))
			.isEqualTo("NIL\n3");
	}

	@Test
	void compileAndRunHandlerCaseInArgumentPosition() throws Exception {
		// Entering the handler clears the operand stack, so the values the enclosing
		// call had already evaluated must be spilled around the protected region.
		assertThat(compileAndRun("""
				(defun hc-arg-risky () (error "boom"))
				(print (list "result:" (handler-case (hc-arg-risky) (error (e) e "caught"))))
				""")).isEqualTo("(\"result:\" \"caught\")");
	}

	@Test
	void compileAndRunHandlerCaseAsFirstOfSeveralArguments() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-first-risky () (error "boom"))
				(print (list (handler-case (hc-first-risky) (error (e) "c")) "tail"))
				""")).isEqualTo("(\"c\" \"tail\")");
	}

	@Test
	void compileAndRunHandlerCaseInUserFunctionArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-fn-risky () (error "boom"))
				(defun hc-fn-take3 (a b c) (list a b c))
				(print (hc-fn-take3 1 (handler-case (hc-fn-risky) (error (e) 2)) 3))
				""")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunHandlerCaseInIntegerArithmeticArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-int-risky () (error "boom"))
				(print (+ 1 (handler-case (hc-int-risky) (error (e) 2))))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunHandlerCaseInFloatArithmeticArgument() throws Exception {
		// The double path keeps a raw (two-slot) double on the operand stack across the
		// argument, so the spill must be width-aware.
		assertThat(compileAndRun("""
				(defun hc-flt-risky () (error "boom"))
				(print (+ 1.5 (handler-case (hc-flt-risky) (error (e) 2.0))))
				(print (* 2.0 (handler-case (hc-flt-risky) (error (e) 3)) 10.0))
				""")).isEqualTo("3.5\n60.0");
	}

	@Test
	void compileAndRunHandlerCaseInConsArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-cons-risky () (error "boom"))
				(print (cons 1 (cons 2 (handler-case (hc-cons-risky) (error (e) 3)))))
				""")).isEqualTo("(1 2 . 3)");
	}

	@Test
	void compileAndRunHandlerCaseInSetfArefValue() throws Exception {
		// The array-store sequence keeps an int index on the operand stack.
		assertThat(compileAndRun("""
				(defun hc-aref-risky () (error "boom"))
				(let ((a (make-array 2)))
				  (setf (aref a 0) (handler-case (hc-aref-risky) (error (e) 9)))
				  (print (aref a 0)))
				""")).isEqualTo("9");
	}

	@Test
	void compileAndRunHandlerCaseInsideLambdaArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-lam-risky (x) (if (= x 2) (error "boom") x))
				(print (mapcar (lambda (x) (list x (handler-case (hc-lam-risky x) (error (e) :err))))
				               (list 1 2 3)))
				""")).isEqualTo("((1 1) (2 :ERR) (3 3))");
	}

	@Test
	void compileAndRunNestedHandlerCaseInArgumentPosition() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-nest-risky () (error "boom"))
				(print (list "x" (handler-case (list "y" (handler-case (hc-nest-risky) (error (e) "i")))
				                   (error (e) "o"))))
				""")).isEqualTo("(\"x\" (\"y\" \"i\"))");
	}

	@Test
	void compileAndRunHandlerCaseInIfBranchInArgumentPosition() throws Exception {
		assertThat(compileAndRun("""
				(defun hc-if-risky () (error "boom"))
				(print (list "r:" (if t (handler-case (hc-if-risky) (error (e) "c")) "n")))
				""")).isEqualTo("(\"r:\" \"c\")");
	}

	@Test
	void compileAndRunIgnoreErrorsInArgumentPosition() throws Exception {
		assertThat(compileAndRun("""
				(defun ie-arg-risky () (error "boom"))
				(print (list "r:" (ignore-errors (ie-arg-risky)) (ignore-errors 42)))
				""")).isEqualTo("(\"r:\" NIL 42)");
	}

	@Test
	void compileAndRunLongQuotedLiteralStaysWithinTheLocalSlots() throws Exception {
		// A quoted list costs locals by its nesting depth, not its length: one temp per
		// cons cell would walk past slot 255, which a one-byte load/store operand cannot
		// name.
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 400; i++) {
			items.append(i).append(' ');
		}
		String source = "(print (length '(%s))) (print (car '(%s))) (print (nth 399 '(%s)))".formatted(items, items,
				items);
		assertThat(compileAndRun(source)).isEqualTo("400\n0\n399");
	}

	@Test
	void compileAndRunHandlerCaseAsTheMessageOfAnError() throws Exception {
		// The throw shape allocates the exception before evaluating its message, so this
		// pins that the message is bound to a local first: an object under construction
		// cannot be spilled across the protected region (the compiler rejects it).
		assertThat(compileAndRun("""
				(defun hc-msg-risky () (error "boom"))
				(print (handler-case (error (handler-case (hc-msg-risky) (error (e) "recovered")))
				         (error (e) (simple-condition-format-control e))))
				""")).isEqualTo("\"recovered\"");
	}

	@Test
	void compileAndRunReturnOutOfHandlerCaseInArgumentPosition() throws Exception {
		// The return abandons the spilled operands of the enclosing list, and the block's
		// exit still has to be reached with the operand stack the block was entered with.
		assertThat(compileAndRun("""
				(defun hc-ret-risky (x) (if (= x 2) (error "boom") x))
				(print (list "outer"
				             (dolist (x (list 1 2 3))
				               (print (list "seen" (handler-case (hc-ret-risky x) (error (e) (return :caught))))))))
				""")).isEqualTo("(\"seen\" 1)\n(\"outer\" :CAUGHT)");
	}

	@Test
	void compileAndRunReturnInArgumentPosition() throws Exception {
		// A return abandons the operands the enclosing call had already evaluated, so
		// the block exit is reached with the same operand stack on every path.
		assertThat(compileAndRun("""
				(print (dolist (x (list 1 2 3))
				         (print (list "seen" x (if (= x 2) (return (list "early" x)) x)))))
				""")).isEqualTo("(\"seen\" 1 1)\n(\"early\" 2)");
	}

	@Test
	void compileAndRunHandlerCaseCatchesUsocketSocketError() throws Exception {
		// A connection failure is re-signaled as a typed usocket:socket-error, catchable
		// by type on the JVM backend (.kb/error-handling.md).
		assertThat(compileAndRunUsocket("""
				(let* ((l (usocket:socket-listen "127.0.0.1" 0))
				       (p (usocket:get-local-port l)))
				  (usocket:socket-close l)
				  (print (handler-case (usocket:socket-connect "127.0.0.1" p)
				           (usocket:socket-error (e) :refused))))
				""")).isEqualTo(":REFUSED");
	}

	@Test
	void compileAndRunUsocketHostToHostnameRendersEveryDesignatorShape() throws Exception {
		// The clack.handler:run address-normalization pair. Pure Lisp in the shim, so
		// the compiled answer must equal the interpreter's for every input shape.
		assertThat(compileAndRunUsocket("""
				(print (usocket:host-to-hostname nil))
				(print (usocket:host-to-hostname #(192 168 0 1)))
				(print (usocket:host-to-hostname 2130706433))
				(print (usocket:host-to-hostname (usocket:get-host-by-name "example.com")))
				""")).isEqualTo("\"0.0.0.0\"\n\"192.168.0.1\"\n\"127.0.0.1\"\n\"example.com\"");
	}

	@Test
	void compileAndRunPrintConditionBacktracePrintsTheCondition() throws Exception {
		// The prelude entry defines the uiop/image symbol; a program spelling either
		// package must select it, so neither one compiles to a call-time
		// undefined-function error.
		assertThat(compileAndRun("""
				(handler-case (error "boom")
				  (error (c) (uiop/image:print-condition-backtrace c :stream *standard-output*)))
				(handler-case (error "boom2")
				  (error (c) (uiop:print-condition-backtrace c :stream *standard-output*)))
				""")).isEqualTo("boom\nboom2");
	}

	@Test
	void compileAndRunTypedErrorKeepsLegacyUncaughtMessage() {
		assertThatThrownBy(() -> compileAndRun(
				"(define-condition my-cond-err (error) ((v :initarg :v))) (error 'my-cond-err :v 42)"))
			.hasRootCauseMessage("Condition (MY-COND-ERR :V 42) was signalled.");
	}

	@Test
	void compileAndRunTypedErrorReportStringMessage() {
		assertThatThrownBy(() -> compileAndRun(
				"(define-condition my-cond-rep (error) () (:report \"it broke\")) (error 'my-cond-rep)"))
			.hasRootCauseMessage("it broke");
	}

	@Test
	void compileAndRunTypedErrorReportLambdaMessage() {
		assertThatThrownBy(() -> compileAndRun("""
				(define-condition my-cond-lam (error) ((v :initarg :v :reader my-cond-lam-v))
				  (:report (lambda (c s) (format s "bad value ~a" (my-cond-lam-v c)))))
				(error 'my-cond-lam :v 42)
				""")).hasRootCauseMessage("bad value 42");
	}

	@Test
	void compileAndRunConditionReportRendersUnderPrincButNotUnderPrin1() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cr-lam (error) ((msg :initarg :msg :reader cr-msg))
				  (:report (lambda (c s) (format s "cr-lam: ~a" (cr-msg c)))))
				(define-condition cr-str (error) () (:report "fixed text"))
				(handler-case (error 'cr-lam :msg "boom")
				  (error (e) (print (list (format nil "~a" e) (format nil "~s" e)
				                          (princ-to-string (make-condition 'cr-str))))))
				""")).isEqualTo("(\"cr-lam: boom\" \"#<CR-LAM :MSG \\\"boom\\\">\" \"fixed text\")");
	}

	@Test
	void compileAndRunConditionReportIsInheritedBySubtypes() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cri-base (error) ((n :initarg :n :reader cri-n))
				  (:report (lambda (c s) (format s "base ~a" (cri-n c)))))
				(define-condition cri-sub (cri-base) ())
				(print (handler-case (error 'cri-sub :n 3) (error (e) (princ-to-string e))))
				""")).isEqualTo("\"base 3\"");
	}

	@Test
	void compileAndRunSimpleConditionFamilyReportsThroughFormatControl() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cr-pg (simple-warning) ())
				(print (list (princ-to-string
				               (make-condition 'cr-pg :format-control "pg ~A/~A" :format-arguments (list 1 2)))
				             (handler-case (error "plain ~a" 7) (error (e) (princ-to-string e)))))
				""")).isEqualTo("(\"pg 1/2\" \"plain 7\")");
	}

	@Test
	void compileAndRunRuntimeControlStringDatumRendersItsFormatArguments() throws Exception {
		assertThat(compileAndRun("""
				(print (list (handler-case (error "lit ~a-~a" 1 2) (error (e) (princ-to-string e)))
				             (let ((c "~a-~a"))
				               (handler-case (error c 1 2) (error (e) (princ-to-string e))))
				             (let ((c "PostgreSQL warning: ~A~@[~%~A~]"))
				               (handler-case (error c "relation already exists, skipping" nil)
				                 (error (e) (princ-to-string e))))
				             (let ((c "sig ~a/~a"))
				               (handler-case (signal c 3 4) (condition (e) (princ-to-string e))))))
				"""))
			.isEqualTo("(\"lit 1-2\" \"1-2\" \"PostgreSQL warning: relation already exists, skipping\" \"sig 3/4\")");
	}

	@Test
	void compileAndRunRuntimeControlStringDatumRendersItsFormatArgumentsUnderWarn() throws Exception {
		assertThat(compileAndRunCapturingErr("(let ((c \"rt ~a/~a\")) (warn c 1 2))")).isEqualTo("WARNING: rt 1/2");
	}

	@Test
	void compileAndRunWarnRendersTheFormatControlArguments() throws Exception {
		assertThat(compileAndRunCapturingErr(
				"(warn 'simple-warning :format-control \"sw ~A/~A\" :format-arguments (list 1 2))"))
			.isEqualTo("WARNING: sw 1/2");
	}

	@Test
	void compileAndRunPrintObjectMethodWinsOverTheConditionReport() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cr-po (error) () (:report "report text"))
				(defmethod print-object ((c cr-po) s) (format s "PO"))
				(print (list (princ-to-string (make-condition 'cr-po)) (prin1-to-string (make-condition 'cr-po))))
				""")).isEqualTo("(\"PO\" \"PO\")");
	}

	@Test
	void compileAndRunConditionWithNoReportKeepsTheInstanceRendering() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cr-bare (error) ((v :initarg :v)))
				(print (princ-to-string (make-condition 'cr-bare :v 1)))
				""")).isEqualTo("\"#<CR-BARE :V 1>\"");
	}

	@Test
	void compileAndRunSignalYieldsNilWhenUnhandled() throws Exception {
		assertThat(compileAndRun("(print (signal \"quiet\"))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunTypecaseOnConditionInstances() throws Exception {
		assertThat(compileAndRun("""
				(define-condition my-cond-tc (error) ())
				(print (typecase (make-condition 'my-cond-tc) (warning 'w) (error 'e) (t 'o)))
				""")).isEqualTo("E");
	}

	@Test
	void compileAndRunWithSlotsReadsInstanceSlots() throws Exception {
		assertThat(compileAndRun("""
				(define-condition my-cond-ws (error) ((a :initarg :a) (b :initarg :b)))
				(print (with-slots (a b) (make-condition 'my-cond-ws :a 1 :b 2) (list a b)))
				""")).isEqualTo("(1 2)");
	}

	@Test
	void compileAndRunUnwindProtectValueAndNormalCleanup() throws Exception {
		assertThat(compileAndRun("(let ((n 1)) (print (unwind-protect (+ n 1) (setq n 10))) (print n))"))
			.isEqualTo("2\n10");
	}

	@Test
	void compileAndRunUnwindProtectCleanupOnErrorUnwind() throws Exception {
		// The error propagates out of main after the cleanup ran, so the output is
		// captured before asserting the throw (compileAndRun would lose it).
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(LispReader.readAllFromString(
				"(defun up-thrower () (error \"boom\")) (unwind-protect (up-thrower) (print :cleaned))"));
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				assertThatThrownBy(() -> main.invoke(null, (Object) new String[0])).hasRootCauseMessage("boom");
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo(":CLEANED");
		}
	}

	@Test
	void compileAndRunUnwindProtectCleanupOnReturnExit() throws Exception {
		assertThat(compileAndRun("""
				(let ((log nil))
				  (dolist (x '(1 2 3))
				    (unwind-protect
				        (when (= x 2) (return))
				      (setq log (cons x log))))
				  (print log))
				""")).isEqualTo("(2 1)");
	}

	@Test
	void compileAndRunUnwindProtectCleanupOnReturnFromDefun() throws Exception {
		assertThat(compileAndRun("""
				(setq up-log nil)
				(defun up-f ()
				  (unwind-protect (return-from up-f :early) (setq up-log :cleaned)))
				(print (up-f))
				(print up-log)
				""")).isEqualTo(":EARLY\n:CLEANED");
	}

	@Test
	void compileAndRunUnwindProtectNestedCleanupsInnermostFirst() throws Exception {
		// The return escapes both scopes; the inlined cleanups run innermost first.
		assertThat(compileAndRun("""
				(setq log nil)
				(dolist (x '(1))
				  (unwind-protect
				      (unwind-protect (return) (setq log (cons :inner log)))
				    (setq log (cons :outer log))))
				(print log)
				""")).isEqualTo("(:OUTER :INNER)");
	}

	@Test
	void compileAndRunUnwindProtectReturnStaysInsideInnerLoopBlock() throws Exception {
		// The return targets the dotimes INSIDE the protected form, so it does not
		// escape the unwind-protect and the cleanup runs exactly once (normal exit).
		assertThat(compileAndRun("""
				(setq log nil)
				(unwind-protect
				    (dotimes (i 3) (when (= i 1) (return)))
				  (setq log (cons :cleaned log)))
				(print log)
				""")).isEqualTo("(:CLEANED)");
	}

	@Test
	void compileAndRunWasmExportIsNoOp() throws Exception {
		// rontolisp:wasm-export is a directive for the WASM backend; on the JVM it is a
		// no-op and
		// the
		// marked function still compiles and runs normally.
		assertThat(compileAndRun("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)" + "(print (fact 5))"))
			.isEqualTo("120");
	}

	@Test
	void compileAndRunWasmImportDirectiveIsNoOpUntilCalled() throws Exception {
		// The directive registers an error-signalling stub; a program that never calls
		// the import still compiles and runs.
		assertThat(compileAndRun(
				"(rontolisp:wasm-import 'add :from \"host\" :params '(:int :int) :returns :int)" + "(print (+ 1 2))"))
			.isEqualTo("3");
	}

	@Test
	void compileAndRunWasmImportStubSignalsErrorWhenCalled() {
		assertThatThrownBy(() -> compileAndRun(
				"(rontolisp:wasm-import 'add :from \"host\" :params '(:int :int) :returns :int)" + "(print (add 1 2))"))
			.hasRootCauseMessage(
					"ADD is a host function declared by rontolisp:wasm-import; it can only be called from a compiled WASM module");
	}

	@Test
	void compileAndRunAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 2))")).isEqualTo("3");
	}

	// print / prin1 / princ return their argument (CL semantics), so the value can be
	// used in a surrounding form -- not nil.
	@Test
	void compileAndRunPrintReturnsArgument() throws Exception {
		assertThat(compileAndRun("(print (print 11))")).isEqualTo("11\n11");
	}

	@Test
	void compileAndRunPrin1ReturnsArgument() throws Exception {
		assertThat(compileAndRun("(prin1 (prin1 11))")).isEqualTo("1111");
	}

	@Test
	void compileAndRunPrincReturnsArgument() throws Exception {
		assertThat(compileAndRun("(princ (princ 11))")).isEqualTo("1111");
	}

	@Test
	void compileAndRunPrintReturnValueThroughLet() throws Exception {
		assertThat(compileAndRun("(let ((x (print 11))) (let ((a x)) (print a)))")).isEqualTo("11\n11");
	}

	@Test
	void compileAndRunPrintToStringStreamReturnsArgument() throws Exception {
		assertThat(compileAndRun("(let ((v nil)) (with-output-to-string (s) (setq v (print 11 s))) (print v))"))
			.isEqualTo("11");
	}

	// defmacro is handled by the compile-path pass (eval.UserMacroExpander, run by the
	// CLI before this compiler), which consumes the definitions and fully expands every
	// call site -- the compiler itself never sees a macro form.
	@Test
	void compileAndRunUserMacroAfterExpansionPass() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(defmacro swap! (a b) `(let ((__tmp ,a)) (setq ,a ,b) (setq ,b __tmp)))
				(print (my-when2 (> 3 1) 10 20))
				(setq p 1)
				(setq q 2)
				(swap! p q)
				(print (list p q))
				"""));
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("20\n(2 1)");
		}
	}

	// A loop destructuring pattern with a dotted sublist is data, not a call form; the
	// expansion pass (active because a user defmacro exists) must leave it intact
	// (s-sql's `loop for ((x . y) . rest) on args`).
	@Test
	void compileAndRunLoopDottedPatternWithUserMacroPresent() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro my-id (x) x)
				(print (my-id (loop for ((x . y) . rest) on '((1 . 2) (3 . 4))
				                    append (list x y (length rest)))))
				"""));
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("(1 2 1 3 4 0)");
		}
	}

	// once-only exercises three levels of read-time backquote through the compile
	// path: the nested backquote is fully expanded by the reader, so the JVM
	// compiler only sees ordinary list/cons/quote calls.
	@Test
	void compileAndRunNestedBackquoteOnceOnly() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro once-only (names &body body)
				  (let ((gensyms (loop for name in names collect (gensym (symbol-name name)))))
				    `(let (,@(loop for g in gensyms
				                   for name in names
				                   collect `(,g (gensym ,(symbol-name name)))))
				       `(let (,,@(loop for g in gensyms for n in names
				                       collect ``(,,g ,,n)))
				          ,(let (,@(loop for n in names for g in gensyms
				                         collect `(,n ,g)))
				             ,@body)))))
				(defmacro square (x) (once-only (x) `(* ,x ,x)))
				(defvar *calls* 0)
				(defun bump () (setq *calls* (+ *calls* 1)) 5)
				(print (square (bump)))
				(print *calls*)
				"""));
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("25\n1");
		}
	}

	@Test
	void compileAndRunGensym() throws Exception {
		assertThat(compileAndRun("(print (gensym)) (print (gensym \"tmp\")) (print (eq (gensym) (gensym)))"
				+ "(print (symbolp (gensym))) (print (symbolp (funcall #'gensym)))"))
			.isEqualTo("#:g1\n#:tmp2\nNIL\nT\nT");
	}

	@Test
	void compileGensymAcceptsAComputedPrefix() throws Exception {
		// A computed prefix lowers to string construction over the literal-prefix
		// gensym, so the name keeps the "#:" shape and the fresh counter suffix
		// (alexandria's format-symbol passes one).
		assertThat(compileAndRun("(setq p \"tmp\") (print (gensym p)) (print (gensym p))")).isEqualTo("#:tmp1\n#:tmp2");
	}

	@Test
	void compileAndRunSymbolNameStripsThePackageMarker() throws Exception {
		assertThat(compileAndRun("(print (symbol-name 'foo)) (print (symbol-name :bar))"
				+ "(print (symbol-name (gensym))) (print (symbol-name nil))"
				+ "(print (funcall #'symbol-name 'xyz)) (print (mapcar #'symbol-name '(a b)))"))
			.isEqualTo("\"FOO\"\n\"BAR\"\n\"g1\"\n\"NIL\"\n\"XYZ\"\n(\"A\" \"B\")");
	}

	@Test
	void compileAndRunString() throws Exception {
		assertThat(compileAndRun("(print (string \"foo\")) (print (string 'foo)) (print (string :bar))"
				+ "(print (string #\\a)) (print (string t)) (print (string nil))"
				+ "(print (funcall #'string 'xyz)) (print (mapcar #'string '(a b)))"))
			.isEqualTo("\"foo\"\n\"FOO\"\n\"BAR\"\n\"a\"\n\"T\"\n\"NIL\"\n\"XYZ\"\n(\"A\" \"B\")");
	}

	@Test
	void compileAndRunIntern() throws Exception {
		assertThat(compileAndRun("(print (intern \"hello\")) (print (eq (intern \"FOO\") 'foo))"
				+ "(print (symbolp (intern \"hello\"))) (print (intern (symbol-name 'round-trip)))"
				+ "(print (funcall #'intern \"abc\"))"))
			.isEqualTo("hello\nT\nT\nROUND-TRIP\nabc");
	}

	@Test
	void compileAndRunInternIntoALiteralPackage() throws Exception {
		// 2-arg intern with a literal package designator lowers to the canonical-
		// spelling build the 2-arg find-symbol lowering already uses (todo-229): an
		// exported name gets the single-colon external spelling (eq to the
		// source-quoted symbol) and resolves through the function registry; an
		// internal name resolves through the registry's single-colon alias row.
		// cl/cl-user need no qualifier.
		assertThat(compileAndRun("""
				(defpackage :rt-pkg (:use :cl) (:export :ex-fn))
				(in-package :rt-pkg)
				(defun ex-fn (x) (+ x 1))
				(defun in-fn (x) (+ x 2))
				(in-package :cl-user)
				(print (eq (intern "EX-FN" :rt-pkg) 'rt-pkg:ex-fn))
				(print (funcall (intern "EX-FN" :rt-pkg) 1))
				(print (funcall (intern "IN-FN" :rt-pkg) 1))
				(print (intern "foo" :cl-user))
				""")).isEqualTo("T\n2\n3\nfoo");
	}

	@Test
	void compileAndRunInternIntoAComputedPackage() throws Exception {
		// clack's handler protocol is late-bound by name against a package VALUE:
		// (apply (intern (string :run) handler-package) ...) in handler.lisp, and
		// find-middleware's (symbol-value (intern (format ...) package)). The
		// computed designator is tested at run time like computedPackageFindSymbol.
		assertThat(compileAndRun("""
				(defpackage :rt-h (:use :cl) (:export :run :*mw*))
				(in-package :rt-h)
				(defun run (x) (concatenate 'string "run:" x))
				(defparameter *mw* "the-mw")
				(in-package :cl-user)
				(let ((pkg (find-package :rt-h)))
				  (print (apply (intern (string :run) pkg) (list "a")))
				  (print (symbol-value (intern (concatenate 'string "*" "MW*") pkg)))
				  (print (boundp (intern "*MW*" pkg))))
				(let ((kw (find-package :keyword)))
				  (print (intern "K2" kw)))
				""")).isEqualTo("\"run:a\"\n\"the-mw\"\nT\n:K2");
	}

	@Test
	void compileInternIntoAnUnknownPackageSignalsAtCallTime() {
		// find-symbol folds an unknown literal package to nil, but intern must
		// SIGNAL there (interpreter parity: PackageResolver.internSpellingIn).
		assertThatThrownBy(() -> compileAndRun("(print (intern \"X\" :rt-no-such-pkg))"))
			.hasRootCauseInstanceOf(RuntimeException.class)
			.rootCause()
			.hasMessageContaining("No such package: RT-NO-SUCH-PKG");
	}

	@Test
	void compileAndRunComputedSymbolFunctionResolvesLate() throws Exception {
		// the jzon :key-fn shape: (funcall (symbol-function sym) x) with a runtime
		// symbol. The computed designator lowers to the symbol itself, which the
		// dispatchers resolve through the _lookup registry (todo-229).
		assertThat(compileAndRun("(defun sf-f (x) (* x 2)) (setq s (intern \"SF-F\"))"
				+ "(print (funcall (symbol-function s) 21)) (print (funcall (fdefinition s) 5))"))
			.isEqualTo("42\n10");
	}

	@Test
	void compileAndRunUiopSymbolCall() throws Exception {
		// REAL on the compile paths since todo-229 (used to be a call-time error, see
		// .kb/asdf.md): lowers to (funcall (intern (string name) (find-package pkg))
		// args...), late-bound through the registry like the interpreter's
		// resolveFunction.
		assertThat(compileAndRun("(defpackage :usc-pkg (:use :cl) (:export :usc-fn))"
				+ "(in-package :usc-pkg) (defun usc-fn (a b) (+ a b)) (in-package :cl-user)"
				+ "(print (uiop:symbol-call :usc-pkg :usc-fn 40 2))" + "(print (uiop:symbol-call :cl :list 1 2))"))
			.isEqualTo("42\n(1 2)");
	}

	@Test
	void compileAndRunApplyOfAnUndefinedRuntimeSymbolSignals() {
		// _apply's symbol-designator miss used to return nil SILENTLY; it must fail
		// loudly like the funcall dispatcher (the tree-shaker carve-out contract:
		// a shaken-out or unknown name errors, never silent wrong output).
		assertThatThrownBy(() -> compileAndRun("(defun rt-a (x) x) (print (apply (intern \"RT-NOPE\") (list 1)))"))
			.hasRootCauseInstanceOf(RuntimeException.class)
			.rootCause()
			.hasMessageContaining("The function RT-NOPE is undefined");
	}

	@Test
	void compileAndRunMakeSymbol() throws Exception {
		assertThat(compileAndRun("(print (make-symbol \"temp\")) (print (symbolp (make-symbol \"temp\")))"
				+ "(print (eq (make-symbol \"foo\") 'foo)) (print (funcall #'make-symbol \"m\"))"))
			.isEqualTo("#:temp\nT\nNIL\n#:m");
	}

	@Test
	void compileAndRunFindSymbolFoldsLiterals() throws Exception {
		assertThat(compileAndRun("(print (find-symbol \"car\")) (print (find-symbol \"cond\"))"
				+ "(print (find-symbol \"no-such-name\")) (defun fs-fn (x) x) (print (find-symbol \"FS-FN\"))"))
			.isEqualTo("NIL\nNIL\nNIL\nFS-FN");
	}

	@Test
	void compileAndRunFindSymbolWithAComputedArgumentInterns() throws Exception {
		// A computed name cannot be folded, so it lowers to intern: a symbol IS its
		// canonical spelling here, and "already interned" is knowledge only an intern
		// table could hold (.kb/symbol-runtime-api.md).
		assertThat(compileAndRun(
				"(setq n \"CAR\") (print (find-symbol n))" + "(setq m \"NO-SUCH-NAME\") (print (find-symbol m))"))
			.isEqualTo("CAR\nNO-SUCH-NAME");
	}

	@Test
	void compileAndRunBoundp() throws Exception {
		assertThat(compileAndRun("(defvar *bp-var* 1) (print (boundp '*bp-var*)) (print (boundp '*bp-nope*))"
				+ "(print (boundp :kw)) (print (boundp T)) (print (boundp nil))"
				+ "(let ((lex 1)) (print (boundp 'lex)))"))
			.isEqualTo("T\nNIL\nT\nT\nT\nNIL");
	}

	@Test
	void compileAndRunSymbolValue() throws Exception {
		assertThat(compileAndRun("(defvar *sv-var* 42) (print (symbol-value '*sv-var*))"
				+ "(setq *sv-var2* 7) (print (symbol-value (intern \"*SV-VAR2*\")))"
				+ "(print (symbol-value :kw)) (print (symbol-value T)) (print (symbol-value nil))"))
			.isEqualTo("42\n7\n:KW\nT\nNIL");
	}

	@Test
	void compileAndRunSymbolValueThrowsOnUnbound() {
		assertThatThrownBy(() -> compileAndRun("(print (symbol-value 'nope))"))
			.hasRootCauseInstanceOf(RuntimeException.class)
			.rootCause()
			.hasMessageContaining("The variable NOPE is unbound");
	}

	@Test
	void compileAndRunFboundp() throws Exception {
		// literal arguments fold at compile time (macros/special forms included);
		// computed arguments probe the runtime _fenv/_lookup registries (functions only)
		assertThat(compileAndRun("(print (fboundp 'car)) (print (fboundp 'cond)) (print (fboundp 'defun))"
				+ "(print (fboundp 'cadr)) (print (fboundp 'no-such-fn))"
				+ "(defun fb-fn (x) x) (print (fboundp 'fb-fn))"
				+ "(print (fboundp (intern \"FB-FN\"))) (print (fboundp (intern \"car\")))"
				+ "(print (fboundp (intern \"nothing\")))"))
			.isEqualTo("T\nT\nT\nT\nNIL\nT\nT\nNIL\nNIL");
	}

	@Test
	void compileAndRunFmakunbound() throws Exception {
		// The tombstone in _fenv shadows the compiled-function registry, so a retired
		// name answers nil at a LITERAL fboundp too (that fold is emitted behind the
		// probe whenever the program calls fmakunbound); an unknown name is a no-op.
		assertThat(compileAndRun("(defun fmk-fn (x) x) (print (fboundp 'fmk-fn))"
				+ "(print (fmakunbound 'fmk-fn)) (print (fboundp 'fmk-fn))"
				+ "(print (fboundp (intern \"FMK-FN\"))) (print (fmakunbound 'fmk-never-defined))"))
			.isEqualTo("T\nFMK-FN\nNIL\nNIL\nFMK-NEVER-DEFINED");
	}

	@Test
	void compileAndRunFindSymbolAnswersNilForAPackageThatDoesNotExist() throws Exception {
		assertThat(compileAndRun("(print (find-package :simple-date))"
				+ "(defun probe (p) (find-package p)) (print (probe :simple-date)) (print (probe nil))"
				+ "(print (probe :cl)) (print (probe :keyword))"))
			.isEqualTo("NIL\nNIL\nNIL\n:CL\n:KEYWORD");
	}

	@Test
	void compileAndRunFindPackageWithAComputedDesignator() throws Exception {
		// A literal designator folds in PackageResolver; a computed one is answered from
		// the package table the backend bakes in from the resolver's final registry.
		assertThat(
				compileAndRun("(defpackage :mypkg (:use :cl) (:nicknames :mp))" + "(defun probe (p) (find-package p))"
						+ "(print (probe :mypkg)) (print (probe \"MP\")) (print (probe \"mypkg\"))"))
			.isEqualTo(":MYPKG\n:MYPKG\nNIL");
	}

	@Test
	void compileAndRunFindSymbolWithAComputedPackageDesignator() throws Exception {
		// (find-symbol name pkg) with pkg in a variable: keyword/cl/cl-user need no
		// qualifier, anything else gets the external "PKG:" spelling.
		assertThat(compileAndRun("(defun fs (n p) (find-symbol n p))"
				+ "(print (fs \"FOO\" :keyword)) (print (fs \"CAR\" :cl)) (print (fs \"BAR\" nil))"))
			.isEqualTo(":FOO\nCAR\nNIL");
	}

	// macroexpand/macroexpand-1 with a literal quoted argument are folded to the
	// expansion by the compile-path pass (eval.UserMacroExpander); the compiler itself
	// never sees them.
	@Test
	void compileAndRunMacroexpandAfterExpansionPass() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(print (macroexpand-1 '(my-when2 (> 2 1) 'a 'b)))
				(print (macroexpand-1 '(unless c x)))
				(print (macroexpand-1 '(+ 1 2)))
				"""));
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim())
				.isEqualTo("(IF (> 2 1) (PROGN (QUOTE A) (QUOTE B)) NIL)\n(IF C NIL X)\n(+ 1 2)");
		}
	}

	// A non-ASCII string or symbol becomes a CONSTANT_Utf8 entry, which must be
	// written as modified UTF-8 with a BYTE length -- a char-count length makes the
	// whole class fail to load with "Illegal UTF8 string in constant pool".
	@Test
	void compileAndRunNonAsciiConstants() throws Exception {
		assertThat(compileAndRun("(princ \"✸⚑✗\")")).isEqualTo("✸⚑✗");
		assertThat(compileAndRun("(princ \"日本語\")")).isEqualTo("日本語");
		assertThat(compileAndRun("(print '日本語)")).isEqualTo("日本語");
		// A supplementary character encodes as a CESU-8 surrogate pair in the pool.
		assertThat(compileAndRun("(princ \"💣\")")).isEqualTo("💣");
	}

	@Test
	void compileAndRunDefvarDefinesGlobal() throws Exception {
		assertThat(compileAndRun("(defvar *x* 42) (print *x*)")).isEqualTo("42");
	}

	@Test
	void compileAndRunDefvarReturnsName() throws Exception {
		assertThat(compileAndRun("(print (defvar *x* 42))")).isEqualTo("*X*");
	}

	@Test
	void compileAndRunDefvarIsIdempotent() throws Exception {
		assertThat(compileAndRun("(defvar *x* 1) (defvar *x* 2) (print *x*)")).isEqualTo("1");
	}

	@Test
	void compileAndRunDefparameterAlwaysAssigns() throws Exception {
		assertThat(compileAndRun("(defparameter *x* 1) (defparameter *x* 2) (print *x*)")).isEqualTo("2");
	}

	@Test
	void compileAndRunDefconstant() throws Exception {
		assertThat(compileAndRun("(defconstant +k+ 7) (print +k+)")).isEqualTo("7");
	}

	@Test
	void compileAndRunGlobalReadInsideFunction() throws Exception {
		// A defparameter global referenced inside a defun body must resolve (previously
		// failed to compile: "Cannot compile symbol reference: *k*").
		assertThat(compileAndRun("(defparameter *k* 3) (defun f (x) (* x *k*)) (print (f 5))")).isEqualTo("15");
	}

	@Test
	void compileAndRunGlobalAssignInsideFunctionVisibleAtTopLevel() throws Exception {
		// setq of a global inside a function mutates the shared backing store, so a
		// top-level read sees the update.
		assertThat(compileAndRun(
				"(defvar *acc* 0) (defun bump () (setq *acc* (+ *acc* 1))) (bump) (bump) (bump) (print *acc*)"))
			.isEqualTo("3");
	}

	@Test
	void compileAndRunLargeTopLevelBodySplitsAcrossMethods() throws Exception {
		// A top-level body that exceeds the JVM 64 KB per-method bytecode limit must be
		// split across several helper methods, called in order from main(), without
		// losing cross-form shared state. *n* is a top-level global (a static field), so
		// the running total survives the method boundaries.
		StringBuilder sb = new StringBuilder("(setq *n* 0)\n");
		int forms = 6000;
		for (int i = 0; i < forms; i++) {
			sb.append("(setq *n* (+ *n* 1))\n");
		}
		sb.append("(print *n*)\n");
		assertThat(compileAndRun(sb.toString())).isEqualTo(String.valueOf(forms));
	}

	@Test
	void compileAndRunNestedFreeSetqSurvivesTopLevelSplit() throws Exception {
		// A variable assigned by a setq nested inside a top-level form (never a direct
		// top-level setq) is, per Common Lisp, a global. It must get a persistent backing
		// store so a value set in one chunk is visible from a later chunk, even when the
		// body is split. The padding forms force more than 64 KB of bytecode so the two
		// references to *m* land in different methods.
		StringBuilder sb = new StringBuilder("(progn (setq *m* 7) nil)\n(setq *pad* 0)\n");
		for (int i = 0; i < 6000; i++) {
			sb.append("(setq *pad* (+ *pad* 1))\n");
		}
		sb.append("(print *m*)\n");
		assertThat(compileAndRun(sb.toString())).isEqualTo("7");
	}

	@Test
	void compileAndRunTopLevelClosureOverLetStillCaptures() throws Exception {
		// Promoting top-level free setq targets to globals must not promote a let-bound
		// variable that a lambda closes over: *make* is a global, but n stays a captured
		// lexical, so the counter increments correctly.
		assertThat(compileAndRun(
				"(setq *make* (let ((n 0)) (lambda () (setq n (+ n 1)) n))) (funcall *make*) (funcall *make*) (print (funcall *make*))"))
			.isEqualTo("3");
	}

	@Test
	void compileAndRunGlobalReadInsideLambda() throws Exception {
		// A global referenced from a lambda nested in a defun (it must be resolved from
		// its static field, not captured as a free variable).
		assertThat(compileAndRun(
				"(defparameter *base* 10) (defun adders (xs) (mapcar (lambda (x) (+ x *base*)) xs)) (print (adders '(1 2 3)))"))
			.isEqualTo("(11 12 13)");
	}

	@Test
	void compileAndRunMapcarMultipleLists() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'+ '(1 2 3 4) '(10 20 30 40)))")).isEqualTo("(11 22 33 44)");
	}

	@Test
	void compileAndRunMapcarMultipleListsStopsAtShortest() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cons '(1 2 3) '(a b)))")).isEqualTo("((1 . A) (2 . B))");
	}

	// The four primitives .todo/219 widened, each one a CL conformance gap that failed
	// identically on all four backends (so none was a backend divergence). They are
	// grouped because one library -- alexandria -- is the first caller of all four.
	@Test
	void compileAndRunLastWithACount() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3) 2))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (last '(1 2 3) 0))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (last '(1 2 3) 5))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (last '(1 2 . 3) 1))")).isEqualTo("(2 . 3)");
		assertThat(compileAndRun("(print (last '(1 2 3)))")).isEqualTo("(3)");
		// As a value the count is optional, so the wrapper dispatches on its presence.
		assertThat(compileAndRun("(print (funcall #'last '(1 2 3) 2))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (funcall #'last '(1 2 3)))")).isEqualTo("(3)");
	}

	@Test
	void compileAndRunEverySomeOverMultipleSequences() throws Exception {
		assertThat(compileAndRun("(print (every #'< '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (every #'< '(1 2) '(3 0)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (every #'< '(1 2 3) '(9 9)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (every #'char= \"abc\" \"abd\"))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (some #'> '(1 5) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (notany #'> '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (notevery #'< '(1 2) '(3 0)))")).isEqualTo("T");
		// The value path forwards the extra sequences too.
		assertThat(compileAndRun("(print (funcall #'every #'< '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (funcall #'some #'> '(1 5) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (funcall #'every #'evenp '(2 4)))")).isEqualTo("T");
	}

	@Test
	void compileAndRunCoerceWithAComputedResultType() throws Exception {
		String cs = "(defun cs (type seq) (coerce seq type))\n";
		assertThat(compileAndRun(cs + "(print (cs 'list (vector 1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(cs + "(print (cs 'vector '(1 2)))")).isEqualTo("#(1 2)");
		assertThat(compileAndRun(cs + "(print (cs 'string '(#\\a #\\b)))")).isEqualTo("\"ab\"");
		assertThat(compileAndRun(cs + "(print (cs '(vector t) '(1 2)))")).isEqualTo("#(1 2)");
		assertThat(compileAndRun(cs + "(print (cs t '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(cs + "(print (cs 'double-float 3))")).isEqualTo("3.0");
	}

	@Test
	void compileAndRunReadSequenceIntoACharacterBuffer() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "abcdef")
				  (let ((buf (make-array 4 :element-type 'character)))
				    (print (list (read-sequence buf s) buf))))
				""")).isEqualTo("(4 \"abcd\")");
		// The element type may be COMPUTED, which is how alexandria allocates the buffer
		// -- make-array then picks the character-vector representation at run time.
		assertThat(compileAndRun("""
				(with-input-from-string (s "xyz")
				  (let ((buf (make-array 3 :element-type (stream-element-type s))))
				    (print (list (read-sequence buf s) buf))))
				""")).isEqualTo("(3 \"xyz\")");
	}

	// The REST of the family over more than one list. Until .todo/218 only mapcar walked
	// N lists here: mapc/mapcan/maplist/mapcon/mapl compiled the first two arguments and
	// dropped the rest, so the answer was a silently wrong list (the interpreter
	// signalled
	// instead -- three answers for one form).
	@Test
	void compileAndRunMapFamilyMultipleLists() throws Exception {
		assertThat(compileAndRun("(print (mapcan #'list '(1 2) '(3 4)))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (maplist #'list '(1 2) '(3 4)))")).isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		assertThat(compileAndRun("(print (mapcon #'list '(1 2) '(3 4)))")).isEqualTo("((1 2) (3 4) (2) (4))");
		// mapc/mapl run for effect and answer the FIRST list.
		assertThat(compileAndRun("(print (mapc (lambda (a b) (print (list a b))) '(1 2) '(3 4)))"))
			.isEqualTo("(1 3)\n(2 4)\n(1 2)");
		assertThat(compileAndRun("(print (mapl (lambda (a b) (print (list a b))) '(1 2) '(3 4)))"))
			.isEqualTo("((1 2) (3 4))\n((2) (4))\n(1 2)");
		// The walk stops at the shortest list, and three lists work like two.
		assertThat(compileAndRun("(print (mapcan #'list '(1 2 3) '(3 4)))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (mapcan #'list '(1 2) '(3 4) '(5 6)))")).isEqualTo("(1 3 5 2 4 6)");
		assertThat(compileAndRun("(print (mapc #'list nil '(1 2)))")).isEqualTo("NIL");
	}

	// #'mapcar AS A VALUE over more than one list: the list count is a runtime property
	// there, so the wrapper walks the list-of-lists itself (BuiltinFunctionWrappers.
	// mapFamilyWrapper). It used to drop every list but the first and answer ((1) (2)) --
	// silently, and only on the compile backends. alexandria:mappend is (apply #'mapcar
	// function lists), so this is the shape a real library takes.
	@Test
	void compileAndRunMapcarAsValueOverMultipleLists() throws Exception {
		assertThat(compileAndRun("(print (apply #'mapcar #'list '((1 2) (3 4))))")).isEqualTo("((1 3) (2 4))");
		assertThat(compileAndRun("(print (apply #'mapcar #'+ '((1 2) (10 20) (100 200))))")).isEqualTo("(111 222)");
		assertThat(compileAndRun("(print (apply #'mapcar #'list '((1 2 3) (3 4))))")).isEqualTo("((1 3) (2 4))");
		assertThat(compileAndRun("(print (funcall #'mapcar #'1+ '(1 2 3)))")).isEqualTo("(2 3 4)");
	}

	// The same wrapper shape for the rest of the family, so no member of it answers a
	// one-list result for a list-of-lists (.todo/218).
	@Test
	void compileAndRunMapFamilyAsValuesOverMultipleLists() throws Exception {
		assertThat(compileAndRun("(print (apply #'mapcan #'list '((1 2) (3 4))))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (apply #'maplist #'list '((1 2) (3 4))))"))
			.isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		assertThat(compileAndRun("(print (apply #'mapcon #'list '((1 2) (3 4))))")).isEqualTo("((1 2) (3 4) (2) (4))");
		assertThat(compileAndRun("(print (apply #'mapc #'list '((1 2) (3 4))))")).isEqualTo("(1 2)");
		assertThat(compileAndRun("(print (apply #'mapl #'list '((1 2) (3 4))))")).isEqualTo("(1 2)");
		// One list still answers what the call-position form answers.
		assertThat(compileAndRun("(print (funcall #'maplist #'identity '(1 2 3)))")).isEqualTo("((1 2 3) (2 3) (3))");
		assertThat(compileAndRun("(print (funcall #'mapcan #'list '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'mapc #'1+ '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun("(print (funcall #'mapl #'car '(1 2)))")).isEqualTo("(1 2)");
	}

	@Test
	void compileAndRunMapIntoList() throws Exception {
		assertThat(compileAndRun("(print (map-into (list 0 0 0 0) #'+ '(1 2 3) '(10 20 30 40)))"))
			.isEqualTo("(11 22 33 0)");
	}

	@Test
	void compileAndRunMapIntoVectorAndSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (map-into (make-array 3) #'* #(2 3 4) #(5 6 7)))")).isEqualTo("#(10 18 28)");
		assertThat(compileAndRun("(print (map-into (list nil nil nil) '1+ '(7 8 9)))")).isEqualTo("(8 9 10)");
	}

	@Test
	void compileAndRunMapIntoMixedOperandsAndLargeList() throws Exception {
		// Runtime list-or-vector dispatch per operand (result and sources independent).
		assertThat(compileAndRun("(print (map-into (list 0 0 0) #'+ #(1 2 3) '(10 20 30)))")).isEqualTo("(11 22 33)");
		assertThat(compileAndRun("(print (map-into (make-array 3) #'+ '(1 2 3) #(10 20 30)))"))
			.isEqualTo("#(11 22 33)");
		// Regression guard: with all-list operands map-into must stay O(n) rather than
		// re-walk each list from the head per iteration (20000 elements is instant when
		// linear, an O(n^2) hang otherwise).
		assertThat(compileAndRun("(print (length (map-into (make-list 20000) (lambda (x) 1) (make-list 20000))))"))
			.isEqualTo("20000");
	}

	@Test
	void compileAndRunRankThreeArrayRefSetAndPrint() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 0 0 0) 1)
				(setf (aref *t* 0 1 1) 4)
				(setf (aref *t* 1 0 1) 6)
				(print (list (aref *t* 0 0 0) (aref *t* 0 1 1) (aref *t* 1 0 1) (aref *t* 1 1 0)))
				(print *t*)
				""")).isEqualTo("(1 4 6 0)\n#3A(((1 0) (0 4)) ((0 6) (0 0)))");
	}

	@Test
	void compileAndRunRankNArrayDimensionsAndIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *t4* (make-array (list 2 3 4 5) :initial-element 0))
				(print (array-dimensions *t4*))
				(print (array-rank *t4*))
				(print (array-dimension *t4* 2))
				(print (array-total-size *t4*))
				""")).isEqualTo("(2 3 4 5)\n4\n4\n120");
	}

	@Test
	void compileAndRunRowMajorArefReadsAndWritesFlat() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(setf (row-major-aref *m* 4) 9)
				(print (list (row-major-aref *m* 4) (aref *m* 1 1) (array-row-major-index *m* 1 1)))
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 1 0 1) 7)
				(print (list (row-major-aref *t* 5) (array-row-major-index *t* 1 0 1)
				             (row-major-aref #(10 20 30) 2)))
				""")).isEqualTo("(9 9 4)\n(7 5 30)");
	}

	@Test
	void compileAndRunClosureReadsLetVarShadowingGlobal() throws Exception {
		// A lambda capturing a let variable must read the captured binding, not a
		// same-named top-level global.
		assertThat(compileAndRun("(setq c 777) (print (let ((c 5)) (funcall (lambda () c))))")).isEqualTo("5");
	}

	@Test
	void compileAndRunClosureWritesLetVarShadowingGlobal() throws Exception {
		// A setq inside the lambda must write the captured cell, and the global must
		// stay untouched.
		assertThat(
				compileAndRun("(setq d 666) (print (let ((d 5)) (funcall (lambda () (setq d (+ d 1)) d)))) (print d)"))
			.isEqualTo("6\n666");
	}

	@Test
	void compileAndRunClosureCapturesParamShadowingGlobal() throws Exception {
		// A lambda capturing an enclosing defun parameter must not resolve it to a
		// same-named global.
		assertThat(compileAndRun("(setq e2 555) (defun g (e2) (funcall (lambda () e2))) (print (g 42))"))
			.isEqualTo("42");
	}

	@Test
	void compileAndRunTime() throws Exception {
		// time prints the elapsed real time and returns the form's value (here printed).
		String output = compileAndRun("(print (time (+ 1 2)))");
		assertThat(output).contains("; Elapsed real time: ").contains(" ms").endsWith("3");
	}

	@Test
	void compileAndRunDoStar() throws Exception {
		assertThat(compileAndRun("(print (do* ((i 1 (+ i 1)) (acc i (* acc i))) ((> i 5) acc)))")).isEqualTo("720");
	}

	@Test
	void compileAndRunLoopNumericCollect() throws Exception {
		assertThat(compileAndRun("(print (loop for i from 1 to 5 collect i))")).isEqualTo("(1 2 3 4 5)");
		assertThat(compileAndRun("(print (loop for i below 5 collect i))")).isEqualTo("(0 1 2 3 4)");
		assertThat(compileAndRun("(print (loop for i from 10 downto 7 collect i))")).isEqualTo("(10 9 8 7)");
	}

	@Test
	void compileAndRunLoopListAndIndex() throws Exception {
		assertThat(compileAndRun("(print (loop for x in '(a b c) for i from 0 collect (list i x)))"))
			.isEqualTo("((0 A) (1 B) (2 C))");
		assertThat(compileAndRun("(print (loop for x on '(1 2 3) collect x))")).isEqualTo("((1 2 3) (2 3) (3))");
		assertThat(compileAndRun("(print (loop for c across \"hello\" collect c))"))
			.isEqualTo("(#\\h #\\e #\\l #\\l #\\o)");
		assertThat(compileAndRun("(print (loop for c across \"hello\" count (eql c #\\l)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunLoopBeingSymbols() throws Exception {
		// Lite `being` package iteration: parses and iterates the empty sequence.
		assertThat(compileAndRun("(print (loop for s being the external-symbols of :cl collect s))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (loop for s being each present-symbols in :cl-user count s))")).isEqualTo("0");
	}

	@Test
	void compileAndRunLoopAccumulators() throws Exception {
		assertThat(compileAndRun("(print (loop for i from 1 to 5 sum i))")).isEqualTo("15");
		assertThat(compileAndRun("(print (loop for i from 1 to 10 count (evenp i)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (loop for i in '(3 1 4 1 5) maximize i))")).isEqualTo("5");
		assertThat(compileAndRun("(print (loop for i from 1 to 3 append (list i i)))")).isEqualTo("(1 1 2 2 3 3)");
	}

	@Test
	void compileAndRunLoopControl() throws Exception {
		assertThat(compileAndRun("(print (loop repeat 3 collect 'x))")).isEqualTo("(X X X)");
		assertThat(compileAndRun("(print (loop for i from 1 to 10 when (evenp i) collect i))"))
			.isEqualTo("(2 4 6 8 10)");
		assertThat(compileAndRun("(print (loop for i from 1 do (when (> i 3) (return i))))")).isEqualTo("4");
		assertThat(compileAndRun("(print (loop with a = 10 for i from 1 to 3 collect (+ a i)))"))
			.isEqualTo("(11 12 13)");
	}

	@Test
	void compileAndRunLoopPositionalWhileUntil() throws Exception {
		assertThat(compileAndRun("(print (loop for x in '(1 2 3 9 4) while (< x 4) collect x))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (let ((n 0)) (loop for i from 1 do (setq n (+ n 1)) while (< i 3)) n))"))
			.isEqualTo("3");
		assertThat(compileAndRun("(print (loop for i from 1 do nil until (> i 2) collect i))")).isEqualTo("(1 2)");
	}

	@Test
	void compileAndRunLoopThereisAlwaysNever() throws Exception {
		assertThat(compileAndRun("(print (loop for x in '(nil nil 7 9) thereis x))")).isEqualTo("7");
		assertThat(compileAndRun("(print (loop for x in '(1 2 3) always (< x 5)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (loop for x in '(1 2 9) always (< x 5)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (loop for x in '(1 2 3) never (> x 5)))")).isEqualTo("T");
	}

	@Test
	void compileAndRunLoopAnaphoricItAndLoopFinish() throws Exception {
		assertThat(compileAndRun("(print (loop for x in '(1 nil 3 nil 5) when x collect it))")).isEqualTo("(1 3 5)");
		assertThat(compileAndRun("(print (loop for x in '(nil 2 nil) when x return it))")).isEqualTo("2");
		assertThat(compileAndRun("(print (loop for i from 1 do (when (> i 3) (loop-finish)) collect i))"))
			.isEqualTo("(1 2 3)");
		assertThat(compileAndRun(
				"(print (loop for i from 1 collect i into xs do (when (>= i 3) (loop-finish)) finally (return (length xs))))"))
			.isEqualTo("3");
	}

	@Test
	void compileAndRunLoopAcrossVectorAndMultiPairSetf() throws Exception {
		assertThat(compileAndRun("(print (loop for x across #(1 2 3 4 5) collect (* x x)))"))
			.isEqualTo("(1 4 9 16 25)");
		assertThat(compileAndRun("(print (let ((l (list 1 2 3))) (setf (car l) 9 (second l) 8) l))"))
			.isEqualTo("(9 8 3)");
	}

	@Test
	void compileAndRunLoopSequentialDriverStepping() throws Exception {
		assertThat(compileAndRun("(print (loop for x in '(1 2 3 4 5) for a = x then (+ a x) finally (return a)))"))
			.isEqualTo("15");
	}

	@Test
	void compileAndRunLoopParallelAndDestructuring() throws Exception {
		assertThat(compileAndRun("(print (loop for a = 0 then b and b = 1 then (+ a b) repeat 8 collect b))"))
			.isEqualTo("(1 1 2 3 5 8 13 21)");
		assertThat(compileAndRun("(print (let ((x 5)) (loop with a = x and x = 10 repeat 1 collect (list a x))))"))
			.isEqualTo("((5 10))");
		assertThat(compileAndRun("(print (loop for (a b) in '((1 2) (3 4) (5 6)) collect (+ a b)))"))
			.isEqualTo("(3 7 11)");
		assertThat(compileAndRun("(print (loop for (a b) = '(1 2) then (list b (+ a b)) repeat 5 collect a))"))
			.isEqualTo("(1 2 3 5 8)");
		assertThat(compileAndRun("(print (loop with (x y) = '(10 20) repeat 1 collect (+ x y)))")).isEqualTo("(30)");
	}

	@Test
	void compileAndRunDelete() throws Exception {
		assertThat(compileAndRun("(print (delete 2 '(1 2 3 2 1)))")).isEqualTo("(1 3 1)");
		assertThat(compileAndRun("(print (delete-if #'evenp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
		assertThat(compileAndRun("(print (delete-if-not #'oddp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunSubstitute() throws Exception {
		assertThat(compileAndRun("(print (substitute 0 2 '(1 2 3 2 1)))")).isEqualTo("(1 0 3 0 1)");
		assertThat(compileAndRun("(print (nsubstitute 9 1 '(1 2 1 3)))")).isEqualTo("(9 2 9 3)");
	}

	@Test
	void compileAndRunDestructiveListOps() throws Exception {
		// The destructive ops reuse cons cells; an alias to the original list observes
		// the
		// mutation (Common Lisp semantics).
		assertThat(compileAndRun("(setq a (list 1 2 3)) (setq b a) (nreverse a) (print b)")).isEqualTo("(1)");
		assertThat(compileAndRun("(setq a (list 1 2 3 2 1)) (setq b a) (delete 2 a) (print b)")).isEqualTo("(1 3 1)");
		assertThat(compileAndRun("(setq a (list 1 2 3 4 5)) (setq b a) (delete-if #'evenp a) (print b)"))
			.isEqualTo("(1 3 5)");
		assertThat(compileAndRun("(setq a (list 1 2 1 3)) (setq b a) (nsubstitute 9 1 a) (print b)"))
			.isEqualTo("(9 2 9 3)");
	}

	@Test
	void compileAndRunSubstituteAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'substitute 0 2 '(2 2 2)))")).isEqualTo("(0 0 0)");
	}

	@Test
	void compileAndRunSubstituteIf() throws Exception {
		assertThat(compileAndRun("(print (substitute-if 0 #'oddp '(1 2 3 4 5)))")).isEqualTo("(0 2 0 4 0)");
		assertThat(compileAndRun("(print (substitute-if-not 0 #'oddp '(1 2 3 4 5)))")).isEqualTo("(1 0 3 0 5)");
		assertThat(compileAndRun("(print (substitute-if 0 #'oddp '((1) (2) (3)) :key #'car))")).isEqualTo("(0 (2) 0)");
		assertThat(compileAndRun(
				"(print (substitute-if #\\- (lambda (c) (member c '(#\\. #\\/) :test 'char=)) \"lack/mw.backtrace\"))"))
			.isEqualTo("\"lack-mw-backtrace\"");
		assertThat(compileAndRun("(print (nsubstitute-if 0 #'oddp (list 1 2 3)))")).isEqualTo("(0 2 0)");
		assertThat(compileAndRun("(print (nsubstitute-if-not 0 #'oddp (list 1 2 3)))")).isEqualTo("(1 0 3)");
		assertThat(compileAndRun("(print (funcall #'substitute-if 0 #'oddp '(1 2 3)))")).isEqualTo("(0 2 0)");
	}

	@Test
	void compileAndRunSleep() throws Exception {
		assertThat(compileAndRun("""
				(setq s (get-internal-real-time))
				(sleep 0.05)
				(print (if (>= (- (get-internal-real-time) s) 40) "slept" "too-fast"))
				(print (sleep 0))
				""")).isEqualTo("\"slept\"\nNIL");
	}

	@Test
	void compileAndRunFileWriteDateAndFileLength() throws Exception {
		String file = tempDir.resolve("meta.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output) (write-line "hello" out))
				(print (integerp (file-write-date "%s")))
				(print (file-write-date "%s.missing"))
				(with-open-file (in "%s") (print (file-length in)))
				(setq h (open "%s"))
				(close h)
				(print (file-length h))
				(print (file-length "not-a-stream"))
				""".formatted(file, file, file, file, file))).isEqualTo("T\nNIL\n6\nNIL\nNIL");
	}

	@Test
	void compileAndRunEnsureDirectoriesExist() throws Exception {
		String base = tempDir.toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(print (ensure-directories-exist "%s/a/b/app.log"))
				(print (if (uiop:directory-exists-p "%s/a/b/") "made" "missing"))
				(print (probe-file "%s/a/b/app.log"))
				""".formatted(base, base, base))).isEqualTo("\"" + base + "/a/b/app.log\"\n\"made\"\nNIL");
	}

	@Test
	void compileAndRunExportAndUnexport() throws Exception {
		// A literal top-level export is consumed by the PackageResolver, which is what
		// makes it work here at all: the compiled output has no package registry.
		assertThat(compileAndRun("""
				(defpackage :expkg (:use :cl))
				(in-package :expkg)
				(export '(run))
				(defun run () 42)
				(in-package :cl-user)
				(print (expkg:run))
				(defpackage :unexpkg (:use :cl) (:export #:a #:b))
				(in-package :unexpkg)
				(unexport 'b)
				(defun a () 1)
				(defun b () 2)
				(in-package :cl-user)
				(print (+ (unexpkg:a) (unexpkg::b)))
				""")).isEqualTo("42\n3");
	}

	@Test
	void compileAndRunLoadContextSpecialsAreLetBindable() throws Exception {
		// clack's %load-file binds all four; on the compile paths they are declared nil
		// and stay nil (nothing is being loaded at run time).
		assertThat(compileAndRun("""
				(print (let ((*package* *package*)
				             (*readtable* *readtable*)
				             (*load-pathname* "p")
				             (*load-truename* "t"))
				         (list *load-pathname* *load-truename* *readtable*)))
				""")).isEqualTo("(\"p\" \"t\" NIL)");
	}

	@Test
	void withOpenFileWriteThenRead() throws Exception {
		String file = tempDir.resolve("wof.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output)
				  (write-line "hello" out)
				  (write-line "world" out))
				(with-open-file (in "%s")
				  (print (read-line in))
				  (print (read-line in))
				  (print (read-line in)))
				""".formatted(file, file))).isEqualTo("\"hello\"\n\"world\"\nNIL");
	}

	@Test
	void probeFileAnswersThePathOrNil() throws Exception {
		String file = tempDir.resolve("probe.txt").toString().replace("\\", "\\\\");
		String missing = tempDir.resolve("absent.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output) (write-line "x" out))
				(print (probe-file "%s"))
				(print (probe-file "%s"))
				(print (if (probe-file "%s") 'yes 'no))
				""".formatted(file, file, missing, missing)))
			.isEqualTo("\"" + tempDir.resolve("probe.txt") + "\"\nNIL\nNO");
	}

	@Test
	void probeFileAsFunctionValueAndViaUiop() throws Exception {
		String file = tempDir.resolve("fc.txt").toString().replace("\\", "\\\\");
		String missing = tempDir.resolve("fc-absent.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output) (write-line "x" out))
				(print (mapcar #'probe-file (list "%s" "%s")))
				(print (uiop:file-exists-p "%s"))
				(print (uiop:file-exists-p "%s"))
				""".formatted(file, file, missing, file, missing)))
			.isEqualTo("(\"" + tempDir.resolve("fc.txt") + "\" NIL)\n\"" + tempDir.resolve("fc.txt") + "\"\nNIL");
	}

	@Test
	void directoryMatchesPathnamesAndDrivesTheUiopWalkers() throws Exception {
		// A subdirectory of its own: the compiled class lands in tempDir and would
		// otherwise show up in the listing.
		java.nio.file.Path root = java.nio.file.Files.createDirectory(tempDir.resolve("tree"));
		String dir = root.toString().replace("\\", "\\\\");
		java.nio.file.Files.writeString(root.resolve("b.txt"), "b\n");
		java.nio.file.Files.writeString(root.resolve("a.txt"), "a\n");
		java.nio.file.Files.createDirectory(root.resolve("sub"));
		java.nio.file.Files.writeString(root.resolve("sub/c.txt"), "c\n");
		String d = root + "/";
		// Same shape as the interpreter test, whose expectations were checked against
		// SBCL.
		assertThat(compileAndRun("""
				(print (directory "%s/*.*"))
				(print (directory "%s/*.txt"))
				(print (directory "%s/*"))
				(print (directory "%s"))
				(print (directory "%s/nope/*.*"))
				(print (uiop:directory-exists-p "%s"))
				(print (uiop:directory-exists-p "%s/a.txt"))
				(print (uiop:directory-files "%s"))
				(print (uiop:subdirectories "%s"))
				(let ((acc nil))
				  (uiop:collect-sub*directories "%s" (constantly t) (constantly t)
				                                (lambda (x) (setq acc (cons x acc))))
				  (print (reverse acc)))
				""".formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir, dir)))
			.isEqualTo(("(\"%sa.txt\" \"%sb.txt\" \"%ssub/\")\n" + "(\"%sa.txt\" \"%sb.txt\")\n" + "(\"%ssub/\")\n"
					+ "(\"%s\")\n" + "NIL\n" + "\"%s\"\n" + "NIL\n" + "(\"%sa.txt\" \"%sb.txt\")\n" + "(\"%ssub/\")\n"
					+ "(\"%s\" \"%ssub/\")")
				.formatted(d, d, d, d, d, d, d, d, d, d, d, d, d));
	}

	@Test
	void withOpenFileReturnsBodyValue() throws Exception {
		String file = tempDir.resolve("wof-ret.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun(
				"(print (with-open-file (out \"" + file + "\" :direction :output) (write-line \"x\" out) 42))"))
			.isEqualTo("42");
	}

	@Test
	void openCloseExplicitStreams() throws Exception {
		String file = tempDir.resolve("manual.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(setq out (open "%s" :output))
				(write-line "line1" out)
				(close out)
				(setq in (open "%s" :input))
				(print (read-line in))
				(close in)
				""".formatted(file, file))).isEqualTo("\"line1\"");
	}

	@Test
	void writeLineWithoutStreamPrintsToStdout() throws Exception {
		assertThat(compileAndRun("(write-line \"to stdout\")")).isEqualTo("to stdout");
	}

	@Test
	void withOutputToStringCollectsPrintFamilyOutput() throws Exception {
		assertThat(compileAndRun("""
				(print (with-output-to-string (s)
				  (princ "a=" s)
				  (princ 42 s)
				  (terpri s)
				  (prin1 "q" s)
				  (write-line " end" s)
				  (write-string "tail" s)))""")).isEqualTo("\"a=42\n\\\"q\\\" end\ntail\"");
	}

	@Test
	void withOutputToStringEmptyBodyIsEmptyString() throws Exception {
		assertThat(compileAndRun("(print (with-output-to-string (s)))")).isEqualTo("\"\"");
	}

	@Test
	void withOutputToStringBindingStandardOutputCapturesStreamlessPrints() throws Exception {
		// Binding *standard-output* as the target variable redirects the whole
		// stream-argument-less print family, including inside called functions
		// (s-sql's to-sql-name / sql-escape-string shape).
		assertThat(compileAndRun("""
				(defun emit-name () (princ "foo") (write-char #\\.) (write-string "bar"))
				(princ (with-output-to-string (*standard-output*)
				  (emit-name)
				  (format t "~a" 42)))
				(princ "|")
				(let ((*standard-output* (%make-string-output-stream)))
				  (princ "hidden"))
				(princ "visible")""")).isEqualTo("foo.bar42|visible");
	}

	@Test
	void errorOutputIsTheProcessErrorStream() throws Exception {
		// *error-output* is the standard ERROR designator (the reserved handle 2), so a
		// diagnostic written through it stays off the program's standard output.
		assertThat(compileAndRunCapturingErr("""
				(format *error-output* "diag ~a~%" 1)
				(write-line "line" *error-output*)
				(princ "on-stdout")""")).isEqualTo("diag 1\nline");
		assertThat(compileAndRun("""
				(format *error-output* "diag~%")
				(princ "on-stdout")""")).isEqualTo("on-stdout");
	}

	@Test
	void bindingErrorOutputCapturesWarnAndRestores() throws Exception {
		// CL's warning-capture idiom: warn's report defaults to *error-output*, so a
		// binding captures it -- and the unbound default still reaches stderr.
		assertThat(compileAndRun("""
				(princ (with-output-to-string (*error-output*)
				  (warn "captured")
				  (format *error-output* "diag~%")))
				(princ "|")
				(princ (open-stream-p *error-output*))""")).isEqualTo("WARNING: captured\ndiag\n|T");
		assertThat(compileAndRunCapturingErr("""
				(let ((s (%make-string-output-stream)))
				  (let ((*error-output* s)) (warn "hidden")))
				(warn "visible")""")).isEqualTo("WARNING: visible");
	}

	@Test
	void readtableCaseIsConstantUpcaseAndInternAcceptsFoundKeywordPackage() throws Exception {
		assertThat(compileAndRun("(print (readtable-case *readtable*))")).isEqualTo(":UPCASE");
		assertThat(compileAndRun("(print (intern \"ZAP\" (find-package :keyword)))")).isEqualTo(":ZAP");
	}

	@Test
	void withArenaIsAPlainProgn() throws Exception {
		// rontolisp:with-arena names a reclamation boundary for --no-gc; the JVM heap is
		// garbage-collected, so it is observationally a progn.
		assertThat(compileAndRun("(print (rontolisp:with-arena () 1 2 (+ 1 2)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (rontolisp:with-arena ()))")).isEqualTo("NIL");
	}

	@Test
	void withOutputToStringDoesNotTouchStandardOutput() throws Exception {
		assertThat(compileAndRun("(with-output-to-string (s) (princ \"hidden\" s)) (princ \"visible\")"))
			.isEqualTo("visible");
	}

	@Test
	void formatStreamDestinationWritesToStringStream() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (format s \"x=~a, y=~s~%\" 1 \"two\")))"))
			.isEqualTo("x=1, y=\"two\"");
	}

	@Test
	void formatRuntimeControlStringHonorsEveryDirective() throws Exception {
		// A control string the compiler cannot see through renders with the shared
		// runtime renderer, whose directive set is the literal expansion's: the padded,
		// iterated and conditional directives here are rendered, not copied out
		// verbatim while still eating their arguments.
		assertThat(compileAndRun("""
				(let ((c "A=~A S=~S D=~D ~5,'0D% ~{~A~^,~} ~@[cond=~A~] ~~ end"))
				  (princ (format nil c 1 "s" 42 7 (list 1 2) "c")))
				""")).isEqualTo("A=1 S=\"s\" D=42 00007% 1,2 cond=c ~ end");
	}

	@Test
	void formatAsAFunctionValueHonorsEveryDirective() throws Exception {
		assertThat(compileAndRun("(princ (apply #'format nil (list \"~{~a~^ | ~} (~5,'0d)\" (list 'a 'b) 7)))"))
			.isEqualTo("A | B (00007)");
	}

	@Test
	void printToStringStream() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (print 42 s)))")).isEqualTo("42");
	}

	@Test
	void withInputFromStringReadsLinesAndData() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "first line
				(1 2 3)
				third")
				  (print (read-line s))
				  (print (read s))
				  (print (read-line s))
				  (print (read-line s)))""")).isEqualTo("\"first line\"\n(1 2 3)\n\"third\"\nNIL");
	}

	// A supplementary code point read through read-char comes back as ONE
	// CHARACTER (a full code point), not as a high UTF-16 surrogate: the
	// _readChar body combines surrogate pairs via BufferedReader.mark/read/reset,
	// mirroring the interpreter's Environment.READ_CHAR. Uses a plain symbol
	// (not keyword) as eof-value to sidestep the compile-path list-princ
	// keyword-with-colon divergence -- the assertion is about the astral decode.
	@Test
	void readCharCombinesSurrogatePairsOnSupplementaryCodePoint() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "😀X")
				  (let* ((c1 (read-char s))
				         (c2 (read-char s))
				         (c3 (read-char s nil 'done)))
				    (princ (list (char-code c1) (char-code c2) c3))))""")).isEqualTo("(128512 88 DONE)");
	}

	@Test
	void stringOutputStreamNamesClearOnRead() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (make-string-output-stream)))
				  (write-string "ab" s)
				  (princ (get-output-stream-string s))
				  (write-string "cd" s)
				  (princ (get-output-stream-string s))
				  (princ (list (length (get-output-stream-string s)))))""")).isEqualTo("abcd(0)");
	}

	@Test
	void peekCharLeavesTheCharacterInTheStream() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "ab")
				  (princ (peek-char nil s))
				  (princ (peek-char nil s))
				  (princ (read-char s))
				  (princ (read-char s))
				  (princ (peek-char nil s nil 'eof)))""")).isEqualTo("aaabEOF");
	}

	@Test
	void peekCharSkipsWhitespaceAndUpToACharacter() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "   xy")
				  (princ (peek-char t s))
				  (princ (read-char s))
				  (princ (peek-char #\\y s))
				  (princ (read-char s)))""")).isEqualTo("xxyy");
	}

	@Test
	void readCharEndOfFileIsCatchableAsEndOfFile() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "")
				  (princ (handler-case (read-char s) (end-of-file () 'caught))))
				(with-input-from-string (s "")
				  (princ (handler-case (read-char s) (error () 'as-error))))""")).isEqualTo("CAUGHTAS-ERROR");
	}

	@Test
	void makeSynonymStreamResolvesTheNamedVariable() throws Exception {
		assertThat(
				compileAndRun("(defvar *sink* (make-synonym-stream '*standard-output*)) (write-string \"via\" *sink*)"))
			.isEqualTo("via");
	}

	@Test
	void synonymStreamOverStandardOutputFollowsALaterBinding() throws Exception {
		// The synonym IS the nil designator, so it resolves at WRITE time -- a binding
		// established after the synonym was built still captures.
		assertThat(compileAndRun("""
				(defvar *sink* (make-synonym-stream '*standard-output*))
				(princ (with-output-to-string (*standard-output*)
				  (write-line "captured" *sink*)))
				(princ "|")
				(write-line "plain" *sink*)""")).isEqualTo("captured\n|plain"); // compileAndRun
																				// strips
																				// the
																				// trailing
																				// newline
	}

	@Test
	void bindingStandardInputRedirectsTheStreamlessReadFamily() throws Exception {
		// The input mirror of the *standard-output* redirect: binding *standard-input*
		// redirects read-line / read-char / read, including inside called functions, and
		// an explicit nil argument is the same designator.
		assertThat(compileAndRun("""
				(defun slurp (&optional stream) (princ (read-line stream)) (princ "|"))
				(with-input-from-string (*standard-input* "one")
				  (slurp))
				(princ (with-input-from-string (*standard-input* "abc") (read-char)))
				(princ (with-input-from-string (*standard-input* "(1 2 3)") (read)))
				(princ (with-input-from-string (*standard-input* "x") (read-line nil)))""")).isEqualTo("one|a(1 2 3)x");
	}

	@Test
	void makeSynonymStreamOverStandardInputIsTheNilDesignator() throws Exception {
		assertThat(compileAndRun("""
				(defvar *src* (make-synonym-stream '*standard-input*))
				(princ (with-input-from-string (*standard-input* "later")
				  (read-line *src*)))""")).isEqualTo("later");
	}

	@Test
	void explicitNilStreamArgumentIsTheStandardOutputDesignator() throws Exception {
		// CL's stream designator rule: a forwarded optional that arrives as nil reaches
		// the CURRENT *standard-output*, not raw stdout.
		assertThat(compileAndRun("""
				(defun emit (x &optional stream)
				  (princ x stream)
				  (write-string "|" stream)
				  (write-line "" stream)
				  (fresh-line stream)
				  (terpri stream))
				(princ (with-output-to-string (*standard-output*)
				  (emit "a")
				  (print 1 nil)))
				(princ "-")
				(emit "b")""")).isEqualTo("a|\n\n1\n-b|"); // compileAndRun strips the
															// trailing newlines
	}

	@Test
	void writeStringWithoutStreamPrintsToStdoutWithoutNewline() throws Exception {
		assertThat(compileAndRun("(write-string \"no\") (write-string \" newline\")")).isEqualTo("no newline");
	}

	@Test
	void writeToStringIsPrin1ToString() throws Exception {
		assertThat(compileAndRun("(princ (write-to-string '(a \"b\" 3)))")).isEqualTo("(A \"b\" 3)");
	}

	@Test
	void princStreamDesignatorTGoesToStandardOutput() throws Exception {
		assertThat(compileAndRun("(princ \"a\" t) (princ \"b\" nil)")).isEqualTo("ab");
	}

	@Test
	void freshLineTracksStreamRoutedStdoutWrites() throws Exception {
		// princ via the runtime-dispatch path (t designator) must update the column
		// tracking so a following fresh-line emits a newline.
		assertThat(compileAndRun("(princ \"x\" t) (fresh-line) (princ \"y\")")).isEqualTo("x\ny");
	}

	@Test
	void readLinesInLoop() throws Exception {
		String file = tempDir.resolve("loop.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output)
				  (write-line "a" out)
				  (write-line "b" out)
				  (write-line "c" out))
				(with-open-file (in "%s")
				  (setq line (read-line in))
				  (while line
				    (princ line)
				    (setq line (read-line in))))
				""".formatted(file, file))).isEqualTo("abc");
	}

	@Test
	void withOpenFileBinaryRoundTrip() throws Exception {
		String file = tempDir.resolve("bin.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 0 out)
				  (write-byte 10 out)
				  (write-byte 34 out)
				  (write-byte 255 out))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in nil nil)))
				""".formatted(file, file))).isEqualTo("0\n10\n34\n255\nNIL");
	}

	@Test
	void readByteEofValueReturned() throws Exception {
		String file = tempDir.resolve("eofv.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8)))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (print (read-byte in nil -1)))
				""".formatted(file, file))).isEqualTo("-1");
	}

	@Test
	void writeByteReturnsByte() throws Exception {
		String file = tempDir.resolve("wb.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (print (write-byte 65 out)))
				""".formatted(file))).isEqualTo("65");
	}

	@Test
	void readWriteSequenceRoundTrip() throws Exception {
		String file = tempDir.resolve("seq.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(setq buf (make-array 4))
				(setf (aref buf 0) 65)
				(setf (aref buf 1) 0)
				(setf (aref buf 2) 10)
				(setf (aref buf 3) 34)
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence buf out))
				(setq buf2 (make-array 8 :initial-element 99))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (print (read-sequence buf2 in)))
				(print (aref buf2 0))
				(print (aref buf2 1))
				(print (aref buf2 2))
				(print (aref buf2 3))
				(print (aref buf2 4))
				""".formatted(file, file))).isEqualTo("4\n65\n0\n10\n34\n99");
	}

	@Test
	void readWriteSequenceStartEnd() throws Exception {
		String file = tempDir.resolve("se.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(setq buf (make-array 4))
				(setf (aref buf 0) 1)
				(setf (aref buf 1) 2)
				(setf (aref buf 2) 3)
				(setf (aref buf 3) 4)
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence buf out :start 1 :end 3))
				(setq buf2 (make-array 4 :initial-element 0))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (print (read-sequence buf2 in :start 2)))
				(print (aref buf2 2))
				(print (aref buf2 3))
				""".formatted(file, file))).isEqualTo("4\n2\n3");
	}

	@Test
	void openNonLiteralElementTypeThrows() {
		String file = tempDir.resolve("nl.dat").toString().replace("\\", "\\\\");
		assertThatThrownBy(() -> compileAndRun("(open \"" + file + "\" :input (list 'unsigned-byte 8))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("element type");
	}

	@Test
	void compileAndRunMultiplication() throws Exception {
		assertThat(compileAndRun("(print (* 3 4))")).isEqualTo("12");
	}

	@Test
	void compileAndRunNestedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ (* 2 3) (- 10 4)))")).isEqualTo("12");
	}

	@Test
	void compileAndRunModTakesSignOfDivisor() throws Exception {
		assertThat(compileAndRun("(print (mod 13 4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (mod -13 4))")).isEqualTo("3");
		assertThat(compileAndRun("(print (mod 13 -4))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (mod -13 -4))")).isEqualTo("-1");
	}

	@Test
	void compileAndRunRemTakesSignOfDividend() throws Exception {
		assertThat(compileAndRun("(print (rem 13 4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (rem -13 4))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (rem 13 -4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (rem -13 -4))")).isEqualTo("-1");
	}

	@Test
	void compileAndRunModBigInteger() throws Exception {
		assertThat(compileAndRun("(print (mod (- 0 (* 100000000000 100000000000)) 7))")).isEqualTo("3");
	}

	@Test
	void compileAndRunModFloat() throws Exception {
		assertThat(compileAndRun("(print (mod -5.5 2.0))")).isEqualTo("0.5");
	}

	@Test
	void compileAndRunVariadicComparison() throws Exception {
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun("(print (< 1 2 3 4))")).isEqualTo("T");
		assertThat(compileAndRun("(print (< 1 2 2 4))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (<= 1 2 2 4))")).isEqualTo("T");
		assertThat(compileAndRun("(print (= 3 3 3))")).isEqualTo("T");
		assertThat(compileAndRun("(print (> 5 4 3 2 1))")).isEqualTo("T");
		assertThat(compileAndRun("(print (< 5))")).isEqualTo("T");
	}

	@Test
	void compileBooleanIsSymbolT() throws Exception {
		// A boolean true prints as the symbol t (not the integer 1), matching the
		// interpreter, so it is indistinguishable from t in a list.
		assertThat(compileAndRun("(print (list (= 1 1) (= 1 0)))")).isEqualTo("(T NIL)");
		assertThat(compileAndRun("(print t)")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq t (= 1 1)))")).isEqualTo("T");
	}

	@Test
	void compileAndRunVariadicMinMax() throws Exception {
		assertThat(compileAndRun("(print (min 5 2 8 1 9))")).isEqualTo("1");
		assertThat(compileAndRun("(print (max 5 2 8 1 9))")).isEqualTo("9");
		assertThat(compileAndRun("(print (min 7))")).isEqualTo("7");
	}

	@Test
	void compileAndRunVariadicGcdLcm() throws Exception {
		assertThat(compileAndRun("(print (gcd 24 36 60))")).isEqualTo("12");
		assertThat(compileAndRun("(print (lcm 2 3 4))")).isEqualTo("12");
		assertThat(compileAndRun("(print (gcd -8))")).isEqualTo("8");
	}

	@Test
	void compileAndRunLengthOfString() throws Exception {
		assertThat(compileAndRun("(print (length \"hello\"))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length \"\"))")).isEqualTo("0");
	}

	@Test
	void compileAndRunLengthOfList() throws Exception {
		assertThat(compileAndRun("(print (length (list 10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length nil))")).isEqualTo("0");
	}

	@Test
	void compileAndRunMultipleExpressions() throws Exception {
		assertThat(compileAndRun("(print 1) (print 2)")).isEqualTo("1\n2");
	}

	@Test
	void compileAndRunIfTrue() throws Exception {
		assertThat(compileAndRun("(print (if t 1 2))")).isEqualTo("1");
	}

	@Test
	void compileAndRunIfFalse() throws Exception {
		assertThat(compileAndRun("(print (if nil 1 2))")).isEqualTo("2");
	}

	@Test
	void compileAndRunLet() throws Exception {
		assertThat(compileAndRun("(print (let ((x 10)) (+ x 5)))")).isEqualTo("15");
	}

	@Test
	void compileAndRunSubtraction() throws Exception {
		assertThat(compileAndRun("(print (- 10 3))")).isEqualTo("7");
	}

	@Test
	void compileAndRunDivision() throws Exception {
		assertThat(compileAndRun("(print (/ 10 3))")).isEqualTo("10/3");
		assertThat(compileAndRun("(print (/ 10 2))")).isEqualTo("5");
	}

	@Test
	void compileAndRunComparisonEqual() throws Exception {
		assertThat(compileAndRun("(print (if (= 1 1) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonNotEqual() throws Exception {
		assertThat(compileAndRun("(print (if (= 1 2) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunComparisonLessThan() throws Exception {
		assertThat(compileAndRun("(print (if (< 1 2) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonGreaterThan() throws Exception {
		assertThat(compileAndRun("(print (if (> 3 2) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonLessOrEqual() throws Exception {
		assertThat(compileAndRun("(print (if (<= 2 2) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonGreaterOrEqual() throws Exception {
		assertThat(compileAndRun("(print (if (>= 2 3) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDefunSquare() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (square 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunDefunFactorial() throws Exception {
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(print (fact 5))
				""")).isEqualTo("120");
	}

	@Test
	void compileAndRunDefunFibonacci() throws Exception {
		assertThat(compileAndRun("""
				(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
				(print (fib 10))
				""")).isEqualTo("55");
	}

	@Test
	void compileAndRunMultipleDefuns() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(defun add1 (x) (+ x 1))
				(print (add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunDefunNoParams() throws Exception {
		assertThat(compileAndRun("""
				(defun answer () 42)
				(print (answer))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunSetqBasic() throws Exception {
		assertThat(compileAndRun("(print (progn (setq x 10) x))")).isEqualTo("10");
	}

	@Test
	void compileAndRunEmptyPrognIsNilInValuePosition() throws Exception {
		// (progn) is nil; in an if-arm it must still push a value (a case clause
		// with an empty body -- cl-postgres' message-case CloseComplete arm --
		// expands to exactly this shape).
		assertThat(compileAndRun("(print (if (= 1 1) (progn) :else))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunSetqReassign() throws Exception {
		assertThat(compileAndRun("(print (progn (setq x 10) (setq x 20) x))")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetqMutateLet() throws Exception {
		assertThat(compileAndRun("(print (let ((x 1)) (setq x 2) x))")).isEqualTo("2");
	}

	@Test
	void compileAndRunSetqInExpression() throws Exception {
		assertThat(compileAndRun("(print (+ (setq x 5) 3))")).isEqualTo("8");
	}

	@Test
	void compileAndRunSetqLambdaSquare() throws Exception {
		assertThat(compileAndRun("""
				(setq square (lambda (x) (* x x)))
				(print (funcall square 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunSetqLambdaFactorial() throws Exception {
		// Lisp-2: a recursive function must be defined with defun (a lambda bound by
		// setq cannot refer to itself through the variable namespace in compiled code).
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(setq fact5 #'fact)
				(print (funcall fact5 5))
				""")).isEqualTo("120");
	}

	@Test
	void compileAndRunSetqLambdaNoParams() throws Exception {
		assertThat(compileAndRun("""
				(setq answer (lambda () 42))
				(print (funcall answer))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunSetqLambdaMultipleFunctions() throws Exception {
		assertThat(compileAndRun("""
				(setq double (lambda (x) (* x 2)))
				(setq add1 (lambda (x) (+ x 1)))
				(print (funcall add1 (funcall double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunMixedDefunAndSetqLambda() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(setq add1 (lambda (x) (+ x 1)))
				(print (funcall add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunLetShadowingBoxedOuterVariable() throws Exception {
		// Regression guard: the boxedVars set tracks names, so an inner let binding a RAW
		// value under a name whose outer binding was boxed (captured by the init
		// lambda) must not be cell-read in the body.
		assertThat(compileAndRun("""
				(let ((g (lambda () 1)))
				  (let ((g (lambda () (+ 10 (funcall g)))))
				    (print (funcall g))))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunLambdaCapturesVariableShadowingFunctionName() throws Exception {
		// A lexical variable named like a built-in function (count/list) is a plain
		// capturable variable (Lisp-2): the capture analysis must not treat the
		// reference as a function name.
		assertThat(compileAndRun("""
				(defun grab (list count)
				  (let ((list (nthcdr count list)))
				    (let ((g (lambda () (car list))))
				      (funcall g))))
				(print (grab '(1 2 3) 1))
				""")).isEqualTo("2");
	}

	@Test
	void compileAndRunLambdaImmediateCall() throws Exception {
		assertThat(compileAndRun("(print ((lambda (x) (* x x)) 5))")).isEqualTo("25");
	}

	@Test
	void compileAndRunLambdaMultipleParams() throws Exception {
		assertThat(compileAndRun("(print ((lambda (x y) (+ x y)) 3 4))")).isEqualTo("7");
	}

	@Test
	void compileAndRunPrintString() throws Exception {
		assertThat(compileAndRun("(print \"hello\")")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunPrin1() throws Exception {
		assertThat(compileAndRun("(prin1 42) (terpri) (prin1 \"hello\")")).isEqualTo("42\n\"hello\"");
	}

	@Test
	void compileAndRunPrinc() throws Exception {
		assertThat(compileAndRun("(princ 42) (terpri) (princ \"hello\")")).isEqualTo("42\nhello");
	}

	@Test
	void compileAndRunPrincList() throws Exception {
		assertThat(compileAndRun("(princ '(1 \"hello\" 3))")).isEqualTo("(1 hello 3)");
	}

	@Test
	void compileAndRunTerpri() throws Exception {
		assertThat(compileAndRun("(prin1 1) (princ 2) (terpri)")).isEqualTo("12");
	}

	@Test
	void compileAndRunFormat() throws Exception {
		assertThat(compileAndRun("(format t \"Hello ~a, you are ~d! ~s~%\" 'world 42 \"str\")"))
			.isEqualTo("Hello WORLD, you are 42! \"str\"");
	}

	@Test
	void compileAndRunFormatList() throws Exception {
		assertThat(compileAndRun("(format t \"list=~a tilde=~~\" (list 1 2 3))")).isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void compileAndRunFormatInsideDefun() throws Exception {
		assertThat(compileAndRun("(defun greet (name) (format t \"Hi, ~a!~%\" name)) (greet 'alice) (greet \"bob\")"))
			.isEqualTo("Hi, ALICE!\nHi, bob!");
	}

	@Test
	void compileAndRunFormatNil() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"Hello ~a, ~d! ~s~%\" 'world 42 \"str\"))"))
			.isEqualTo("Hello WORLD, 42! \"str\"");
	}

	@Test
	void compileAndRunFormatNilList() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"list=~a tilde=~~\" (list 1 2 3)))"))
			.isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void compileAndRunFormatNilIsString() throws Exception {
		assertThat(compileAndRun("(print (stringp (format nil \"~a\" 1))) (print (length (format nil \"~a\" 12345)))"))
			.isEqualTo("T\n5");
	}

	@Test
	void compileAndRunFormatDollarAndFixed() throws Exception {
		assertThat(compileAndRun("(format T \"~$ ~5$ ~,2f ~v$\" 3.14159 3.14159 3.14159 3 3.14159)"))
			.isEqualTo("3.14 3.14159 3.14 3.142");
	}

	@Test
	void compileAndRunFormatExponential() throws Exception {
		assertThat(compileAndRun("(format t \"~e ~,4e ~e ~,2e ~e\" pi pi 1234.5 9.999 0.0)"))
			.isEqualTo("3.141593e+0 3.1416e+0 1.2345e+3 1.00e+1 0.0e+0");
	}

	@Test
	void compileAndRunFormatDecimalModifiers() throws Exception {
		assertThat(compileAndRun("(format t \"~:d ~@d ~:@d\" 1000000 1000000 1000000)"))
			.isEqualTo("1,000,000 +1000000 +1,000,000");
	}

	@Test
	void compileAndRunFormatPadding() throws Exception {
		assertThat(compileAndRun("(format t \"~10a|~10@a|~5,'0d|\" \"foo\" \"foo\" 42)"))
			.isEqualTo("foo       |       foo|00042|");
	}

	@Test
	void compileAndRunFormatFreshLine() throws Exception {
		assertThat(compileAndRun("(format t \"a\") (format t \"~&b~&c~%\") (fresh-line) (princ \"d\")"))
			.isEqualTo("a\nb\nc\nd");
	}

	@Test
	void compileAndRunFormatEdges() throws Exception {
		// Negative-width padding, a custom comma character, a runtime (v) width and
		// bignum
		// grouping all run through the same expansion on the JVM backend.
		assertThat(compileAndRun("(format t \"[~6d][~,,'.:d][~va][~:d]\" -42 1234567 8 \"hi\" 100000000000000000000)"))
			.isEqualTo("[   -42][1.234.567][hi      ][100,000,000,000,000,000,000]");
	}

	@Test
	void compileAndRunFormatRadix() throws Exception {
		assertThat(compileAndRun("(format t \"~x ~o ~b ~8r ~x [~8,'0x]\" 255 256 10 4096 -255 255)"))
			.isEqualTo("FF 400 1010 10000 -FF [000000FF]");
	}

	@Test
	void compileAndRunFormatCharacter() throws Exception {
		assertThat(compileAndRun("(format t \"~c ~@c ~:c ~:c\" #\\a #\\b #\\Newline #\\z)"))
			.isEqualTo("a #\\b Newline z");
	}

	@Test
	void compileAndRunFormatCaseConversion() throws Exception {
		assertThat(compileAndRun(
				"(format t \"~(~a~) ~:@(~a~) ~:(~a~) ~@(~a~)\" \"FOO BAR\" \"foo bar\" \"foo bar\" \"foo BAR\")"))
			.isEqualTo("foo bar FOO BAR Foo Bar Foo bar");
	}

	@Test
	void compileAndRunFormatConditional() throws Exception {
		assertThat(compileAndRun("(format t \"~[a~a~;b~a~:;c~a~]|~:[no~a~;yes~a~]|~@[v=~a~] ~a\" 1 10 nil 20 nil 30)"))
			.isEqualTo("b10|no20| 30");
		assertThat(compileAndRun("(format t \"~#[none~;one ~a~;two ~a ~a~]\" 5 6)")).isEqualTo("two 5 6");
	}

	@Test
	void compileAndRunFormatNestedConditionalClauses() throws Exception {
		// A ~:[ nested inside another ~:[ clause, whose two clauses consume a different
		// number of arguments -- expandFormat distributes the control string remainder
		// over both branches. postmodern's deftable constraint strings end with this.
		String deferrable = "~:[NOT DEFERRABLE~;DEFERRABLE INITIALLY ~:[IMMEDIATE~;DEFERRED~]~]";
		assertThat(compileAndRun("(format t \"" + deferrable + "\" nil nil)")).isEqualTo("NOT DEFERRABLE");
		assertThat(compileAndRun("(format t \"" + deferrable + "\" t nil)"))
			.isEqualTo("DEFERRABLE INITIALLY IMMEDIATE");
		assertThat(compileAndRun("(format t \"" + deferrable + "\" t t)")).isEqualTo("DEFERRABLE INITIALLY DEFERRED");
		// A directive after the divergent conditional: each branch continues from its
		// own argument position.
		assertThat(compileAndRun("(format t \"[~:[x~;y~a~]|~a]\" nil \"P\" \"Q\")")).isEqualTo("[x|P]");
		assertThat(compileAndRun("(format t \"[~:[x~;y~a~]|~a]\" t \"P\" \"Q\")")).isEqualTo("[yP|Q]");
	}

	@Test
	void compileAndRunGetfDefaultAndRassocIf() throws Exception {
		assertThat(compileAndRun("(princ (getf '(:a 1) :on-delete :restrict))")).isEqualTo("RESTRICT");
		assertThat(compileAndRun("(princ (getf '(:on-delete :cascade) :on-delete :restrict))")).isEqualTo("CASCADE");
		assertThat(compileAndRun("(princ (getf '(:a nil) :a :fallback))")).isEqualTo("NIL");
		assertThat(compileAndRun("(princ (funcall #'getf '(:x 10) :y :none))")).isEqualTo("NONE");
		// getf is a FUNCTION: the default is evaluated hit OR miss, like the
		// interpreter's eager argument evaluation (so it cannot live in the do result).
		assertThat(compileAndRun("(let ((n 0)) (getf '(:a 1) :a (setq n 1)) (princ n))")).isEqualTo("1");
		assertThat(compileAndRun("(let ((n 0)) (getf '(:a 1) :b (setq n 1)) (princ n))")).isEqualTo("1");
		assertThat(compileAndRun("(princ (rassoc-if #'consp '((1 . 2) (3 4 . 5))))")).isEqualTo("(3 4 . 5)");
		assertThat(compileAndRun("(princ (funcall #'rassoc-if #'consp '((1 . 2) (3 4 . 5))))")).isEqualTo("(3 4 . 5)");
	}

	@Test
	void compileAndRunStringTrimListCharacterBag() throws Exception {
		// A literal list bag folds to a string constant; a runtime bag goes through the
		// same (coerce bag 'string) widening.
		assertThat(compileAndRun("(princ (string-trim '(#\\Space #\\Tab) \"\tx y \t\"))")).isEqualTo("x y");
		assertThat(compileAndRun("(princ (string-left-trim '(#\\Space) \"  z\"))")).isEqualTo("z");
		assertThat(compileAndRun("(princ (string-right-trim '(#\\x #\\y) \"helloxy\"))")).isEqualTo("hello");
		assertThat(compileAndRun("(let ((bag (list #\\Space))) (princ (string-trim bag \" q \")))")).isEqualTo("q");
	}

	@Test
	void compileAndRunFormatIteration() throws Exception {
		assertThat(compileAndRun("(format t \"~{<~a>~}|~2{ ~a~}|~:{(~a,~a)~}\" '(1 2) '(a b c d) '((x 1) (y 2)))"))
			.isEqualTo("<1><2>| A B|(X,1)(Y,2)");
		assertThat(compileAndRun("(format t \"x~2@{ ~a~}|~:@{(~a)~}\" 1 2 '(3) '(4))")).isEqualTo("x 1 2|(3)(4)");
	}

	@Test
	void compileAndRunFormatArgumentJump() throws Exception {
		assertThat(compileAndRun("(format t \"~a ~2* ~a ~2:* ~a\" 1 2 3 4)")).isEqualTo("1  4  3");
	}

	@Test
	void compileAndRunFormatIterationEscape() throws Exception {
		assertThat(compileAndRun("(format t \"~{~a~^, ~}|~@{~a~^, ~}\" '(1 2 3) 4 5)")).isEqualTo("1, 2, 3|4, 5");
		assertThat(compileAndRun("(format t \"~:['{}'~;ARRAY[~:*~{~A~^, ~}]~]\" '(1 2 3))"))
			.isEqualTo("ARRAY[1, 2, 3]");
	}

	@Test
	void compileAndRunFormatRuntimePadCharAndExponentParams() throws Exception {
		assertThat(compileAndRun("(format t \"~v,vd [~15,5,3e] [~8,4,,,'*e]\" 6 #\\0 42 pi pi)"))
			.isEqualTo("000042 [   3.14159e+000] [********]");
	}

	@Test
	void compileAndRunFormatGeneralFloat() throws Exception {
		assertThat(compileAndRun("(format t \"~g ~g ~g ~g\" 1234.5 0.5 0.00012345 0.0)"))
			.isEqualTo("1234.5 0.5 1.2345e-4 0.0");
	}

	@Test
	void compileAndRunFormatFixedOverflow() throws Exception {
		assertThat(compileAndRun("(format t \"[~10,8,,'*,'0f][~10,9,,'*,'0f]\" pi pi)"))
			.isEqualTo("[3.14159265][**********]");
	}

	@Test
	void compileAndRunPrincToString() throws Exception {
		assertThat(compileAndRun("(print (princ-to-string 42)) (princ (princ-to-string 'sym))"))
			.isEqualTo("\"42\"\nSYM");
	}

	@Test
	void compileAndRunPrin1ToString() throws Exception {
		assertThat(compileAndRun("(princ (prin1-to-string \"abc\"))")).isEqualTo("\"abc\"");
	}

	@Test
	void compileAndRunPrin1EscapesQuotesAndBackslashesInStrings() throws Exception {
		// *print-escape* = t escapes the embedded " and \ (todo 216); princ / ~a do not.
		// A bare SYMBOL still prints verbatim -- the leading quote is the discriminator.
		assertThat(compileAndRun("(prin1 \"{\\\"hello\\\":\\\"aaa\\\"}\")"))
			.isEqualTo("\"{\\\"hello\\\":\\\"aaa\\\"}\"");
		assertThat(compileAndRun("(prin1 (list \"x\\\"y\" 'foo))")).isEqualTo("(\"x\\\"y\" FOO)");
		assertThat(compileAndRun("(princ (prin1-to-string \"a\\\"b\\\\c\"))")).isEqualTo("\"a\\\"b\\\\c\"");
		assertThat(compileAndRun("(princ (format nil \"~s|~a\" \"a\\\"b\" \"a\\\"b\"))")).isEqualTo("\"a\\\"b\"|a\"b");
		assertThat(compileAndRun("(princ \"{\\\"hello\\\":\\\"aaa\\\"}\")")).isEqualTo("{\"hello\":\"aaa\"}");
	}

	@Test
	void compileAndRunPrin1OutputReadsBackAsTheSameString() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (concatenate 'string "a" (string (code-char 34)) "b"
				                      (string (code-char 92)) "c"
				                      (string #\\Newline) "d")))
				  (prin1 (list (equal (read-from-string (prin1-to-string s)) s) (length s))))
				""")).isEqualTo("(T 7)");
	}

	@Test
	void compileAndRunConcatenate() throws Exception {
		assertThat(compileAndRun("(princ (concatenate 'string \"foo\" \"bar\" \"baz\"))")).isEqualTo("foobarbaz");
	}

	@Test
	void compileAndRunConcatenateListAndVectorResultTypes() throws Exception {
		assertThat(compileAndRun("(print (concatenate 'list '(1 2) \"ab\" #(3)))")).isEqualTo("(1 2 #\\a #\\b 3)");
		assertThat(compileAndRun("(print (concatenate 'vector '(1 2) #(3)))")).isEqualTo("#(1 2 3)");
		assertThat(compileAndRun("(print (concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)))"))
			.isEqualTo("#(1 2 3)");
		assertThat(compileAndRun("(print (concatenate 'list))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (concatenate 'vector))")).isEqualTo("#()");
		assertThat(compileAndRun("(print (let ((a (list 1 2))) (eq a (concatenate 'list a))))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunConcatenateStringTakesAnySequence() throws Exception {
		// The string family walks any character sequence, and nil -- the empty list -- is
		// the one real code leans on: s-sql builds "CREATE TABLE x" as
		// (concatenate 'string (unless tableset "TABLE ") name). Each non-literal
		// argument goes through the injected %seq-string helper, never an inlined coerce
		// loop (.kb/concatenate-result-families.md).
		assertThat(compileAndRun("(print (concatenate 'string \"a\" '(#\\b #\\c) #(#\\d) nil \"e\"))"))
			.isEqualTo("\"abcde\"");
		assertThat(compileAndRun("(print (concatenate 'string (unless t \"TABLE \") \"person\"))"))
			.isEqualTo("\"person\"");
		assertThat(compileAndRun("(print (apply #'concatenate 'string (list nil \"x\" '(#\\y))))")).isEqualTo("\"xy\"");
	}

	@Test
	void compileAndRunErrorOutputInsideALambda() throws Exception {
		// The standard stream variables are GLOBAL, not lexicals a lambda captures:
		// postmodern's generate-prepared reports its reconnect with
		// (format *error-output* ...) from inside a handler-bind handler, which used to
		// fail the compile with "Cannot capture variable: *ERROR-OUTPUT*". The report
		// lands on standard ERROR (the designator's default), not on standard output.
		assertThat(compileAndRunCapturingErr("""
				(defun report (f) (funcall f "x"))
				(report (lambda (m) (format *error-output* "seen ~a" m)))
				""")).isEqualTo("seen x");
	}

	@Test
	void compileAndRunDefunClosingOverAWithMutexLock() throws Exception {
		// with-mutex puts a VALUE in its one-element spec, the opposite of the with-*
		// stream macros, so FreeVarAnalyzer has to expand it before it can see that the
		// defun captures the top-level let's lock. postmodern's prepare.lisp guards its
		// statement-id counter exactly this way.
		assertThat(compileAndRun("""
				(let ((n 0) (lock (rontolisp:make-mutex)))
				  (defun next-id () (rontolisp:with-mutex (lock) (setf n (1+ n)) n)))
				(next-id)
				(print (next-id))
				""")).isEqualTo("2");
	}

	@Test
	void compileAndRunConcatenateAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (apply #'concatenate '(vector (unsigned-byte 8)) (list #(1) #(2 3))))"))
			.isEqualTo("#(1 2 3)");
		assertThat(compileAndRun("(print (apply #'concatenate 'list (list '(1) '(2 3))))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (apply #'concatenate 'string (list \"a\" \"b\")))")).isEqualTo("\"ab\"");
	}

	@Test
	void compileConcatenateWithComputedResultTypeFails() {
		assertThatThrownBy(() -> compileAndRun("(let ((ty 'string)) (princ (concatenate ty \"a\" \"b\")))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("literal quoted");
	}

	@Test
	void compileAndRunPrincToStringAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'princ-to-string (list 1 2)))")).isEqualTo("(\"1\" \"2\")");
	}

	@Test
	void compileAndRunMapListOverLists() throws Exception {
		assertThat(compileAndRun("(print (map 'list #'+ '(1 2 3) '(10 20 30)))")).isEqualTo("(11 22 33)");
	}

	@Test
	void compileAndRunMapListStopsAtShortestSequence() throws Exception {
		assertThat(compileAndRun("(print (map 'list #'+ '(1 2 3) '(10 20)))")).isEqualTo("(11 22)");
	}

	@Test
	void compileAndRunMapStringOverString() throws Exception {
		assertThat(compileAndRun("(print (map 'string #'char-upcase \"abc\"))")).isEqualTo("\"ABC\"");
	}

	@Test
	void compileAndRunMapListOverString() throws Exception {
		assertThat(compileAndRun("(print (map 'list (lambda (c) (char-code c)) \"AB\"))")).isEqualTo("(65 66)");
	}

	@Test
	void compileAndRunMapNilCallsForEffect() throws Exception {
		assertThat(compileAndRun("(map nil #'print '(7 8 9))")).isEqualTo("7\n8\n9");
	}

	@Test
	void compileAndRunStringUpcaseDowncase() throws Exception {
		assertThat(compileAndRun("(princ (string-upcase \"Hello, World\"))")).isEqualTo("HELLO, WORLD");
		assertThat(compileAndRun("(princ (string-downcase \"Hello, World\"))")).isEqualTo("hello, world");
	}

	@Test
	void compileAndRunStringCapitalize() throws Exception {
		assertThat(compileAndRun("(princ (string-capitalize \"hello world  foo\"))")).isEqualTo("Hello World  Foo");
	}

	@Test
	void compileAndRunSubseq() throws Exception {
		assertThat(compileAndRun("(princ (subseq \"hello world\" 6))")).isEqualTo("world");
		assertThat(compileAndRun("(princ (subseq \"hello world\" 0 5))")).isEqualTo("hello");
	}

	@Test
	void compileAndRunSubseqList() throws Exception {
		assertThat(compileAndRun("(print (subseq '(1 2 3 4 5) 1 3))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (subseq '(1 2 3 4 5) 2))")).isEqualTo("(3 4 5)");
		assertThat(compileAndRun("(print (subseq '(a b c) 0))")).isEqualTo("(A B C)");
		assertThat(compileAndRun("(print (subseq '(1 2 3) 3))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunMakeString() throws Exception {
		assertThat(compileAndRun("(princ (make-string 3 :initial-element #\\x))")).isEqualTo("xxx");
		assertThat(compileAndRun("(princ (length (make-string 5)))")).isEqualTo("5");
		// (length ...) avoids the compileAndRun trim() eating the funcall's trailing
		// spaces.
		assertThat(compileAndRun("(princ (length (funcall #'make-string 2)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunReplace() throws Exception {
		assertThat(compileAndRun("(princ (replace (make-string 5 :initial-element #\\a) \"XY\" :start1 1))"))
			.isEqualTo("aXYaa");
		assertThat(compileAndRun("(princ (replace \"aaaaa\" \"XY\"))")).isEqualTo("XYaaa");
		assertThat(compileAndRun("(princ (funcall #'replace \"aaaaa\" \"XY\"))")).isEqualTo("XYaaa");
	}

	@Test
	void compileAndRunWriteSequenceString() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (write-sequence \"abcd\" s :start 1 :end 3)))"))
			.isEqualTo("bc");
	}

	@Test
	void compileAndRunCasePredicatesAndConstantp() throws Exception {
		assertThat(compileAndRun("(print (list (lower-case-p #\\a) (upper-case-p #\\A)))")).isEqualTo("(T T)");
		assertThat(compileAndRun("(print (list (lower-case-p #\\A) (upper-case-p #\\a)))")).isEqualTo("(NIL NIL)");
		assertThat(compileAndRun("(print (list (constantp 5) (constantp 'x) (constantp '(quote y))))"))
			.isEqualTo("(T NIL T)");
		assertThat(compileAndRun("(print (mapcar #'upper-case-p '(#\\A #\\b)))")).isEqualTo("(T NIL)");
	}

	@Test
	void compileAndRunStreamp() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (princ (streamp s) s)))")).isEqualTo("T");
		assertThat(compileAndRun("(princ (with-output-to-string (s) (check-type s stream) (write-string \"ok\" s)))"))
			.isEqualTo("ok");
	}

	@Test
	void compileAndRunStringEquality() throws Exception {
		assertThat(compileAndRun("(print (string= \"abc\" \"abc\"))")).isEqualTo("T");
		assertThat(compileAndRun("(print (string= \"abc\" \"abd\"))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (string-equal \"ABC\" \"abc\"))")).isEqualTo("T");
		// The bounding-index keywords lower onto subseq; the two-string intrinsic is
		// unchanged.
		assertThat(compileAndRun("(print (string= \"together\" \"frog\" :start1 1 :end1 3 :start2 2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (string= \"abc\" \"xabc\" :start2 1))")).isEqualTo("T");
		assertThat(compileAndRun("(print (string-equal \"TOGETHER\" \"frog\" :start1 1 :end1 3 :start2 2))"))
			.isEqualTo("T");
		// ... and through the first-class value, whose wrapper re-reads the keywords
		// from its &rest list (an ordinary two-argument :test #'string= stays direct).
		assertThat(compileAndRun("(print (funcall #'string= \"xabc\" \"abc\" :start1 1)) "
				+ "(print (apply #'string-equal \"XABC\" \"abc\" '(:start1 1))) "
				+ "(print (find \"b\" '(\"a\" \"b\" \"c\") :test #'string=))"))
			.isEqualTo("T\nT\n\"b\"");
	}

	@Test
	void compileAndRunStringOrderingPredicates() throws Exception {
		// The prelude splice mirrors the CLI pipeline (the whole comparison family are
		// prelude defuns over %string-compare, pulled in transitively).
		assertThat(compileAndRun("""
				(print (list (string< "aaaa" "aaab") (string< "abc" "abc")))
				(print (list (string> "abcd" "abc") (string> "abc" "abd")))
				(print (list (string<= "abc" "abc") (string<= "abd" "abc")))
				(print (list (string>= "aaaaa" "aaaa") (string>= "abc" "abd")))
				(print (list (string/= "abc" "abd") (string/= "abc" "abc")))
				(print (list (string-lessp "ABC" "abd") (string-greaterp "ABD" "abc")))
				(print (list (string-not-greaterp "Abcde" "abcdE") (string-not-lessp "Abcde" "abcdE")))
				(print (list (string-not-equal "AAAA" "aaaA") (string-not-equal "AAAB" "aaaa")))
				(print (string-lessp "012AAAA789" "01aaab6" :start1 3 :end1 7 :start2 2 :end2 6))
				(print (sort (list "pear" "Apple" "banana") #'string-lessp))
				""")).isEqualTo("""
				(3 NIL)
				(3 NIL)
				(3 NIL)
				(4 NIL)
				(2 NIL)
				(2 2)
				(5 5)
				(NIL 3)
				6
				("Apple" "banana" "pear")""");
	}

	@Test
	void compileAndRunStringTrim() throws Exception {
		assertThat(compileAndRun("(princ (string-trim \" xy\" \"xyhelloyx \"))")).isEqualTo("hello");
		assertThat(compileAndRun("(princ (string-left-trim \"x\" \"xxhello\"))")).isEqualTo("hello");
		assertThat(compileAndRun("(princ (string-right-trim \"x\" \"helloxx\"))")).isEqualTo("hello");
	}

	@Test
	void compileAndRunStringFunctionsAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'string-upcase (list \"ab\" \"cd\")))")).isEqualTo("(\"AB\" \"CD\")");
		assertThat(compileAndRun("(print (funcall #'subseq \"hello\" 2))")).isEqualTo("\"llo\"");
	}

	@Test
	void compileAndRunQuoteInteger() throws Exception {
		assertThat(compileAndRun("(print '42)")).isEqualTo("42");
	}

	@Test
	void compileAndRunQuoteList() throws Exception {
		assertThat(compileAndRun("(print '(1 2 3))")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunQuoteNestedList() throws Exception {
		assertThat(compileAndRun("(print '(1 (2 3) 4))")).isEqualTo("(1 (2 3) 4)");
	}

	@Test
	void compileAndRunQuoteNil() throws Exception {
		assertThat(compileAndRun("(print (quote nil))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunStringInLet() throws Exception {
		assertThat(compileAndRun("(let ((x \"world\")) (print x))")).isEqualTo("\"world\"");
	}

	@Test
	void compileAndRunQuoteWithSymbol() throws Exception {
		assertThat(compileAndRun("(print '(+ 1 2))")).isEqualTo("(+ 1 2)");
	}

	@Test
	void compileAndRunListCarCdr() throws Exception {
		assertThat(compileAndRun("(print (car (list 1 2 3)))")).isEqualTo("1");
	}

	@Test
	void compileAndRunListCarCdr2() throws Exception {
		assertThat(compileAndRun("(print (car (cdr (list 1 2 3))))")).isEqualTo("2");
	}

	@Test
	void compileAndRunCons() throws Exception {
		assertThat(compileAndRun("(print (car (cons 1 2)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (cdr (cons 1 2)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunCarCdrOfNil() throws Exception {
		assertThat(compileAndRun("(print (car nil))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (cdr nil))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (car '()))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (cdr '()))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunHigherOrderFunction() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun apply-twice (f x) (funcall f (funcall f x)))
				(print (apply-twice #'square 3))
				""")).isEqualTo("81");
	}

	@Test
	void compileAndRunLambdaAsArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun apply-twice (f x) (funcall f (funcall f x)))
				(print (apply-twice (lambda (x) (+ x 10)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunClosure() throws Exception {
		assertThat(compileAndRun("""
				(defun make-adder (n) (lambda (x) (+ x n)))
				(setq add5 (make-adder 5))
				(print (funcall add5 10))
				""")).isEqualTo("15");
	}

	@Test
	void compileAndRunClosureMutation() throws Exception {
		assertThat(compileAndRun("""
				(defun make-counter ()
				  (let ((n 0))
				    (lambda ()
				      (setq n (+ n 1))
				      n)))
				(setq counter (make-counter))
				(funcall counter)
				(funcall counter)
				(print (funcall counter))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunDynamicFunctionSelection() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun forty-two (x) 42)
				(setq f (if t #'square #'forty-two))
				(print (funcall f 6))
				""")).isEqualTo("36");
	}

	@Test
	void compileAndRunFuncall() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall #'square 7))
				""")).isEqualTo("49");
	}

	@Test
	void compileAndRunFuncallLambda() throws Exception {
		assertThat(compileAndRun("""
				(print (funcall (lambda (x) (* x x)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunVariadicBuiltinWrappers() throws Exception {
		// Regression guard: funcall/apply of a variadic builtin wrapper with an arity
		// other than the old fixed one used to return nil silently on the JVM.
		assertThat(compileAndRun("(print (funcall #'+ 1 2 3))")).isEqualTo("6");
		assertThat(compileAndRun("(print (funcall #'+))")).isEqualTo("0");
		assertThat(compileAndRun("(print (apply #'+ (list 1 2 3 4)))")).isEqualTo("10");
		assertThat(compileAndRun("(print (funcall #'* 2 3 4))")).isEqualTo("24");
		assertThat(compileAndRun("(print (funcall #'- 10 1 2))")).isEqualTo("7");
		assertThat(compileAndRun("(print (funcall #'- 5))")).isEqualTo("-5");
		assertThat(compileAndRun("(print (funcall #'/ 100 2 5))")).isEqualTo("10");
		assertThat(compileAndRun("(print (funcall #'list 1 2 3))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'max 3 7 2))")).isEqualTo("7");
		assertThat(compileAndRun("(print (funcall #'min 3 7 2))")).isEqualTo("2");
	}

	@Test
	void compileAndRunFunctionInList() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall (car (list #'square)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunNullPredicate() throws Exception {
		assertThat(compileAndRun("(print (if (null nil) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (null 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunAtom() throws Exception {
		assertThat(compileAndRun("(print (if (atom 1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (atom '(1 2)) 42 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (if (atom nil) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunNumberp() throws Exception {
		assertThat(compileAndRun("(print (if (numberp 42) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (numberp 3.14) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (numberp \"hello\") 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunIntegerp() throws Exception {
		assertThat(compileAndRun("(print (if (integerp 42) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (integerp 3.14) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunFloatp() throws Exception {
		assertThat(compileAndRun("(print (if (floatp 3.14) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (floatp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunSymbolp() throws Exception {
		assertThat(compileAndRun("(print (if (symbolp 'foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (symbolp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunStringp() throws Exception {
		assertThat(compileAndRun("(print (if (stringp \"hello\") 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (stringp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunListp() throws Exception {
		assertThat(compileAndRun("(print (if (listp '(1 2)) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (listp nil) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (listp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunConsp() throws Exception {
		assertThat(compileAndRun("(print (if (consp '(1 2)) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (consp nil) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDoubleLiteral() throws Exception {
		assertThat(compileAndRun("(print 3.14)")).isEqualTo("3.14");
	}

	@Test
	void compileAndRunExponentFloatLiteral() throws Exception {
		// Common Lisp exponent-marker float literals all compile to a double.
		assertThat(compileAndRun("(print 1d0)")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (* 2 1d0))")).isEqualTo("2.0");
		assertThat(compileAndRun("(print 1.5d3)")).isEqualTo("1500.0");
	}

	@Test
	void compileAndRunPiConstant() throws Exception {
		assertThat(compileAndRun("(print pi)")).isEqualTo("3.141592653589793");
	}

	@Test
	void compileAndRunDoubleAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1.5 2.5))")).isEqualTo("4.0");
	}

	@Test
	void compileAndRunDoubleMixedAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 1.5))")).isEqualTo("2.5");
	}

	@Test
	void compileAndRunDoubleSubtraction() throws Exception {
		assertThat(compileAndRun("(print (- 3.5 1.5))")).isEqualTo("2.0");
	}

	@Test
	void compileAndRunDoubleMultiplication() throws Exception {
		assertThat(compileAndRun("(print (* 2.0 3.0))")).isEqualTo("6.0");
	}

	@Test
	void compileAndRunDoubleDivision() throws Exception {
		assertThat(compileAndRun("(print (/ 7.0 2.0))")).isEqualTo("3.5");
	}

	@Test
	void compileAndRunDoubleComparison() throws Exception {
		assertThat(compileAndRun("(print (if (= 1.0 1.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (< 1.0 2.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (> 2.0 1.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (<= 1.5 1.5) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (>= 2.0 3.0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDoubleNestedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ (* 2.0 3.0) (- 10.0 4.0)))")).isEqualTo("12.0");
	}

	@Test
	void compileAndRunOnePlus() throws Exception {
		assertThat(compileAndRun("(print (1+ 5))")).isEqualTo("6");
	}

	@Test
	void compileAndRunOneMinus() throws Exception {
		assertThat(compileAndRun("(print (1- 5))")).isEqualTo("4");
	}

	@Test
	void compileAndRunZerop() throws Exception {
		assertThat(compileAndRun("(print (if (zerop 0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (zerop 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunPlusp() throws Exception {
		assertThat(compileAndRun("(print (if (plusp 1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (plusp 0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunMinusp() throws Exception {
		assertThat(compileAndRun("(print (if (minusp -1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (minusp 0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEvenp() throws Exception {
		assertThat(compileAndRun("(print (if (evenp 4) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (evenp 3) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunOddp() throws Exception {
		assertThat(compileAndRun("(print (if (oddp 3) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (oddp 4) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunAbs() throws Exception {
		assertThat(compileAndRun("(print (abs 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (abs -5))")).isEqualTo("5");
	}

	@Test
	void compileAndRunAbsOnFloatThroughAVariable() throws Exception {
		// Regression: abs of a float reaching it through a variable (no compile-time
		// double literal in the argument) used to take the integer runtime path and trap
		// with "Double cannot be cast to BigInteger". _abs now dispatches on the runtime
		// type and handles a Double.
		assertThat(compileAndRun("(let ((x -0.5)) (print (abs x)))")).isEqualTo("0.5");
		assertThat(compileAndRun("(let ((x 0.5)) (print (abs x)))")).isEqualTo("0.5");
		assertThat(compileAndRun("""
				(defun g (a) (abs a))
				(print (g -2.5))
				""")).isEqualTo("2.5");
		// A literal-double argument (the compile-time fast path) still works.
		assertThat(compileAndRun("(print (abs -2.5))")).isEqualTo("2.5");
	}

	@Test
	void compileAndRunMin() throws Exception {
		assertThat(compileAndRun("(print (min 3 5))")).isEqualTo("3");
		assertThat(compileAndRun("(print (min 5 3))")).isEqualTo("3");
	}

	@Test
	void compileAndRunMax() throws Exception {
		assertThat(compileAndRun("(print (max 3 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (max 5 3))")).isEqualTo("5");
	}

	@Test
	void compileAndRunSqrt() throws Exception {
		assertThat(compileAndRun("(print (sqrt 16))")).isEqualTo("4.0");
		assertThat(compileAndRun("(print (sqrt 2))")).isEqualTo("1.4142135623730951");
		assertThat(compileAndRun("(print (sqrt 2.0))")).isEqualTo("1.4142135623730951");
	}

	@Test
	void compileAndRunIsqrt() throws Exception {
		assertThat(compileAndRun("(print (isqrt 17))")).isEqualTo("4");
		assertThat(compileAndRun("(print (isqrt 16))")).isEqualTo("4");
	}

	@Test
	void compileAndRunExpt() throws Exception {
		assertThat(compileAndRun("(print (expt 2 10))")).isEqualTo("1024");
		assertThat(compileAndRun("(print (expt 3 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (expt 2.0 3))")).isEqualTo("8.0");
		assertThat(compileAndRun("(print (expt 2 70))")).isEqualTo("1180591620717411303424");
	}

	@Test
	void compileAndRunGcdLcm() throws Exception {
		assertThat(compileAndRun("(print (gcd 12 18))")).isEqualTo("6");
		assertThat(compileAndRun("(print (gcd 0 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (lcm 4 6))")).isEqualTo("12");
		assertThat(compileAndRun("(print (lcm 0 6))")).isEqualTo("0");
	}

	@Test
	void compileAndRunFloatArithmeticOnNonLiteralOperands() throws Exception {
		// Float values reaching an operator through variables/parameters (not as a
		// literal) must still use float arithmetic, with integer contagion.
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1.5 2.5))")).isEqualTo("4.0");
		assertThat(compileAndRun("(defun f (a b) (- a b)) (print (f 1.0 0.25))")).isEqualTo("0.75");
		assertThat(compileAndRun("(defun f (a b) (* a b)) (print (f 1.5 2.5))")).isEqualTo("3.75");
		assertThat(compileAndRun("(defun f (a b) (/ a b)) (print (f 3.0 2.0))")).isEqualTo("1.5");
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1 2.0))")).isEqualTo("3.0");
		assertThat(compileAndRun("(defun neg (a) (- a)) (print (neg 2.5))")).isEqualTo("-2.5");
		// Comparisons on non-literal float operands.
		assertThat(compileAndRun("(defun lt (a b) (if (< a b) 1 0)) (print (lt 1.5 2.5))")).isEqualTo("1");
		assertThat(compileAndRun("(defun gt (a b) (if (> a b) 1 0)) (print (gt 1.5 2.5))")).isEqualTo("0");
		assertThat(compileAndRun("(defun eq2 (a b) (if (= a b) 1 0)) (print (eq2 2.0 2.0))")).isEqualTo("1");
		// Operators as first-class values over floats.
		assertThat(compileAndRun("(print (reduce #'+ (list 1.0 2.0 3.0) :initial-value 0))")).isEqualTo("6.0");
		assertThat(compileAndRun("(print (funcall #'* 1.5 2.0))")).isEqualTo("3.0");
		// Integer, big-integer and ratio paths are unaffected by the float fast path.
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1 2))")).isEqualTo("3");
		assertThat(compileAndRun("(defun f (a b) (* a b)) (print (f 1000000000000 1000000000000))"))
			.isEqualTo("1000000000000000000000000");
		assertThat(compileAndRun("(defun f (a b) (/ a b)) (print (f 1 3))")).isEqualTo("1/3");
	}

	@Test
	void compileAndRunRandom() throws Exception {
		// (random 1) is always 0; the result type follows the limit and stays in range.
		assertThat(compileAndRun("(print (random 1))")).isEqualTo("0");
		assertThat(compileAndRun("(print (integerp (random 100)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (floatp (random 5.0)))")).isEqualTo("T");
		assertThat(compileAndRun("(let ((r (random 10))) (if (and (>= r 0) (< r 10)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
		assertThat(compileAndRun(
				"(let ((r (random 1.0))) (if (and (>= r 0.0) (< r 1.0)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
	}

	@Test
	void compileAndRunRandomOnFloatLimitThroughAVariable() throws Exception {
		// Regression: like abs/signum, a float limit reaching random through a variable
		// (no float literal in the argument) used to take the integer path and trap with
		// "Double cannot be cast to Long". _random now dispatches on the runtime type, so
		// a float limit yields a float and an integer limit yields an integer.
		assertThat(compileAndRun("(let ((x 5.0)) (print (floatp (random x))))")).isEqualTo("T");
		assertThat(compileAndRun("(let ((x 5)) (print (integerp (random x))))")).isEqualTo("T");
		assertThat(compileAndRun("""
				(defun rnd (limit) (random limit))
				(let ((r (rnd 1.0))) (if (and (>= r 0.0) (< r 1.0)) (print "in") (print "oob")))
				""")).isEqualTo("\"in\"");
		assertThat(compileAndRun("""
				(defun rndi (limit) (random limit))
				(let ((r (rndi 10))) (if (and (>= r 0) (< r 10)) (print "in") (print "oob")))
				""")).isEqualTo("\"in\"");
	}

	@Test
	void compileAndRunTimeFunctions() throws Exception {
		// get-universal-time is seconds since 1900; well past 2020 (> 3.78e9).
		assertThat(compileAndRun("(print (> (get-universal-time) 3786825600))")).isEqualTo("T");
		assertThat(compileAndRun("(print (integerp (get-internal-real-time)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (integerp (get-internal-run-time)))")).isEqualTo("T");
	}

	@Test
	void compileAndRunGetenv() throws Exception {
		// PATH is set in the test environment; an unset variable yields nil.
		assertThat(compileAndRun("(print (stringp (uiop:getenv \"PATH\")))")).isEqualTo("T");
		assertThat(compileAndRun("(print (uiop:getenv \"RONTOLISP_DEFINITELY_UNSET_VAR\"))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunSignum() throws Exception {
		assertThat(compileAndRun("(print (signum -5))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (signum 0))")).isEqualTo("0");
		assertThat(compileAndRun("(print (signum 7))")).isEqualTo("1");
		assertThat(compileAndRun("(print (signum 3.5))")).isEqualTo("1.0");
	}

	@Test
	void compileAndRunSignumOnFloatThroughAVariable() throws Exception {
		// Regression: like abs, signum of a float reaching it through a variable used to
		// take the integer path (_ratnum -> _big) and trap with "Double cannot be cast to
		// BigInteger". _signum now dispatches on the runtime type.
		assertThat(compileAndRun("(let ((x -0.5)) (print (signum x)))")).isEqualTo("-1.0");
		assertThat(compileAndRun("(let ((x 2.5)) (print (signum x)))")).isEqualTo("1.0");
		assertThat(compileAndRun("(let ((x 0.0)) (print (signum x)))")).isEqualTo("0.0");
		assertThat(compileAndRun("""
				(defun s (a) (signum a))
				(print (s -3.0))
				""")).isEqualTo("-1.0");
		// Integer / ratio still return an integer sign.
		assertThat(compileAndRun("(let ((x -5)) (print (signum x)))")).isEqualTo("-1");
	}

	@Test
	void compileAndRunTranscendental() throws Exception {
		assertThat(compileAndRun("(print (sin 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (cos 0))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (exp 0))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (log 1))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (atan 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (tanh 0))")).isEqualTo("0.0");
	}

	@Test
	void compileAndRunMathAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'sqrt (list 1 4 9)))")).isEqualTo("(1.0 2.0 3.0)");
		assertThat(compileAndRun("(print (reduce #'gcd (list 24 36 48)))")).isEqualTo("12");
		assertThat(compileAndRun("(print (eval (list (quote expt) 2 8)))")).isEqualTo("256");
		assertThat(compileAndRun("(print (eval (list (quote sin) 0)))")).isEqualTo("0.0");
	}

	@Test
	void compileAndRunUnless() throws Exception {
		assertThat(compileAndRun("(print (unless nil 42))")).isEqualTo("42");
		assertThat(compileAndRun("(print (unless t 42))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunWhile() throws Exception {
		assertThat(compileAndRun("(print (let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s))"))
			.isEqualTo("10");
		assertThat(compileAndRun("(print (let ((n 0)) (while nil (setq n 99)) n))")).isEqualTo("0");
	}

	@Test
	void compileAndRunDotimes() throws Exception {
		assertThat(compileAndRun("(print (let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s))")).isEqualTo("10");
		assertThat(compileAndRun("(print (dotimes (i 3)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2)))))")).isEqualTo("16");
		assertThat(compileAndRun("(print (let ((s 7)) (dotimes (i 0) (setq s 0)) s))")).isEqualTo("7");
		assertThat(compileAndRun("(print (let ((s 0)) (dotimes (i 3) (dotimes (j 2) (setq s (+ s 1)))) s))"))
			.isEqualTo("6");
	}

	@Test
	void compileAndRunProg1() throws Exception {
		assertThat(compileAndRun("(print (prog1 1 2 3))")).isEqualTo("1");
		assertThat(compileAndRun("(print (prog1 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (let ((x (list 1 2 3))) (prog1 (car x) (setq x (cdr x)))))")).isEqualTo("1");
	}

	@Test
	void compileAndRunFirst() throws Exception {
		assertThat(compileAndRun("(print (first '(1 2 3)))")).isEqualTo("1");
	}

	@Test
	void compileAndRunNth() throws Exception {
		assertThat(compileAndRun("(print (nth 0 '(1 2 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (nth 2 '(1 2 3)))")).isEqualTo("3");
	}

	@Test
	void compileAndRunNthcdr() throws Exception {
		assertThat(compileAndRun("(print (nthcdr 0 '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (nthcdr 2 '(1 2 3)))")).isEqualTo("(3)");
	}

	@Test
	void compileAndRunSecond() throws Exception {
		assertThat(compileAndRun("(print (second '(1 2 3)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunThird() throws Exception {
		assertThat(compileAndRun("(print (third '(1 2 3)))")).isEqualTo("3");
	}

	@Test
	void compileAndRunFourth() throws Exception {
		assertThat(compileAndRun("(print (fourth '(1 2 3 4)))")).isEqualTo("4");
	}

	@Test
	void compileAndRunCarCdrComposition() throws Exception {
		assertThat(compileAndRun("(print (cadr '(1 2 3)))")).isEqualTo("2");
		assertThat(compileAndRun("(print (caddr '(1 2 3)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (caar '((1 2) 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (cadddr '(1 2 3 4)))")).isEqualTo("4");
	}

	@Test
	void compileAndRunRplaca() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(rplaca x 10)
				(print (car x))
				""")).isEqualTo("10");
	}

	@Test
	void compileAndRunRplacd() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(rplacd x 20)
				(print (cdr x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfCar() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (car x) 10)
				(print (car x))
				""")).isEqualTo("10");
	}

	@Test
	void compileAndRunSetfCdr() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(setf (cdr x) 20)
				(print (cdr x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfNth() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (nth 1 x) 20)
				(print (nth 1 x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfSecond() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (second x) 20)
				(print (second x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfReturnsValue() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(print (setf (car x) 42))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqSameInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eq 1 1) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqDifferentInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eq 1 2) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEqSymbols() throws Exception {
		assertThat(compileAndRun("(print (if (eq 'foo 'foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqNilNil() throws Exception {
		assertThat(compileAndRun("(print (if (eq nil nil) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqNilAndValue() throws Exception {
		assertThat(compileAndRun("(print (if (eq nil 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEqlSameInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eql 3 3) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqlDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (if (eql 3 3.0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEqlSymbols() throws Exception {
		assertThat(compileAndRun("(print (if (eql 'foo 'foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqlAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'eql 5 5))")).isEqualTo("T");
	}

	@Test
	void compileAndRunEqlFloatsByValue() throws Exception {
		assertThat(compileAndRun("(print (eql 1.5 1.5))")).isEqualTo("T");
	}

	@Test
	void compileAndRunEqFloatsNotEq() throws Exception {
		assertThat(compileAndRun("(print (eq 1.5 1.5))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunEqIntegersStillEq() throws Exception {
		assertThat(compileAndRun("(print (eq 3 3))")).isEqualTo("T");
	}

	@Test
	void compileAndRunEqualNestedLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2 (3)) '(1 2 (3))))")).isEqualTo("T");
	}

	@Test
	void compileAndRunEqualDifferentLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2) '(1 3)))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunEqualStrings() throws Exception {
		assertThat(compileAndRun("(print (equal \"abc\" \"abc\"))")).isEqualTo("T");
	}

	@Test
	void compileAndRunEqualDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (equal 3 3.0))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunEqualFreshConsesUnlikeEql() throws Exception {
		assertThat(compileAndRun("(print (eql (list 1 2) (list 1 2)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (equal (list 1 2) (list 1 2)))")).isEqualTo("T");
	}

	@Test
	void compileAndRunEqualAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'equal '(1) '(1)))")).isEqualTo("T");
	}

	@Test
	void compileAndRunPush() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 2 3))
				(push 1 x)
				(print x)
				""")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunPop() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(print (pop x))
				(print x)
				""")).isEqualTo("1\n(2 3)");
	}

	@Test
	void compileAndRunRemfHead() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'a)
				(print plist)
				""")).isEqualTo("(B 2 C 3)");
	}

	@Test
	void compileAndRunRemfMiddle() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'b)
				(print plist)
				""")).isEqualTo("(A 1 C 3)");
	}

	@Test
	void compileAndRunRemfTail() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'c)
				(print plist)
				""")).isEqualTo("(A 1 B 2)");
	}

	@Test
	void compileAndRunRemfNotFound() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2))
				(print (if (remf plist 'z) 42 99))
				""")).isEqualTo("99");
	}

	@Test
	void compileAndRunRemfEmpty() throws Exception {
		assertThat(compileAndRun("""
				(setq plist nil)
				(print (if (remf plist 'a) 42 99))
				""")).isEqualTo("99");
	}

	@Test
	void compileAndRunKeywordPrint() throws Exception {
		assertThat(compileAndRun("(print :foo)")).isEqualTo(":FOO");
	}

	@Test
	void compileAndRunKeywordEq() throws Exception {
		assertThat(compileAndRun("(print (if (eq :foo :foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (eq :foo :bar) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunKeywordp() throws Exception {
		assertThat(compileAndRun("(print (if (keywordp :foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (keywordp 'foo) 42 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (if (keywordp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunKeywordSymbolp() throws Exception {
		assertThat(compileAndRun("(print (if (symbolp :foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunReduceWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (reduce #'+ '(1 2 3 4 5) :initial-value 0))")).isEqualTo("15");
	}

	@Test
	void compileAndRunReduceWithBuiltinMul() throws Exception {
		assertThat(compileAndRun("(print (reduce #'* '(1 2 3 4 5) :initial-value 1))")).isEqualTo("120");
	}

	@Test
	void compileAndRunReduceFromEnd() throws Exception {
		assertThat(compileAndRun("(print (reduce #'- '(1 2 3 4) :from-end t))")).isEqualTo("-2");
	}

	@Test
	void compileAndRunReduceFromEndWithInitialValue() throws Exception {
		assertThat(compileAndRun("(print (reduce #'cons '(1 2 3) :from-end t :initial-value nil))"))
			.isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunReduceKey() throws Exception {
		assertThat(compileAndRun("(print (reduce #'+ '((1) (2) (3)) :key #'car))")).isEqualTo("6");
	}

	@Test
	void compileAndRunReduceKeyAndFromEnd() throws Exception {
		assertThat(compileAndRun("(print (reduce #'cons '((1) (2) (3)) :initial-value nil :from-end t :key #'car))"))
			.isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunRest() throws Exception {
		assertThat(compileAndRun("(print (rest '(1 2 3))) (print (rest '(1)))")).isEqualTo("(2 3)\nNIL");
	}

	@Test
	void compileAndRunSetfRestPlace() throws Exception {
		assertThat(compileAndRun("(setq l (list 1 2 3)) (setf (rest l) '(9)) (print l)")).isEqualTo("(1 9)");
	}

	@Test
	void compileAndRunRestInsideEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(rest '(1 2 3))))")).isEqualTo("(2 3)");
	}

	@Test
	void compileAndRunLetStar() throws Exception {
		assertThat(compileAndRun("(print (let* ((x 2) (y (* x 3))) (+ x y)))")).isEqualTo("8");
	}

	@Test
	void compileAndRunDolist() throws Exception {
		assertThat(compileAndRun("(setq s 0) (dolist (e '(1 2 3 4)) (setq s (+ s e))) (print s)")).isEqualTo("10");
	}

	@Test
	void compileAndRunCaseSingleKey() throws Exception {
		assertThat(compileAndRun("(print (case 2 (1 'one) (2 'two) (3 'three)))")).isEqualTo("TWO");
	}

	@Test
	void compileAndRunCaseKeyList() throws Exception {
		assertThat(compileAndRun("(print (case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("SMALL");
	}

	@Test
	void compileAndRunCaseOtherwise() throws Exception {
		assertThat(compileAndRun("(print (case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("BIG");
	}

	@Test
	void compileAndRunCaseNoMatchReturnsNil() throws Exception {
		assertThat(compileAndRun("(print (case 5 (1 'a) (2 'b)))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunDolistResultForm() throws Exception {
		assertThat(compileAndRun("(print (dolist (e '(1 2) 99)))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDo() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1)) (s 0)) ((= i 5) s) (setq s (+ s i))))")).isEqualTo("10");
	}

	@Test
	void compileAndRunDoParallelStep() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1)) (a 0 b) (b 1 (+ a b))) ((= i 10) a)))")).isEqualTo("55");
	}

	@Test
	void compileAndRunReturnFromDolist() throws Exception {
		assertThat(compileAndRun("(print (dolist (m '(2 3 5) t) (if (= m 3) (return))))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunReturnWithValue() throws Exception {
		assertThat(compileAndRun("(print (dotimes (i 5 -1) (if (evenp i) (return i))))")).isEqualTo("0");
	}

	@Test
	void compileAndRunReturnFromDo() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1))) ((> i 100) -1) (if (= i 4) (return i))))")).isEqualTo("4");
	}

	@Test
	void compileAndRunReturnExitsInnermostLoopOnly() throws Exception {
		assertThat(compileAndRun("""
				(setq total 0)
				(dolist (a '(1 2 3))
				  (dolist (b '(10 20 30))
				    (if (= b 20) (return))
				    (setq total (+ total b))))
				(print total)""")).isEqualTo("30");
	}

	@Test
	void compileAndRunIncfDecf() throws Exception {
		assertThat(compileAndRun("(setq n 10) (incf n) (incf n 5) (decf n 6) (print n)")).isEqualTo("10");
	}

	@Test
	void compileAndRunIncfPlace() throws Exception {
		assertThat(compileAndRun("(setq l (list 1 2 3)) (incf (cadr l)) (print l)")).isEqualTo("(1 3 3)");
	}

	@Test
	void compileAndRunLength() throws Exception {
		assertThat(compileAndRun("(print (length '(1 2 3 4 5))) (print (length nil))")).isEqualTo("5\n0");
	}

	@Test
	void compileAndRunReverse() throws Exception {
		assertThat(compileAndRun("(print (reverse '(1 2 3))) (print (reverse nil))")).isEqualTo("(3 2 1)\nNIL");
	}

	@Test
	void compileAndRunMember() throws Exception {
		assertThat(compileAndRun("(print (member 3 '(1 2 3 4))) (print (member 9 '(1 2 3)))")).isEqualTo("(3 4)\nNIL");
	}

	@Test
	void compileAndRunMemberWithTest() throws Exception {
		assertThat(compileAndRun("(print (member '(a d) '((a b) (a c) (a d) (a e)) :test 'equal)) "
				+ "(print (member '(a d) '((a b) (a c) (a d) (a e))))"))
			.isEqualTo("((A D) (A E))\nNIL");
	}

	@Test
	void compileAndRunFind() throws Exception {
		assertThat(compileAndRun(
				"(print (find 3 '(1 2 3 4))) (print (find 9 '(1 2 3))) (print (funcall #'find 2 '(1 2 3)))"))
			.isEqualTo("3\nNIL\n2");
	}

	@Test
	void compileAndRunFindIf() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if #'evenp '(1 3 5 6 7))) (print (find-if #'oddp '(2 4 6))) (print (funcall #'find-if #'plusp '(-1 -2 3 4)))"))
			.isEqualTo("6\nNIL\n3");
	}

	@Test
	void compileAndRunFindIfNot() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if-not #'evenp '(2 4 5 6))) (print (find-if-not #'plusp '(1 2 3))) (print (funcall #'find-if-not #'oddp '(1 3 4)))"))
			.isEqualTo("5\nNIL\n4");
	}

	@Test
	void compileAndRunPosition() throws Exception {
		assertThat(compileAndRun(
				"(print (position 3 '(1 2 3 4))) (print (position 9 '(1 2 3))) (print (funcall #'position 2 '(5 2 8)))"))
			.isEqualTo("2\nNIL\n1");
	}

	@Test
	void compileAndRunPositionOnString() throws Exception {
		assertThat(compileAndRun(
				"(print (position #\\space \"hello world\")) (print (position #\\z \"abc\")) (print (funcall #'position #\\l \"hello\"))"))
			.isEqualTo("5\nNIL\n2");
	}

	@Test
	void compileAndRunScanFunctionsOnStrings() throws Exception {
		assertThat(compileAndRun("""
				(print (find #\\l "hello"))
				(print (find-if #'digit-char-p "ab3c"))
				(print (find-if-not #'digit-char-p "12a3"))
				(print (position-if #'digit-char-p "ab3c"))
				(print (count #\\a "banana"))
				(print (count-if #'digit-char-p "a1b2"))
				(print (every #'digit-char-p "12a"))
				(print (some #'digit-char-p "abc1"))
				(print (notany #'digit-char-p "ab1"))
				(print (notevery #'digit-char-p "12a"))
				(print (reduce (lambda (acc c) (if (char= c #\\a) (+ acc 1) acc)) "banana" :initial-value 0))"""))
			.isEqualTo("#\\l\n#\\3\n#\\a\n2\n3\n2\nNIL\n1\nNIL\nT\n3");
	}

	@Test
	void compileAndRunSequenceReturningFunctionsOnStrings() throws Exception {
		assertThat(compileAndRun("""
				(print (reverse "abc"))
				(print (remove #\\l "hello"))
				(print (remove-if #'digit-char-p "a1b2"))
				(print (remove-if-not #'digit-char-p "a1b2"))
				(print (remove-duplicates "banana"))
				(print (substitute #\\o #\\a "banana"))
				(print (sort "cab" #'char<))
				(print (funcall #'reverse "abc"))"""))
			.isEqualTo("\"cba\"\n\"heo\"\n\"ab\"\n\"12\"\n\"bna\"\n\"bonono\"\n\"abc\"\n\"cba\"");
	}

	@Test
	void compileAndRunPositionIf() throws Exception {
		assertThat(compileAndRun(
				"(print (position-if #'evenp '(1 3 5 6 7))) (print (position-if #'plusp '(-1 -2 -3))) (print (funcall #'position-if #'oddp '(2 4 5)))"))
			.isEqualTo("3\nNIL\n2");
	}

	@Test
	void compileAndRunCount() throws Exception {
		assertThat(compileAndRun(
				"(print (count 2 '(1 2 3 2 2))) (print (count 9 '(1 2 3))) (print (funcall #'count 2 '(2 2 8)))"))
			.isEqualTo("3\n0\n2");
	}

	@Test
	void compileAndRunCountIf() throws Exception {
		assertThat(compileAndRun(
				"(print (count-if #'evenp '(1 2 3 4 5 6))) (print (count-if #'oddp '(2 4 6))) (print (funcall #'count-if #'evenp '(2 2 8 1)))"))
			.isEqualTo("3\n0\n3");
	}

	@Test
	void compileAndRunAssoc() throws Exception {
		assertThat(compileAndRun("(print (assoc 'b '((a 1) (b 2) (c 3)))) (print (assoc 'z '((a 1))))"))
			.isEqualTo("(B 2)\nNIL");
	}

	@Test
	void compileAndRunAssocOnDottedAlistLiteral() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc 'b '((a . 1) (b . 2) (c . 3)))) (print (cdr (assoc 'b '((a . 1) (b . 2)))))"))
			.isEqualTo("(B . 2)\n2");
	}

	@Test
	void compileAndRunAssocWithTest() throws Exception {
		assertThat(compileAndRun("(print (assoc \"b\" '((\"a\" . 1) (\"b\" . 2)) :test #'equal)) "
				+ "(print (assoc \"z\" '((\"a\" . 1)) :test 'equal)) (print (funcall #'assoc 'b '((a . 1) (b . 2))))"))
			.isEqualTo("(\"b\" . 2)\nNIL\n(B . 2)");
	}

	@Test
	void compileAndRunRassocWithTest() throws Exception {
		assertThat(compileAndRun("(print (rassoc \"x\" '((a . \"w\") (b . \"x\")) :test #'equal)) "
				+ "(print (rassoc \"z\" '((a . \"w\")) :test 'equal)) (print (funcall #'rassoc 2 '((a . 1) (b . 2))))"))
			.isEqualTo("(B . \"x\")\nNIL\n(B . 2)");
	}

	@Test
	void compileAndRunAssocWithKey() throws Exception {
		assertThat(compileAndRun("(print (assoc 2 '((1 . a) (2 . b) (3 . c)) :key (lambda (k) (+ k 1)))) "
				+ "(print (member 3 '((1 2) (3 4) (5 6)) :key #'car)) "
				+ "(print (rassoc 2 '((a . 1) (b . 3)) :key (lambda (v) (- v 1))))"))
			.isEqualTo("(1 . A)\n((3 4) (5 6))\n(B . 3)");
	}

	@Test
	void compileAndRunSequenceFunctionsWithTest() throws Exception {
		assertThat(compileAndRun("(print (find \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (position \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (count \"a\" '(\"a\" \"b\" \"a\") :test #'string=)) "
				+ "(print (remove \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (delete \"b\" (list \"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (remove-duplicates '(\"a\" \"b\" \"a\" \"c\") :test #'string=)) "
				+ "(print (substitute \"X\" \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (nsubstitute \"X\" \"b\" (list \"a\" \"b\") :test #'string=)) "
				+ "(print (union '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)) "
				+ "(print (intersection '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)) "
				+ "(print (set-difference '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)) "
				+ "(print (adjoin \"a\" '(\"a\" \"b\") :test #'string=)) "
				+ "(print (adjoin \"z\" '(\"a\" \"b\") :test #'string=))"))
			.isEqualTo("\"b\"\n1\n2\n(\"a\" \"c\")\n(\"a\" \"c\")\n(\"b\" \"a\" \"c\")\n(\"a\" \"X\" \"c\")\n"
					+ "(\"a\" \"X\")\n(\"c\" \"a\" \"b\")\n(\"b\")\n(\"a\")\n(\"a\" \"b\")\n(\"z\" \"a\" \"b\")");
	}

	@Test
	void compileAndRunSequenceFunctionsWithKey() throws Exception {
		assertThat(compileAndRun("(print (find 4 '((1 2) (3 4)) :key #'cadr)) "
				+ "(print (position 3 '(1 2 3 4) :key (lambda (x) (- x 1)))) "
				+ "(print (count 2 '((1) (2) (2) (3)) :key #'car)) "
				+ "(print (remove 1 '((1 a) (2 b) (1 c)) :key #'car)) "
				+ "(print (delete 1 (list '(1 a) '(2 b)) :key #'car)) "
				+ "(print (remove-duplicates '((1 a) (2 b) (1 c)) :key #'car)) "
				+ "(print (substitute 'x 2 '((1) (2) (3)) :key #'car)) "
				+ "(print (nsubstitute 'x 2 (list '(1) '(2)) :key #'car)) "
				+ "(print (union '((1)) '((1) (2)) :test #'equal :key #'car)) "
				+ "(print (intersection '((1) (2)) '((2) (3)) :key #'car)) "
				+ "(print (set-difference '((1) (2)) '((2) (3)) :key #'car)) "
				+ "(print (adjoin '(1 x) '((1 a) (2 b)) :key #'car))"))
			.isEqualTo("(3 4)\n3\n2\n((2 B))\n((2 B))\n((2 B) (1 C))\n((1) X (3))\n((1) X)\n((2) (1))\n"
					+ "((2))\n((1))\n((1 A) (2 B))");
	}

	@Test
	void compileAndRunAconsAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'acons 'a 1 '((b . 2))))")).isEqualTo("((A . 1) (B . 2))");
	}

	@Test
	void compileAndRunQuotedCharacterList() throws Exception {
		assertThat(compileAndRun("(print '(#\\a #\\b)) (print (char= (car '(#\\a #\\b)) #\\a)) (print '(#\\a . #\\b))"))
			.isEqualTo("(#\\a #\\b)\nT\n(#\\a . #\\b)");
	}

	@Test
	void compileImproperCallFormFails() {
		assertThatThrownBy(() -> compileAndRun("(+ 1 . 2)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Improper list in call position");
		assertThatThrownBy(() -> compileAndRun("(print (list 1 . 2))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Improper list in call position");
	}

	@Test
	void compileAndRunPairlis() throws Exception {
		assertThat(compileAndRun("(print (pairlis '(a b c) '(1 2 3))) (print (pairlis '(a b) '(1 2) '((c . 3)))) "
				+ "(print (pairlis nil nil)) (print (pairlis '(a b) '(1))) (print (funcall #'pairlis '(a) '(1)))"))
			.isEqualTo("((A . 1) (B . 2) (C . 3))\n((A . 1) (B . 2) (C . 3))\nNIL\n((A . 1))\n((A . 1))");
	}

	@Test
	void compileAndRunCopyAlist() throws Exception {
		assertThat(compileAndRun("""
				(print (copy-alist '((a . 1) (b . 2))))
				(print (copy-alist nil))
				(let* ((orig (list (cons 'a 1) (cons 'b 2)))
				       (copy (copy-alist orig)))
				  (rplacd (assoc 'a copy) 99)
				  (print (cdr (assoc 'a orig))))
				(print (funcall #'copy-alist '((a . 1))))""")).isEqualTo("((A . 1) (B . 2))\nNIL\n1\n((A . 1))");
	}

	@Test
	void compileAndRunLast() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3))) (print (last nil))")).isEqualTo("(3)\nNIL");
	}

	@Test
	void compileAndRunMemberIf() throws Exception {
		assertThat(compileAndRun(
				"(print (member-if #'oddp '(2 4 5 6))) (print (member-if #'evenp '(1 3 5))) (print (funcall #'member-if #'plusp '(-1 3 4)))"))
			.isEqualTo("(5 6)\nNIL\n(3 4)");
	}

	@Test
	void compileAndRunAssocIf() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc-if #'oddp '((2 a) (3 b) (5 c)))) (print (assoc-if #'evenp '((1 a) (3 b)))) (print (funcall #'assoc-if #'plusp '((-1 a) (2 b))))"))
			.isEqualTo("(3 B)\nNIL\n(2 B)");
	}

	@Test
	void compileAndRunGetf() throws Exception {
		assertThat(compileAndRun(
				"(print (getf '(:a 1 :b 2) :b)) (print (getf '(:a 1) :x)) (print (funcall #'getf '(:x 10 :y 20) :y))"))
			.isEqualTo("2\nNIL\n20");
	}

	@Test
	void compileAndRunRemoveDuplicates() throws Exception {
		assertThat(compileAndRun(
				"(print (remove-duplicates '(1 2 1 3))) (print (remove-duplicates '(1 2 3))) (print (funcall #'remove-duplicates '(a b a a c)))"))
			.isEqualTo("(2 1 3)\n(1 2 3)\n(B A C)");
	}

	@Test
	void compileAndRunButlast() throws Exception {
		assertThat(compileAndRun(
				"(print (butlast '(1 2 3))) (print (butlast '(1))) (print (butlast nil)) (print (funcall #'butlast '(a b c d)))"))
			.isEqualTo("(1 2)\nNIL\nNIL\n(A B C)");
	}

	@Test
	void compileAndRunNconc() throws Exception {
		assertThat(compileAndRun(
				"(print (nconc (list 1 2) (list 3 4))) (print (nconc nil (list 1 2))) (print (funcall #'nconc (list 'a) (list 'b 'c)))"))
			.isEqualTo("(1 2 3 4)\n(1 2)\n(A B C)");
	}

	@Test
	void compileAndRunIdentity() throws Exception {
		assertThat(compileAndRun("(print (identity 42)) (print (identity '(1 2 3))) (print (funcall #'identity 'x))"))
			.isEqualTo("42\n(1 2 3)\nX");
	}

	@Test
	void compileAndRunCopyList() throws Exception {
		assertThat(compileAndRun(
				"(print (copy-list '(1 2 3))) (print (copy-list nil)) (print (funcall #'copy-list '(a b)))"))
			.isEqualTo("(1 2 3)\nNIL\n(A B)");
	}

	@Test
	void compileAndRunNreverse() throws Exception {
		assertThat(compileAndRun(
				"(print (nreverse '(1 2 3))) (print (nreverse nil)) (print (funcall #'nreverse '(a b c)))"))
			.isEqualTo("(3 2 1)\nNIL\n(C B A)");
	}

	@Test
	void compileAndRunMakeList() throws Exception {
		assertThat(compileAndRun("(print (make-list 3)) (print (make-list 0)) (print (funcall #'make-list 2))"))
			.isEqualTo("(NIL NIL NIL)\nNIL\n(NIL NIL)");
	}

	@Test
	void compileAndRunUnion() throws Exception {
		assertThat(compileAndRun(
				"(print (union '(1 2 3) '(2 3 4))) (print (union nil '(1 2))) (print (funcall #'union '(a) '(a b)))"))
			.isEqualTo("(4 1 2 3)\n(2 1)\n(B A)");
	}

	@Test
	void compileAndRunIntersection() throws Exception {
		assertThat(compileAndRun(
				"(print (intersection '(1 2 3) '(2 3 4))) (print (intersection '(1 2) '(3 4))) (print (funcall #'intersection '(a b c) '(b c d)))"))
			.isEqualTo("(3 2)\nNIL\n(C B)");
	}

	@Test
	void compileAndRunSetDifference() throws Exception {
		assertThat(compileAndRun(
				"(print (set-difference '(1 2 3) '(2))) (print (set-difference '(1 2 3) '(1 2 3))) (print (funcall #'set-difference '(a b c) '(b)))"))
			.isEqualTo("(3 1)\nNIL\n(C A)");
	}

	@Test
	void compileAndRunAdjoin() throws Exception {
		assertThat(compileAndRun(
				"(print (adjoin 1 '(2 3))) (print (adjoin 2 '(1 2 3))) (print (adjoin 'a nil)) (print (funcall #'adjoin 5 '(5 6)))"))
			.isEqualTo("(1 2 3)\n(1 2 3)\n(A)\n(5 6)");
	}

	@Test
	void compileAndRunBitwiseOps() throws Exception {
		assertThat(compileAndRun(
				"(print (logand 12 10)) (print (logior 12 10)) (print (logxor 12 10)) (print (lognot 5)) (print (ash 1 4)) (print (ash 255 -4))"))
			.isEqualTo("8\n14\n6\n-6\n16\n15");
	}

	@Test
	void compileAndRunBitwiseVariadicAndFirstClass() throws Exception {
		assertThat(compileAndRun(
				"(print (logand 12 10 6)) (print (logior 1 2 4 8)) (print (funcall #'logand 6 3)) (print (funcall #'lognot 0))"))
			.isEqualTo("0\n15\n2\n-1");
	}

	@Test
	void compileAndRunIntegerLengthAndLogbitp() throws Exception {
		assertThat(compileAndRun(
				"(print (integer-length 0)) (print (integer-length 5)) (print (integer-length 255)) (print (integer-length -1)) (print (integer-length -5))"))
			.isEqualTo("0\n3\n8\n0\n3");
		assertThat(compileAndRun(
				"(print (logbitp 0 5)) (print (logbitp 1 5)) (print (logbitp 2 5)) (print (logbitp 3 -1)) (print (funcall #'integer-length 8)) (print (funcall #'logbitp 1 2))"))
			.isEqualTo("T\nNIL\nT\nT\n4\nT");
	}

	@Test
	void compileAndRunByteFieldOps() throws Exception {
		assertThat(compileAndRun(
				"(print (byte-size (byte 8 3))) (print (byte-position (byte 8 3))) (print (ldb (byte 8 0) 255)) (print (ldb (byte 4 4) 255)) (print (ldb (byte 8 8) 65535))"))
			.isEqualTo("8\n3\n255\n15\n255");
		assertThat(compileAndRun(
				"(print (dpb 0 (byte 4 0) 255)) (print (dpb 5 (byte 4 4) 0)) (print (funcall #'ldb (byte 4 4) 255)) (print (funcall #'dpb 0 (byte 4 0) 255)) (print (funcall #'byte-size (byte 6 2)))"))
			.isEqualTo("240\n80\n15\n240\n6");
	}

	@Test
	void compileAndRunListStarAndAcons() throws Exception {
		assertThat(compileAndRun(
				"(print (list* 1 2 '(3 4))) (print (list* 1 2 3)) (print (list* 'x)) (print (acons 'a 1 nil))"))
			.isEqualTo("(1 2 3 4)\n(1 2 . 3)\nX\n((A . 1))");
	}

	@Test
	void compileAndRunEltEndpRassoc() throws Exception {
		assertThat(compileAndRun(
				"(print (elt '(a b c) 1)) (print (endp nil)) (print (endp '(1))) (print (rassoc 2 (list (cons 'a 1) (cons 'b 2))))"))
			.isEqualTo("B\nT\nNIL\n(B . 2)");
	}

	@Test
	void compileAndRunRevappendMaplistMapcon() throws Exception {
		assertThat(compileAndRun(
				"(print (revappend '(1 2 3) '(4 5))) (print (nreconc '(1 2 3) '(4 5))) (print (maplist #'identity '(1 2 3))) (print (mapcon #'(lambda (x) (list (car x))) '(1 2 3)))"))
			.isEqualTo("(3 2 1 4 5)\n(3 2 1 4 5)\n((1 2 3) (2 3) (3))\n(1 2 3)");
	}

	@Test
	void compileAndRunNotanyNotevery() throws Exception {
		assertThat(compileAndRun(
				"(print (notany #'evenp '(1 3 5))) (print (notany #'evenp '(1 2 3))) (print (notevery #'evenp '(2 4 5))) (print (notevery #'evenp '(2 4 6)))"))
			.isEqualTo("T\nNIL\nT\nNIL");
	}

	@Test
	void compileAndRunProg2Psetq() throws Exception {
		assertThat(compileAndRun("(print (prog2 1 2 3)) (print (let ((a 1) (b 2)) (psetq a b b a) (list a b)))"))
			.isEqualTo("2\n(2 1)");
	}

	@Test
	void compileAndRunNamedBlockReturnFrom() throws Exception {
		// Lexical named blocks: the return-from crosses the dotimes loop's %block
		// (which does not catch the named exit) straight to the named block, so the
		// after-loop code never runs -- matching the interpreter.
		assertThat(compileAndRun("""
				(print (block scan
				         (dotimes (i 10)
				           (when (= i 4) (return-from scan (* i 100))))
				         :fell-through))
				(print (block nil (return 7) 9))
				(print (block direct (return-from direct 42) 9))
				""")).isEqualTo("400\n7\n42");
	}

	@Test
	void compileAndRunReturnFromExitsDefunAcrossLoop() throws Exception {
		// The %fn-block function boundary: a return-from naming the defun exits the
		// function from inside a loop whose after-loop code must not run.
		assertThat(compileAndRun("""
				(defun find-first-even (xs)
				  (dolist (x xs)
				    (when (evenp x) (return-from find-first-even x)))
				  :none)
				(print (find-first-even '(1 3 6 7)))
				(print (find-first-even '(1 3 5)))
				(defun nested-blocks ()
				  (block outer
				    (block inner
				      (return-from outer :from-inner))
				    :unreachable))
				(print (nested-blocks))
				""")).isEqualTo("6\n:NONE\n:FROM-INNER");
	}

	@Test
	void compileAndRunReturnFromInsideLambdaExitsOuterDefun() throws Exception {
		// A return-from inside a lambda whose name matches an enclosing defun exits that
		// defun as a non-local exit -- matching the interpreter and CL. The lowering keys
		// on a dynamic block-instance id the lambda closes over, thrown to the
		// establishing
		// block's %nlx-catch. (Was a pinned compile-path deviation that stayed
		// lambda-local.)
		assertThat(compileAndRun("""
				(defun probe (xs)
				  (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs))
				(print (probe '(1 2 3)))
				""")).isEqualTo(":EVEN");
	}

	@Test
	void compileAndRunCrossLambdaReturnFromMidExpression() throws Exception {
		// The cross-lambda exit value is consumed mid-expression: the abandoned outer
		// (list :head ... :tail) operands are discarded, like a plain return.
		assertThat(compileAndRun("""
				(defun probe (xs)
				  (list :head (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs) :tail))
				(print (probe '(1 2 3)))
				(print (probe '(1 3 5)))
				""")).isEqualTo(":EVEN\n(:HEAD (1 3 5) :TAIL)");
	}

	@Test
	void compileAndRunCrossLambdaReturnFromRecursiveTargetsCorrectFrame() throws Exception {
		// A recursive defun whose lambda does a cross-lambda return-from: the dynamic
		// block-instance id targets the innermost active frame, so the exit unwinds one
		// activation, not the whole recursion.
		assertThat(compileAndRun("""
				(defun deep (n)
				  (if (= n 0)
				      :bottom
				      (block done
				        (mapcar #'(lambda (x) (return-from done (list n (deep (- n 1)) x))) '(:mark))
				        :unreached)))
				(print (deep 3))
				""")).isEqualTo("(3 (2 (1 :BOTTOM :MARK) :MARK) :MARK)");
	}

	@Test
	void compileAndRunCrossLambdaExitToleratesBlockAsVariableName() throws Exception {
		// A cons headed by `block`/`return-from` in a non-operator position -- here a let
		// binding whose variable is named `block` (md5 does this) -- must not be
		// mis-parsed
		// as the special form; the cross-lambda return-from referencing it still works.
		assertThat(compileAndRun("""
				(defun uses-block-as-var (xs)
				  (let ((block 10) (return-from 20))
				    (mapcar #'(lambda (x)
				                (if (evenp x)
				                    (return-from uses-block-as-var (+ x block return-from))
				                    x))
				            xs)))
				(print (uses-block-as-var '(1 2 3)))
				(print (uses-block-as-var '(1 3 5)))
				""")).isEqualTo("32\n(1 3 5)");
	}

	@Test
	void compileAndRunCrossLambdaReturnFromUserBlockAndFlet() throws Exception {
		// A cross-lambda return-from to a user (block name ...) in argument position
		// (spilling), plus one that also crosses an flet local function holding the
		// lambda
		// (the id is captured through both closure levels).
		assertThat(compileAndRun("""
				(defun f (v)
				  (+ 100 (block b (mapcar #'(lambda (x) (if (evenp x) (return-from b x) 0)) v) 7)))
				(print (f '(1 4 5)))
				(print (f '(1 3 5)))
				(defun g (xs)
				  (flet ((h () (mapcar #'(lambda (x) (return-from g (* x 10))) xs)))
				    (h)))
				(print (g '(3 4)))
				""")).isEqualTo("104\n107\n30");
	}

	@Test
	void compileAndRunCrossLambdaReturnFromDoesNotLeakHandlerDepth() throws Exception {
		// A cross-lambda return-from unwinding through a handler-case must leave the
		// handler depth balanced, so a later unhandled signal still yields nil.
		assertThat(compileAndRun("""
				(defun leak (xs)
				  (handler-case
				      (mapcar #'(lambda (x) (return-from leak :exited)) xs)
				    (error (e) :caught)))
				(print (leak '(1)))
				(print (signal "quiet"))
				""")).isEqualTo(":EXITED\nNIL");
	}

	@Test
	void compileAndRunCrossLambdaReturnFromThroughHandlerCase() throws Exception {
		// A cross-lambda return-from unwinding through a handler-case is NOT intercepted
		// by it (it is a non-local exit, not a signaled condition) -- the handler-case
		// depth/spill machinery must let it pass.
		assertThat(compileAndRun("""
				(defun probe (xs)
				  (handler-case
				      (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs)
				    (error (e) :caught)))
				(print (probe '(1 2 3)))
				(print (probe '(1 3 5)))
				""")).isEqualTo(":EVEN\n(1 3 5)");
	}

	@Test
	void compileAndRunGoInsideLambdaReentersOuterTagbody() throws Exception {
		// A go whose tag belongs to a tagbody in the ENCLOSING function -- the shape a
		// handler-bind handler resuming its loop produces (quri's :lenient
		// percent-decoding). The lowering throws the target label's re-entry index to
		// the tagbody's catch, which re-dispatches into the tagbody at that label and
		// carries on. (Was a call-time "no lexically enclosing tagbody" error.)
		assertThat(compileAndRun("""
				(define-condition my-err (error) ())
				(defun f (x)
				  (tagbody
				   top
				     (handler-bind ((my-err (lambda (e) (declare (ignore e)) (go done))))
				       (when (> x 0) (error 'my-err))
				       (princ "no-error"))
				   done)
				  :ok)
				(print (f 1))
				(print (f 0))
				""")).isEqualTo(":OK\nno-error:OK");
	}

	@Test
	void compileAndRunCrossLambdaGoResumesAndLoops() throws Exception {
		// The re-entry keeps the tagbody running: a crossing go lands on its label and
		// falls through the rest of the body, and a BACKWARD one re-runs the loop.
		assertThat(compileAndRun("""
				(defun scan (items)
				  (let ((seen nil) (skipped 0))
				    (tagbody
				     start
				       (mapcar (lambda (x) (if (eq x :skip) (go bump)) (setq seen (cons x seen))) items)
				       (go done)
				     bump
				       (setq skipped (+ skipped 1))
				     done)
				    (list (reverse seen) skipped)))
				(print (scan '(1 2 3)))
				(print (scan '(1 :skip 3)))
				(defun countdown (n)
				  (let ((out nil))
				    (tagbody
				     again
				       (setq out (cons n out))
				       (setq n (- n 1))
				       (funcall (lambda () (if (> n 0) (go again)))))
				    (reverse out)))
				(print (countdown 4))
				""")).isEqualTo("((1 2 3) 0)\n((1) 1)\n(4 3 2 1)");
	}

	@Test
	void compileAndRunCrossLambdaGoRecursiveTargetsCorrectFrame() throws Exception {
		// The thrown id is the per-activation %nlx-tag lexical, so a recursive
		// function's inner activation cannot catch an outer one's go; an escaped
		// unwind-protect cleanup still runs on the way.
		assertThat(compileAndRun("""
				(defun rec (n)
				  (let ((hit nil))
					(tagbody
					 top
					   (if (= n 0) (go fin))
					   (setq hit (rec (- n 1)))
					   (funcall (lambda () (go fin)))
					   (setq hit :never)
					 fin)
					(list n hit)))
				(print (rec 2))
				(defun cleaned ()
				  (let ((log nil))
					(tagbody
					 top
					   (unwind-protect (funcall (lambda () (go out)))
						 (setq log (cons :cleanup log)))
					   (setq log (cons :never log))
					 out
					   (setq log (cons :out log)))
					(reverse log)))
				(print (cleaned))
				""")).isEqualTo("(2 (1 (0 NIL)))\n(:CLEANUP :OUT)");
	}

	@Test
	void compileAndRunCrossLambdaGoInProgKeepsTheReturnBlock() throws Exception {
		// prog establishes BOTH the tags a go targets and the nil block a (return)
		// exits: the re-entry loop replaces the body items, so both still work.
		assertThat(compileAndRun("""
				(defun via-prog (x)
				  (prog ((acc nil))
					 (funcall (lambda () (if (eq x :jump) (go later))))
					 (setq acc (cons :first acc))
					 (return (cons :early acc))
				   later
					 (setq acc (cons :later acc))
					 (return acc)))
				(print (via-prog :plain))
				(print (via-prog :jump))
				""")).isEqualTo("(:EARLY :FIRST)\n(:LATER)");
	}

	@Test
	void compileAndRunCatchThrow() throws Exception {
		// catch/throw: a DYNAMIC non-local exit -- the throw need not be lexically inside
		// the catch, only within its dynamic extent, and the tag is a runtime value
		// compared with eq. The innermost matching catcher wins; a non-matching one lets
		// the exit through.
		assertThat(compileAndRun("""
				(print (catch 'done (+ 1 2)))
				(print (catch 'done (throw 'done :thrown) :not-reached))
				(defun deep (n) (if (= n 0) (throw 'bottom :hit) (deep (- n 1))))
				(print (catch 'bottom (deep 5)))
				(print (catch 'outer (catch 'inner (throw 'outer :to-outer)) :not-reached))
				(print (list :a (catch 'k (throw 'k :b)) :c))
				(let ((tag (list 1)))
				  (print (catch tag (throw tag :computed-tag))))
				(print (catch 'outer (catch (list 1) (throw 'outer :not-eq-to-inner))))
				""")).isEqualTo("3\n:THROWN\n:HIT\n:TO-OUTER\n(:A :B :C)\n:COMPUTED-TAG\n:NOT-EQ-TO-INNER");
	}

	@Test
	void compileAndRunCatchThrowRunsUnwindProtectCleanups() throws Exception {
		// A throw unwinds the real stack, so every intervening unwind-protect cleanup
		// runs
		// -- innermost first -- before the catch delivers the value.
		assertThat(compileAndRun("""
				(let ((log nil))
				  (print (catch 'z
				           (unwind-protect
				               (unwind-protect (throw 'z :deep) (setq log (cons :inner log)))
				             (setq log (cons :outer log)))))
				  (print log))
				(print (catch 'c (unwind-protect :body (throw 'c :from-cleanup))))
				""")).isEqualTo(":DEEP\n(:OUTER :INNER)\n:FROM-CLEANUP");
	}

	@Test
	void compileAndRunThrowIsNotCaughtByHandlerCase() throws Exception {
		// A throw is a non-local exit, not a signaled condition: handler-case must let it
		// pass, and leave the handler depth balanced so a later unhandled signal still
		// yields nil.
		assertThat(compileAndRun("""
				(defun hc-throw (xs)
				  (handler-case (mapcar (lambda (x) (if (evenp x) (throw 'up :hit) x)) xs)
				    (error (e) :caught)))
				(print (catch 'up (hc-throw '(1 2 3))))
				(print (hc-throw '(1 3)))
				(print (signal "quiet"))
				(print (catch 'up (handler-case (error "boom") (error (e) :caught))))
				""")).isEqualTo(":HIT\n(1 3)\nNIL\n:CAUGHT");
	}

	@Test
	void compileAndRunCatchThrowAndCrossLambdaExitDoNotCollide() throws Exception {
		// catch/throw share the non-local-exit channel with a lowered cross-lambda
		// return-from, so each must pass through the other's landing pad untouched --
		// including when a fixnum catch tag numerically equals a live block-instance id
		// (on wasm those ids are i31 values compared with ref.eq).
		assertThat(compileAndRun("""
				(defun probe2 (xs)
				  (list :after (catch 1 (mapcar #'(lambda (x) (if (evenp x) (return-from probe2 :exited) x)) xs))))
				(print (probe2 '(1 2)))
				(defun probe3 (xs)
				  (list :from-probe
				        (mapcar #'(lambda (x) (if (evenp x) (return-from probe3 :nlx) (throw 1 :thrown))) xs)))
				(print (catch 1 (list :outer (probe3 '(1 2)))))
				""")).isEqualTo(":EXITED\n:THROWN");
	}

	@Test
	void compileAndRunCharComparisonExtensions() throws Exception {
		assertThat(compileAndRun("""
				(print (char> #\\c #\\b #\\a))
				(print (char>= #\\b #\\b #\\a))
				(print (char/= #\\a #\\b #\\a))
				(print (char-equal #\\A #\\a))
				(print (search "bc" "abcd"))
				(print (copy-tree '(1 (2 3))))
				""")).isEqualTo("T\nT\nNIL\nT\n1\n(1 (2 3))");
	}

	@Test
	void compileAndRunPsetf() throws Exception {
		// Place subforms are evaluated before any assignment: (cdr last-cdr) reads the
		// old last-cdr even though the first pair reassigns it.
		assertThat(compileAndRun("""
				(print (let ((a 1) (b 2)) (psetf a b b a) (list a b)))
				(print (let* ((tail (list 2))
				              (last-cdr tail)
				              (fresh (list 3)))
				         (psetf last-cdr fresh
				                (cdr last-cdr) fresh)
				         (list tail last-cdr)))
				""")).isEqualTo("(2 1)\n((2 3) (3))");
	}

	@Test
	void compileAndRunSubstAndSimpleStringP() throws Exception {
		assertThat(compileAndRun("""
				(print (subst 'x 'a '(a (b a) c)))
				(print (subst 9 '(m) '(f (m) g) :test #'equal))
				(print (simple-string-p "abc"))
				(print (simple-string-p 42))
				""")).isEqualTo("(X (B X) C)\n(F 9 G)\nT\nNIL");
	}

	@Test
	void compileAndRunClosReaderMethodsDispatchPerClass() throws Exception {
		// The same accessor name over different slot positions in unrelated classes,
		// plus a plain defmethod on a third class, merge into one generic; the write
		// side dispatches through the %setf- writer generic.
		assertThat(compileAndRun("""
				(defclass w1 () ((pad :initarg :pad) (size :initarg :size :accessor size)))
				(defclass w2 () ((size :initarg :size :accessor size)))
				(defclass w3 () ())
				(defmethod size ((w w3)) 0)
				(defvar *w2* (make-instance 'w2 :size 22))
				(setf (size *w2*) 23)
				(print (list (size (make-instance 'w1 :pad 9 :size 11)) (size *w2*) (size (make-instance 'w3))))
				""")).isEqualTo("(11 23 0)");
	}

	@Test
	void compileAndRunInitializeInstanceAfterMethod() throws Exception {
		assertThat(compileAndRun("""
				(defclass counted () ((n :initarg :n :accessor n)))
				(defmethod initialize-instance :after ((c counted) &rest init-args)
				  (setf (n c) (* 10 (n c))))
				(print (list (n (make-instance 'counted :n 4)) (n (make-instance 'counted :n 5))))
				""")).isEqualTo("(40 50)");
	}

	@Test
	void compileAndRunTypecase() throws Exception {
		assertThat(compileAndRun(
				"(print (typecase 42 (string \"s\") (integer \"i\") (t \"?\"))) (print (typecase \"x\" (string \"s\") (integer \"i\") (t \"?\"))) (print (typecase 'sym (string \"s\") (integer \"i\") (t \"?\")))"))
			.isEqualTo("\"i\"\n\"s\"\n\"?\"");
	}

	@Test
	void compileAndRunEcase() throws Exception {
		assertThat(compileAndRun(
				"(print (ecase 2 (1 \"one\") (2 \"two\") (3 \"three\"))) (print (ecase 'b ((a) \"A\") ((b c) \"BC\")))"))
			.isEqualTo("\"two\"\n\"BC\"");
		assertThatThrownBy(() -> compileAndRun("(print (ecase 9 (1 \"one\") (2 \"two\")))"))
			.hasRootCauseMessage("ECASE: no clause matches 9");
	}

	@Test
	void compileAndRunCcase() throws Exception {
		assertThat(compileAndRun("(print (ccase 1 (1 \"one\") (2 \"two\")))")).isEqualTo("\"one\"");
		assertThatThrownBy(() -> compileAndRun("(print (ccase 9 (1 \"one\")))"))
			.hasRootCauseMessage("ECASE: no clause matches 9");
	}

	@Test
	void compileAndRunEtypecase() throws Exception {
		assertThat(compileAndRun(
				"(print (etypecase 42 (string \"s\") (integer \"i\"))) (print (etypecase \"x\" (string \"s\") (integer \"i\")))"))
			.isEqualTo("\"i\"\n\"s\"");
		assertThatThrownBy(() -> compileAndRun("(print (etypecase 'sym (string \"s\") (integer \"i\")))"))
			.hasRootCauseMessage("ETYPECASE: no clause matches SYM");
	}

	@Test
	void compileAndRunEtypecaseInsideACapturingLambda() throws Exception {
		// The lack-middleware-backtrace shape: an etypecase whose clause heads include
		// (or pathname string), inside a lambda that captures the scrutinee. The
		// free/captured-variable analysis must read the clause HEADS as type
		// specifiers, not variable references -- it used to try to capture PATHNAME.
		assertThat(compileAndRun("""
				(defparameter *mw*
				  (lambda (app &key (output '*error-output*) result-on-error)
				    (check-type output (or symbol stream pathname string))
				    (flet ((classify ()
				             (etypecase output
				               (symbol :symbol)
				               (stream :stream)
				               ((or pathname string) :path))))
				      (lambda (env)
				        (list (classify) (funcall app env))))))
				(print (funcall (funcall *mw* (lambda (e) (* e 2))) 21))
				""")).isEqualTo("(:SYMBOL 42)");
	}

	@Test
	void compileAndRunError() throws Exception {
		assertThatThrownBy(() -> compileAndRun("(error \"boom\")")).hasRootCauseMessage("boom");
		assertThatThrownBy(() -> compileAndRun("(error \"bad value: ~a\" (+ 1 2))"))
			.hasRootCauseMessage("bad value: 3");
	}

	@Test
	void compileAndRunDeclarations() throws Exception {
		assertThat(compileAndRun("(declaim (optimize (speed 3))) (proclaim '(special *x*))"
				+ " (defun decl-fn (x) (declare (ignore x)) 42) (print (decl-fn 1))"
				+ " (print (let ((y 1)) (declare (type integer y)) (+ y 1)))"))
			.isEqualTo("42\n2");
	}

	@Test
	void compileAndRunThe() throws Exception {
		assertThat(compileAndRun("(print (the integer (+ 1 2))) (print (the (or null string) \"s\"))"))
			.isEqualTo("3\n\"s\"");
	}

	@Test
	void compileAndRunEvalWhen() throws Exception {
		assertThat(compileAndRun("(eval-when (:compile-toplevel :load-toplevel :execute)"
				+ " (defun ew-fn (x) (* x 2))) (print (ew-fn 21))"))
			.isEqualTo("42");
	}

	@Test
	void compileAndRunEvalWhenWrappedDefmacroThroughUserMacroExpander() throws Exception {
		// The CLI runs UserMacroExpander before the compiler; mirror that here so the
		// eval-when-wrapped defmacro is consumed at compile time.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander
			.expand(LispReader.readAllFromString("(eval-when (:compile-toplevel :load-toplevel :execute)"
					+ " (defmacro ew-twice (x) (list '+ x x))) (print (ew-twice 21))"));
		assertThat(compileAndRun(program)).isEqualTo("42");
	}

	@Test
	void compileAndRunCheckType() throws Exception {
		assertThat(compileAndRun("(let ((n 5)) (check-type n (integer 0 9)) (print \"ok\"))"
				+ " (let ((s \"x\")) (check-type s (or null string)) (print \"ok2\"))"))
			.isEqualTo("\"ok\"\n\"ok2\"");
		assertThatThrownBy(() -> compileAndRun("(let ((n \"5\")) (check-type n integer))"))
			.hasRootCauseMessage("The value of N is \"5\", which is not of type INTEGER.");
	}

	@Test
	void compileAndRunAssert() throws Exception {
		assertThat(compileAndRun("(assert (= 1 1)) (print \"ok\")")).isEqualTo("\"ok\"");
		assertThatThrownBy(() -> compileAndRun("(assert (= 1 2))"))
			.hasRootCauseMessage("The assertion (= 1 2) failed.");
		assertThatThrownBy(() -> compileAndRun("(let ((x 0)) (assert (> x 0) (x) \"x must be positive, got ~a\" x))"))
			.hasRootCauseMessage("x must be positive, got 0");
	}

	@Test
	void compileAndRunFlet() throws Exception {
		assertThat(compileAndRun("(flet ((add1 (x) (+ x 1))) (print (add1 41)))")).isEqualTo("42");
		assertThat(compileAndRun("(flet ((sq (x) (* x x)) (dbl (x) (* 2 x)))"
				+ " (print (mapcar #'sq '(1 2 3))) (print (funcall #'dbl 21)) (print (sq (dbl 3))))"))
			.isEqualTo("(1 4 9)\n42\n36");
		// The definition body sees the OUTER function binding, not itself.
		assertThat(compileAndRun("(defun shadow-fn (x) (* 100 x))"
				+ " (print (flet ((shadow-fn (x) (if (= x 0) 'zero (shadow-fn 0)))) (shadow-fn 5)))"))
			.isEqualTo("0");
		assertThat(compileAndRun("(print (flet () 'ok))")).isEqualTo("OK");
	}

	// macrolet is consumed by the compile-path pass (eval.UserMacroExpander); the
	// compiler
	// itself never sees it, so the test mirrors the CLI by running the pass first.
	@Test
	void compileAndRunMacroletThroughUserMacroExpander() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(macrolet ((sq (x) `(* ,x ,x)) (twice (x) `(+ ,x ,x)))
				  (print (+ (sq 5) (twice 5))))
				(defun apply-in-body (n)
				  (macrolet ((mklist (&rest xs) `(list ,@xs)))
				    (mklist n (sq-outer n))))
				(defun sq-outer (x) (* x x))
				(print (apply-in-body 4))
				"""));
		assertThat(compileAndRun(program)).isEqualTo("35\n(4 16)");
	}

	@Test
	void compileAndRunDefineCompilerMacroRewritesCallSites() throws Exception {
		// define-compiler-macro is consumed by the compile-path pass
		// (eval.UserMacroExpander), which the CLI runs before the compiler; mirror that
		// here. The macro rewrites the call site; returning the &whole form declines (and
		// must not loop); an error in the body leaves the ordinary call alone.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander
			.expand(LispReader.readAllFromString("(defun myinc (x) (+ x 1))"
					+ " (define-compiler-macro myinc (x) `(+ ,x 100)) (print (myinc 10))" + " (defun mydec (x) (- x 1))"
					+ " (define-compiler-macro mydec (&whole form x) (declare (ignore x)) form) (print (mydec 10))"
					+ " (defun mysafe (x) (+ x 1))"
					+ " (define-compiler-macro mysafe (x) (error \"no\")) (print (mysafe 10))"));
		assertThat(compileAndRun(program)).isEqualTo("110\n9\n11");
	}

	@Test
	void compileAndRunRestartCaseEvaluatesPrimaryForm() throws Exception {
		// Lite: the restart clauses are dead; the primary form is the value.
		assertThat(compileAndRun("(print (restart-case (+ 1 2) (continue () 99)))")).isEqualTo("3");
	}

	@Test
	void compileAndRunFletLambdaListExtensionsAndClosure() throws Exception {
		assertThat(compileAndRun(
				"(flet ((opt (a &optional (b 10) &rest r) (list a b r)))" + " (print (opt 1)) (print (opt 1 2 3 4)))"))
			.isEqualTo("(1 10 NIL)\n(1 2 (3 4))");
		assertThat(compileAndRun("(let ((base 100)) (flet ((offs (x) (+ base x))) (print (offs 5))))"))
			.isEqualTo("105");
	}

	@Test
	void compileAndRunFletNestedShadowingAndDataPositions() throws Exception {
		assertThat(compileAndRun("(flet ((g () 1)) (flet ((h () (g))) (flet ((g () 2)) (print (list (g) (h))))))"))
			.isEqualTo("(2 1)");
		assertThat(compileAndRun("(flet ((k (x) (* x 10)))"
				+ " (print (case 2 ((1 2) (k 3)) (t 'other))) (print (car '(k 1))) (print (let ((k 5)) (k k))))"))
			.isEqualTo("30\nK\n50");
	}

	@Test
	void compileAndRunLabelsRecursion() throws Exception {
		assertThat(compileAndRun("(labels ((fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))) (print (fact 6)))"))
			.isEqualTo("720");
		assertThat(compileAndRun("(labels ((ev (n) (if (= n 0) t (od (- n 1))))"
				+ " (od (n) (if (= n 0) nil (ev (- n 1))))) (print (list (ev 10) (od 10))))"))
			.isEqualTo("(T NIL)");
		assertThat(compileAndRun("(labels ((tri (n) (if (= n 0) 0 (+ n (tri (- n 1))))))"
				+ " (print (reduce #'+ (mapcar #'tri '(1 2 3)))))"))
			.isEqualTo("10");
	}

	@Test
	void compileAndRunFletInsideDefun() throws Exception {
		assertThat(compileAndRun("(defun poly (x) (flet ((sq (v) (* v v))) (+ (sq x) x))) (print (poly 5))"))
			.isEqualTo("30");
		assertThat(compileAndRun(
				"(defun count-down (n) (labels ((go-down (i acc) (if (= i 0) acc (go-down (- i 1) (cons i acc)))))"
						+ " (go-down n nil))) (print (count-down 4))"))
			.isEqualTo("(1 2 3 4)");
	}

	@Test
	void compileAndRunValuesInSingleValueContext() throws Exception {
		// Extra values are discarded but still evaluated (prog1 semantics);
		// (values) reads as nil; #'values yields the primary value.
		assertThat(compileAndRun("(setq mv-side 0) (setq mv-primary (values 1 (setq mv-side 9)))"
				+ " (print (list mv-primary mv-side)) (print (values)) (print (funcall #'values 1 2))"))
			.isEqualTo("(1 9)\nNIL\n1");
	}

	@Test
	void compileAndRunMultipleValueBind() throws Exception {
		assertThat(compileAndRun("(multiple-value-bind (a b c) (values 1 2) (print (list a b c)))"))
			.isEqualTo("(1 2 NIL)");
		assertThat(compileAndRun("(multiple-value-bind (q r) (floor 7 2) (print (list q r)))"
				+ " (multiple-value-bind (q r) (truncate -7 2) (print (list q r)))"
				+ " (multiple-value-bind (q r) (ceiling 7 2) (print (list q r)))"
				+ " (multiple-value-bind (q r) (round 7 2) (print (list q r)))"
				+ " (multiple-value-bind (q r) (floor 7.5) (print (list q r)))"))
			.isEqualTo("(3 1)\n(-3 -1)\n(4 -1)\n(4 -1)\n(7 0.5)");
		// A single-value producer supplies its primary value only.
		assertThat(compileAndRun("(multiple-value-bind (a b) (+ 1 2) (print (list a b)))")).isEqualTo("(3 NIL)");
		// (floor a b) in a single-value context yields the quotient.
		assertThat(compileAndRun("(print (floor 7 2)) (print (ceiling 7 2)) (print (round 7 2))"
				+ " (print (truncate -7 2)) (print (floor 7.5 2))"))
			.isEqualTo("3\n4\n4\n-3\n3");
		// Inside a defun and a lambda.
		assertThat(compileAndRun("(defun mv-q100r (n) (multiple-value-bind (q r) (floor n 3) (+ (* 100 q) r)))"
				+ " (print (mv-q100r 17))"
				+ " (print (mapcar (lambda (n) (multiple-value-list (floor n 4))) '(9 10 11)))"))
			.isEqualTo("502\n((2 1) (2 2) (2 3))");
	}

	@Test
	void compileAndRunMultipleValueBindGethash() throws Exception {
		assertThat(compileAndRun(
				"(setq mv-h (make-hash-table))" + " (setf (gethash 'x mv-h) nil) (setf (gethash 'y mv-h) 42)"
						+ " (multiple-value-bind (v p) (gethash 'y mv-h) (print (list v p)))"
						+ " (multiple-value-bind (v p) (gethash 'x mv-h) (print (list v p)))"
						+ " (multiple-value-bind (v p) (gethash 'z mv-h) (print (list v p)))"
						+ " (multiple-value-bind (v p) (gethash 'z mv-h 'dflt) (print (list v p)))"))
			.isEqualTo("(42 T)\n(NIL T)\n(NIL NIL)\n(DFLT NIL)");
	}

	@Test
	void compileAndRunMultipleValueSetq() throws Exception {
		assertThat(compileAndRun("(let (a b) (multiple-value-setq (a b) (values 1 2)) (print (list a b)))"))
			.isEqualTo("(1 2)");
		// Returns the primary value; floor-family producer; extra vars nil.
		assertThat(compileAndRun("(let (a b) (print (multiple-value-setq (a b) (floor 17 5))) (print (list a b)))"))
			.isEqualTo("3\n(3 2)");
		assertThat(compileAndRun("(let (a b c) (multiple-value-setq (a b c) (values 1 2)) (print (list a b c)))"))
			.isEqualTo("(1 2 NIL)");
	}

	@Test
	void compileAndRunRotatef() throws Exception {
		assertThat(compileAndRun("(let ((x 1) (y 2)) (rotatef x y) (print (list x y)))")).isEqualTo("(2 1)");
		assertThat(compileAndRun("(let ((a 1) (b 2) (c 3)) (rotatef a b c) (print (list a b c)))"))
			.isEqualTo("(2 3 1)");
		assertThat(compileAndRun("(let ((x (cons 1 2))) (rotatef (car x) (cdr x)) (print x))")).isEqualTo("(2 . 1)");
	}

	@Test
	void compileAndRunShiftf() throws Exception {
		assertThat(compileAndRun("(let ((x 1) (y 2) (z 3)) (print (shiftf x y z 4)) (print (list x y z)))"))
			.isEqualTo("1\n(2 3 4)");
		assertThat(compileAndRun("(let ((c (cons 1 2))) (print (shiftf (car c) 9)) (print c))"))
			.isEqualTo("1\n(9 . 2)");
	}

	@Test
	void compileAndRunLoadTimeValue() throws Exception {
		assertThat(compileAndRun("(print (+ (load-time-value (* 2 3)) 4))")).isEqualTo("10");
		assertThat(compileAndRun("(print (load-time-value 7 t))")).isEqualTo("7");
		// Hoisted into a lazily filled slot: one evaluation per occurrence, however many
		// times the enclosing function runs; a second occurrence gets its own slot, and a
		// nil result still counts as computed. Printed one call per form -- the argument
		// evaluation order of a single call is a separate cross-backend question.
		assertThat(compileAndRun("(defvar ltv-n 0)" + " (defun ltv-bump () (setq ltv-n (+ ltv-n 1)) ltv-n)"
				+ " (defun ltv-probe () (load-time-value (ltv-bump)))"
				+ " (defun ltv-other () (load-time-value (ltv-bump)))" + " (print (ltv-probe)) (print (ltv-probe))"
				+ " (print (ltv-probe)) (print (ltv-other)) (print ltv-n)" + " (defvar ltv-m 0)"
				+ " (defun ltv-nil () (load-time-value (progn (setq ltv-m (+ ltv-m 1)) nil)))"
				+ " (print (ltv-nil)) (print (ltv-nil)) (print ltv-m)"))
			.isEqualTo("1\n1\n1\n2\n2\nNIL\nNIL\n1");
	}

	@Test
	void compileAndRunTypep() throws Exception {
		assertThat(compileAndRun("(print (typep 1 'integer))")).isEqualTo("T");
		assertThat(compileAndRun("(print (typep \"s\" 'string))")).isEqualTo("T");
		assertThat(compileAndRun("(print (typep 1 'string))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (typep 1.5 'number))")).isEqualTo("T");
		assertThat(compileAndRun("(print (typep nil 'null))")).isEqualTo("T");
		assertThat(compileAndRun(
				"(defclass pt () ((x :initarg :x))) (print (typep (make-instance 'pt :x 1) 'pt)) (print (typep 1 'pt))"))
			.isEqualTo("T\nNIL");
	}

	@Test
	void compileAndRunTypepWithComputedSpecifier() throws Exception {
		// A computed specifier lowers to the shared %typep-runtime defun (its data
		// table is a top-level defvar): the inline per-class dispatch overflowed the
		// JVM's 16-bit branch offsets at cl-postgres scale (ironclad's pbkdf1
		// shared-initialize). The answers must match the literal-specifier expansion.
		assertThat(compileAndRun("""
				(defclass animal () ((name :initarg :name)))
				(defclass dog (animal) ())
				(defstruct point x y)
				(let ((d (make-instance 'dog)) (p (make-point :x 1 :y 2)))
				  (let ((ty 'dog)) (print (typep d ty)))
				  (let ((ty 'animal)) (print (typep d ty)))
				  (let ((ty 'point)) (print (typep p ty)))
				  (let ((ty 'integer)) (print (typep 42 ty)))
				  (let ((ty 'string)) (print (typep d ty)))
				  (let ((ty 'standard-object)) (print (typep d ty)))
				  (let ((ty 'structure-object)) (print (typep p ty)))
				  (let ((ty 'no-such-type)) (print (typep 1 ty)))
				  (let ((ty t)) (print (typep 1 ty))))
				""")).isEqualTo("T\nT\nT\nT\nNIL\nT\nT\nNIL\nT");
	}

	@Test
	void compileAndRunSlotValueOfANameTwoUnrelatedClassesPlaceDifferently() throws Exception {
		// A slot name at DIFFERENT indexes in unrelated classes reads through an
		// instance-tag dispatch. It used to route through a same-named READER generic
		// instead, which is wrong when that generic belongs to an unrelated class: with
		// ironclad and cl-postgres both loaded, (slot-value <protocol-error> 'message)
		// dispatched into IRONCLAD::MESSAGE and died with "No applicable method" while
		// rendering the protocol error's own report.
		assertThat(compileAndRun("""
				(defclass other () ((filler :initarg :filler) (message :initarg :message :reader message)))
				(define-condition my-proto-error (error) ((message :initarg :message)))
				(print (message (make-instance 'other :filler 1 :message "from-other")))
				(print (slot-value (make-instance 'other :filler 1 :message "read-other") 'message))
				(print (slot-value (make-condition 'my-proto-error :message "from-condition") 'message))
				""")).isEqualTo("\"from-other\"\n\"read-other\"\n\"from-condition\"");
	}

	@Test
	void compileAndRunErrorWithComputedConditionType() throws Exception {
		// A computed condition-type datum WITH initargs lowers to the shared
		// %error-runtime dispatch (one construction helper per condition class):
		// inlining every class's typed expansion at the call site grew cl-postgres'
		// get-error to 90 KB, past the JVM's 64 KB hard method limit.
		assertThat(compileAndRun("""
				(define-condition my-err (error) ((code :initarg :code :reader my-err-code)))
				(defun boom (ty) (error ty :code 42))
				(print (handler-case (boom 'my-err) (my-err (c) (list :caught (my-err-code c)))))
				(print (handler-case (boom 'type-error) (type-error (c) :caught-type-error)))
				""")).isEqualTo("(:CAUGHT 42)\n:CAUGHT-TYPE-ERROR");
	}

	// define-setf-expander / defsetf are handled by the compile-path pass
	// (eval.UserMacroExpander): the definition is consumed and each (setf (place ...) v)
	// call site is rewritten to primitives, so the compiler never sees the expander.
	@Test
	void compileAndRunDefineSetfExpander() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defun aget (alist key) (cdr (assoc key alist :test #'equal)))
					(defun %aput (alist key value)
					  (let ((kv (assoc key alist :test #'equal)))
					    (if kv (progn (rplacd kv value) alist) (cons (cons key value) alist))))
					(define-setf-expander aget (alist key &environment env)
					  (multiple-value-bind (d v n setter getter) (get-setf-expansion alist env)
					    (let ((nv (first n)))
					      (values d v n `(let ((,nv (%aput ,alist ,key ,nv))) ,setter ,nv) `(aget ,getter ,key)))))
					(let ((d (list (cons :a 1))))
					  (setf (aget d :a) 100)
					  (setf (aget d :b) 2)
					  (incf (aget d :a) 5)
					  (print (list (aget d :a) (aget d :b))))
					""")));
		assertThat(compileAndRun(program)).isEqualTo("(105 2)");
	}

	@Test
	void compileAndRunDefsetf() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(am.ik.rontolisp.eval.UserMacroExpander
			.expand(LispReader.readAllFromString("(defun ref (box) (car box)) (defsetf ref rplaca)"
					+ " (defun head (x) (car x)) (defsetf head (lst) (v) `(progn (rplaca ,lst ,v) ,v))"
					+ " (print (let ((b (list 1))) (setf (ref b) 9) b))"
					+ " (print (let ((l (list 7 8))) (setf (head l) 70) l))")));
		assertThat(compileAndRun(program)).isEqualTo("(9)\n(70 8)");
	}

	@Test
	void compileAndRunTypepResolvesUserDeftype() throws Exception {
		assertThat(compileAndRun("""
				(deftype my-even () '(satisfies evenp))
				(defun my-alistp (x) (and (listp x) (every #'consp x)))
				(deftype my-alist () '(satisfies my-alistp))
				(deftype my-int () 'integer)
				(print (typep 4 'my-even)) (print (typep 3 'my-even))
				(print (typep '((a . 1)) 'my-alist)) (print (typep '(a b) 'my-alist))
				(print (typep 7 'my-int)) (print (typep 'x 'my-int))
				""")).isEqualTo("T\nNIL\nT\nNIL\nT\nNIL");
	}

	@Test
	void compileAndRunSubtypep() throws Exception {
		assertThat(compileAndRun("(print (subtypep 'cons 'list))")).isEqualTo("T");
		assertThat(compileAndRun("(print (subtypep 'integer 'number))")).isEqualTo("T");
		assertThat(compileAndRun("(print (subtypep 'string 'number))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (subtypep 'single-float 'float))")).isEqualTo("T");
		assertThat(
				compileAndRun("(defclass base () ()) (defclass derived (base) ()) (print (subtypep 'derived 'base))"))
			.isEqualTo("T");
	}

	@Test
	void compileAndRunSetfValuesAndSetfThroughThe() throws Exception {
		assertThat(compileAndRun("(let ((a 0) (b 0)) (setf (values a b) (values 1 2)) (print (list a b)))"))
			.isEqualTo("(1 2)");
		assertThat(compileAndRun("(let ((x 0)) (setf (the integer x) 5) (print x))")).isEqualTo("5");
	}

	@Test
	void compileAndRunSymbolpNilAndT() throws Exception {
		assertThat(compileAndRun("(print (list (symbolp nil) (symbolp t) (symbolp 'foo) (symbolp \"s\") (symbolp 1)))"))
			.isEqualTo("(T T T NIL NIL)");
	}

	@Test
	void compileAndRunTagbodyGo() throws Exception {
		// Backward go: a plain counting loop.
		assertThat(compileAndRun("(let ((i 0)) (tagbody top (setq i (+ i 1)) (if (< i 3) (go top))) (print i))"))
			.isEqualTo("3");
		// Forward go skips a form.
		assertThat(compileAndRun("(tagbody (print 1) (go skip) (print 2) skip (print 3))")).isEqualTo("1\n3");
		// go from a nested if arm, plus fall-through over a label.
		assertThat(compileAndRun("(let ((n 0) (acc nil)) (tagbody loop (setq n (+ n 1)) (push n acc)"
				+ " (if (< n 4) (go loop)) done) (print acc))"))
			.isEqualTo("(4 3 2 1)");
		// Nested tagbody: the inner go resolves innermost-first, the outer tag is
		// reachable from the inner body.
		assertThat(compileAndRun("(let ((x 0)) (tagbody outer (setq x (+ x 10))"
				+ " (tagbody (if (> x 10) (go end)) (go outer)) end) (print x))"))
			.isEqualTo("20");
		// tagbody value is nil.
		assertThat(compileAndRun("(print (tagbody (print 1)))")).isEqualTo("1\nNIL");
		// go in an argument position abandons the outer call.
		assertThat(
				compileAndRun("(let ((i 0)) (tagbody top (setq i (+ i 1)) (print (+ 100 (if (< i 3) (go top) i)))))"))
			.isEqualTo("103");
	}

	@Test
	void compileAndRunProgAndProgStar() throws Exception {
		assertThat(compileAndRun("(print (prog ((i 0)) top (setq i (+ i 1)) (if (< i 3) (go top)) (return i)))"))
			.isEqualTo("3");
		assertThat(compileAndRun("(print (prog* ((x 2) (y (* x 3))) (return (+ x y))))")).isEqualTo("8");
		assertThat(compileAndRun("(print (prog ((x 1)) (print x)))")).isEqualTo("1\nNIL");
	}

	@Test
	void compileAndRunGoEscapesUnwindProtect() throws Exception {
		assertThat(compileAndRun("(let ((i 0)) (tagbody top (setq i (+ i 1))"
				+ " (unwind-protect (if (< i 3) (go top)) (print (list :cleanup i)))) (print i))"))
			.isEqualTo("(:CLEANUP 1)\n(:CLEANUP 2)\n(:CLEANUP 3)\n3");
	}

	@Test
	void compileAndRunMaskFieldAndScaleFloat() throws Exception {
		assertThat(compileAndRun("(print (mask-field (byte 4 4) 255))")).isEqualTo("240");
		assertThat(compileAndRun("(print (mask-field (byte 8 0) 300))")).isEqualTo("44");
		assertThat(compileAndRun("(print (scale-float 1.5 3))")).isEqualTo("12.0");
		// The subnormal-to-max edge: 2^-1074 scaled by 2097 is exactly 2^1023.
		assertThat(compileAndRun("(print (scale-float 4.9406564584124654d-324 2097))"))
			.isEqualTo("8.98846567431158E307");
		assertThat(compileAndRun("(print (scale-float 1.0 -100000))")).isEqualTo("0.0");
	}

	@Test
	void compileAndRunCharName() throws Exception {
		assertThat(compileAndRun("(print (char-name #\\Space))")).isEqualTo("\"Space\"");
		assertThat(compileAndRun("(print (char-name #\\a))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (char-name (code-char 1)))")).isEqualTo("\"U+0001\"");
		assertThat(compileAndRun("(print (char-name (code-char 128)))")).isEqualTo("\"U+0080\"");
	}

	@Test
	void compileAndRunFdefinition() throws Exception {
		assertThat(compileAndRun("(defun fd-doubler (x) (* x 2)) (print (funcall (fdefinition 'fd-doubler) 21))"))
			.isEqualTo("42");
	}

	@Test
	void compileAndRunLiteStreamBuiltins() throws Exception {
		assertThat(compileAndRun("(print (file-position t)) (print (file-length t)) (print (pathnamep \"/tmp/x\"))"
				+ " (print (stream-element-type t))"))
			.isEqualTo("NIL\nNIL\nNIL\nCHARACTER");
		assertThat(compileAndRun("(print (input-stream-p t)) (print (output-stream-p (make-broadcast-stream)))"
				+ " (print (input-stream-p \"s\"))"))
			.isEqualTo("T\nT\nNIL");
	}

	@Test
	void compileAndRunClassOf() throws Exception {
		// class-of answers the metaobject view: class-name reads the name of the
		// memoized standard-class instance, eq to what find-class yields -- for
		// built-ins, CLOS instances and struct instances alike.
		assertThat(compileAndRun("(print (class-name (class-of 42))) (print (class-name (class-of \"s\")))"
				+ " (print (class-name (class-of 'foo))) (print (class-name (class-of :k)))"
				+ " (print (class-name (class-of 1.5))) (print (class-name (class-of (CONS 1 2))))"
				+ " (print (class-name (class-of nil))) (print (class-name (class-of t)))"
				+ " (print (class-name (class-of (make-hash-table)))) (print (class-name (class-of #'car)))"
				+ " (print (class-name (class-of (make-array 1))))"))
			.isEqualTo("INTEGER\nSTRING\nSYMBOL\nKEYWORD\nFLOAT\nCONS\nNULL\nBOOLEAN\nHASH-TABLE\nFUNCTION\nT");
		assertThat(compileAndRun("""
				(defclass co-pt () ((x :initarg :x)))
				(defstruct co-node value)
				(print (list (eq (class-of (make-instance 'co-pt :x 1)) (find-class 'co-pt))
				             (eq (class-of (make-co-node :value 1)) (find-class 'co-node))
				             (eq (class-of 42) (find-class 'integer))
				             (class-name (class-of (make-instance 'co-pt :x 1)))
				             (class-name (class-of (make-co-node :value 2)))))
				""")).isEqualTo("(T T T CO-PT CO-NODE)");
	}

	@Test
	void compileAndRunClassDesignatorKeepsTheTagView() throws Exception {
		// The internal %class-designator is the pre-migration class-of: instance tags
		// and built-in type name symbols -- the light view type-of and the serializers
		// ride, with no metaobject runtime in the artifact.
		assertThat(compileAndRun("""
				(defclass cd-pt () ())
				(defstruct cd-node)
				(print (list (%class-designator (make-instance 'cd-pt))
				             (%class-designator (make-cd-node))
				             (%class-designator 42)
				             (%class-designator "s")))
				""")).isEqualTo("(%class-CD-PT %struct-CD-NODE INTEGER STRING)");
	}

	@Test
	void compileAndRunClassSlotDefsAcceptsAClassMetaobjectDesignator() throws Exception {
		// A metaobject designates through its name slot, so the pre-migration idiom
		// (%class-slot-defs (class-of x)) keeps answering the (name type) pairs.
		assertThat(compileAndRun("""
				(defclass csm-p () ((name :initarg :name) (age :type integer)))
				(print (list (%class-slot-defs (class-of (make-instance 'csm-p)))
				             (%class-slot-defs (%class-designator (make-instance 'csm-p)))
				             (%class-slot-defs (find-class 'integer))))
				""")).isEqualTo("(((NAME T) (AGE INTEGER)) ((NAME T) (AGE INTEGER)) NIL)");
	}

	@Test
	void compileAndRunSlotShadowingChangeClassWithAccessorsAndPrintObject() throws Exception {
		assertThat(compileAndRun("""
				(defclass jc-base () ((open-p :initform t :reader jc-open-p) (conn :initarg :conn :reader jc-conn)))
				(defclass jc-sub (jc-base) ((open-p :initform :maybe :accessor jc-sub-open-p)
				                            (extra :initarg :extra :initform :none :accessor jc-extra)))
				(defstruct jc-node value)
				(defmethod print-object ((n jc-node) stream)
				  (print-unreadable-object (n stream :type t) (princ (jc-node-value n) stream)))
				(let* ((b (make-instance 'jc-base :conn "c")) (alias b))
				  (print (jc-open-p b))
				  (change-class b 'jc-sub :extra :pooled)
				  (print (list (type-of alias) (jc-conn alias) (jc-extra alias) (jc-sub-open-p alias)))
				  (with-accessors ((e jc-extra)) alias (setf e (list e :seen)))
				  (print (jc-extra alias)))
				(print (format nil "~a|~s" (make-jc-node :value 1) (make-jc-node :value 2)))
				""")).isEqualTo("T\n(JC-SUB \"c\" :POOLED T)\n(:POOLED :SEEN)\n\"#<JC-NODE 1>|#<JC-NODE 2>\"");
	}

	@Test
	void compileAndRunSlotBoundpAndSlotMakunbound() throws Exception {
		// Real unboundness (todo-199): a slot written with no :initform starts UNBOUND,
		// slot-makunbound puts it back, and a read of an unbound slot signals
		// unbound-slot.
		assertThat(compileAndRun("(defclass sb-pt () ((x :initarg :x) (y :initform 9)))"
				+ " (let ((p (make-instance 'sb-pt)))" + " (print (slot-boundp p 'x)) (print (slot-boundp p 'y))"
				+ " (print (slot-boundp p 'sb-absent))" + " (setf (slot-value p 'x) 1) (print (slot-boundp p 'x))"
				+ " (slot-makunbound p 'x) (print (slot-boundp p 'x))"
				+ " (print (handler-case (slot-value p 'x) (unbound-slot (e) (list :unbound (slot-value e 'name))))))"))
			.isEqualTo("NIL\nT\nNIL\nT\nNIL\n(:UNBOUND X)");
	}

	@Test
	void compileAndRunSimpleConditionFormatAccessors() throws Exception {
		// The built-in simple-* condition tags carry the rendered message at slot 1.
		assertThat(compileAndRun(
				"(handler-case (error \"boom ~a\" 1)" + " (error (c) (print (simple-condition-format-control c))"
						+ " (print (simple-condition-format-arguments c))))"))
			.isEqualTo("\"boom 1\"\nNIL");
		// A user condition class reads its declared slots.
		assertThat(compileAndRun("(define-condition sc-err (error)"
				+ " ((format-control :initarg :format-control) (format-arguments :initarg :format-arguments)))"
				+ " (handler-case (error 'sc-err :format-control \"ctl\" :format-arguments '(1 2))"
				+ " (error (c) (print (simple-condition-format-control c))"
				+ " (print (simple-condition-format-arguments c))))"))
			.isEqualTo("\"ctl\"\n(1 2)");
	}

	@Test
	void compileAndRunWriteStringBoundsAndReplaceNilBounds() throws Exception {
		assertThat(compileAndRun("(write-string \"hello\" t :start 1 :end 3) (terpri)"
				+ " (write-string \"hello\" t :start 1 :end nil) (terpri) (print (write-string \"xy\"))"))
			.isEqualTo("el\nello\nxy\"xy\"");
		assertThat(compileAndRun("(let ((s \"abcdef\")) (print (replace s \"XYZ\" :start1 1 :end1 nil)))"))
			.isEqualTo("\"aXYZef\"");
	}

	@Test
	void compileAndRunUiopAddPackageLocalNickname() throws Exception {
		// The literal top-level call is consumed by the PackageResolver like a
		// defpackage clause, so the nickname works on the compiled backends too.
		assertThat(compileAndRun("""
				(defpackage #:com.example.deeply.nested (:use #:cl) (:export #:apn-answer))
				(in-package #:com.example.deeply.nested)
				(defun apn-answer () 42)
				(in-package #:cl-user)
				(uiop:add-package-local-nickname '#:apn-nick '#:com.example.deeply.nested)
				(print (apn-nick:apn-answer))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunUsePackage() throws Exception {
		// The literal top-level (use-package P) is consumed by the PackageResolver, so
		// the inherited bare names resolve at compile time -- nothing reaches runtime.
		assertThat(compileAndRun("""
				(defpackage #:up-greeter (:use #:cl) (:export #:up-hello))
				(in-package #:up-greeter)
				(defun up-hello () "hi")
				(in-package #:cl-user)
				(use-package '#:up-greeter)
				(print (up-hello))
				""")).isEqualTo("\"hi\"");
	}

	@Test
	void compileAndRunGrayStreamInstanceDispatch() throws Exception {
		// The GrayStreamsLibrary pre-pass splices gray.lisp and rewrites the
		// write-string/write-char call sites onto the dispatch helpers, mirroring the
		// CLI pipeline.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary.process(LispReader.readAllFromString("""
				(defclass gs-upcase (rontolisp:fundamental-character-output-stream)
				  ((acc :initform "")))
				(defmethod rontolisp:stream-write-string ((s gs-upcase) str)
				  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string-upcase str)))
				  str)
				(let ((s (make-instance 'gs-upcase)))
				  (write-string "hello" s)
				  (write-char #\\! s)
				  (print (slot-value s 'acc)))
				(write-string "still-works" t)
				(terpri)
				""")))).isEqualTo("\"HELLO!\"\nstill-works");
	}

	@Test
	void compileAndRunReadTimeEval() throws Exception {
		// The CLI pipeline: marker read -> UserMacroExpander resolves each marker
		// against the macro-time evaluator -> the compilers see plain forms.
		assertThat(compileAndRun(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllWithReadEvalMarkers(
				"(defvar +re-six+ #.(* 2 3)) (print +re-six+)" + " (print #.(+ 40 2)) (print '(a #.(+ 1 2) c))"
						+ " (defmacro re-stamp (&rest body) `(list #.(* 7 6) ,@body)) (print (re-stamp 1 2))",
				am.ik.rontolisp.reader.Features.JVM))))
			.isEqualTo("6\n42\n(A 3 C)\n(42 1 2)");
	}

	@Test
	void compileAndRunIeee754Bits() throws Exception {
		assertThat(compileAndRun("(print (%ieee754-double-bits 1.0)) (print (%ieee754-double-bits -2.5))"
				+ " (print (%ieee754-double-from-bits 4607182418800017408))"
				+ " (print (%ieee754-single-bits 1.0)) (print (%ieee754-single-bits -2.5))"
				+ " (print (%ieee754-single-from-bits 1065353216))"
				+ " (print (%ieee754-double-from-bits (%ieee754-double-bits -0.5)))"
				+ " (print (%ieee754-single-from-bits (%ieee754-single-bits -0.5)))"))
			.isEqualTo("4607182418800017408\n13836183955189006336\n1.0\n1065353216\n3223322624\n1.0\n-0.5\n-0.5");
	}

	@Test
	void compileAndRunDestructuringBind() throws Exception {
		assertThat(compileAndRun("(destructuring-bind (a (b c) d) '(1 (2 3) 4) (print (+ a b c d)))")).isEqualTo("10");
		assertThat(compileAndRun("(destructuring-bind (a &optional (b 10) c) '(1) (print (list a b c)))"))
			.isEqualTo("(1 10 NIL)");
		assertThat(compileAndRun("(destructuring-bind (a &rest r) '(1 2 3) (print (list a r)))"))
			.isEqualTo("(1 (2 3))");
		assertThat(compileAndRun("(destructuring-bind (a &key k (j 5)) '(1 :k 2) (print (list a k j)))"))
			.isEqualTo("(1 2 5)");
		assertThat(compileAndRun("(destructuring-bind ((a &key k) b) '((1 :k 2) 3) (print (list a k b)))"))
			.isEqualTo("(1 2 3)");
		// Inside a defun and a lambda (the pattern variables are lambda-local).
		assertThat(compileAndRun(
				"(defun db-sum (pair) (destructuring-bind (x y) pair (+ x y)))" + " (print (db-sum '(1 2)))"
						+ " (print (mapcar (lambda (p) (destructuring-bind (x y) p (* x y))) '((1 2) (3 4))))"))
			.isEqualTo("3\n(2 12)");
	}

	// defmacro destructuring/extended lambda lists go through the same compile-path
	// pass as plain defmacro (eval.UserMacroExpander).
	@Test
	void compileAndRunUserMacroWithDestructuringLambdaList() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro with-pair ((a b) &body body) `(let ((,a 1) (,b 2)) ,@body))
				(defmacro scaled (x &key (by 1)) `(* ,x ,by))
				(print (with-pair (p q) (+ p q)))
				(print (scaled 5 :by 3))
				"""));
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("3\n15");
		}
	}

	@Test
	void compileAndRunMultipleValueListAndNthValue() throws Exception {
		assertThat(compileAndRun("(print (multiple-value-list (values 1 2 3)))"
				+ " (print (multiple-value-list (floor 17 5)))" + " (print (multiple-value-list (+ 1 2)))"
				+ " (print (nth-value 1 (floor 7 2)))" + " (print (nth-value 0 (values 'a 'b)))"))
			.isEqualTo("(1 2 3)\n(3 2)\n(3)\n1\nA");
	}

	@Test
	void compileAndRunMultipleValueCall() throws Exception {
		assertThat(
				compileAndRun("(print (multiple-value-call #'+ (values 1 2)))" + " (defun mv-collect (&rest args) args)"
						+ " (print (multiple-value-call #'mv-collect 1 (values 2 3) (floor 9 4)))"))
			.isEqualTo("3\n(1 2 3 2 1)");
	}

	@Test
	void compileAndRunMapFamilyErrorsOnNonList() throws Exception {
		// The map* family operates on lists; a non-list (e.g. a string) signals an error
		// rather than silently returning nil, matching the interpreter.
		assertThatThrownBy(() -> compileAndRun("(mapcar #'identity \"abc\")"))
			.hasRootCauseMessage("MAPCAR: argument is not a list (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(mapc #'identity \"abc\")"))
			.hasRootCauseMessage("MAPC: argument is not a list (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(mapcan #'list \"abc\")"))
			.hasRootCauseMessage("MAPCAN: argument is not a list (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(maplist #'identity \"abc\")"))
			.hasRootCauseMessage("MAPLIST: argument is not a list: \"abc\" (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(mapcon #'list \"abc\")"))
			.hasRootCauseMessage("MAPCON: argument is not a list: \"abc\" (use map for strings/vectors)");
		// nil (the empty list) and proper lists stay accepted.
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3))) (print (mapcar #'1+ nil))")).isEqualTo("(2 3 4)\nNIL");
		assertThat(compileAndRun("(print (maplist #'identity nil))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunEvery() throws Exception {
		assertThat(compileAndRun("(print (every #'evenp '(2 4 6))) (print (every #'evenp '(2 3 6)))"))
			.isEqualTo("T\nNIL");
	}

	@Test
	void compileAndRunSome() throws Exception {
		assertThat(compileAndRun("(print (some #'oddp '(2 4 5))) (print (some #'oddp '(2 4 6)))")).isEqualTo("T\nNIL");
		// some returns the first non-nil predicate result.
		assertThat(compileAndRun("(print (some (lambda (x) (if (> x 3) (* x 10))) '(1 2 5)))")).isEqualTo("50");
	}

	@Test
	void compileAndRunRemove() throws Exception {
		assertThat(compileAndRun("(print (remove 2 '(1 2 3 2 4))) (print (remove 9 '(1 2 3)))"))
			.isEqualTo("(1 3 4)\n(1 2 3)");
	}

	@Test
	void compileAndRunRemoveIf() throws Exception {
		assertThat(compileAndRun("(print (remove-if #'evenp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunRemoveIfNot() throws Exception {
		assertThat(compileAndRun("(print (remove-if-not #'evenp '(1 2 3 4 5)))")).isEqualTo("(2 4)");
	}

	@Test
	void compileAndRunRemoveIfNotAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'remove-if-not #'oddp '(1 2 3 4)))")).isEqualTo("(1 3)");
	}

	@Test
	void compileAndRunRemoveAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'remove 2 '(1 2 3 2)))")).isEqualTo("(1 3)");
	}

	@Test
	void compileAndRunMapcan() throws Exception {
		assertThat(compileAndRun("(print (mapcan (lambda (x) (list x x)) '(1 2 3)))")).isEqualTo("(1 1 2 2 3 3)");
		assertThat(compileAndRun("(print (mapcan (lambda (x) (if (evenp x) (list x) nil)) '(1 2 3 4)))"))
			.isEqualTo("(2 4)");
		assertThat(compileAndRun("(print (funcall #'mapcan (lambda (x) (list x)) '(1 2 3)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunSort() throws Exception {
		assertThat(compileAndRun("(print (sort '(3 1 4 1 5 9 2 6) #'<))")).isEqualTo("(1 1 2 3 4 5 6 9)");
		assertThat(compileAndRun("(print (sort '(3 1 4) #'>))")).isEqualTo("(4 3 1)");
		assertThat(compileAndRun("(print (sort '() #'<)) (print (sort '(5) #'<))")).isEqualTo("NIL\n(5)");
		assertThat(compileAndRun("(print (funcall #'sort '(2 3 1) #'<))")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunApply() throws Exception {
		// In compiled code apply dispatches by the actual argument count, so the applied
		// function must have a matching arity (the eval-runtime limitation): binary
		// built-in wrappers and user defuns of any fixed arity work.
		assertThat(compileAndRun("(print (apply #'+ '(1 2)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (apply #'cons 1 '(2)))")).isEqualTo("(1 . 2)");
		assertThat(compileAndRun("(print (apply (lambda (a b) (+ a b)) '(3 4)))")).isEqualTo("7");
		assertThat(compileAndRun("(defun add3 (a b c) (+ a (+ b c))) (print (apply #'add3 1 2 '(3)))")).isEqualTo("6");
	}

	@Test
	void compileAndRunSequenceFunctionsAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'length '(7 8 9))) (print (mapcar #'reverse '((1 2) (3 4))))"))
			.isEqualTo("3\n((2 1) (4 3))");
	}

	@Test
	void compileAndRunSequenceFunctionInsideEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(reverse '(1 2 3))))")).isEqualTo("(3 2 1)");
	}

	@Test
	void compileAndRunMapWithBuiltinCar() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'car '((1 2) (3 4) (5 6))))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunMapWithBuiltinCdr() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cdr '((1 2) (3 4) (5 6))))")).isEqualTo("((2) (4) (6))");
	}

	@Test
	void compileAndRunMapWithBuiltin1Plus() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3)))")).isEqualTo("(2 3 4)");
	}

	@Test
	void compileAndRunMapcReturnsOriginalList() throws Exception {
		// mapc prints each element (side effect) and returns the original list.
		assertThat(compileAndRun("(print (mapc #'print '(10 20)))")).isEqualTo("10\n20\n(10 20)");
	}

	@Test
	void compileAndRunFuncallWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall #'+ 3 4))")).isEqualTo("7");
	}

	@Test
	void compileAndRunBuiltinAsVariable() throws Exception {
		assertThat(compileAndRun("""
				(setq my-op #'+)
				(print (funcall my-op 10 20))
				""")).isEqualTo("30");
	}

	// Lisp-2 (separate function/variable namespaces) tests

	@Test
	void compileAndRunFuncallSharpQuotedPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall #'+ 1 2))")).isEqualTo("3");
	}

	@Test
	void compileAndRunMapSharpQuotedCar() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'car '((1 2) (3 4))))")).isEqualTo("(1 3)");
	}

	@Test
	void compileAndRunFuncallQuotedSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (funcall 'car '(9 8)))")).isEqualTo("9");
	}

	@Test
	void compileAndRunMapSharpQuotedCadr() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cadr '((1 2) (3 4))))")).isEqualTo("(2 4)");
	}

	@Test
	void compileAndRunSetqSharpQuotedBuiltinThenFuncall() throws Exception {
		assertThat(compileAndRun("""
				(setq f #'+)
				(print (funcall f 1 2))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunSymbolFunction() throws Exception {
		assertThat(compileAndRun("(print (funcall (symbol-function 'car) '(5 6)))")).isEqualTo("5");
	}

	@Test
	void compileBareFunctionNameInValuePositionThrows() {
		assertThatThrownBy(() -> compileAndRun("(print car)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Cannot compile symbol reference: CAR");
	}

	@Test
	void compileRequireOrProvideNotConsumedByInlinerThrows() {
		// A literal top-level require/provide is consumed by the compile-time
		// LoadInliner pass (cli); one that reaches the compiler (nested, or a unit test
		// bypassing the pass) is a hard error -- unlike load, the compiled runtime
		// reader cannot execute it.
		assertThatThrownBy(() -> compileAndRun("(if t (require :util))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("REQUIRE is only supported as a literal top-level form");
		assertThatThrownBy(() -> compileAndRun("(if t (provide :util))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("PROVIDE is only supported as a literal top-level form");
	}

	@Test
	void compileRatioLiteral() throws Exception {
		assertThat(compileAndRun("(print 1/3)")).isEqualTo("1/3");
		assertThat(compileAndRun("(print -2/4)")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print '4/2)")).isEqualTo("2");
	}

	@Test
	void compileRatioArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ 1/2 1/3))")).isEqualTo("5/6");
		assertThat(compileAndRun("(print (+ 1/2 1/2))")).isEqualTo("1");
		assertThat(compileAndRun("(print (- 1/2 1/3))")).isEqualTo("1/6");
		assertThat(compileAndRun("(print (* 2/3 3))")).isEqualTo("2");
		assertThat(compileAndRun("(print (/ 1/2 1/3))")).isEqualTo("3/2");
		assertThat(compileAndRun("(print (- 1/2))")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print (+ 1 1/2))")).isEqualTo("3/2");
	}

	@Test
	void compileRatioFloatContagion() throws Exception {
		assertThat(compileAndRun("(print (/ 1 2.0))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (float 1/2))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (+ 1/2 0.5))")).isEqualTo("1.0");
	}

	@Test
	void compileRatioComparison() throws Exception {
		assertThat(compileAndRun("(print (if (< 1/3 1/2) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (if (= 2/4 1/2) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (if (= 1/2 0.5) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (eql 1/2 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq 1/2 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (max 1/2 1/3))")).isEqualTo("1/2");
		assertThat(compileAndRun("(print (min 1/2 1/3))")).isEqualTo("1/3");
		assertThat(compileAndRun("(print (abs -1/2))")).isEqualTo("1/2");
	}

	@Test
	void compileRatioConversions() throws Exception {
		assertThat(compileAndRun("(print (truncate 7/2))")).isEqualTo("3");
		assertThat(compileAndRun("(print (truncate -7/2))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (floor 7/2))")).isEqualTo("3");
		assertThat(compileAndRun("(print (floor -7/2))")).isEqualTo("-4");
		assertThat(compileAndRun("(print (ceiling 7/2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (ceiling -7/2))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (round 7/2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (round 5/2))")).isEqualTo("2");
		assertThat(compileAndRun("(print (round 1/3))")).isEqualTo("0");
	}

	@Test
	void compileRatioPredicatesAndAccessors() throws Exception {
		assertThat(compileAndRun("(print (numberp 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (integerp 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (rationalp 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (rationalp 5))")).isEqualTo("T");
		assertThat(compileAndRun("(print (rationalp 0.5))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (numerator 3/4))")).isEqualTo("3");
		assertThat(compileAndRun("(print (denominator 3/4))")).isEqualTo("4");
		assertThat(compileAndRun("(print (numerator 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (denominator 5))")).isEqualTo("1");
		assertThat(compileAndRun("(print (consp 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (atom 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (listp 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (zerop 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (plusp 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (minusp -1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (signum -1/2))")).isEqualTo("-1");
	}

	@Test
	void compileRatioExpt() throws Exception {
		assertThat(compileAndRun("(print (expt 1/2 2))")).isEqualTo("1/4");
		assertThat(compileAndRun("(print (expt 1/2 -2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (expt 2 -1))")).isEqualTo("1/2");
	}

	@Test
	void compileRatioInList() throws Exception {
		assertThat(compileAndRun("(print (list 1/2 2/3))")).isEqualTo("(1/2 2/3)");
		assertThat(compileAndRun("(print (cons 1 1/2))")).isEqualTo("(1 . 1/2)");
		assertThat(compileAndRun("(print (1+ 1/2))")).isEqualTo("3/2");
	}

	// read-line tests

	private String compileAndRunWithStdin(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			InputStream oldIn = System.in;
			System.setOut(new PrintStream(baos));
			System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
				System.setIn(oldIn);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void compileAndRunReadLine() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read-line))", "hello\n")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunReadLineEof() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read-line)))", "")).isEqualTo("T");
	}

	@Test
	void compileAndRunReadLineStringp() throws Exception {
		assertThat(compileAndRunWithStdin("(print (stringp (read-line)))", "hello\n")).isEqualTo("T");
	}

	// === read ===

	@Test
	void compileAndRunReadInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "42\n")).isEqualTo("42");
	}

	@Test
	void compileAndRunReadNegativeInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "-7\n")).isEqualTo("-7");
	}

	@Test
	void compileAndRunReadBigInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "100000000000000000000\n"))
			.isEqualTo("100000000000000000000");
	}

	@Test
	void compileAndRunReadFloat() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "3.14\n")).isEqualTo("3.14");
	}

	@Test
	void compileAndRunReadSymbol() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "foo\n")).isEqualTo("FOO");
	}

	@Test
	void compileAndRunReadString() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "\"hello\"\n")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunReadList() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "(+ 1 2)\n")).isEqualTo("(+ 1 2)");
	}

	@Test
	void compileAndRunReadCarList() throws Exception {
		assertThat(compileAndRunWithStdin("(print (car (read)))", "(a b c)\n")).isEqualTo("A");
	}

	@Test
	void compileAndRunReadQuote() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "'x\n")).isEqualTo("(QUOTE X)");
	}

	@Test
	void compileAndRunReadNil() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "nil\n")).isEqualTo("T");
	}

	@Test
	void compileAndRunReadThenEval() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(+ 1 2 3)\n")).isEqualTo("6");
	}

	@Test
	void compileEvalResolvesTopLevelGlobalVariable() throws Exception {
		// A top-level setq/defvar global must be visible to the embedded eval runtime
		// (its value is mirrored into the runtime's global environment).
		assertThat(compileAndRun("(setq foo 42) (print (eval (quote foo)))")).isEqualTo("42");
		assertThat(compileAndRun("(defvar *g* 99) (print (eval (quote *g*)))")).isEqualTo("99");
		assertThat(compileAndRun("(defparameter *p* 7) (print (eval (quote *p*)))")).isEqualTo("7");
		// A closure stored in a top-level global, then funcall'd through eval.
		assertThat(compileAndRun(
				"(defun make-adder (n) (lambda (x) (+ x n))) (setq add10 (make-adder 10)) (print (eval (quote (funcall add10 100))))"))
			.isEqualTo("110");
	}

	@Test
	void compileEvalResolvesGlobalClosureViaReadFuncall() throws Exception {
		// The exact playground scenario: define a closure global, then funcall it from
		// an expression read at runtime.
		assertThat(compileAndRunWithStdin(
				"(defun make-adder (n) (lambda (x) (+ x n))) (setq add10 (make-adder 10)) (print (eval (read)))",
				"(funcall add10 100)\n"))
			.isEqualTo("110");
	}

	@Test
	void compileAndRunReadEof() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "")).isEqualTo("T");
	}

	@Test
	void compileAndRunReadSharpQuote() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "#'car\n")).isEqualTo("(FUNCTION CAR)");
	}

	@Test
	void compileAndRunReadSharpQuoteThenEvalFuncall() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(funcall #'+ 1 2)\n")).isEqualTo("3");
	}

	@Test
	void compileAndRunReadSharpQuoteLambdaThenEval() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(mapcar #'(lambda (x) (* x x)) '(1 2 3))\n"))
			.isEqualTo("(1 4 9)");
	}

	@Test
	void compileAndRunReadSkipsBlankAndCommentLines() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "\n   \n; comment only\n42\n")).isEqualTo("42");
	}

	@Test
	void compileAndRunReadEvalPrintLoop() throws Exception {
		String repl = "(setq form (read)) (while form (print (eval form)) (setq form (read)))";
		assertThat(
				compileAndRunWithStdin(repl, "(defun square (x) (* x x))\n(square 7)\n\n(mapcar #'square '(1 2 3))\n"))
			.isEqualTo("SQUARE\n49\n(1 4 9)");
	}

	// === load ===

	@Test
	void compileAndRunLoadDefun() throws Exception {
		Path lib = tempDir.resolve("lib.lisp");
		Files.writeString(lib, "(defun square (x) (* x x))\n(setq base 10)\n");
		// Definitions from the loaded file live in the eval runtime's global env, so they
		// are used through eval.
		String code = "(load \"" + lib + "\") (print (eval '(square base)))";
		assertThat(compileAndRun(code)).isEqualTo("100");
	}

	@Test
	void compileAndRunLoadMultipleForms() throws Exception {
		Path lib = tempDir.resolve("lib2.lisp");
		Files.writeString(lib, "(defun inc (x) (+ x 1))\n(defun dbl (x) (* x 2))\n");
		String code = "(load \"" + lib + "\") (print (eval '(dbl (inc 4))))";
		assertThat(compileAndRun(code)).isEqualTo("10");
	}

	// === dynamic mode (late binding) ===

	private String compileAndRunDynamic(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test", true);
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void dynamicCallToLoadDefinedFunction() throws Exception {
		Path lib = tempDir.resolve("fn.lisp");
		Files.writeString(lib, "(defun cube (x) (* x x x))\n");
		// (cube 3) is unknown at compile time; --dynamic resolves it at runtime.
		String code = "(load \"" + lib + "\") (print (cube 3))";
		assertThat(compileAndRunDynamic(code)).isEqualTo("27");
	}

	@Test
	void dynamicCallFromCompiledFunctionSeesLocals() throws Exception {
		Path lib = tempDir.resolve("fn2.lisp");
		Files.writeString(lib, "(defun cube (x) (* x x x))\n(defun square (x) (* x x))\n");
		// caller is compiled; its local n must reach the runtime-resolved cube/square.
		String code = "(load \"" + lib + "\") (defun caller (n) (+ (cube n) (square n))) (print (caller 5))";
		assertThat(compileAndRunDynamic(code)).isEqualTo("150");
	}

	@Test
	void dynamicReferenceToLoadDefinedVariable() throws Exception {
		Path lib = tempDir.resolve("var.lisp");
		Files.writeString(lib, "(setq base 7)\n");
		String code = "(load \"" + lib + "\") (print base)";
		assertThat(compileAndRunDynamic(code)).isEqualTo("7");
	}

	// === eval ===

	@Test
	void evalSelfEvaluatingInteger() throws Exception {
		assertThat(compileAndRun("(print (eval 42))")).isEqualTo("42");
	}

	@Test
	void evalQuotedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 2)))")).isEqualTo("3");
	}

	@Test
	void evalVariadicArithmetic() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 2 3 4)))")).isEqualTo("10");
	}

	@Test
	void evalUnaryMinusNegates() throws Exception {
		assertThat(compileAndRun("(print (eval '(- 5)))")).isEqualTo("-5");
		assertThat(compileAndRun("(print (eval '(- -5)))")).isEqualTo("5");
	}

	@Test
	void evalUnaryDivideReciprocal() throws Exception {
		assertThat(compileAndRun("(print (eval '(/ 2)))")).isEqualTo("1/2");
	}

	@Test
	void evalQuotedList() throws Exception {
		assertThat(compileAndRun("(print (eval '(list 1 2 3)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void evalIf() throws Exception {
		assertThat(compileAndRun("(print (eval '(if (= 1 1) 10 20)))")).isEqualTo("10");
	}

	@Test
	void evalLetBindsVariable() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 10)) (+ x 5))))")).isEqualTo("15");
	}

	@Test
	void evalLambdaCapturesLexicalEnv() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((n 3)) (funcall (lambda (x) (+ x n)) 4))))")).isEqualTo("7");
	}

	@Test
	void evalCond() throws Exception {
		assertThat(compileAndRun("(print (eval '(cond ((= 1 2) 10) ((= 1 1) 20) (t 30))))")).isEqualTo("20");
	}

	@Test
	void evalAnd() throws Exception {
		assertThat(compileAndRun("(print (eval '(and 1 2 3)))")).isEqualTo("3");
	}

	@Test
	void evalOr() throws Exception {
		assertThat(compileAndRun("(print (eval '(or nil 5 nil)))")).isEqualTo("5");
	}

	@Test
	void evalWhenUnless() throws Exception {
		assertThat(compileAndRun("(print (eval '(when (= 1 1) 99)))")).isEqualTo("99");
		assertThat(compileAndRun("(print (eval '(unless (= 1 2) 88)))")).isEqualTo("88");
	}

	@Test
	void evalWhile() throws Exception {
		assertThat(compileAndRun(
				"(print (eval '(let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s)))"))
			.isEqualTo("10");
	}

	@Test
	void evalDotimes() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s)))")).isEqualTo("10");
		assertThat(compileAndRun("(print (eval '(dotimes (i 3))))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (eval '(let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2))))))"))
			.isEqualTo("16");
		// the loop variable holds the count value when the result form is evaluated
		assertThat(compileAndRun("(print (eval '(dotimes (i 3 i))))")).isEqualTo("3");
	}

	@Test
	void evalSetqInLet() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 1)) (setq x 42) x)))")).isEqualTo("42");
	}

	@Test
	void evalSetqGlobalPersistsAcrossEvalCalls() throws Exception {
		assertThat(compileAndRun("(eval '(setq counter 10)) (print (eval 'counter))")).isEqualTo("10");
	}

	@Test
	void evalRuntimeFunctionDefinition() throws Exception {
		assertThat(compileAndRun("(eval '(setq sq (lambda (x) (* x x)))) (print (eval '(funcall sq 6)))"))
			.isEqualTo("36");
	}

	@Test
	void evalNestedEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(eval '(+ 2 3))))")).isEqualTo("5");
	}

	@Test
	void evalFuncall() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall (lambda (x y) (+ x y)) 3 4)))")).isEqualTo("7");
	}

	@Test
	void evalMapWithLambda() throws Exception {
		assertThat(compileAndRun("(print (eval '(mapcar (lambda (x) (* x x)) (list 1 2 3))))")).isEqualTo("(1 4 9)");
	}

	@Test
	void evalReduce() throws Exception {
		assertThat(compileAndRun("(print (eval '(reduce (lambda (a b) (+ a b)) (list 1 2 3 4))))")).isEqualTo("10");
	}

	@Test
	void evalCarCdrComposition() throws Exception {
		assertThat(compileAndRun("(print (eval '(cadr (list 1 2 3))))")).isEqualTo("2");
	}

	@Test
	void evalNumberedAccessors() throws Exception {
		assertThat(compileAndRun("(print (eval '(third (list 10 20 30 40))))")).isEqualTo("30");
	}

	@Test
	void evalNth() throws Exception {
		assertThat(compileAndRun("(print (eval '(nth 2 (list 10 20 30 40))))")).isEqualTo("30");
	}

	@Test
	void evalUserDefinedFunction() throws Exception {
		assertThat(compileAndRun("(defun double (x) (* x 2)) (print (eval '(double 21)))")).isEqualTo("42");
	}

	@Test
	void evalBuiltinAsValue() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall #'+ 10 20)))")).isEqualTo("30");
	}

	@Test
	void evalSharpQuotedBuiltinFuncall() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall #'+ 10 20)))")).isEqualTo("30");
	}

	@Test
	void evalDefunThenCall() throws Exception {
		assertThat(compileAndRun("(print (eval '(progn (defun sq2 (x) (* x x)) (sq2 6))))")).isEqualTo("36");
	}

	@Test
	void evalSetfCarCdr() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x (list 1 2 3))) (setf (car x) 99) x)))")).isEqualTo("(99 2 3)");
	}

	@Test
	void evalPush() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x (list 2 3))) (push 1 x) x)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void evalPop() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x (list 1 2 3))) (pop x))))")).isEqualTo("1");
	}

	@Test
	void bigIntegerFactorialPromotesOnOverflow() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (fact 32))
				""";
		assertThat(compileAndRun(code)).isEqualTo("263130836933693530167218012160000000");
	}

	@Test
	void bigIntegerAdditionOverflow() throws Exception {
		assertThat(compileAndRun("(print (+ 9223372036854775807 1))")).isEqualTo("9223372036854775808");
	}

	@Test
	void bigIntegerMultiplicationOverflow() throws Exception {
		assertThat(compileAndRun("(print (* 1000000000000 1000000000000))")).isEqualTo("1000000000000000000000000");
	}

	@Test
	void bigIntegerSubtractionUnderflow() throws Exception {
		assertThat(compileAndRun("(print (- -9223372036854775807 1000))")).isEqualTo("-9223372036854776807");
	}

	@Test
	void bigIntegerResultDemotedWhenFitsInLong() throws Exception {
		// (fact 32) / (fact 31) = 32 fits in a long again.
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (/ (fact 32) (fact 31)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("32");
	}

	@Test
	void bigIntegerLiteral() throws Exception {
		assertThat(compileAndRun("(print 123456789012345678901234567890)")).isEqualTo("123456789012345678901234567890");
	}

	@Test
	void bigIntegerLiteralArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ 123456789012345678901234567890 1))"))
			.isEqualTo("123456789012345678901234567891");
	}

	@Test
	void bigIntegerComparison() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (> (fact 30) (fact 25)))
				(print (< (fact 30) (fact 25)))
				""";
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun(code)).isEqualTo("T\nNIL");
	}

	@Test
	void bigIntegerIntegerp() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (integerp (fact 25)))
				""";
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun(code)).isEqualTo("T");
	}

	@Test
	void bigIntegerEvenp() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (evenp (fact 25)))
				""";
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun(code)).isEqualTo("T");
	}

	@Test
	void bigIntegerMod() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (mod (fact 25) 1000000007))
				""";
		assertThat(compileAndRun(code)).isEqualTo("440732388");
	}

	@Test
	void bigIntegerAbsAndNegation() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (abs (- (fact 25))))
				""";
		assertThat(compileAndRun(code)).isEqualTo("15511210043330985984000000");
	}

	@Test
	void bigIntegerMax() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (max (fact 25) (fact 30)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("265252859812191058636308480000000");
	}

	@Test
	void compileAndRunRontolispVersion() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:version))")).contains(":VERSION")
			.contains(am.ik.rontolisp.Version.getVersion());
	}

	@Test
	void compileAndRunPackageVar() throws Exception {
		assertThat(compileAndRun("(print *package*)")).isEqualTo("CL-USER");
	}

	@Test
	void compileAndRunInPackageThenUnqualifiedVersion() throws Exception {
		String code = """
				(in-package rontolisp)
				(cl:print (cl:cadr (version)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("\"" + am.ik.rontolisp.Version.getVersion() + "\"");
	}

	@Test
	void compileAndRunDefpackageDefunAndCallAcrossPackages() throws Exception {
		String code = """
				(defpackage :mypkg (:use :cl) (:export :greet :twice))
				(in-package :mypkg)
				(defun greet (name) (concatenate 'string "hello, " name))
				(defun twice (x) (* x 2))
				(defun helper () 42)
				(in-package :cl-user)
				(print (mypkg:greet "world"))
				(print (mapcar #'mypkg:twice '(1 2 3)))
				(print (mypkg::helper))
				(print (rontolisp:list-functions :mypkg))
				""";
		assertThat(compileAndRun(code))
			.isEqualTo("\"hello, world\"\n(2 4 6)\n42\n(MYPKG::HELPER MYPKG:GREET MYPKG:TWICE)");
	}

	@Test
	void compileAndRunListMacros() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-macros))")).isEqualTo(
				"(AND ASSERT BLOCK CASE CCASE CERROR CHANGE-CLASS CHECK-TYPE COMPLEMENT COMPLEX COND DECF DECLAIM DECLARE DEFINE-COMPILER-MACRO DEFINE-CONDITION DEFINE-MODIFY-MACRO DEFINE-SETF-EXPANDER DEFSETF DEFTYPE DESTRUCTURING-BIND DO DO* DO-EXTERNAL-SYMBOLS DOCUMENTATION DOLIST DOTIMES ECASE ERROR ETYPECASE EVAL-WHEN FLET FORMAT HANDLER-BIND HANDLER-CASE IGNORE-ERRORS INCF LABELS LET* LOAD-TIME-VALUE LOCALLY LOOP MACROLET MAKE-CONDITION MAKE-INSTANCE MAKE-SEQUENCE MULTIPLE-VALUE-BIND MULTIPLE-VALUE-CALL MULTIPLE-VALUE-LIST MULTIPLE-VALUE-PROG1 MULTIPLE-VALUE-SETQ NTH-VALUE OR POP PRINT-UNREADABLE-OBJECT PROCLAIM PROG PROG* PROG1 PROG2 PSETF PSETQ PUSH PUSHNEW REMF RESTART-BIND RESTART-CASE RETURN-FROM ROTATEF SETF SHIFTF SIGNAL SLOT-BOUNDP SLOT-MAKUNBOUND SLOT-VALUE THE TIME TYPECASE TYPEP UNLESS WARN WHEN WITH-ACCESSORS WITH-INPUT-FROM-STRING WITH-OPEN-FILE WITH-OPEN-STREAM WITH-OUTPUT-TO-STRING WITH-PACKAGE-ITERATOR WITH-SIMPLE-RESTART WITH-SLOTS WITH-STANDARD-IO-SYNTAX WRITE-CHAR)");
	}

	@Test
	void compileAndRunListSpecialForms() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-special-forms))")).isEqualTo(
				"(CATCH DEFCLASS DEFCONSTANT DEFGENERIC DEFMACRO DEFMETHOD DEFPACKAGE DEFPARAMETER DEFSTRUCT DEFUN DEFVAR FUNCTION GO IF IN-PACKAGE LAMBDA LET PROGN PROGV QUOTE RETURN SETQ TAGBODY THROW UNWIND-PROTECT WHILE)");
	}

	@Test
	void compileAndRunListFunctionsLength() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions)))")).isEqualTo("362");
	}

	@Test
	void compileAndRunListFunctionsAcceptsBareSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions cl)))")).isEqualTo("362");
	}

	@Test
	void compileAndRunListFunctionsForClUserListsUserDefunsOnly() throws Exception {
		// Wrapper defuns injected for built-ins (car, +, ...) must not appear.
		String code = """
				(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
				(defun add2 (a) (+ a 2))
				(print (rontolisp:list-functions :cl-user))
				""";
		assertThat(compileAndRun(code)).isEqualTo("(ADD2 FIB)");
	}

	@Test
	void compileAndRunListFunctionsForClUserWithoutDefunsIsNil() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :cl-user))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunListFunctionsForRontolisp() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :rontolisp))")).isEqualTo(
				"(AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :rontolisp))")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunListFunctionsForJava() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :java))"))
			.isEqualTo("(CALL FIELD NEW PROXY STATIC)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :java))")).isEqualTo("NIL");
	}

	@Test
	void compileListFunctionsUnknownPackageThrows() {
		assertThatThrownBy(() -> compileAndRun("(print (rontolisp:list-functions :foo))"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: FOO");
	}

	@Test
	void compileAndRunFirstRestNthAsFunctionValues() throws Exception {
		assertThat(compileAndRun("(print (funcall #'first '(1 2 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (mapcar #'rest '((1 2) (3 4))))")).isEqualTo("((2) (4))");
		assertThat(compileAndRun("(print (funcall #'nth 1 '(1 2 3)))")).isEqualTo("2");
		assertThat(compileAndRun("(print (mapcar #'second '((1 2) (3 4))))")).isEqualTo("(2 4)");
	}

	@Test
	void compileAndRunFetchReturnsStatusBodyAndHeaders() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/echo", exchange -> {
			String received = exchange.getRequestHeaders().getFirst("X-Custom");
			byte[] body = ("got:" + received).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("X-Test", "ok");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			String url = "http://127.0.0.1:" + port + "/echo";
			assertThat(compileAndRun("(print (getf (rontolisp:await (rontolisp:fetch \"" + url
					+ "\" (list :headers (list (cons \"X-Custom\" \"abc\"))))) " + ":status))"))
				.isEqualTo("200");
			assertThat(compileAndRun(
					"(print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await" + " (rontolisp:fetch \"" + url
							+ "\" (list :headers (list (cons \"X-Custom\" \"abc\")))))" + " :body))))"))
				.isEqualTo("\"got:abc\"");
			assertThat(compileAndRun(
					"(print (rontolisp:streamp (getf (rontolisp:await (rontolisp:fetch \"" + url + "\")) :body)))"))
				.isEqualTo("T");
			String headers = compileAndRun(
					"(print (getf (rontolisp:await (rontolisp:fetch \"" + url + "\")) :headers))");
			assertThat(headers).contains("x-test");
			// a settled promise can be awaited more than once, and two in-flight promises
			// resolve independently
			assertThat(compileAndRun("(let ((p1 (rontolisp:fetch \"" + url + "\")) (p2 (rontolisp:fetch \"" + url
					+ "\"))) (rontolisp:await p1)"
					+ " (print (list (getf (rontolisp:await p2) :status) (getf (rontolisp:await p1) :status))))"))
				.isEqualTo("(200 200)");
			// a fetch future prints opaquely and satisfies futurep
			assertThat(compileAndRun("(let ((p (rontolisp:fetch \"" + url + "\")))"
					+ " (print (rontolisp:futurep p)) (print p)" + " (print (getf (rontolisp:await p) :status)))"))
				.isEqualTo("T\n#<FUTURE>\n200");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void compileAndRunFetchSendsMethodAndBody() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/post", exchange -> {
			String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			byte[] body = (exchange.getRequestMethod() + ":" + received).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			assertThat(compileAndRun("(print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await"
					+ " (rontolisp:fetch \"http://127.0.0.1:" + port
					+ "/post\" (list :method \"post\" :body \"hello\"))) :body))))"))
				.isEqualTo("\"POST:hello\"");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void compileAndRunFetchRejectsUnsupportedMethod() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/x", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			assertThatThrownBy(
					() -> compileAndRun("(rontolisp:fetch \"http://127.0.0.1:" + port + "/x\" (list :method \"FOO\"))"))
				.hasStackTraceContaining("unsupported method");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void compileFetchRejectsWrongArgCount() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:fetch)")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> compileAndRun("(rontolisp:await)")).isInstanceOf(UnsupportedOperationException.class);
	}

	// The serving round trip lives in HttpHandlerJvmTest (eval package, where the test
	// server shutdown seam is accessible); this pins the class-level design: the
	// generated class itself implements HttpHandlerSupport.Handler (like the
	// tls-connect X509TrustManager trick) so HttpHandlerSupport.serve can drive it.
	@Test
	void compileHttpHandlerImplementsHandlerInterface() throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler
			.compile(LispReader.readAllFromString("(defun h (r) (list :body \"ok\")) (rontolisp:http-handler 'h)"));
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			assertThat(am.ik.rontolisp.eval.HttpHandlerSupport.Handler.class).isAssignableFrom(clazz);
			assertThat(clazz.getMethod("handle", am.ik.rontolisp.eval.HttpHandlerSupport.Request.class)).isNotNull();
		}
	}

	@Test
	void compileHttpHandlerRejectsUnquotedHandlerName() {
		assertThatThrownBy(() -> compileAndRun("(defun h (r) nil) (rontolisp:http-handler h)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("quoted handler name");
	}

	@Test
	void compileAndRunFetchFailureSignalsAtAwaitTime() throws Exception {
		// fetch itself returns the future even for a request that will fail; the
		// failure surfaces when the future is awaited (JavaScript await-rejection
		// timing)
		assertThat(
				compileAndRun("(let ((p (rontolisp:fetch \"http://127.0.0.1:1/x\"))) (print (rontolisp:futurep p)))"))
			.isEqualTo("T");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:await (rontolisp:fetch \"http://127.0.0.1:1/x\"))"))
			.hasStackTraceContaining("java.net.ConnectException");
	}

	@Test
	void compileAndRunTcpEchoRoundTripOnLoopback() throws Exception {
		// Single-threaded choreography on port 0: connect before accept (the connection
		// sits in the listen backlog) and write before the peer reads (small payloads
		// sit in the kernel socket buffers), so nothing deadlocks.
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port)))
				  (write-line "hello" client)
				  (let* ((server (rontolisp:tcp-accept listener))
				         (line (read-line server)))
				    (write-line line server)
				    (let ((reply (read-line client)))
				      (close server)
				      (close client)
				      (close listener)
				      (print reply))))
				""")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunTcpByteOpsOnSocket() throws Exception {
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port)))
				  (write-byte 65 client)
				  (let* ((server (rontolisp:tcp-accept listener))
				         (b (read-byte server)))
				    (close server)
				    (close client)
				    (close listener)
				    (print b)))
				""")).isEqualTo("65");
	}

	@Test
	void compileAndRunTcpReadLineReturnsNilAfterPeerClose() throws Exception {
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (let ((line (read-line server)))
				    (close server)
				    (close listener)
				    (print line)))
				""")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunTcpConnectRefusedThrows() throws Exception {
		assertThatThrownBy(() -> compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener)))
				  (close listener)
				  (rontolisp:tcp-connect "127.0.0.1" port))
				""")).hasStackTraceContaining("java.net.ConnectException");
	}

	@Test
	void compileTcpRejectsWrongArgCount() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-connect \"127.0.0.1\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-CONNECT expects 2 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-listen)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-LISTEN expects 1 or 2 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-accept)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-ACCEPT expects 1 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-local-port 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-LOCAL-PORT expects 1 arguments");
	}

	@Test
	void compileAndRunTcpAddressAccessorsOnLoopback() throws Exception {
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (print (rontolisp:tcp-local-address listener))
				  (print (rontolisp:tcp-peer-address client))
				  (print (= (rontolisp:tcp-peer-port client) port))
				  (print (rontolisp:tcp-peer-address server))
				  (close server)
				  (close client)
				  (close listener))
				""")).isEqualTo("\"127.0.0.1\"\n\"127.0.0.1\"\nT\n\"127.0.0.1\"");
	}

	@Test
	void compileTcpAddressAccessorsRejectWrongArgCount() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-peer-address)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-PEER-ADDRESS expects 1 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-peer-port 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-PEER-PORT expects 1 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tcp-local-address)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TCP-LOCAL-ADDRESS expects 1 arguments");
	}

	@Test
	void compileAndRunUsocketEchoOverLoopback() throws Exception {
		// The Postmodern (cl-postgres) shape on the compiled backend: socket-connect
		// with :element-type, socket-stream, stream I/O, socket-close.
		assertThat(compileAndRunUsocket("""
				(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
				  (write-line "hello" (usocket:socket-stream client))
				  (let* ((server (usocket:socket-accept listener))
				         (line (read-line (usocket:socket-stream server))))
				    (usocket:socket-close server)
				    (usocket:socket-close client)
				    (usocket:socket-close listener)
				    (print line)))
				""")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunUsocketAddressAccessors() throws Exception {
		assertThat(compileAndRunUsocket("""
				(let* ((listener (usocket:socket-listen usocket:*wildcard-host* 0))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port))
				       (server (usocket:socket-accept listener)))
				  (print (usocket:get-peer-address client))
				  (print (= (usocket:get-peer-port client) port))
				  (print (usocket:get-peer-name server))
				  (usocket:socket-close server)
				  (usocket:socket-close client)
				  (usocket:socket-close listener))
				""")).isEqualTo("\"127.0.0.1\"\nT\n\"127.0.0.1\"");
	}

	@Test
	void compileAndRunUsocketWithMacrosEchoOverLoopback() throws Exception {
		assertThat(compileAndRunUsocket("""
				(usocket:with-socket-listener (listener "127.0.0.1" 0)
				  (usocket:with-client-socket (client stream "127.0.0.1" (usocket:get-local-port listener))
				    (write-line "ping" stream)
				    (usocket:with-connected-socket (server (usocket:socket-accept listener))
				      (print (read-line server)))))
				""")).isEqualTo("\"ping\"");
	}

	@Test
	void compileAndRunTlsEchoRoundTripOnLoopback() throws Exception {
		// A TLS handshake needs the server to participate concurrently, so the echo
		// peer runs on a background thread. The compiled class runs in-process, so
		// pointing javax.net.ssl.trustStore at the test keystore makes the emitted
		// _tlsConnect (which initializes a fresh SSLContext per call) trust the
		// self-signed certificate.
		am.ik.rontolisp.TlsTestSupport.withTrustStore(() -> {
			try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
				Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
				assertThat(compileAndRun("""
						(let ((client (rontolisp:tls-connect "127.0.0.1" %d)))
						  (write-line "hello over tls" client)
						  (let ((reply (read-line client)))
						    (close client)
						    (print reply)))
						""".formatted(server.getLocalPort()))).isEqualTo("\"hello over tls\"");
				echo.join();
			}
		});
	}

	@Test
	void compileAndRunTlsUntrustedCertificateThrows() throws Exception {
		// Without the trust-store override the JDK default trust store does not trust
		// the self-signed server certificate, so the handshake must fail.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			assertThatThrownBy(
					() -> compileAndRun("(rontolisp:tls-connect \"127.0.0.1\" " + server.getLocalPort() + ")"))
				.hasStackTraceContaining("SSLHandshakeException");
		}
	}

	@Test
	void compileAndRunTlsListenEchoRoundTripOnLoopback() throws Exception {
		// The compiled program is the TLS *server* (tls-listen + the plain
		// tcp-accept); the peer is a Java client thread that trusts the self-signed
		// certificate directly and retries connecting until the listener is bound.
		int port = am.ik.rontolisp.TlsTestSupport.freePort();
		Thread client = am.ik.rontolisp.TlsTestSupport.startOneShotEchoClient(port, "hello over server tls");
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tls-listen "%s" "%s" %d "127.0.0.1"))
				       (client (rontolisp:tcp-accept listener))
				       (line (read-line client)))
				  (write-line line client)
				  (close client)
				  (close listener)
				  (print line))
				""".formatted(am.ik.rontolisp.TlsTestSupport.keyStore(), am.ik.rontolisp.TlsTestSupport.STORE_PASSWORD,
				port)))
			.isEqualTo("\"hello over server tls\"");
		client.join();
	}

	@Test
	void compileAndRunTlsListenP12EchoRoundTripOnLoopback() throws Exception {
		// The compiled program is the TLS server configured from an embedded PKCS12
		// keystore (the shape the tls-listen-pem inliner produces). The keystore is
		// built from the shared PEM material; the peer trusts the same certificate.
		int port = am.ik.rontolisp.TlsTestSupport.freePort();
		java.nio.file.Path[] pem = am.ik.rontolisp.TlsTestSupport.pemFiles();
		String base64 = am.ik.rontolisp.eval.TlsPemSupport.pemToBase64Pkcs12(pem[0].toString(), pem[1].toString());
		String password = am.ik.rontolisp.eval.TlsPemSupport.KEYSTORE_PASSWORD;
		Thread client = am.ik.rontolisp.TlsTestSupport.startOneShotEchoClient(port, "hello over p12 tls");
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:%%tls-listen-p12 "%s" "%s" %d "127.0.0.1"))
				       (client (rontolisp:tcp-accept listener))
				       (line (read-line client)))
				  (write-line line client)
				  (close client)
				  (close listener)
				  (print line))
				""".formatted(base64, password, port))).isEqualTo("\"hello over p12 tls\"");
		client.join();
	}

	@Test
	void compileTlsRejectsWrongArgCount() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tls-connect \"127.0.0.1\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-CONNECT expects 2 or 4 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tls-connect \"127.0.0.1\" 443 :insecure)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-CONNECT expects 2 or 4 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tls-connect \"127.0.0.1\" 443 :verify t)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects :insecure");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tls-listen \"ks.p12\" \"pw\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-LISTEN expects 3 or 4 arguments");
	}

	@Test
	void compileAndRunTlsInsecureSkipsCertificateVerification() throws Exception {
		// The self-signed server certificate is NOT trusted here; :insecure t makes
		// the emitted _tlsConnect trust any chain (the generated class itself is the
		// X509TrustManager) and skip the hostname check.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			assertThat(compileAndRun("""
					(let ((client (rontolisp:tls-connect "127.0.0.1" %d :insecure t)))
					  (write-line "hello insecurely" client)
					  (let ((reply (read-line client)))
					    (close client)
					    (print reply)))
					""".formatted(server.getLocalPort()))).isEqualTo("\"hello insecurely\"");
			echo.join();
		}
	}

	@Test
	void compileAndRunTlsInsecureNilStillVerifies() throws Exception {
		// :insecure nil is the verifying default, so the untrusted certificate must
		// still fail the handshake.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			assertThatThrownBy(() -> compileAndRun(
					"(rontolisp:tls-connect \"127.0.0.1\" " + server.getLocalPort() + " :insecure nil)"))
				.hasStackTraceContaining("SSLHandshakeException");
		}
	}

	@Test
	void compileAndRunTlsInsecureSurvivesOptimize() throws Exception {
		// --optimize (JvmClassShaker) must keep the X509TrustManager methods of the
		// generated class: JSSE invokes them through the interface, which the shaker
		// cannot see as references.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			List<LispVal> program = LispReader.readAllFromString("""
					(let ((client (rontolisp:tls-connect "127.0.0.1" %d :insecure t)))
					  (write-line "hello optimized" client)
					  (let ((reply (read-line client)))
					    (close client)
					    (print reply)))
					""".formatted(server.getLocalPort()));
			byte[] classBytes = new JvmLispCompiler("Test", false, true).compile(program);
			Path classFile = tempDir.resolve("Test.class");
			Files.write(classFile, classBytes);
			try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
					ClassLoader.getSystemClassLoader())) {
				Class<?> clazz = loader.loadClass("Test");
				Method main = clazz.getMethod("main", String[].class);
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				PrintStream oldOut = System.out;
				System.setOut(new PrintStream(baos));
				try {
					main.invoke(null, (Object) new String[0]);
				}
				finally {
					System.setOut(oldOut);
				}
				assertThat(baos.toString().trim()).isEqualTo("\"hello optimized\"");
			}
			echo.join();
		}
	}

	@Test
	void compileAndRunAwaitPassesNonPromiseThrough() throws Exception {
		// like JavaScript await, a non-promise value is returned unchanged
		assertThat(compileAndRun(
				"(print (rontolisp:await 42)) (print (rontolisp:await \"p\"))" + " (print (rontolisp:await nil))"))
			.isEqualTo("42\n\"p\"\nNIL");
	}

	@Test
	void promisepIsGoneOnTheJvm() {
		// The promise-era promisep was deleted in the async/await redesign (futurep is
		// the one predicate). The name rontolisp:then was later restored as a
		// future-as-value combinator on top of the async surface -- exercised by
		// JvmAsyncCompilerTest -- so only promisep is expected to fail resolution.
		assertThatThrownBy(() -> compileAndRun("(rontolisp:promisep 42)"))
			.hasMessageContaining("not external in the RONTOLISP package");
	}

	// Characters and string/number parsing

	@Test
	void compileCharAccessors() throws Exception {
		assertThat(compileAndRun("(print (char-code #\\A)) (print (code-char 66)) (print (char \"hello\" 1))"))
			.isEqualTo("65\n#\\B\n#\\e");
	}

	@Test
	void compileCharCaseAndPredicates() throws Exception {
		assertThat(compileAndRun("""
				(print (char-upcase #\\a))
				(print (char-downcase #\\Z))
				(print (characterp #\\a))
				(print (characterp 5))
				(print (alpha-char-p #\\x))
				(print (alpha-char-p #\\5))
				(print (digit-char-p #\\7))
				(print (digit-char-p #\\f 16))
				(print (digit-char-p #\\9 8))
				""")).isEqualTo("#\\A\n#\\z\nT\nNIL\nT\nNIL\n7\n15\nNIL");
	}

	@Test
	void compileCharComparisonsVariadic() throws Exception {
		assertThat(compileAndRun(
				"(print (char= #\\a #\\a)) (print (char< #\\a #\\b #\\c)) (print (char<= #\\a #\\a #\\b)) (print (char< #\\b #\\a))"))
			.isEqualTo("T\nT\nT\nNIL");
	}

	@Test
	void compileCharEqualityAndFirstClass() throws Exception {
		assertThat(compileAndRun("""
				(print (eql #\\a #\\a))
				(print (equal (list #\\a #\\b) (list #\\a #\\b)))
				(print (mapcar #'char-upcase (list #\\a #\\b #\\c)))
				""")).isEqualTo("T\nT\n(#\\A #\\B #\\C)");
	}

	@Test
	void compileCharPrinting() throws Exception {
		assertThat(compileAndRun("(prin1 #\\a) (prin1 #\\Space) (prin1 #\\Newline) (princ #\\!)"))
			.isEqualTo("#\\a#\\Space#\\Newline!");
	}

	// A CHARACTER is a Unicode code point on every backend: (code-char 128512) survives
	// as U+1F600 (not truncated to a lone surrogate) and prints as its glyph. Pins the
	// JVM int[]{cp} widening from todo 153; see .kb/characters-code-points.md.
	@Test
	void compileCharBeyondBmpCodePoint() throws Exception {
		assertThat(compileAndRun("""
				(print (char-code (code-char 128512)))
				(print (string (code-char 128512)))
				(print (char-code (code-char 233)))
				(print (string (code-char 233)))
				""")).isEqualTo("128512\n\"😀\"\n233\n\"é\"");
	}

	// Full-Unicode case fold via Character.toUpperCase(int) / toLowerCase(int) so a
	// Latin-1 supplement letter and non-Latin scripts fold correctly (not just ASCII).
	@Test
	void compileCharCaseFoldBeyondAscii() throws Exception {
		assertThat(compileAndRun("""
				(print (char-code (char-upcase (code-char 233))))
				(print (char-code (char-downcase (code-char 201))))
				(print (char-code (char-upcase (code-char 945))))
				(print (char-code (char-downcase (code-char 1040))))
				""")).isEqualTo("201\n233\n913\n1072");
	}

	// String indexing (length / char / subseq) walks BY CODE POINT so a supplementary
	// code point counts as one indexed character.
	@Test
	void compileStringIndexingByCodePoint() throws Exception {
		assertThat(compileAndRun("""
				(print (length "😀"))
				(print (length "aé😀b"))
				(print (char-code (char "aé😀b" 2)))
				(print (subseq "aé😀b" 1 3))
				""")).isEqualTo("1\n4\n128512\n\"é😀\"");
	}

	@Test
	void compileParseInteger() throws Exception {
		assertThat(compileAndRun("""
				(print (parse-integer "42"))
				(print (parse-integer "  -13  "))
				(print (parse-integer "ff" :radix 16))
				(print (parse-integer "12abc" :junk-allowed t))
				(print (parse-integer "xyz" :junk-allowed t))
				""")).isEqualTo("42\n-13\n255\n12\nNIL");
	}

	@Test
	void compileReadFromString() throws Exception {
		assertThat(compileAndRun("(print (read-from-string \"(+ 1 2)\")) (print (read-from-string \"42\"))"))
			.isEqualTo("(+ 1 2)\n42");
	}

	@Test
	void compileReadFromStringDottedPair() throws Exception {
		assertThat(compileAndRun("(print (read-from-string \"(a . 1)\")) (print (read-from-string \"(a b . c)\")) "
				+ "(print (read-from-string \"((a . 1) (b . 2))\")) (print (read-from-string \"3.5\"))"))
			.isEqualTo("(A . 1)\n(A B . C)\n((A . 1) (B . 2))\n3.5");
	}

	@Test
	void compileParseIntegerAndReadFromStringAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'parse-integer (list \"1\" \"2\" \"3\")))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'read-from-string \"(a b c)\"))")).isEqualTo("(A B C)");
	}

	// === the emitted reader's # dispatch (frontend parity) ===

	@Test
	void compileReadFromStringCharLiterals() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string "#\\\\a"))
				(print (read-from-string "#\\\\Space"))
				(print (read-from-string (prin1-to-string #\\Newline)))
				(print (char-code (read-from-string "#\\\\A")))
				(print (read-from-string "#\\\\("))
				""")).isEqualTo("#\\a\n#\\Space\n#\\Newline\n65\n#\\(");
	}

	@Test
	void compileReadFromStringRatiosAndRadix() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string "1/3"))
				(print (read-from-string "-2/8"))
				(print (* 3 (read-from-string "1/3")))
				(print (read-from-string "4/2"))
				(print (read-from-string "#x10"))
				(print (read-from-string "#o17"))
				(print (read-from-string "#b101"))
				(print (read-from-string "#x-ff"))
				(print (read-from-string "+347"))
				(print (read-from-string "+2.5"))
				""")).isEqualTo("1/3\n-1/4\n1\n2\n16\n15\n5\n-255\n347\n2.5");
	}

	@Test
	void compileReadFromStringRadixBigInteger() throws Exception {
		assertThat(compileAndRun("(print (read-from-string \"#x10000000000000000\"))"))
			.isEqualTo("18446744073709551616");
	}

	@Test
	void compileReadFromStringVectorsAndArrays() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string (prin1-to-string #(1 2 3))))
				(print (aref (read-from-string "#(7 8 9)") 1))
				(print (read-from-string "#2A((1 2) (3 4))"))
				(print (read-from-string "#*101"))
				(print (read-from-string (prin1-to-string #f(1.0 2.0))))
				(print (read-from-string (prin1-to-string #d((1.0 2.0) (3.0 4.0)))))
				""")).isEqualTo("#(1 2 3)\n8\n#2A((1 2) (3 4))\n#(1 0 1)\n#f(1.0 2.0)\n#d((1.0 2.0) (3.0 4.0))");
	}

	@Test
	void compileReadFromStringStructLiterals() throws Exception {
		assertThat(compileAndRun("""
				(defstruct rdpt x y)
				(print (read-from-string (prin1-to-string (make-rdpt :x 1 :y 2))))
				(print (rdpt-y (read-from-string "#S(RDPT :X 5 :Y 6)")))
				(print (read-from-string "#S(RDPT :X 5)"))
				(defstruct rdcfg (retries 3) (host "h") (tag 'none))
				(print (read-from-string "#S(RDCFG)"))
				"""))
			.isEqualTo("#S(RDPT :X 1 :Y 2)\n6\n#S(RDPT :X 5 :Y NIL)\n#S(RDCFG :RETRIES 3 :HOST \"h\" :TAG NONE)");
	}

	@Test
	void compileReadFromStringSymbolParityAndBlockComments() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string "#foo"))
				(print (read-from-string "#|note|# 7"))
				""")).isEqualTo("#FOO\n7");
	}

	@Test
	void compileReadFromStringReaderErrorsMatchTheFrontend() throws Exception {
		assertThat(compileAndRun("""
				(defstruct rdpt x y)
				(defun try (s)
				  (handler-case (progn (read-from-string s) "no-error")
				    (error (e) (simple-condition-format-control e))))
				(print (try "#\\\\Foo"))
				(print (try "#xZZ"))
				(print (try "#S(NOSUCH :X 1)"))
				(print (try "#S(SIMPLE-ERROR)"))
				(print (try "#S(RDPT :Z 1)"))
				(print (try "#2A((1 2) (3))"))
				(print (try "1/0"))
				(print (try "#.(+ 1 2)"))
				(print (try "#1=(a b)"))
				""")).isEqualTo(
				"""
						"Unknown character name: #\\\\Foo"
						"Invalid digits after #x: Z"
						"#S(NOSUCH ...): NOSUCH is not a defined structure type"
						"#S(SIMPLE-ERROR ...): SIMPLE-ERROR is not a defined structure type (it names a class; #S reads defstruct types only)"
						"#S(RDPT ...): RDPT has no slot named :Z"
						"#2A: ragged contents, expected 2 elements, got 1"
						"Division by zero in ratio literal: 1/0"
						"#. read-time evaluation is not supported"
						"reader labels (#N=/#N#) are not supported by the compiled runtime reader"
						"""
					.trim());
	}

	@Test
	void compileHashTablePutGet() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(print (list (gethash "a" *h*) (gethash "b" *h*) (gethash "c" *h*)))
				""")).isEqualTo("(1 2 NIL)");
	}

	@Test
	void compileHashTableGetWithDefault() throws Exception {
		assertThat(compileAndRun("(print (gethash 'x (make-hash-table) 42))")).isEqualTo("42");
	}

	@Test
	void compileHashTableListKeysWithEqual() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *q* (make-hash-table :test 'equal))
				(setf (gethash (list 0 1 2) *q*) 7)
				(print (gethash (list 0 1 2) *q* 0))
				""")).isEqualTo("7");
	}

	@Test
	void compileHashTableIncf() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(dolist (w (list "a" "b" "a" "a" "b"))
				  (incf (gethash w *h* 0)))
				(print (list (gethash "a" *h*) (gethash "b" *h*)))
				""")).isEqualTo("(3 2)");
	}

	@Test
	void compileHashTableCountRemhashAndPredicate() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table))
				(setf (gethash 1 *h*) 'a)
				(setf (gethash 2 *h*) 'b)
				(remhash 1 *h*)
				(print (list (hash-table-count *h*) (gethash 1 *h*) (gethash 2 *h*)
				             (hash-table-p *h*) (hash-table-p 5) (consp *h*)))
				""")).isEqualTo("(1 NIL B T NIL NIL)");
	}

	@Test
	void compileHashTableMaphashSumsValues() throws Exception {
		assertThat(compileAndRun("""
				(defun sum-values (h)
				  (let ((acc 0))
				    (maphash (lambda (k v) (setq acc (+ acc v))) h)
				    acc))
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 10)
				(setf (gethash "b" *h*) 20)
				(setf (gethash "c" *h*) 30)
				(print (sum-values *h*))
				""")).isEqualTo("60");
	}

	@Test
	void compileHashTableFunctionsAsFirstClassValues() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(print (list (funcall #'gethash "a" *h*)
				             (mapcar #'hash-table-p (list *h* 5))
				             (funcall #'hash-table-count *h*)))
				""")).isEqualTo("(1 (T NIL) 2)");
	}

	@Test
	void compileMakeHashTableAsFirstClassValue() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (funcall #'make-hash-table)))
				  (setf (gethash 1 h) 'x)
				  (print (gethash 1 h)))
				""")).isEqualTo("X");
	}

	@Test
	void compileMakeArrayVectorRefAndSet() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :initial-element 0))
				(setf (aref *v* 0) 10)
				(setf (aref *v* 4) 40)
				(incf (aref *v* 0) 5)
				(print (list (aref *v* 0) (aref *v* 1) (aref *v* 4)))
				""")).isEqualTo("(15 0 40)");
	}

	@Test
	void compileMakeArrayTwoDimensional() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 7))
				(setf (aref *m* 0 0) 1)
				(setf (aref *m* 1 2) 99)
				(print (list (aref *m* 0 0) (aref *m* 0 1) (aref *m* 1 2)))
				""")).isEqualTo("(1 7 99)");
	}

	@Test
	void compileMakeArraySingleElementListIsRankOne() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *w* (make-array (list 3) :initial-element 2))
				(setf (aref *w* 1) 8)
				(print (list (aref *w* 0) (aref *w* 1) (aref *w* 2)))
				""")).isEqualTo("(2 8 2)");
	}

	@Test
	void compileArrayCapturedInClosure() throws Exception {
		// Each bump is sequenced through a top-level defparameter so the result does not
		// depend on argument evaluation order within a single call form.
		assertThat(compileAndRun("""
				(defun make-counter (vec)
				  (lambda (i) (setf (aref vec i) (+ 1 (aref vec i))) (aref vec i)))
				(defparameter *c* (make-array 2 :initial-element 0))
				(defparameter *bump* (make-counter *c*))
				(defparameter *a* (funcall *bump* 0))
				(defparameter *b* (funcall *bump* 0))
				(defparameter *d* (funcall *bump* 1))
				(print (list *a* *b* *d*))
				""")).isEqualTo("(1 2 1)");
	}

	@Test
	void compileArgumentFormsEvaluateLeftToRight() throws Exception {
		// Common Lisp evaluates argument forms left to right. list (and everything that
		// lowers onto it -- backquote, make-array :initial-contents) LINKS its cons
		// chain from the last element backwards, and emitting the arguments in that
		// consumption order used to run the side effects right to left (.todo/014):
		// a `(:a ,(read-a) :b ,(read-b)) plist off a byte stream read its fields in
		// reverse. compiler.ArgumentOrder decides which arguments need the temp.
		assertThat(compileAndRun("""
				(defun noter (buf pos)
				  (lambda (x)
				    (setf (aref buf (aref pos 0)) x)
				    (setf (aref pos 0) (+ 1 (aref pos 0)))
				    x))
				(defparameter *buf* (make-array 3 :initial-element 0))
				(defparameter *pos* (make-array 1 :initial-element 0))
				(defparameter *note* (noter *buf* *pos*))
				(defun reset () (setf (aref *pos* 0) 0))
				(defun seen () (list (aref *buf* 0) (aref *buf* 1) (aref *buf* 2)))
				(reset)
				(print (list (funcall *note* 1) (funcall *note* 2) (funcall *note* 3)))
				(print (seen))
				(reset)
				(print `(,(funcall *note* 1) ,(funcall *note* 2) ,(funcall *note* 3)))
				(print (seen))
				(reset)
				(print (make-array 3 :initial-contents
				                   (list (funcall *note* 1) (funcall *note* 2) (funcall *note* 3))))
				(print (seen))
				""")).isEqualTo("""
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				#(1 2 3)
				(1 2 3)""");
	}

	@Test
	void compileArrayDefaultInitialElementIsNil() throws Exception {
		assertThat(compileAndRun("(print (aref (make-array 3) 2))")).isEqualTo("NIL");
	}

	@Test
	void compileVectorLiteralPrintsAsHashParen() throws Exception {
		assertThat(compileAndRun("(print #(1 2 3))")).isEqualTo("#(1 2 3)");
	}

	@Test
	void compileVectorLiteralIsAReadableArray() throws Exception {
		assertThat(compileAndRun("(print (aref #(10 20 30) 1))")).isEqualTo("20");
	}

	@Test
	void compileVectorLiteralPrin1QuotesStringsPrincDoesNot() throws Exception {
		assertThat(compileAndRun("(prin1 #(a \"b\")) (terpri) (princ #(a \"b\"))")).isEqualTo("#(A \"b\")\n#(A b)");
	}

	@Test
	void compileNestedVectorLiteral() throws Exception {
		assertThat(compileAndRun("(print #(#(1 2) #(3 4)))")).isEqualTo("#(#(1 2) #(3 4))");
	}

	@Test
	void compileMakeArrayResultPrintsAsHashParen() throws Exception {
		assertThat(compileAndRun("(print (make-array 3 :initial-element 7))")).isEqualTo("#(7 7 7)");
	}

	@Test
	void compileTwoDimensionalArrayPrintsAsHash2A() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(setf (aref *m* 0 0) 1)
				(setf (aref *m* 1 2) 9)
				(print *m*)
				""")).isEqualTo("#2A((1 0 0) (0 0 9))");
	}

	@Test
	void compileEmptyVectorLiteral() throws Exception {
		assertThat(compileAndRun("(print #())")).isEqualTo("#()");
	}

	@Test
	void compileRank2ArrayLiteralPrintsAsHash2A() throws Exception {
		assertThat(compileAndRun("(print #2A((1 2 3) (4 5 6)))")).isEqualTo("#2A((1 2 3) (4 5 6))");
	}

	@Test
	void compileRank2ArrayLiteralIsAReadableArray() throws Exception {
		assertThat(compileAndRun("(print (aref #2A((1 2) (3 4)) 1 0))")).isEqualTo("3");
		assertThat(compileAndRun("(print (array-dimensions #2A((1 2 3) (4 5 6))))")).isEqualTo("(2 3)");
	}

	@Test
	void compileRank3ArrayLiteral() throws Exception {
		assertThat(compileAndRun("(print (aref #3A(((1 2) (3 4)) ((5 6) (7 8))) 1 0 1))")).isEqualTo("6");
	}

	@Test
	void compileLengthOfVectorReturnsElementCount() throws Exception {
		assertThat(compileAndRun("(print (length (make-array 5 :initial-element 0)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length #(10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length #()))")).isEqualTo("0");
	}

	@Test
	void compileVectorSvrefAndArrayIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(print (vector 1 2 3))
				(print (svref (vector 10 20 30) 1))
				(defparameter *v* (vector 1 2 3))
				(setf (svref *v* 0) 99)
				(print *v*)
				(print (array-dimensions (make-array '(2 3) :initial-element 0)))
				(print (array-dimensions (vector 1 2)))
				(print (array-rank (make-array '(2 3))))
				(print (array-rank (vector 1)))
				(print (array-dimension (make-array '(2 3)) 1))
				(print (array-total-size (make-array '(2 3))))
				(print (array-total-size (vector 1 2 3)))
				""")).isEqualTo("#(1 2 3)\n20\n#(99 2 3)\n(2 3)\n(2)\n2\n1\n3\n6\n3");
	}

	@Test
	void compileFillPointerLengthAndAccessors() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
				(print (list (length *v*) (fill-pointer *v*) (array-has-fill-pointer-p *v*) (adjustable-array-p *v*)))
				""")).isEqualTo("(2 2 T NIL)");
	}

	@Test
	void compileFillPointerVectorPrintsUpToFillPointer() throws Exception {
		assertThat(compileAndRun("""
				(let ((v (make-array 5 :fill-pointer 3 :initial-element 9)))
				  (print v))
				""")).isEqualTo("#(9 9 9)");
	}

	@Test
	void compileFillPointerTIsTheVectorSize() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 4 :fill-pointer t :initial-element 1))
				(print (list (length *v*) (fill-pointer *v*)))
				""")).isEqualTo("(4 4)");
	}

	@Test
	void compileVectorPushStoresAndReturnsIndexOrNil() throws Exception {
		// Each push is sequenced through a top-level defparameter: the compilers
		// evaluate list argument forms right-to-left, so side-effecting forms must
		// not share one list form.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :fill-pointer 0))
				(defparameter *a* (vector-push 10 *v*))
				(defparameter *b* (vector-push 20 *v*))
				(defparameter *c* (vector-push 30 *v*))
				(defparameter *d* (vector-push 40 *v*))
				(print (list *a* *b* *c* *d*))
				""")).isEqualTo("(0 1 2 NIL)");
	}

	@Test
	void compileVectorPushThenReadBack() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :fill-pointer 0))
				(vector-push 10 *v*)
				(vector-push 20 *v*)
				(print (list (length *v*) (aref *v* 0) (aref *v* 1)))
				""")).isEqualTo("(2 10 20)");
	}

	@Test
	void compileVectorPop() throws Exception {
		// The pop is sequenced before the length read: the compile path evaluates
		// argument forms right-to-left.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :fill-pointer 3 :initial-element 0))
				(setf (aref *v* 2) 99)
				(defparameter *p* (vector-pop *v*))
				(print (list *p* (length *v*)))
				""")).isEqualTo("(99 2)");
	}

	@Test
	void compileVectorPushExtendGrowsBeyondCapacity() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 2 :fill-pointer 0 :adjustable t))
				(vector-push-extend 1 *v*)
				(vector-push-extend 2 *v*)
				(vector-push-extend 3 *v*)
				(print (list (length *v*) (adjustable-array-p *v*) (aref *v* 2)))
				""")).isEqualTo("(3 T 3)");
	}

	@Test
	void compileSetfFillPointer() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :fill-pointer 5 :initial-element 7))
				(setf (fill-pointer *v*) 2)
				(print (list (length *v*) (fill-pointer *v*)))
				""")).isEqualTo("(2 2)");
	}

	@Test
	void compileSimpleVectorHasNoFillPointer() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :initial-element 0))
				(print (list (array-has-fill-pointer-p *v*) (adjustable-array-p *v*) (array-element-type *v*)))
				""")).isEqualTo("(NIL NIL T)");
	}

	@Test
	void compileFillPointerFirstClassWrappers() throws Exception {
		// The fill-pointer read is sequenced before the mutating pop, the compile path
		// evaluating argument forms right-to-left.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :fill-pointer 0))
				(funcall #'vector-push 5 *v*)
				(defparameter *fp* (funcall #'fill-pointer *v*))
				(defparameter *popped* (funcall #'vector-pop *v*))
				(print (list *fp* *popped* (funcall #'array-has-fill-pointer-p *v*)))
				""")).isEqualTo("(1 5 T)");
	}

	@Test
	void compileClUtilitiesCopyArray() throws Exception {
		// The cl-utilities copy-array definition verbatim, the headline adjustable-array
		// exercise, on the JVM backend: array-element-type, make-array
		// :adjustable/:fill-pointer,
		// array-has-fill-pointer-p, fill-pointer, adjustable-array-p, array-total-size
		// and row-major-aref cooperating.
		assertThat(compileAndRun("""
				(defun copy-array (array &key
				                   (element-type (array-element-type array))
				                   (fill-pointer (and (array-has-fill-pointer-p array)
				                                      (fill-pointer array)))
				                   (adjustable (adjustable-array-p array)))
				  (let* ((dimensions (array-dimensions array))
				         (new-array (make-array dimensions
				                                :element-type element-type
				                                :adjustable adjustable
				                                :fill-pointer fill-pointer)))
				    (dotimes (i (array-total-size array))
				      (setf (row-major-aref new-array i)
				            (row-major-aref array i)))
				    new-array))
				(defparameter *v* (make-array 4 :fill-pointer 3 :adjustable t :initial-element 5))
				(setf (aref *v* 1) 8)
				(defparameter *c* (copy-array *v*))
				(setf (aref *c* 0) 99)
				(print (list *c* *v* (fill-pointer *c*) (adjustable-array-p *c*)))
				""")).isEqualTo("(#(99 8 5) #(5 8 5) 3 T)");
	}

	@Test
	void compileCharVectorAccumulator() throws Exception {
		// A fill-pointered/adjustable character vector is a mutable string on the JVM
		// backend: stringp, length, string=, princ (bare content) and prin1 (quoted)
		// all treat it as a string.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 4 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\a *s*)
				(vector-push-extend #\\b *s*)
				(print (list (stringp *s*) (length *s*) (string= *s* "ab")))
				(princ *s*)
				(terpri)
				(prin1 *s*)
				""")).isEqualTo("(T 2 T)\nab\n\"ab\"");
	}

	@Test
	void compileCharVectorReplaceMutatesInPlace() throws Exception {
		// replace on a character vector mutates IN PLACE (visible through an alias
		// made before the write), unlike the functional rebuild on an immutable string.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 8 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\h *s*)
				(vector-push-extend #\\e *s*)
				(vector-push-extend #\\l *s*)
				(vector-push-extend #\\l *s*)
				(vector-push-extend #\\o *s*)
				(setf (fill-pointer *s*) 5)
				(defparameter *alias* *s*)
				(replace *s* "xyz" :start1 1 :start2 0 :end2 2)
				(print (list (string= *alias* "hxylo") (subseq *alias* 0)))
				""")).isEqualTo("(T \"hxylo\")");
	}

	@Test
	void compileCharVectorAdjustArrayGrows() throws Exception {
		// adjust-array on an adjustable character vector grows it in place: the
		// mutable-string marker survives, the fill pointer carries over, and pushes
		// beyond the old capacity land after the kept content.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 2 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\a *s*)
				(vector-push-extend #\\b *s*)
				(adjust-array *s* 6)
				(vector-push-extend #\\c *s*)
				(print (list (stringp *s*) (string= (subseq *s* 0) "abc") (length *s*)))
				""")).isEqualTo("(T T 3)");
	}

	@Test
	void compileCharVectorInitialContentsCopies() throws Exception {
		// (make-array n :element-type 'character :initial-contents charvec) yields a
		// fresh simple string of the ACTIVE content, unaffected by later pushes.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 4 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\a *s*)
				(vector-push-extend #\\b *s*)
				(defparameter *copy* (make-array (fill-pointer *s*) :element-type 'character :initial-contents *s*))
				(vector-push-extend #\\c *s*)
				(print (list *copy* (stringp *copy*) (string= *copy* "ab") (string= *s* "abc")))
				""")).isEqualTo("(\"ab\" T T T)");
	}

	@Test
	void compileCharVectorEqualAndHashKey() throws Exception {
		// equal compares a character vector to a string by content, and an
		// equal-test hash table finds a string-keyed entry through it.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 2 :element-type 'character :fill-pointer 0))
				(vector-push #\\a *s*)
				(vector-push #\\b *s*)
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "ab" *h*) 1)
				(print (list (equal *s* "ab") (gethash *s* *h*)))
				""")).isEqualTo("(T 1)");
	}

	@Test
	void compileScharSetfMutatesCharVectorInPlace() throws Exception {
		// (setf (schar cv i) ch) writes the character vector IN PLACE, so an alias
		// made before the write sees the update (an immutable string place only
		// rebinds the variable).
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 3 :element-type 'character :fill-pointer 3 :initial-element #\\a))
				(defparameter *alias* *s*)
				(setf (schar *s* 1) #\\z)
				(print (list (string= *alias* "aza") (schar *s* 1)))
				""")).isEqualTo("(T #\\z)");
	}

	@Test
	void compileSetfEltOnAStringMutatesIt() throws Exception {
		// (setf (elt seq i) v) dispatches at run time over all three sequence
		// representations: rplaca for a list, the schar-set rebuild for a string,
		// %aset for a vector. The string arm is what makes this the worked example of
		// .todo/209: NOTHING in the source is an array operator, yet the expansion
		// reaches the array runtime (%arrayp / %row-major-aset inside the schar-set
		// rebuild). With the gate still a source scan the class called _aset1 without
		// declaring it and died with NoSuchMethodError; the gate is now a consequence
		// of what the bodies reference (.kb/adjustable-arrays.md).
		assertThat(compileAndRun("""
				(let ((s "abc")) (setf (elt s 0) #\\z) (print s))
				""")).isEqualTo("\"zbc\"");
	}

	@Test
	void compileSetfEltOnAVectorAndAList() throws Exception {
		assertThat(compileAndRun("""
				(let ((v (vector 1 2 3))) (setf (elt v 0) 9) (print v))
				(let ((l (list 1 2 3))) (setf (elt l 0) 8) (print l))
				(let ((s (make-string 3 :initial-element #\\a))) (setf (elt s 1) #\\z) (print s))
				""")).isEqualTo("#(9 2 3)\n(8 2 3)\n\"aza\"");
	}

	@Test
	void anArrayFreeProgramReferencesNoArrayRuntimeHelper() {
		// .todo/210: the injected built-in wrappers used to carry an array arm each
		// (#'+ / #'reverse / #'sort / #'subseq / #'string= ... all fold or scan through
		// the sequence dispatch), so a class as small as this one named _aref1 /
		// _arrayMake / _aset1 without declaring them -- harmless at run time, but it
		// forced the gate check to ignore every wrapper-only dangling call. Each of
		// those lowerings is gated on Ctx.usesArrays now, so nothing dangles and the
		// check applies to wrappers too (.kb/adjustable-arrays.md).
		byte[] classBytes = new JvmLispCompiler("Test")
			.compile(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("(print (+ 1 2))")));
		assertThat(am.ik.jvm.JvmClassShaker.unresolvedSelfMethods(classBytes)).isEmpty();
	}

	@Test
	void compileSequenceOperatorsWithoutTheArrayRuntime() throws Exception {
		// The other half of the gate above: with the array arms dropped, every
		// sequence operator whose lowering carried one still answers for the two
		// representations that CAN exist in an array-free program (list and string).
		assertThat(compileAndRun("""
				(print (elt "abc" 1))
				(print (elt '(10 20 30) 1))
				(print (coerce "ab" 'list))
				(print (map 'string (lambda (c) c) "xyz"))
				(print (reverse "abc"))
				(print (subseq '(1 2 3 4) 1 3))
				(print (replace "abcdef" "XY" :start1 1))
				(print (remove-duplicates '(1 2 1 3)))
				(print (sort "cba" #'char<))
				(print (reduce #'+ '(1 2 3)))
				""")).isEqualTo("#\\b\n20\n(#\\a #\\b)\n\"xyz\"\n\"cba\"\n(2 3)\n\"aXYdef\"\n(2 1 3)\n\"abc\"\n6");
	}

	@Test
	void compileFuncallAsAFirstClassValue() throws Exception {
		// #'funcall's injected wrapper body is (apply f r), which needs the eval
		// runtime's _apply. The wrapper used to be emitted in every class while _apply
		// stayed gated on the source mentioning eval/apply, so this died with
		// NoSuchMethodError; wrapper and runtime are now gated on the same reference.
		assertThat(compileAndRun("""
				(print (funcall #'funcall #'+ 1 2))
				(print (mapcar #'funcall (list #'car #'cdr) (list (list 1 2) (list 3 4))))
				""")).isEqualTo("3\n(1 (4))");
	}

	@Test
	void compileCharVectorEltAndCoerce() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 3 :element-type 'character :fill-pointer 0))
				(vector-push #\\x *s*)
				(vector-push #\\y *s*)
				(vector-push #\\z *s*)
				(print (list (elt *s* 1) (coerce *s* 'list)))
				""")).isEqualTo("(#\\y (#\\x #\\y #\\z))");
	}

	@Test
	void compileJzonAccumulatorPattern() throws Exception {
		// The jzon writer shape: a reusable adjustable accumulator filled with
		// vector-push-extend, snapshotted via make-array :initial-contents, and reset
		// with (setf (fill-pointer a) 0) between uses.
		assertThat(compileAndRun("""
				(defun make-adjustable-string ()
				  (make-array 256 :element-type 'character :fill-pointer 0 :adjustable t))
				(defparameter *acc* (make-adjustable-string))
				(dolist (ch (coerce "hello" 'list))
				  (vector-push-extend ch *acc*))
				(defparameter *out1* (make-array (fill-pointer *acc*) :element-type 'character :initial-contents *acc*))
				(setf (fill-pointer *acc*) 0)
				(dolist (ch (coerce "world" 'list))
				  (vector-push-extend ch *acc*))
				(defparameter *out2* (make-array (fill-pointer *acc*) :element-type 'character :initial-contents *acc*))
				(print (list *out1* *out2*))
				""")).isEqualTo("(\"hello\" \"world\")");
	}

	@Test
	void compileReplaceOnImmutableStringStaysFunctional() throws Exception {
		// replace on an immutable string keeps returning a fresh rebuilt string; the
		// original is untouched. The make-array keeps the array gate (and thus the
		// runtime %arrayp branch) in this program.
		assertThat(compileAndRun("""
				(defparameter *pad* (make-array 1 :initial-element 0))
				(defparameter *orig* "abc")
				(defparameter *r* (replace *orig* "xy"))
				(print (list *r* *orig*))
				""")).isEqualTo("(\"xyc\" \"abc\")");
	}

	@Test
	void compilePlainCharacterMakeArrayIsStillAString() throws Exception {
		// Without :fill-pointer/:adjustable a rank-1 character array keeps the
		// make-string lowering: an immutable simple string.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 3 :element-type 'character :initial-element #\\x))
				(print (list *s* (stringp *s*)))
				""")).isEqualTo("(\"xxx\" T)");
	}

	@Test
	void compileAdjustArray() throws Exception {
		// Non-adjustable -> fresh array; :adjustable -> adjusted in place (eq);
		// rank-2 keeps the elements at their subscripts; the fill pointer carries
		// over without an explicit :fill-pointer.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :initial-element 7))
				(defparameter *v2* (adjust-array *v* 5 :initial-element 0))
				(print (list *v2* (eq *v* *v2*)))
				(defparameter *w* (make-array 3 :adjustable t :initial-element 1))
				(defparameter *w2* (adjust-array *w* 5 :initial-element 9))
				(print (list (eq *w* *w2*) *w*))
				(defparameter *m* (make-array '(2 2) :initial-element 0))
				(setf (aref *m* 0 0) 1) (setf (aref *m* 0 1) 2)
				(setf (aref *m* 1 0) 3) (setf (aref *m* 1 1) 4)
				(print (adjust-array *m* '(3 3) :initial-element 0))
				(defparameter *fv* (make-array 4 :fill-pointer 2 :initial-element 5))
				(print (fill-pointer (adjust-array *fv* 8)))
				""")).isEqualTo("(#(7 7 7 0 0) NIL)\n(T #(1 1 1 9 9))\n#2A((1 2 0) (3 4 0) (0 0 0))\n2");
	}

	@Test
	void compileDisplacedArrays() throws Exception {
		// A displaced view aliases the target's storage in both directions, prints and
		// measures with its own dims, works over a rank-2 target, and keeps following
		// an adjustable target grown in place by adjust-array.
		assertThat(compileAndRun("""
				(defparameter *base* (make-array 6 :initial-element 0))
				(dotimes (i 6) (setf (aref *base* i) (* i 10)))
				(defparameter *view* (make-array 3 :displaced-to *base* :displaced-index-offset 2))
				(setf (aref *view* 0) 99)
				(print (aref *base* 2))
				(setf (aref *base* 4) 111)
				(print (list *view* (aref *view* 2) (length *view*)))
				(defparameter *mat* (make-array '(2 3) :initial-element 0))
				(dotimes (i 6) (setf (row-major-aref *mat* i) i))
				(print (make-array 3 :displaced-to *mat* :displaced-index-offset 3))
				(defparameter *tgt* (make-array 4 :adjustable t :initial-element 1))
				(defparameter *dv* (make-array 2 :displaced-to *tgt* :displaced-index-offset 1))
				(adjust-array *tgt* 6 :initial-element 8)
				(setf (aref *tgt* 1) 55)
				(print (aref *dv* 0))
				""")).isEqualTo("99\n(#(99 30 111) 111 3)\n#(3 4 5)\n55");
	}

	@Test
	void compileArrayDisplacementValues() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *base* (make-array 5))
				(defparameter *view* (make-array 2 :displaced-to *base* :displaced-index-offset 3))
				(multiple-value-bind (tgt off) (array-displacement *view*)
				  (print (list (eq tgt *base*) off)))
				(multiple-value-bind (tgt off) (array-displacement *base*)
				  (print (list tgt off)))
				""")).isEqualTo("(T 3)\n(NIL 0)");
	}

	@Test
	void compileMakeArrayDisplacedKeywordComboIsACompileError() {
		assertThatThrownBy(() -> compileAndRun("(print (make-array 3 :displaced-to (make-array 5) :fill-pointer 2))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("cannot be combined");
	}

	@Test
	void compileCoerceConversions() throws Exception {
		assertThat(compileAndRun("""
				(print (coerce '(1 2 3) 'vector))
				(print (coerce (vector 1 2 3) 'list))
				(print (coerce "ab" 'list))
				(print (coerce '(#\\a #\\b) 'string))
				(print (coerce '(1 2) 'list))
				(print (coerce "hi" 'string))
				""")).isEqualTo("#(1 2 3)\n(1 2 3)\n(#\\a #\\b)\n\"ab\"\n(1 2)\n\"hi\"");
	}

	@Test
	void compileAndRunLinalgConstructorsAndShape() throws Exception {
		assertThat(compileAndRunLinalg("""
				(print (linalg:zeros 3))
				(print (linalg:eye 2))
				(print (linalg:arange 2 10 2))
				(print (linalg:linspace 0 1 5))
				(print (linalg:from-list '((1 2) (3 4))))
				(print (linalg:shape (linalg:from-list '((1 2 3) (4 5 6)))))
				(print (linalg:reshape (linalg:arange 6) '(2 3)))
				(print (linalg:transpose (linalg:from-list '((1 2 3) (4 5 6)))))
				"""))
			.isEqualTo("#d(0.0 0.0 0.0)\n#d((1.0 0.0) (0.0 1.0))\n#d(2.0 4.0 6.0 8.0)\n#d(0.0 0.25 0.5 0.75 1.0)\n"
					+ "#d((1.0 2.0) (3.0 4.0))\n(2 3)\n#d((0.0 1.0 2.0) (3.0 4.0 5.0))\n#d((1.0 4.0) (2.0 5.0) (3.0 6.0))");
	}

	@Test
	void compileAndRunLinalgArithmeticAndReductions() throws Exception {
		assertThat(compileAndRunLinalg("""
				(print (linalg:add (linalg:from-list '(1 2 3)) 10))
				(print (linalg:mul (linalg:from-list '((1 2) (3 4))) (linalg:from-list '((5 6) (7 8)))))
				(print (linalg:emap (lambda (x) (* x x)) (linalg:arange 4)))
				(print (linalg:dot (linalg:from-list '(1 2 3)) (linalg:from-list '(4 5 6))))
				(print (linalg:matmul (linalg:from-list '((1 2) (3 4))) (linalg:from-list '((5 6) (7 8)))))
				(print (linalg:sum (linalg:from-list '((1 2) (3 4)))))
				(print (linalg:mean (linalg:from-list '(1 2 3 4))))
				(print (linalg:argmax (linalg:from-list '(1 9 3))))
				(print (linalg:norm (linalg:from-list '(3 4))))
				""")).isEqualTo("#d(11.0 12.0 13.0)\n#d((5.0 12.0) (21.0 32.0))\n#d(0.0 1.0 4.0 9.0)\n32.0\n"
				+ "#d((19.0 22.0) (43.0 50.0))\n10.0\n2.5\n1\n5.0");
	}

	@Test
	void compileAndRunLinalgLinearAlgebra() throws Exception {
		// linalg computes in packed double-float (speed over exactness); inv/solve use
		// power-of-two matrices whose float results are exact and print identically on
		// every backend (a general 2x2 inverse would carry roundoff that WASM prints at
		// fewer significant digits). det of a non-singular matrix stays a clean double.
		assertThat(compileAndRunLinalg("""
				(print (linalg:det (linalg:from-list '((1 2) (3 4)))))
				(print (linalg:inv (linalg:from-list '((4 0) (2 4)))))
				(print (linalg:solve (linalg:from-list '((4 0) (2 4))) (linalg:from-list '(8 8))))
				(print (linalg:array-equal (linalg:matmul (linalg:from-list '((4 0) (2 4)))
				                                          (linalg:inv (linalg:from-list '((4 0) (2 4)))))
				                           (linalg:eye 2)))
				""")).isEqualTo("-2.0\n#d((0.25 0.0) (-0.125 0.25))\n#d(2.0 1.0)\nT");
	}

	@Test
	void compileExptWithRuntimeDoubleBase() throws Exception {
		// hasDoubleLiteral only sees literals: a double base arriving through a
		// variable or slot used to hit the exact-rational _pow and cast Double to
		// BigInteger. _pow now short-circuits a Double base to Math.pow.
		assertThat(compileAndRun("""
				(defparameter *b* 0.999)
				(defparameter *i* 0)
				(setq *i* (+ *i* 1))
				(print (expt *b* *i*))
				(print (expt *b* 3))
				(defparameter *db* 2.0)
				(print (expt *db* -2))
				(defparameter *ib* 2)
				(print (expt *ib* 10))
				(print (expt *ib* -1))
				""")).isEqualTo("0.999\n0.997002999\n0.25\n1024\n1/2");
	}

	@Test
	void compileLambdaCapturesAcrossDotimesAndDoStar() throws Exception {
		// FreeVarAnalyzer must expand dotimes/do* before the free-variable walk;
		// without the cases their loop variables were misread as free references
		// ("Cannot capture variable: k" from a maphash callback).
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 10)
				(setf (gethash "b" *h*) 20)
				(let ((scale 2)
				      (total 0))
				  (maphash (lambda (key v)
				             (dotimes (k 2)
				               (setq total (+ total (* scale v)))))
				           *h*)
				  (print total))
				(let ((acc 0))
				  (funcall (lambda ()
				             (do* ((i 1 (+ i 1))
				                   (s i (+ s i)))
				                  ((> i 3))
				               (setq acc (+ acc s)))))
				  (print acc))
				""")).isEqualTo("120\n10");
	}

	@Test
	void compileAndRunLinalgAxisReductionsAndRandom() throws Exception {
		// The deep-learning-from-scratch additions: numpy axis/keepdims reductions,
		// reshape -1 inference, the seeded Wichmann-Hill RNG (bit-identical on every
		// backend), and the indexing/selection/comparison helpers.
		assertThat(compileAndRunLinalg("""
				(defparameter *m* (linalg:from-list '((1 2 3) (4 5 6))))
				(print (linalg:sum *m* 0))
				(print (linalg:sum *m* -1 t))
				(print (linalg:mean *m* 0))
				(print (linalg:amax *m* 1))
				(print (linalg:amin *m* 0 t))
				(print (linalg:argmax *m* 1))
				(print (linalg:argmin *m* 0))
				(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) 1))
				(print (linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1))))
				(linalg:seed 42)
				(print (linalg:choice 60000 4))
				(linalg:seed 9)
				(print (linalg:permutation 10))
				(linalg:seed 1)
				(print (linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:rand '(2 2))))
				(linalg:seed 7)
				(print (linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:randn 4)))
				(print (linalg:take-rows *m* #(1 0)))
				(print (linalg:row *m* 1))
				(print (linalg:gather *m* #(2 0)))
				(print (linalg:one-hot #(1 0 2) 3))
				(print (linalg:greater *m* 3))
				(print (linalg:equal (linalg:argmax *m* 1) #(2 2)))
				(print (linalg:zeros-like (linalg:ones 2 'single-float)))
				""")).isEqualTo("#d(5.0 7.0 9.0)\n#d((6.0) (15.0))\n#d(2.5 3.5 4.5)\n#d(3.0 6.0)\n#d((1.0 2.0 3.0))\n"
				+ "#d(2.0 2.0)\n#d(0.0 0.0 0.0)\n#d((12.0 15.0 18.0 21.0) (48.0 51.0 54.0 57.0))\n(3 4)\n"
				+ "#d(26833.0 11120.0 29256.0 22347.0)\n#d(4.0 5.0 6.0 2.0 9.0 7.0 1.0 0.0 8.0 3.0)\n"
				+ "#d((317.0 637.0) (949.0 376.0))\n#d(284.0 -21.0 221.0 -1653.0)\n"
				+ "#d((4.0 5.0 6.0) (1.0 2.0 3.0))\n#d(4.0 5.0 6.0)\n#d(3.0 4.0)\n"
				+ "#d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))\n#d((0.0 0.0 0.0) (1.0 1.0 1.0))\n"
				+ "#d(1.0 1.0)\n#f(0.0 0.0)");
	}

	@Test
	void compileAndRunLinalgTransposeAxesPadIm2col() throws Exception {
		// The ch07 CNN additions: transpose with an axes list (rank-n
		// permutation; the 2-argument call declines the --simd transpose kernel),
		// np.pad's constant-0 mode, and the internal rank-4 %la-im2col/%la-col2im
		// pair behind the convolution examples.
		assertThat(compileAndRunLinalg("""
				(defparameter *x* (linalg:reshape (linalg:arange 24) '(2 3 4)))
				(print (linalg:shape (linalg:transpose *x* '(1 0 2))))
				(print (linalg:transpose (linalg:from-list '((1 2) (3 4))) '(1 0)))
				(print (linalg:array-equal (linalg:transpose (linalg:transpose *x* '(1 2 0)) '(2 0 1)) *x*))
				(print (linalg:pad (linalg:from-list '((1 2) (3 4))) '((1 1) (2 2))))
				(print (linalg:pad #(1 2) 1))
				(defparameter *img* (linalg:reshape (linalg:arange 16) '(1 1 4 4)))
				(defparameter *col* (linalg::%la-im2col *img* 2 2 2 0))
				(print *col*)
				(print (linalg:array-equal (linalg::%la-col2im *col* '(1 1 4 4) 2 2 2 0) *img*))
				(print (linalg::%la-col2im (linalg::%la-im2col (linalg:ones '(1 1 3 3)) 2 2 1 0) '(1 1 3 3) 2 2 1 0))
				(print (array-element-type (linalg:transpose (linalg:ones '(2 2 2) 'single-float) '(2 1 0))))
				(print (array-element-type (linalg:pad (linalg:ones 2 'single-float) 1)))
				""")).isEqualTo("(3 2 4)\n#d((1.0 3.0) (2.0 4.0))\nT\n"
				+ "#d((0.0 0.0 0.0 0.0 0.0 0.0) (0.0 0.0 1.0 2.0 0.0 0.0) (0.0 0.0 3.0 4.0 0.0 0.0)"
				+ " (0.0 0.0 0.0 0.0 0.0 0.0))\n#d(0.0 1.0 2.0 0.0)\n"
				+ "#d((0.0 1.0 4.0 5.0) (2.0 3.0 6.0 7.0) (8.0 9.0 12.0 13.0) (10.0 11.0 14.0 15.0))\nT\n"
				+ "#d((((1.0 2.0 1.0) (2.0 4.0 2.0) (1.0 2.0 1.0))))\nSINGLE-FLOAT\nSINGLE-FLOAT");
	}

	@Test
	void compileAndRunLinalgRankThreeElementwise() throws Exception {
		assertThat(compileAndRunLinalg("""
				(defparameter *c* (linalg:reshape (linalg:arange 8) '(2 2 2)))
				(print *c*)
				(print (linalg:add *c* 10))
				(print (linalg:mul *c* *c*))
				(print (linalg:sum *c*))
				(print (linalg:amax *c*))
				(print (linalg:array-equal (linalg:flatten *c*) (linalg:arange 8)))
				(print (linalg:zeros '(2 2 2)))
				(print (linalg:ndim *c*))
				(print (linalg:ndim 5))
				""")).isEqualTo("#d(((0.0 1.0) (2.0 3.0)) ((4.0 5.0) (6.0 7.0)))\n"
				+ "#d(((10.0 11.0) (12.0 13.0)) ((14.0 15.0) (16.0 17.0)))\n"
				+ "#d(((0.0 1.0) (4.0 9.0)) ((16.0 25.0) (36.0 49.0)))\n28.0\n7.0\nT\n"
				+ "#d(((0.0 0.0) (0.0 0.0)) ((0.0 0.0) (0.0 0.0)))\n3\n0");
	}

	@Test
	void compileAndRunLinalgNumpyStyleBroadcasting() throws Exception {
		// numpy general broadcasting between arrays of different shapes: trailing
		// axes align, and an axis of extent 1 (or a missing leading axis) stretches.
		// The result keeps the FIRST array operand's width.
		assertThat(compileAndRunLinalg("""
				(print (linalg:mul #2A((1 2) (3 4)) #(10 20)))
				(print (linalg:add #2A((1 2) (3 4)) #2A((100) (200))))
				(print (linalg:sub #(1 2) #d(1.0)))
				(print (linalg:maximum #2A((1 5) (4 2)) #(3 3)))
				(print (linalg:mul (linalg:from-list '((1 2) (3 4)) 'single-float) #(10 20)))
				(print (linalg:div #3A(((2.0 4.0) (6.0 8.0))) #(2 4)))
				""")).isEqualTo("#d((10.0 40.0) (30.0 80.0))\n#d((101.0 102.0) (203.0 204.0))\n#d(0.0 1.0)\n"
				+ "#d((3.0 5.0) (4.0 3.0))\n#f((10.0 40.0) (30.0 80.0))\n#d(((1.0 1.0) (3.0 2.0)))");
	}

	@Test
	void compileAndRunLinalgDiffAndGradient() throws Exception {
		// numpy calculus parity: diff = n-th discrete difference along the last axis
		// (numpy's docs example values), gradient = second-order central differences
		// with first-order one-sided ends over a uniform scalar spacing or a
		// non-uniform coordinate vector. All sample values differentiate exactly,
		// so the printed doubles are identical on every backend.
		assertThat(compileAndRunLinalg("""
				(print (linalg:diff #(1 2 4 7 0)))
				(print (linalg:diff #(1 2 4 7 0) 2))
				(print (linalg:diff #2A((1 3 6) (0 5 6))))
				(print (linalg:gradient #(0 1 4 9 16)))
				(print (linalg:gradient #(0 1 4 9 16) 2))
				(print (linalg:gradient #(0 1 9) #(0 1 3)))
				(print (array-element-type (linalg:gradient (linalg:arange 0 4 'single-float))))
				""")).isEqualTo("#d(1.0 2.0 3.0 -7.0)\n#d(1.0 1.0 -10.0)\n#d((2.0 3.0) (5.0 1.0))\n"
				+ "#d(1.0 2.0 4.0 6.0 7.0)\n#d(0.5 1.0 2.0 3.0 3.5)\n#d(1.0 2.0 4.0)\nSINGLE-FLOAT");
	}

	@Test
	void compileAndRunLinalgSingleFloatWidthPolymorphism() throws Exception {
		// linalg is width-polymorphic -- a constructor opts into single-float
		// (#f) with a trailing element-type, and every transform PRESERVES the input
		// width, so a #f value is never silently widened to double (which would force a
		// mixed-width --simd error on a following vec:matvec). Double stays the default.
		// f32-exact values (integers / halves) so the printed form is byte-identical to
		// the interpreter and WASM.
		assertThat(compileAndRunLinalg("""
				(print (linalg:zeros '(2 2) 'single-float))
				(print (linalg:zeros '(2 2)))
				(print (linalg:arange 0 8 2 'single-float))
				(print (linalg:from-list '((1 2) (3 4)) 'single-float))
				(let ((w (linalg:from-list '((1 2) (3 4)) 'single-float)))
				  (print (linalg:sub w (linalg:mul (linalg:ones '(2 2) 'single-float) 0.5)))
				  (print (array-element-type (linalg:transpose w)))
				  (print (linalg:matmul w (linalg:eye 2 'single-float))))
				(print (array-element-type (linalg:add (linalg:from-list '(1 2 3)) 10)))
				(print (linalg:dot (linalg:from-list '(1 2 3) 'single-float)
				                   (linalg:from-list '(1 2 3) 'single-float)))
				""")).isEqualTo("#f((0.0 0.0) (0.0 0.0))\n#d((0.0 0.0) (0.0 0.0))\n#f(0.0 2.0 4.0 6.0)\n"
				+ "#f((1.0 2.0) (3.0 4.0))\n#f((0.5 1.5) (2.5 3.5))\nSINGLE-FLOAT\n"
				+ "#f((1.0 2.0) (3.0 4.0))\nDOUBLE-FLOAT\n14.0");
	}

	@Test
	void compileAndRunJsonParseReturnsHashTablesAndVectors() throws Exception {
		assertThat(compileAndRunJson("""
				(let ((h (rontolisp:json-parse "{\\"name\\": \\"rontolisp\\", \\"n\\": 2}")))
				  (print (list (gethash "name" h) (gethash "n" h))))
				""")).isEqualTo("(\"rontolisp\" 2)");
		assertThat(compileAndRunJson(
				"(print (gethash \"b\" (gethash \"a\" (rontolisp:json-parse \"{\\\"a\\\": {\\\"b\\\": [1, true, null]}}\"))))"))
			.isEqualTo("#(1 T NULL)");
	}

	@Test
	void compileAndRunBuiltinNicknamesTriggerLibrarySplice() throws Exception {
		// rl:/la: are built-in nicknames for rontolisp/linalg; the library pre-passes
		// scan the program before package resolution, so they must recognize the
		// nickname spellings or the splice never fires and compilation fails.
		assertThat(compileAndRunJson("(print (gethash \"a\" (rl:json-parse \"{\\\"a\\\": 7}\")))")).isEqualTo("7");
		assertThat(compileAndRunLinalg("(print (la:to-list (la:from-list '(1 2 3))))")).isEqualTo("(1.0 2.0 3.0)");
	}

	@Test
	void compileAndRunJsonParseScalarsAndEscapes() throws Exception {
		assertThat(compileAndRunJson("""
				(print (rontolisp:json-parse "42"))
				(print (rontolisp:json-parse "-3.5"))
				(print (rontolisp:json-parse "1e3"))
				(print (rontolisp:json-parse "1234567890123"))
				(print (floatp (rontolisp:json-parse "1234567890123456789")))
				(print (rontolisp:json-parse "[1, [2, \\"x\\"], null]"))
				(print (rontolisp:json-parse "\\"\\\\u0041\\\\u3042\\""))
				""")).isEqualTo("42\n-3.5\n1000.0\n1234567890123\nT\n#(1 #(2 \"x\") NULL)\n\"A\u3042\"");
	}

	@Test
	void compileAndRunJsonParseObjectsAreHashTables() throws Exception {
		assertThat(compileAndRunJson("""
				(print (gethash "content-type"
				                (rontolisp:json-parse "{\\"content-type\\": \\"text/html\\"}")))
				""")).isEqualTo("\"text/html\"");
	}

	@Test
	void compileAndRunJsonStringify() throws Exception {
		assertThat(compileAndRunJson("""
				(print (rontolisp:json-stringify (list 1 (list 2 3) nil)))
				(print (rontolisp:json-stringify (vector t 'null 1.5)))
				(print (rontolisp:json-stringify 3/2))
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "x" h) (list 1 2))
				  (print (rontolisp:json-stringify h)))
				""")).isEqualTo("\"[1,[2,3],false]\"\n\"[true,null,1.5]\"\n\"1.5\"\n\"{\\\"x\\\":[1,2]}\"");
	}

	@Test
	void compileAndRunJsonRoundTrip() throws Exception {
		assertThat(compileAndRunJson(
				"(print (rontolisp:json-stringify (rontolisp:json-parse \"{\\\"deep\\\": {\\\"list\\\": [{\\\"k\\\": \\\"v\\\"}, 2.5, true]}}\")))"))
			.isEqualTo("\"{\\\"deep\\\":{\\\"list\\\":[{\\\"k\\\":\\\"v\\\"},2.5,true]}}\"");
	}

	@Test
	void compileAndRunJsonFunctionsAreFirstClass() throws Exception {
		assertThat(compileAndRunJson("""
				(print (funcall #'rontolisp:json-stringify (list 1 2)))
				(print (funcall #'rontolisp:json-parse "[7]"))
				""")).isEqualTo("\"[1,2]\"\n#(7)");
	}

	@Test
	void compileAndRunJsonDoubleColonQualifierNamesTheSameFunctions() throws Exception {
		assertThat(compileAndRunJson("""
				(print (rontolisp::json-stringify (list 1 2 3)))
				(print (gethash "n" (rontolisp::json-parse "{\\"n\\": 5}")))
				""")).isEqualTo("\"[1,2,3]\"\n5");
	}

	@Test
	void compileAndRunJsonSplicedLibraryStaysOutOfClUserIntrospection() throws Exception {
		assertThat(compileAndRunJson("""
				(defun my-fn (x) x)
				(print (rontolisp:json-parse "1"))
				(print (rontolisp:list-functions :cl-user))
				""")).isEqualTo("1\n(MY-FN)");
	}

	@Test
	void compileAndRunPlistAndAlistHashTable() throws Exception {
		// single-key objects: hash-table iteration order is backend-specific
		List<LispVal> program = am.ik.rontolisp.eval.JsonLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(print (rontolisp:json-stringify (rontolisp:plist-hash-table (list :name "x"))))
					(print (rontolisp:hash-table-plist (rontolisp:plist-hash-table (list :a 5))))
					(print (rontolisp:json-stringify (rontolisp:alist-hash-table (list (cons "msg" "hi")))))
					(print (rontolisp:hash-table-alist (rontolisp:alist-hash-table (list (cons "k" 7)))))
					""")));
		assertThat(compileAndRun(program))
			.isEqualTo("\"{\\\"name\\\":\\\"x\\\"}\"\n(:A 5)\n\"{\\\"msg\\\":\\\"hi\\\"}\"\n((\"k\" . 7))");
	}

	@Test
	void compileAndRunAlistPlistAndPlistAlist() throws Exception {
		// no hash table in between, so the order is deterministic on every backend
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (rontolisp:alist-plist (list (cons :a 1) (cons :b 2))))
				(print (rontolisp:plist-alist (list :a 1 :b 2)))
				(print (rontolisp:alist-plist (rontolisp:plist-alist (list :x 1 :y 2))))
				"""));
		assertThat(compileAndRun(program)).isEqualTo("(:A 1 :B 2)\n((:A . 1) (:B . 2))\n(:X 1 :Y 2)");
	}

	@Test
	void compileAndRunJsonStringifyClosInstance() throws Exception {
		// CLOS instance -> object (slots in definition order), a hash-table slot
		// nests as an object, a list slot as an array -- matching jzon
		assertThat(compileAndRunJson("""
				(defclass json-resp () ((status :initarg :status) (headers :initarg :headers) (items :initarg :items)))
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "content-type" h) "application/json")
				  (print (rontolisp:json-stringify
				          (make-instance 'json-resp :status 200 :headers h :items (list 1 2 3)))))
				""")).isEqualTo(
				"\"{\\\"status\\\":200,\\\"headers\\\":{\\\"content-type\\\":\\\"application/json\\\"},\\\"items\\\":[1,2,3]}\"");
	}

	@Test
	void compileAndRunUrlDecodeAndEncode() throws Exception {
		assertThat(compileAndRunUrl("""
				(print (rontolisp:url-decode "Will+it+work%3F"))
				(print (rontolisp:url-decode "%E3%81%82%E3%81%84"))
				(print (rontolisp:url-encode "a b/c~d"))
				(print (rontolisp:url-encode "あ"))
				(print (rontolisp:url-decode (rontolisp:url-encode "日本語 text?&=")))
				""")).isEqualTo("\"Will it work?\"\n\"あい\"\n\"a%20b%2Fc~d\"\n\"%E3%81%82\"\n\"日本語 text?&=\"");
	}

	@Test
	void compileAndRunQueryParamsAndQueryParam() throws Exception {
		assertThat(compileAndRunUrl("""
				(print (rontolisp:query-params "a=1&b=two&flag"))
				(print (rontolisp:query-params nil))
				(print (rontolisp:query-param "a=1&name=ronto%20lisp" "name"))
				(print (rontolisp:query-param "a=1" "missing"))
				""")).isEqualTo("((\"a\" . \"1\") (\"b\" . \"two\") (\"flag\" . \"\"))\nNIL\n\"ronto lisp\"\nNIL");
	}

	@Test
	void compileAndRunUrlPathAndUrlQuery() throws Exception {
		assertThat(compileAndRunUrl("""
				(print (rontolisp:url-path "/get?a=1"))
				(print (rontolisp:url-query "/get?a=1"))
				(print (rontolisp:url-query "/get"))
				""")).isEqualTo("\"/get\"\n\"a=1\"\nNIL");
	}

	@Test
	void compileAndRunUrlFunctionsAreFirstClass() throws Exception {
		assertThat(compileAndRunUrl("""
				(print (mapcar #'rontolisp:url-decode (list "a%2Bb" "1+2")))
				(print (funcall #'rontolisp:url-encode "x y"))
				""")).isEqualTo("(\"a+b\" \"1 2\")\n\"x%20y\"");
	}

	@Test
	void compileAndRunUrlSplicedLibraryStaysOutOfClUserIntrospection() throws Exception {
		assertThat(compileAndRunUrl("""
				(defun my-fn (x) x)
				(print (rontolisp:url-decode "1"))
				(print (rontolisp:list-functions :cl-user))
				""")).isEqualTo("\"1\"\n(MY-FN)");
	}

	@Test
	void compileAndRunDefunRest() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &rest r) (list a r))
				(print (f 1 2 3))
				(print (f 1))
				""")).isEqualTo("(1 (2 3))\n(1 NIL)");
	}

	@Test
	void compileAndRunDefunOptional() throws Exception {
		assertThat(compileAndRun("""
				(defun f (x &optional (y 10) (z (* y 2) zp)) (list x y z zp))
				(print (f 1))
				(print (f 1 2))
				(print (f 1 2 3))
				""")).isEqualTo("(1 10 20 NIL)\n(1 2 4 NIL)\n(1 2 3 T)");
	}

	@Test
	void compileAndRunDefunKeywordArguments() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &key (k 1 kp) m) (list a k kp m))
				(print (f 0))
				(print (f 0 :k 5))
				(print (f 0 :m 7 :k 9))
				(print (f 0 :bogus 1 :allow-other-keys t))
				""")).isEqualTo("(0 1 NIL NIL)\n(0 5 T NIL)\n(0 9 T 7)\n(0 1 NIL NIL)");
	}

	@Test
	void compileAndRunDefunOptionalRestKeyCombined() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &optional b &rest r &key c &allow-other-keys) (list a b r c))
				(print (f 1 2 :c 3 :d 4))
				(defun g (x &aux (y (+ x 1)) z) (list x y z))
				(print (g 5))
				""")).isEqualTo("(1 2 (:C 3 :D 4) 3)\n(5 6 NIL)");
	}

	@Test
	void compileAndRunVariadicFirstClass() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &rest r) (list a r))
				(print (funcall (lambda (&rest xs) xs) 1 2 3))
				(print (funcall #'f 1 2 3))
				(print (apply #'f 1 (list 2 3)))
				(print (mapcar (lambda (x &optional (y 100)) (+ x y)) (list 1 2 3)))
				(print ((lambda (a &rest r) (list a r)) 1 2 3))
				""")).isEqualTo("(1 2 3)\n(1 (2 3))\n(1 (2 3))\n(101 102 103)\n(1 (2 3))");
	}

	@Test
	void compileDefunArityMismatchFails() {
		assertThatThrownBy(() -> compileAndRun("(defun f (a b) (+ a b)) (print (f 1))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects 2 arguments, got 1");
		assertThatThrownBy(() -> compileAndRun("(defun f (a &rest r) r) (print (f))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects at least 1 argument, got 0");
	}

	// The instance tag is written with |...| because the reader upcases every ordinary
	// symbol: a source-written '%struct-POINT reads as %STRUCT-POINT and can never match
	// a real tag, which is what stops a program forging an instance.
	@Test
	void compileAndRunInstancePrimitives() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(setq p (%obj-new '|%struct-POINT| 10 20))
				(print (%obj-ref p 0))
				(print (%obj-set p 1 99))
				(print (%obj-ref p 1))
				(print (%obj-is p '|%struct-POINT|))
				(print (%obj-is p '|%struct-OTHER| '|%struct-POINT|))
				(print (%obj-is p '|%struct-OTHER|))
				(print (%obj-tag p))
				(print (%obj-p p))
				(print (%obj-ref (%obj-new '|%struct-POINT| 1) 1))
				""")).isEqualTo("10\n99\n99\nT\nT\nNIL\n%struct-POINT\nT\nNIL");
	}

	@Test
	void compileAndRunInstancePredicateRejectsEveryOtherShape() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(print (list (%obj-p nil) (%obj-p (cons 1 2)) (%obj-p '(1 2 3)) (%obj-p 1/2)
				             (%obj-p #\\a) (%obj-p "s") (%obj-p 5) (%obj-p 1.5)
				             (%obj-p (make-array 2)) (%obj-p (make-hash-table)) (%obj-p #'car)
				             (%obj-p (cons #'car 2)) (%obj-p (cons "x" 2))))
				(print (%obj-tag (cons 1 2)))
				""")).isEqualTo("(NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL)\nNIL");
	}

	@Test
	void compileAndRunInstanceSlotsAndEqualp() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(defstruct empty)
				(defclass box () ((w :initarg :w)))
				(print (list (%obj-slots (%obj-new '|%struct-POINT| 1 "hi"))
				             (%obj-slots (%obj-new '|%struct-EMPTY|))
				             (%obj-slots '(1 2)) (%obj-slots 5)))
				(print (list (equalp (make-point :x 1 :y "A") (make-point :x 1 :y "a"))
				             (equalp (make-point :x 1 :y "A") (make-point :x 2 :y "a"))
				             (equalp (make-point :x 1 :y 2) (make-point :x 1.0 :y 2))
				             (equalp (make-instance 'box :w 1) (make-instance 'box :w 1))
				             (equalp (make-point :x 1 :y 2) (make-instance 'box :w 1))))
				(print (list (equal (make-point :x 1 :y 2) (make-point :x 1 :y 2))
				             (equal (make-point :x 1 :y 2) (make-point :x 1 :y 9))
				             (equal (make-point :x 1 :y 2) (list 1 2))))
				""")).isEqualTo("((1 \"hi\") NIL NIL NIL)\n(T NIL T T NIL)\n(T NIL NIL)");
	}

	// A program with BOTH a layout constant and a condition channel shares one <clinit>;
	// an under-declared max_stack there is a load-time VerifyError, so this must RUN.
	@Test
	void compileAndRunInstancePrimitivesAlongsideHandlerCase() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(setq p (%obj-new '|%struct-POINT| 1 2))
				(print (handler-case (error "boom") (error (e) (%obj-ref p 1))))
				""")).isEqualTo("2");
	}

	// A #S(...) source literal is folded into an instance before compilation, so it must
	// print and behave exactly like the constructed one. The printer's max_stack is
	// hand-written, so this RUNS the class.
	@Test
	void compileAndRunStructLiteral() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(print #S(POINT :X 1 :Y "hi"))
				(print (list (point-p #S(POINT :X 1 :Y 2)) (consp #S(POINT :X 1 :Y 2)) (point-x #S(POINT :X 7 :Y 2))))
				(print (equal #S(POINT :X 1 :Y 2) (make-point :x 1 :y 2)))
				""")).isEqualTo("#S(POINT :X 1 :Y \"hi\")\n(T NIL 7)\nT");
	}

	@Test
	void compileAndRunStructLiteralShapes() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point (x 10) (y 20))
				(defstruct empty)
				(defstruct outer i)
				(print '#S(POINT :X 1 :Y 2))
				(print `(a #S(POINT :X 3 :Y 4)))
				(print (aref #(#S(POINT :X 5 :Y 6)) 0))
				(print #s(point :y 2))
				(print #S(POINT :X 1 :Y 2 :X 99))
				(print #S(EMPTY))
				(print #S(OUTER :I #S(EMPTY)))
				(print (point-x #S(POINT :X (+ 1 2))))
				""")).isEqualTo("""
				#S(POINT :X 1 :Y 2)
				(A #S(POINT :X 3 :Y 4))
				#S(POINT :X 5 :Y 6)
				#S(POINT :X 10 :Y 2)
				#S(POINT :X 1 :Y 2)
				#S(EMPTY)
				#S(OUTER :I #S(EMPTY))
				(+ 1 2)""");
	}

	@Test
	void compileStructLiteralOfAnUnknownTypeFails() {
		assertThatThrownBy(() -> compileAndRun("(print #S(NOPE :X 1))"))
			.hasMessageContaining("NOPE is not a defined structure type");
	}

	@Test
	void compileStructLiteralOfAnUnknownSlotFails() {
		assertThatThrownBy(() -> compileAndRun("(defstruct point x y) (print #S(POINT :Z 1))"))
			.hasMessageContaining("POINT has no slot named :Z");
	}

	@Test
	void compileStructLiteralBeforeItsDefstructFails() {
		assertThatThrownBy(() -> compileAndRun("(print #S(POINT :X 1)) (defstruct point x)"))
			.hasMessageContaining("POINT is not a defined structure type");
	}

	@Test
	void compileAndRunDefstructBasics() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x (y 10))
				(setq p (make-point :x 1))
				(print (point-x p))
				(print (point-y p))
				(print (point-p p))
				(print (point-p '(1 2)))
				(setq q (copy-point p))
				(setf (point-x q) 100)
				(print (point-x p))
				(print (point-x q))
				""")).isEqualTo("1\n10\nT\nNIL\n1\n100");
	}

	@Test
	void compileAndRunDefstructSetfPlacesAndFirstClassAccessors() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(setq p (make-point :x 1 :y 2))
				(setf (point-x p) 99)
				(incf (point-y p) 5)
				(print (list (point-x p) (point-y p)))
				(print (mapcar #'point-x (list (make-point :x 10) (make-point :x 20))))
				(defun shift (pt dx) (incf (point-x pt) dx) pt)
				(print (point-x (shift p 1)))
				""")).isEqualTo("(99 7)\n(10 20)\n100");
	}

	@Test
	void compileAndRunSetfFunctionDefinition() throws Exception {
		assertThat(compileAndRun("""
				(defvar *mode* :xml)
				(defun (setf my-mode) (m) (setq *mode* m))
				(setf (my-mode) :html5)
				(print *mode*)
				(funcall #'(setf my-mode) :sgml)
				(print *mode*)
				""")).isEqualTo(":HTML5\n:SGML");
	}

	@Test
	void compileAndRunDefstructInUserPackage() throws Exception {
		assertThat(compileAndRun("""
				(defpackage :geo (:use :cl))
				(in-package :geo)
				(defstruct pt x y)
				(defun dist2 (p) (+ (* (pt-x p) (pt-x p)) (* (pt-y p) (pt-y p))))
				(in-package :cl-user)
				(setq p (geo::make-pt :x 3 :y 4))
				(print (geo::dist2 p))
				(print (geo::pt-p p))
				""")).isEqualTo("25\nT");
	}

	@Test
	void compileNestedDefstructFails() {
		assertThatThrownBy(() -> compileAndRun("(defun f () (defstruct point x y)) (f)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("DEFSTRUCT is only supported as a top-level form");
	}

	@Test
	void compileAndRunDefgenericDefmethodEqlDispatch() throws Exception {
		assertThat(compileAndRun("""
				(defgeneric describe-it (x))
				(defmethod describe-it (x) (list :default x))
				(defmethod describe-it ((x (eql :br))) (list :special x))
				(print (list (describe-it 5) (describe-it :br)))
				(print (funcall #'describe-it 9))
				""")).isEqualTo("((:DEFAULT 5) (:SPECIAL :BR))\n(:DEFAULT 9)");
	}

	@Test
	void compileAndRunDefclassSlotsAccessorsAndDispatch() throws Exception {
		assertThat(compileAndRun("""
				(defclass animal () ((name :initarg :name :accessor animal-name)))
				(defclass dog (animal) ((breed :initarg :breed :initform "mixed" :reader dog-breed)))
				(defgeneric speak (x))
				(defmethod speak ((x dog)) "woof")
				(defmethod speak ((x animal)) "...")
				(defmethod speak ((x integer)) "number")
				(defmethod speak (x) "?")
				(setq d (make-instance 'dog :name "Rex"))
				(print (list (speak d) (speak (make-instance 'animal :name "A")) (speak 1) (speak "s")))
				(print (list (animal-name d) (dog-breed d) (slot-value d 'name)))
				(setf (animal-name d) "Max")
				(setf (slot-value d 'name) (concatenate 'string (slot-value d 'name) "!"))
				(print (animal-name d))
				""")).isEqualTo("(\"woof\" \"...\" \"number\" \"?\")\n(\"Rex\" \"mixed\" \"Rex\")\n\"Max!\"");
	}

	@Test
	void compileAndRunMultiParameterMethodDispatch() throws Exception {
		assertThat(compileAndRun("""
				(defclass mp-animal () ())
				(defclass mp-dog (mp-animal) ())
				(defclass mp-cat (mp-animal) ())
				(defgeneric mp-meets (a b))
				(defmethod mp-meets ((a mp-dog) (b mp-cat)) :chase)
				(defmethod mp-meets ((a mp-cat) (b mp-dog)) :flee)
				(defmethod mp-meets ((a mp-animal) (b mp-animal)) :ignore)
				(let ((d (make-instance 'mp-dog)) (c (make-instance 'mp-cat)))
				  (print (list (mp-meets d c) (mp-meets c d) (mp-meets d d))))
				""")).isEqualTo("(:CHASE :FLEE :IGNORE)");
	}

	@Test
	void compileAndRunVariadicGenericForwardsRestTail() throws Exception {
		assertThat(compileAndRun("""
				(defclass vg-base () ())
				(defclass vg-sub (vg-base) ())
				(defgeneric vg-desc (x &rest extras))
				(defmethod vg-desc ((x vg-sub) &rest extras) (list :sub extras))
				(defmethod vg-desc ((x vg-base) &rest extras) (list :base extras))
				(print (vg-desc (make-instance 'vg-sub) 1 2))
				(print (vg-desc (make-instance 'vg-base) 3))
				""")).isEqualTo("(:SUB (1 2))\n(:BASE (3))");
	}

	@Test
	void compileAndRunDefclassDefaultInitargs() throws Exception {
		assertThat(compileAndRun("""
				(defclass di-conf () ((host :initarg :host) (port :initarg :port))
				  (:default-initargs :host "localhost" :port 8080))
				(let ((c1 (make-instance 'di-conf)) (c2 (make-instance 'di-conf :port 9090)))
				  (print (list (slot-value c1 'host) (slot-value c1 'port)))
				  (print (slot-value c2 'port)))
				""")).isEqualTo("(\"localhost\" 8080)\n9090");
	}

	@Test
	void compileAndRunWithSlotsWriteThrough() throws Exception {
		assertThat(compileAndRun("""
				(defclass ws-pt () ((x :initarg :x) (y :initarg :y)))
				(let ((p (make-instance 'ws-pt :x 1 :y 2)))
				  (with-slots (x (why y)) p
				    (setf x (+ x 10))
				    (print (list x why)))
				  (print (slot-value p 'x)))
				""")).isEqualTo("(11 2)\n11");
	}

	@Test
	void compileAndRunDefstructOptions() throws Exception {
		assertThat(compileAndRun("""
				(defstruct (so-kv (:constructor make-so-pair) (:conc-name so-get-)
				                  (:predicate so-kv?) (:copier so-clone))
				  key val)
				(let ((k (make-so-pair :key 'a :val 1)))
				  (print (list (so-get-key k) (so-kv? k) (so-kv? 5)))
				  (let ((k2 (so-clone k)))
				    (setf (so-get-val k2) 99)
				    (print (list (so-get-val k) (so-get-val k2)))))
				""")).isEqualTo("(A T NIL)\n(1 99)");
	}

	@Test
	void compileAndRunDefstructIncludePredicateMatchesLaterChildren() throws Exception {
		// The parent's predicate is regenerated once the whole program is registered, so
		// a child (and grandchild) whose defstruct FOLLOWS the parent's still answers T
		// -- and an unrelated value still answers NIL.
		assertThat(compileAndRun("""
				(defstruct dp-base a)
				(defstruct (dp-child (:include dp-base)) b)
				(defstruct (dp-grand (:include dp-child)) c)
				(print (list (dp-base-p (make-dp-child)) (dp-base-p (make-dp-grand))
				             (dp-child-p (make-dp-grand)) (dp-grand-p (make-dp-child))
				             (dp-base-p (make-dp-base)) (dp-base-p 5)))
				""")).isEqualTo("(T T T NIL T NIL)");
	}

	@Test
	void compileAndRunListSpecializedMethodExcludesClassInstances() throws Exception {
		// An instance is a tagged cons internally, but must dispatch to the
		// standard-object/default method, not a list/cons/sequence-specialized one.
		assertThat(compileAndRun("""
				(defgeneric spx-kind (x)
				  (:method (x) :object)
				  (:method ((x list)) :list)
				  (:method ((x standard-object)) :instance))
				(defclass spx-thing () ((v :initarg :v)))
				(print (list (spx-kind (make-instance 'spx-thing :v 1)) (spx-kind '(1 2)) (spx-kind 5)))
				""")).isEqualTo("(:INSTANCE :LIST :OBJECT)");
	}

	@Test
	void compileAndRunEqualpComparesArraysElementwise() throws Exception {
		assertThat(compileAndRun("""
				(print (equalp #(1 "A" (2 3)) #(1 "a" (2 3.0))))
				(print (equalp #(1) #(1 2)))
				(print (equalp #(1) "x"))
				""")).isEqualTo("T\nNIL\nNIL");
	}

	@Test
	void compileAndRunStreampAcceptsTheStandardOutputDesignator() throws Exception {
		assertThat(compileAndRun("""
				(print (streamp t))
				(print (streamp "x"))
				(let ((s t)) (check-type s stream) (print :ok))
				""")).isEqualTo("T\nNIL\n:OK");
	}

	@Test
	void compileAndRunFormatAsFirstClassFunction() throws Exception {
		assertThat(compileAndRun("""
				(print (apply #'format nil "x=~a y=~d" '(5 7)))
				(funcall #'format t "to-stdout ~a~%" "ok")
				(print (funcall #'format nil "~s" "q"))
				""")).isEqualTo("\"x=5 y=7\"\nto-stdout ok\n\"\\\"q\\\"\"");
	}

	@Test
	void compileAndRunPipeEscapedSymbols() throws Exception {
		assertThat(compileAndRun("""
				(print (symbol-name '|when used|))
				(print '|noChange|)
				""")).isEqualTo("\"when used\"\nnoChange");
	}

	@Test
	void compileAndRunDefmethodBeforeDefclassSubclassStillDispatches() throws Exception {
		// The pre-pass collects the whole program before generating dispatchers, so a
		// subclass defined AFTER the method still matches its class specializer.
		assertThat(compileAndRun("""
				(defclass animal () ())
				(defgeneric speak (x))
				(defmethod speak ((x animal)) :animal)
				(defclass cat (animal) ())
				(print (speak (make-instance 'cat)))
				""")).isEqualTo(":ANIMAL");
	}

	@Test
	void compileAndRunClosInUserPackage() throws Exception {
		assertThat(compileAndRun("""
				(defpackage :zoo (:use :cl) (:export :speak :make-dog))
				(in-package :zoo)
				(defclass dog () ((name :initarg :name :accessor dog-name)))
				(defgeneric speak (x))
				(defmethod speak ((x dog)) (list :woof (dog-name x)))
				(defmethod speak (x) :silence)
				(defun make-dog (name) (make-instance 'dog :name name))
				(in-package :cl-user)
				(print (zoo:speak (zoo:make-dog "Rex")))
				(print (zoo:speak 42))
				""")).isEqualTo("(:WOOF \"Rex\")\n:SILENCE");
	}

	@Test
	void compileAndRunMacroCallingGenericAtExpansionTime() throws Exception {
		// The cl-who pattern: a defmacro whose expansion-time helper chain calls a
		// generic function (with an eql-specialized method). Pre-processed with
		// UserMacroExpander like the CLI compile path.
		assertThat(compileAndRun(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defgeneric convert-tag (tag body)
				  (:documentation "Convert a tag."))
				(defmethod convert-tag (tag body)
				  "The standard method which is not specialized."
				  (declare (optimize speed space))
				  (list "<" tag ">" body "</" tag ">"))
				(defmethod convert-tag ((tag (eql :br)) body)
				  (list "<br/>"))
				(defun process-tag (tag body) (convert-tag tag body))
				(defmacro html (tag body) `(list ,@(process-tag tag body)))
				(print (html "p" "hi"))
				(print (html :br nil))
				""")))).isEqualTo("(\"<\" \"p\" \">\" \"hi\" \"</\" \"p\" \">\")\n(\"<br/>\")");
	}

	@Test
	void compileAndRunMethodQualifiersAndCallNextMethod() throws Exception {
		assertThat(compileAndRun("""
				(defclass animal () ())
				(defclass dog (animal) ())
				(defparameter *log* nil)
				(defgeneric touch (x))
				(defmethod touch ((x animal)) (push :primary-animal *log*) :done)
				(defmethod touch ((x dog)) (push :primary-dog *log*) (call-next-method))
				(defmethod touch :before ((x animal)) (push :before-animal *log*))
				(defmethod touch :before ((x dog)) (push :before-dog *log*))
				(defmethod touch :after ((x animal)) (push :after-animal *log*))
				(defmethod touch :after ((x dog)) (push :after-dog *log*))
				(print (touch (make-instance 'dog)))
				(print (reverse *log*))
				""")).isEqualTo(
				":DONE\n" + "(:BEFORE-DOG :BEFORE-ANIMAL :PRIMARY-DOG :PRIMARY-ANIMAL :AFTER-ANIMAL :AFTER-DOG)");
	}

	@Test
	void compileAndRunAroundMethodAndNextMethodP() throws Exception {
		assertThat(compileAndRun("""
				(defclass thing () ())
				(defclass gadget (thing) ())
				(defgeneric render (x))
				(defmethod render ((x thing)) (list :thing (next-method-p)))
				(defmethod render ((x gadget)) (cons :gadget (call-next-method)))
				(defmethod render :around ((x thing)) (list :around (call-next-method)))
				(print (render (make-instance 'gadget)))
				""")).isEqualTo("(:AROUND (:GADGET :THING NIL))");
	}

	@Test
	void compileNestedDefmethodFails() {
		assertThatThrownBy(() -> compileAndRun("(defun f () (defmethod g (x) x)) (f)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("DEFMETHOD is only supported as a top-level form");
	}

	// --- Dynamic (special) variable binding ---

	@Test
	void specialVarLetHasDynamicExtent() throws Exception {
		assertThat(compileAndRun("""
				(defvar *x* 10)
				(print (list *x* (let ((*x* 20)) *x*) *x*))
				""")).isEqualTo("(10 20 10)");
	}

	@Test
	void specialVarBindingVisibleAcrossFunctionCalls() throws Exception {
		// The dynamic binding is seen by a called function reading the special's static
		// field.
		assertThat(compileAndRun("""
				(defvar *y* 1)
				(defun get-y () *y*)
				(print (list (get-y) (let ((*y* 2)) (get-y)) (get-y)))
				""")).isEqualTo("(1 2 1)");
	}

	@Test
	void specialVarNestedBindingsStackAndSetq() throws Exception {
		assertThat(compileAndRun("""
				(defvar *x* 1)
				(print (list *x* (let ((*x* 2)) (let ((*x* 3)) *x*)) (let ((*x* 5)) (setq *x* 6) *x*) *x*))
				""")).isEqualTo("(1 3 6 1)");
	}

	@Test
	void specialVarLetStarIsSequential() throws Exception {
		assertThat(compileAndRun("""
				(defvar *a* 1)
				(print (let* ((*a* 2) (b *a*)) (list *a* b)))
				""")).isEqualTo("(2 2)");
	}

	@Test
	void defparameterAndDeclaimSpecialAreDynamic() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *p* 5)
				(print (list (let ((*p* 6)) *p*) *p*))
				""")).isEqualTo("(6 5)");
		assertThat(compileAndRun("""
				(declaim (special *s*))
				(setq *s* 100)
				(print (list (let ((*s* 7)) *s*) *s*))
				""")).isEqualTo("(7 100)");
	}

	@Test
	void lexicalGlobalLetStaysLexical() throws Exception {
		// A top-level setq global that is NOT special is rebound lexically by let.
		assertThat(compileAndRun("""
				(setq g 1)
				(defun read-g () g)
				(print (list (let ((g 2)) (read-g)) g))
				""")).isEqualTo("(1 1)");
	}

	@Test
	void progvIsRejectedOnJvm() {
		assertThatThrownBy(() -> compileAndRun("(progv '(a) '(1) a)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("PROGV");
	}

	@Test
	void specialVarBindingIsThreadScoped() throws Exception {
		// Interpreter parity (LispEvaluatorTest.specialVariablesAreThreadScoped): a
		// dynamic binding belongs to the thread that established it. A thread spawned
		// during the extent reads the GLOBAL value, its own binding is invisible to the
		// spawner, and its exit restore must not clobber the spawner's still-active
		// binding. Thread.start/join make every step deterministic (no sleeps).
		assertThat(compileAndRun("""
				(defvar *v* :none)
				(defun peek () *v*)
				(defun run-thread (f)
				  (let ((th (java:new "java.lang.Thread" f)))
				    (java:call th "start")
				    (java:call th "join")))
				(let ((*v* :outer))
				  (run-thread (lambda (m) (print (list :spawned (peek)))))
				  (run-thread (lambda (m) (let ((*v* :inner)) (print (list :rebound (peek))))))
				  (print (list :outer (peek))))
				(print (list :global (peek)))
				""")).isEqualTo("""
				(:SPAWNED :NONE)
				(:REBOUND :INNER)
				(:OUTER :OUTER)
				(:GLOBAL :NONE)""");
	}

	@Test
	void specialVarSetqOutsideAnyBindingReachesTheGlobal() throws Exception {
		// A setq of a dynamically-bound special with NO binding active must write the
		// global cell (and a later binding still save/restores over it).
		assertThat(compileAndRun("""
				(defvar *w* 1)
				(defun poke (v) (setq *w* v))
				(poke 2)
				(print (list *w* (let ((*w* 3)) (poke 4) *w*) *w*))
				""")).isEqualTo("(2 4 2)");
	}

	// ---- IEEE-754 float edge semantics: literal-path and comparison groups ----

	@Test
	void unaryMinusOfFloatLiteralFormNegates() throws Exception {
		// The double-literal fast path used to compile unary minus as identity.
		assertThat(compileAndRun("(print (- 5.0))")).isEqualTo("-5.0");
		assertThat(compileAndRun("(print (- (* 2.0 3.0)))")).isEqualTo("-6.0");
		assertThat(compileAndRun("(print (- -1.5))")).isEqualTo("1.5");
		assertThat(compileAndRun("(print (- 0.0))")).isEqualTo("-0.0");
	}

	@Test
	void negativeZeroLiteralKeepsItsSign() throws Exception {
		// -0.0 used to fold into DCONST_0 because -0.0 == 0.0.
		assertThat(compileAndRun("(print -0.0)")).isEqualTo("-0.0");
		assertThat(compileAndRun("(print (/ 1.0 -0.0))")).isEqualTo("-Infinity");
	}

	@Test
	void nanComparisonsAreUnorderedOnBothPaths() throws Exception {
		// DCMPL-for-every-operator made < and <= answer t against NaN.
		assertThat(compileAndRun("(print (< (/ 0.0 0.0) 1.0))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (<= (/ 0.0 0.0) 1.0))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (> (/ 0.0 0.0) 1.0))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (>= (/ 0.0 0.0) 1.0))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (= (/ 0.0 0.0) (/ 0.0 0.0)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (/= (/ 0.0 0.0) (/ 0.0 0.0)))")).isEqualTo("T");
		// No float literal in the comparison form: the _cmp runtime path.
		assertThat(compileAndRun(
				"(let ((n (/ 0.0 0.0)) (one 1.0)) (print (list (< n one) (<= n one) (> n one) (>= n one) (= n n) (/= n n))))"))
			.isEqualTo("(NIL NIL NIL NIL NIL T)");
		assertThat(compileAndRun("(let ((z (* -1.0 0.0)) (p (* 1.0 0.0))) (print (= z p)))")).isEqualTo("T");
	}

	@Test
	void minMaxDoubleLiteralPathFollowsMathMinMax() throws Exception {
		// Passes once the -0.0 literal survives compilation: Math.min/max are
		// sign- and NaN-aware on the double-literal path.
		assertThat(compileAndRun("(print (min 0.0 -0.0))")).isEqualTo("-0.0");
		assertThat(compileAndRun("(print (max -0.0 0.0))")).isEqualTo("0.0");
	}

	// ---- Method name mangling ----

	@Test
	void percentInAFunctionNameIsMangledAway() throws Exception {
		// '%' is legal in a JVM method name, but JVMCI passes a message containing the
		// name as the format string of BailoutException: under a JVMCI compiler, a hot
		// method whose name contains e.g. '%l' kills its own JIT compilation
		// (UnknownFormatConversionException) and prints a systemic-compilation-failure
		// warning into the program's stdout.
		assertThat(JvmLispCompiler.mangleMethodName("linalg::%la-matmul")).isEqualTo("linalg$colon$colon$pctla-matmul");
		byte[] classBytes = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(defun %la-double (x) (* 2 x)) (print (%la-double 21))"));
		assertThat(new String(classBytes, StandardCharsets.ISO_8859_1)).doesNotContain("%la-double")
			.contains("$pctLA-DOUBLE");
		assertThat(compileAndRun("(defun %la-double (x) (* 2 x)) (print (%la-double 21))")).isEqualTo("42");
	}

	@Test
	void percentGlobalVariableNameIsMangledAway() throws Exception {
		// Static field names go through the same mangler ("_g$" + mangleMethodName).
		byte[] classBytes = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(defvar *%p* 7) (print *%p*)"));
		assertThat(new String(classBytes, StandardCharsets.ISO_8859_1)).doesNotContain("_g$*%p*");
		assertThat(compileAndRun("(defvar *%p* 7) (print *%p*)")).isEqualTo("7");
	}

	// ---- rontolisp:wit-import: one WIT, a provider per backend ----
	//
	// The JVM half of the boundary: the very programs LispEvaluatorTest interprets are
	// COMPILED and RUN here, and must answer the same. The Preview 1 WASM half -- where
	// the
	// host is the provider and the directive lowers to a rontolisp:wasm-import block
	// byte-identical to a hand-written one -- is pinned by WitImportInlinerTest.

	/**
	 * The interface the tests bind: a wasi:keyvalue-shaped store, whose {@code bucket} is
	 * a WIT resource (so its methods bind as {@code bucket-get} / {@code bucket-set} /
	 * ... taking the handle as their first argument), with a {@code variant} error arm
	 * and a freestanding opener.
	 */
	private static final String KEYVALUE_WIT = """
			package wasi:keyvalue@0.2.0;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string),
			  }

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			    delete: func(key: string) -> result<_, error>;
			    exists: func(key: string) -> result<bool, error>;
			    list-keys: func() -> result<list<string>, error>;
			  }

			  open: func(identifier: string) -> result<bucket, error>;
			}
			""";

	/**
	 * A second interface, bound by the test that pins what a call with NO provider does.
	 */
	private static final String WEBGL_WIT = """
			package local:webgl;

			interface gl {
			  create-shader: func(kind: s32) -> s32;
			}
			""";

	/**
	 * The store the tests bind -- an in-memory wasi:keyvalue/store, the shape of
	 * {@code examples/wit/keyvalue/memory-store.lisp}, and character for character the
	 * one {@code LispEvaluatorTest} interprets.
	 *
	 * <p>
	 * rontolisp ships NO provider for any concrete interface: the core knows the provider
	 * MECHANISM, and nothing about what wasi:keyvalue is. Implementing a WIT interface is
	 * ordinary user code, so it is ordinary Lisp -- which means it COMPILES into the
	 * class like any other defun, and the provider a {@code %wit-call} dispatches through
	 * is one this program itself defined.
	 */
	private static final String MEMORY_STORE = """
			(defvar *buckets* (make-hash-table :test #'eql))
			(defvar *next-handle* 1)
			(defun store-bucket (handle)
			  (let ((bucket (gethash handle *buckets*)))
			    (if (null bucket)
			        (error 'rontolisp:wit-error :payload :no-such-store
			               :message "memory store: not an open bucket handle")
			        bucket)))
			(defun memory-store (member &rest args)
			  (cond ((string= member "open")
			         (let ((handle *next-handle*))
			           (setq *next-handle* (+ handle 1))
			           (setf (gethash handle *buckets*) (make-hash-table :test #'equal))
			           handle))
			        ((string= member "bucket-get")
			         (gethash (nth 1 args) (store-bucket (nth 0 args))))
			        ((string= member "bucket-set")
			         (setf (gethash (nth 1 args) (store-bucket (nth 0 args))) (nth 2 args))
			         nil)
			        ((string= member "bucket-delete")
			         (remhash (nth 1 args) (store-bucket (nth 0 args)))
			         nil)
			        ((string= member "bucket-exists")
			         (if (gethash (nth 1 args) (store-bucket (nth 0 args))) t nil))
			        ((string= member "bucket-list-keys")
			         (let ((keys nil))
			           (maphash (lambda (key value) value (push key keys))
			                    (store-bucket (nth 0 args)))
			           (nreverse keys)))
			        (t (error 'rontolisp:wit-error :payload (list :other member)
			                  :message "memory store: no such member"))))
			(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store)
			""";

	// wit-import tests run the compile-path pre-passes the CLI runs, in the CLI's order:
	// WitImportInliner lowers the directive into ordinary defuns dispatching through the
	// interface's provider (BEFORE macro expansion -- the (defpackage kv ...) it
	// synthesizes
	// must exist before any pass resolves a kv: call site), and WitLibrary then splices
	// the
	// WIT runtime those %wit-call bodies land in. The prelude carries string<.
	private String compileAndRunWit(String lispCode) throws Exception {
		List<LispVal> lowered = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(lispCode),
				tempDir.toString(), am.ik.rontolisp.compiler.WitExportDirective.Backend.OTHER,
				am.ik.rontolisp.eval.SourceLoader.fileSystem());
		return compileAndRun(am.ik.rontolisp.eval.WitLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(lowered))));
	}

	// Writes a WIT into the temp dir -- the base directory compileAndRunWit resolves the
	// directive's relative path against, exactly as the CLI resolves it against the
	// source
	// file's own directory -- and returns the directive that binds it.
	private String witImport(String file, String wit, String iface, String pkg) throws Exception {
		Files.writeString(tempDir.resolve(file), wit);
		return "(rontolisp:wit-import \"" + file + "\" :interface \"" + iface + "\" :package " + pkg + ")\n";
	}

	private String keyvalueImport() throws Exception {
		return witImport("kv.wit", KEYVALUE_WIT, "wasi:keyvalue/store@0.2.0", "kv");
	}

	@Test
	void compileAndRunWitImportAgainstTheProviderTheProgramBinds() throws Exception {
		// The whole interface, driven end to end through a store the PROGRAM binds -- and
		// the store is plain Lisp, so it compiles into the class alongside the bindings
		// that dispatch through it: a resource is an opaque integer handle, a value is a
		// byte string (list<u8>), and a missing key is nil (option<list<u8>> =
		// value-or-nil,
		// not an error). Same source, same answer as the interpreter.
		assertThat(compileAndRunWit(keyvalueImport() + MEMORY_STORE + """
				(defvar *b* (kv:open "cache"))
				(kv:bucket-set *b* "greeting" "hello")
				(kv:bucket-set *b* "count" "3")
				(defvar *stored* (list (integerp *b*)
				                       (kv:bucket-get *b* "greeting")
				                       (kv:bucket-get *b* "absent")
				                       (kv:bucket-exists *b* "greeting")
				                       (sort (kv:bucket-list-keys *b*) #'string<)))
				(kv:bucket-delete *b* "greeting")
				(print (append *stored* (list (kv:bucket-exists *b* "greeting")
				                              (sort (kv:bucket-list-keys *b*) #'string<))))
				""")).isEqualTo("(T \"hello\" NIL T (\"count\" \"greeting\") NIL (\"count\"))");
	}

	@Test
	void compileAndRunWitImportBindingsAreOrdinaryDefunsSoTheyWorkAsValues() throws Exception {
		// The property most likely to regress: a binding is an ORDINARY defun, so it is
		// collected in Pass 1 like any other and #'kv:bucket-get / funcall / apply /
		// mapcar
		// work with no extra wiring.
		assertThat(compileAndRunWit(keyvalueImport() + MEMORY_STORE + """
				(defvar *b* (kv:open "cache"))
				(kv:bucket-set *b* "a" "1")
				(kv:bucket-set *b* "b" "2")
				(print (list (functionp #'kv:bucket-get)
				             (funcall #'kv:bucket-get *b* "a")
				             (apply #'kv:bucket-get (list *b* "b"))
				             (mapcar (lambda (k) (funcall #'kv:bucket-get *b* k)) '("a" "b" "zz"))))
				""")).isEqualTo("(T \"1\" \"2\" (\"1\" \"2\" NIL))");
	}

	@Test
	void compileAndRunASecondWitProvideReplacesTheFirst() throws Exception {
		// Binding a provider REPLACES whatever was bound for the interface before it, and
		// that is what makes a store swappable: examples/wit/keyvalue develops against an
		// in-memory store and then, on THIS backend, binds a
		// java.util.LinkedHashMap-backed
		// one over the top of it -- one line, and not a character of the program changes.
		// Here the memory store is bound first and a stub replaces it, so every call
		// below
		// lands in the stub.
		assertThat(compileAndRunWit(keyvalueImport() + MEMORY_STORE + """
				(defvar *writes* nil)
				(defun stub-store (member &rest args)
				  (cond ((string= member "open") 7)
				        ((string= member "bucket-set")
				         (setq *writes* (cons (nth 1 args) *writes*))
				         nil)
				        ((string= member "bucket-get")
				         (concatenate 'string "stub:" (nth 1 args)))
				        (t (error 'rontolisp:wit-error :payload (list :other member)
				                  :message "the stub store does not implement it"))))
				(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'stub-store)
				(defvar *b* (kv:open "cache"))
				(kv:bucket-set *b* "k" "v")
				(print (list *b* (kv:bucket-get *b* "k") *writes*))
				""")).isEqualTo("(7 \"stub:k\" (\"k\"))");
	}

	@Test
	void compileAndRunWitErrorArmSignalsWitErrorCarryingTheMappedPayload() throws Exception {
		// The settled mapping: a WIT result<T, E>'s ok arm is the value and its error arm
		// SIGNALS rontolisp:wit-error, whose payload is the mapped E. The PROVIDER is
		// what
		// signals it -- so both arms below come out of a store written in the test: the
		// memory store's `no-such-store` for a handle it never handed out (an enum-shaped
		// variant arm = a keyword), and a stub's own `other(string)` (a payload-carrying
		// arm = a tagged list). Every handler-case stays in a defvar initform: in
		// argument
		// position it would meet a non-empty operand stack, which this backend cannot
		// compile.
		assertThat(compileAndRunWit(keyvalueImport() + MEMORY_STORE + """
				(defvar *bad-handle* (handler-case (kv:bucket-get 424242 "k")
				                       (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
				(defun stub-store (member &rest args)
				  (if (string= member "open")
				      7
				      (error 'rontolisp:wit-error :payload (list :other member)
				             :message "the stub store does not implement it")))
				(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'stub-store)
				(defvar *stub* (handler-case (kv:bucket-set (kv:open "cache") "k" "v")
				                 (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
				(print (list *bad-handle* *stub*))
				""")).isEqualTo("(:NO-SUCH-STORE (:OTHER \"bucket-set\"))");
	}

	@Test
	void compileAndRunWitImportWithNoProviderBoundSignalsAClearWitError() throws Exception {
		// The honest statement of what the core does with a wit-import: it binds the
		// WIT's
		// functions, and NOTHING ELSE. rontolisp knows the provider mechanism; it does
		// not
		// know what any concrete interface means, and it ships an implementation of none
		// --
		// so a bound function with no provider behind it does not quietly answer a
		// default,
		// it says so, and names the one thing that fixes it.
		assertThat(compileAndRunWit(witImport("gl.wit", WEBGL_WIT, "local:webgl/gl", "gl") + """
				(defvar *r* (handler-case (gl:create-shader 35633)
				              (rontolisp:wit-error (e)
				                (list (rontolisp:wit-error-payload e) (format nil "~a" e)))))
				(print (car *r*))
				(print (car (cdr *r*)))
				"""))
			// The payload is the interface nothing was bound for, and the report says
			// what
			// to do about it.
			.contains("\"local:webgl/gl\"")
			.contains("No provider is bound for the WIT interface local:webgl/gl")
			.contains("rontolisp:wit-provide");
	}

	@Test
	void compileAndRunUpcaseReaderMode() throws Exception {
		// The reader's upcase premise is a frontend concern: the compiler sees the
		// folded token stream, so mixed-case operators, upcased user symbols, upcased
		// keyword arguments and upcased data keywords all behave as on the
		// interpreter.
		am.ik.rontolisp.reader.Features upcase = am.ik.rontolisp.reader.Features.JVM;
		String output = compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(DEFUN ADD2 (X) (+ X 2))
				(PRINT (add2 40))
				(PRINT (CDR (ASSOC "x" '(("x" . 1)) :TEST #'EQUAL)))
				(PRINT (CDR (ASSOC :b '((:A . 1) (:B . 2)))))
				(PRINT (SYMBOL-NAME :foo))
				""", upcase)));
		assertThat(output).isEqualTo("42\n1\n2\n\"FOO\"");
	}

	@Test
	void compileAndRunUpcaseReaderModeFoldsRuntimeRead() throws Exception {
		// The embedded reader runtime folds like the frontend (upcase premise): a user
		// symbol reads upcased, a standard operator folds back to its canonical
		// lowercase spelling so it stays eq to a compiled quoted reference, and the fold
		// is byte-identical to the interpreter and both WASM backends.
		assertThat(compileAndRun("(print (read-from-string \"foo\"))")).isEqualTo("FOO");
		assertThat(compileAndRun("(print (symbol-name (read-from-string \"foo\")))")).isEqualTo("\"FOO\"");
		assertThat(compileAndRun("(print (eq (read-from-string \"car\") 'car))")).isEqualTo("T");
		assertThat(compileAndRun("(print (read-from-string \"(x . 9)\"))")).isEqualTo("(X . 9)");
	}

	@Test
	void compilePackedIntVectorMakeArrayMasksAndReadsUnsigned() throws Exception {
		// .kb/packed-integer-vectors.md: stores mask to the width, reads widen
		// unsigned, setf returns the value AS STORED -- matching the interpreter.
		// let* sequencing, not (list (setf ...) (aref ...)): compiled list arguments
		// evaluate right-to-left (.todo/014), so the store must be ordered explicitly.
		assertThat(compileAndRun("""
				(let* ((a (make-array 4 :element-type '(unsigned-byte 8) :initial-element 7))
				       (stored (setf (aref a 1) 300))
				       (readback (aref a 1)))
				  (print (list stored readback (aref a 0) (length a) a)))
				""")).isEqualTo("(44 44 7 4 #(7 44 7 7))");
		assertThat(compileAndRun(
				"(print (make-array 3 :element-type '(unsigned-byte 16) :initial-contents '(1 70000 3)))"))
			.isEqualTo("#(1 4464 3)");
		assertThat(compileAndRun("""
				(let ((a (make-array 2 :element-type '(unsigned-byte 32))))
				  (setf (aref a 0) 4294967295)
				  (setf (aref a 1) 4294967296)
				  (print a))
				""")).isEqualTo("#(4294967295 0)");
	}

	@Test
	void compilePackedIntVectorIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(let ((a (make-array 3 :element-type '(unsigned-byte 8))))
				  (print (list (array-element-type a) (arrayp a) (vectorp a) (array-dimensions a)
				               (typep a '(simple-array (unsigned-byte 8) (*))))))
				""")).isEqualTo("((UNSIGNED-BYTE 8) T T (3) T)");
		// A rank-n shape (runtime-detected) and a fill-pointer combination keep the
		// general boxed representation.
		assertThat(compileAndRun("(print (array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8))))"))
			.isEqualTo("T");
		assertThat(compileAndRun("""
				(let ((a (make-array '(2 2) :element-type '(unsigned-byte 8) :initial-element 3)))
				  (setf (aref a 1 1) 9)
				  (print (list (aref a 0 0) (aref a 1 1))))
				""")).isEqualTo("(3 9)");
	}

	@Test
	void compilePackedIntVectorSubseqCopySeqReplacePreserveThePackedType() throws Exception {
		assertThat(compileAndRun("""
				(let* ((a (make-array 4 :element-type '(unsigned-byte 8) :initial-contents '(9 8 7 6)))
				       (s (subseq a 1 3)))
				  (setf (aref s 0) 300)
				  (print (list s (array-element-type s) a)))
				""")).isEqualTo("(#(44 7) (UNSIGNED-BYTE 8) #(9 8 7 6))");
		assertThat(compileAndRun("""
				(let* ((a (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)))
				       (c (copy-seq a)))
				  (print (list c (array-element-type c))))
				""")).isEqualTo("(#(1 2 3) (UNSIGNED-BYTE 8))");
		// replace mask-stores element-wise into a packed target.
		assertThat(compileAndRun("""
				(let ((dst (make-array 3 :element-type '(unsigned-byte 8)))
				      (src #(300 2 3)))
				  (replace dst src)
				  (print dst))
				""")).isEqualTo("#(44 2 3)");
	}

	@Test
	void compilePackedIntVectorReaderLiteralAndRowMajor() throws Exception {
		// ironclad's #N@(...) table syntax bakes as a native packed long[] for the
		// 8/16/32 widths.
		assertThat(compileAndRun("(print (list #8@(1 2 300) (array-element-type #32@(1 2))))"))
			.isEqualTo("(#(1 2 44) (UNSIGNED-BYTE 32))");
		assertThat(compileAndRun("""
				(let ((a #8@(1 2 3)))
				  (%row-major-aset a 1 999)
				  (print (list (row-major-aref a 1) a)))
				""")).isEqualTo("(231 #(1 231 3))");
	}

	@Test
	void compilePackedIntVectorRejectsNonIntegerStoresAndFillPointerSurface() {
		assertThatThrownBy(() -> compileAndRun("(setf (aref (make-array 2 :element-type '(unsigned-byte 8)) 0) 1.5)"))
			.rootCause()
			.hasMessageContaining("integer");
		assertThatThrownBy(() -> compileAndRun("(aref (make-array 2 :element-type '(unsigned-byte 8)) 5)")).rootCause()
			.hasMessageContaining("out of range");
		assertThatThrownBy(() -> compileAndRun("(vector-push 1 (make-array 2 :element-type '(unsigned-byte 8)))"))
			.rootCause()
			.hasMessageContaining("packed integer vector");
	}

}
