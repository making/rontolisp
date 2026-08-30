package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.runtime.RontoHttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.macro.FoldDifferential;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.LoweredBuiltinValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class JvmLispCompilerTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode) throws Exception {
		// mirror the CLI pipeline's prelude splice (equalp/string</read-all) so the
		// prelude-backed built-ins resolve like they do in a real compile
		return compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	@Test
	void aFoldedCallPrintsWhatTheRuntimeWouldHave() throws Exception {
		// The pure-builtin fold renders in JAVA at compile time what the emitted class
		// would have computed at run time, so the two have to agree character for
		// character. Hiding the arguments behind a function parameter is what keeps the
		// runtime in the picture; every table entry has a row
		// (am.ik.rontolisp.macro.FoldDifferential).
		FoldDifferential.assertNoDivergence(compileAndRun(FoldDifferential.program()));
	}

	// Gray-stream tests pre-process with LispPreludeLibrary.process +
	// GrayStreamsLibrary.process, in the CLI pipeline's order: the dispatch helpers
	// gray.lisp splices resolve their stream through the prelude's %stream-target.
	private String compileAndRunGray(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode))));
	}

	// unread-char tests pre-process with UnreadCharLibrary.process, in the CLI
	// pipeline's order: it runs LAST, over the Gray rewrite's output, because a Gray
	// dispatch helper's handle FALLBACK is itself a call site that must reach the cell.
	private String compileAndRunUnread(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.UnreadCharLibrary.process(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode)))));
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

	// torch tests pre-process with TorchLibrary.process then LinalgLibrary.process,
	// mirroring the compile-path pre-pass order run by RontoLispCli (torch first, so
	// the linalg references inside the spliced torch defuns pull linalg in too).
	private String compileAndRunTorch(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.LinalgLibrary
			.process(am.ik.rontolisp.eval.TorchLibrary.process(LispReader.readAllFromString(lispCode))));
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

	// The CLI pipeline's order: UserMacroExpander first (it is what expands a user
	// macro, and what substitutes a spliced literal object's make-load-form), then the
	// prelude splice.
	private String compileAndRunExpanded(String lispCode) throws Exception {
		return compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString(lispCode))));
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
	void anUncaughtConditionReportsOneLineAndRethrowsWithoutATrace() throws Exception {
		// The generated main's last exception-table entry: one line on standard error
		// (the same one the interpreter and the wasm-GC landing pad write), then the
		// SAME exception rethrown with an emptied trace, so the launcher's echo is one
		// line and an in-process caller still observes the failure.
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		Throwable cause;
		try {
			cause = catchThrowable(() -> compileAndRun("""
					(define-condition uc-db (error) ((text :initarg :text :reader uc-db-text))
					  (:report (lambda (c s) (format s "database error: ~a" (uc-db-text c)))))
					(error 'uc-db :text "password authentication failed")
					"""));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(err.toString().trim())
			.isEqualTo("Unhandled condition: database error: password authentication failed");
		assertThat(cause).isInstanceOf(InvocationTargetException.class);
		Throwable signaled = ((InvocationTargetException) cause).getTargetException();
		assertThat(signaled).isInstanceOf(RuntimeException.class)
			.hasMessage("database error: password authentication failed");
		assertThat(signaled.getStackTrace()).isEmpty();
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
	void compileSetfFindClassRegistersAnAliasNameForTheSameClass() throws Exception {
		// The alias joins the registry in the definition walk, so it is an extra SPELLING
		// of the target's %class-meta-table% entry: the runtime %find-class memoizes one
		// metaobject for both names, and the static make-instance / typep / handler-case
		// resolutions see the target's class.
		assertThat(compileAndRun("""
				(defclass fca-shape () ((n :initarg :n :reader fca-n)))
				(setf (find-class '<fca-shape>) (find-class 'fca-shape))
				(define-condition fca-error (error) ((code :initarg :code :reader fca-code)))
				(setf (find-class '<fca-error>) (find-class 'fca-error))
				(print (list (eq (find-class '<fca-shape>) (find-class 'fca-shape))
				             (fca-n (make-instance '<fca-shape> :n 7))
				             (typep (make-instance 'fca-shape :n 1) '<fca-shape>)
				             (class-name (find-class '<fca-shape>))
				             (handler-case (error 'fca-error :code 42) (<fca-error> (e) (fca-code e)))))
				""")).isEqualTo("(T 7 T FCA-SHAPE 42)");
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
	void compileSlotExistsPAnswersDeclaredSlotsRegardlessOfBoundness() throws Exception {
		// The compile-path twin of the interpreter case: a literal name compiles to a
		// %obj-is membership test over the declaring layouts, a runtime name to the
		// shared %slot-exists-p-runtime dispatch.
		assertThat(compileAndRun("""
				(defclass se-box () ((a :initarg :a) (b :initform 7)))
				(let ((o (make-instance 'se-box)))
				  (print (list (slot-exists-p o 'a) (slot-exists-p o 'b)
				               (slot-exists-p o 'zz) (slot-exists-p 42 'a)
				               (slot-exists-p o (car (list 'a))))))
				""")).isEqualTo("(T T NIL NIL T)");
	}

	@Test
	void compileRuntimeErrorDispatchScalesPastTheBranchLimit() throws Exception {
		// %error-runtime used to be ONE cond over every condition class, and
		// past ~140 the outermost arm's else-branch overflowed the signed-16-bit
		// branch encoding. The dispatch is chained now (%error-runtime -> %er-1 ->
		// ...); 200 classes leave headroom past the old threshold, and the computed
		// (error ty :initarg v) at the END of the chain must still dispatch -- RUN, not
		// just compile: a segmentation bug is a VerifyError at class-load time.
		StringBuilder program = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			program.append("(define-condition big-e").append(i).append(" (simple-error) ())\n");
		}
		program.append("""
				(define-condition big-last (error) ((who :initarg :who :reader big-last-who)))
				(print (handler-case (error (car (list 'big-last)) :who 7)
				         (big-last (e) (list :caught (big-last-who e)))))
				""");
		assertThat(compileAndRun(program.toString())).isEqualTo("(:CAUGHT 7)");
	}

	@Test
	void compileSlotValueWithAPackageQualifiedRuntimeSlotName() throws Exception {
		// The runtime name arrives in the caller's package spelling while the shared
		// %slot-value-runtime dispatch matches package-stripped slot base names:
		// sxql's compute-select-statement-children reads struct slots through a
		// quoted clause-type list resolved under sxql/statement.
		assertThat(compileAndRun("""
				(defpackage :rsn-pkg (:use :cl) (:export #:make-holder))
				(in-package :rsn-pkg)
				(defstruct holder (fields-clause 7))
				(in-package :cl-user)
				(let ((h (rsn-pkg:make-holder))
				      (n (car (list 'rsn-pkg::fields-clause))))
				  (setf (slot-value h n) 8)
				  (print (slot-value h n)))
				""")).isEqualTo("8");
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
	void compileRuntimeClassDesignatorResolvesAnInternedNonExportedName() throws Exception {
		// rove's make-reporter is (make-instance (intern (format nil "~A-~A" style
		// '#:reporter) package) ...): the compile paths have no package registry at run
		// time, so intern always builds the single-colon EXTERNAL spelling
		// (RCD-PKG:SPEC-REP) while the class is registered as RCD-PKG::SPEC-REP. Every
		// generated designator dispatch matches both spellings, so the class need not be
		// exported for make-instance / find-class / typep / subtypep / the
		// condition-class arm of error to resolve it -- the interpreter's registry folds
		// the same two spellings (ClosRegistry.normalize).
		assertThat(compileAndRun("""
				(defpackage :rcd-pkg (:use :cl))
				(in-package :rcd-pkg)
				(defclass spec-rep () ((s :initarg :s)))
				(defclass sub-rep (spec-rep) ())
				(define-condition rcd-err (error) ((k :initarg :k)))
				(defun make-rep (style package)
				  (make-instance (intern (format nil "~A-~A" style '#:rep) package) :s 7))
				(in-package :cl-user)
				(let ((n (intern "SPEC-REP" :rcd-pkg)))
				  (print (slot-value (rcd-pkg::make-rep '#:spec (find-package :rcd-pkg)) 's))
				  (print (eq (find-class n) (find-class 'rcd-pkg::spec-rep)))
				  (print (typep (make-instance n :s 1) n))
				  (print (subtypep (intern "SUB-REP" :rcd-pkg) n))
				  (print (handler-case (error (intern "RCD-ERR" :rcd-pkg) :k 5)
				           (rcd-pkg::rcd-err (c) (slot-value c 'k)))))
				""")).isEqualTo("7\nT\nT\nT\n5");
	}

	@Test
	void compileTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage() throws Exception {
		// The JVM half of the type-of spelling contract (rove prints (type-of
		// (assertion-reason f)) in its failure report): type-of interns the remainder of
		// the %class- / %struct- tag, which for a class defined in another package is
		// already a canonical PKG:NAME spelling, and the compile paths' intern is
		// package-blind so it keeps that spelling. The interpreter used to home it into
		// the CURRENT package instead (TOF-APP::TOF-LIB:WIDGET) -- pinned here so the
		// four backends stay one answer
		// (LispEvaluatorTest#evalTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage).
		assertThat(compileAndRun("""
				(defpackage :tof-lib (:use :cl) (:export :widget :make-w))
				(in-package :tof-lib)
				(defclass widget () ())
				(defclass gadget () ())
				(defstruct point x y)
				(defun make-w () (make-instance 'widget))
				(defun make-g () (make-instance 'gadget))
				(defpackage :tof-app (:use :cl))
				(in-package :tof-app)
				(print (list (type-of (tof-lib:make-w))
				             (type-of (tof-lib::make-g))
				             (type-of (tof-lib::make-point :x 1 :y 2))))
				(print (list (eq (type-of (tof-lib:make-w)) 'tof-lib:widget)
				             (eq (type-of (tof-lib::make-g)) 'tof-lib::gadget)
				             (eq (type-of (tof-lib::make-point :x 1 :y 2)) 'tof-lib::point)
				             (eq (type-of (tof-lib:make-w)) (class-name (class-of (tof-lib:make-w))))
				             (type-of 42)))
				""")).isEqualTo("(TOF-LIB:WIDGET TOF-LIB::GADGET TOF-LIB::POINT)\n(T T T T INTEGER)");
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
	void compileTypepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers() throws Exception {
		// The compile-path twin of
		// LispEvaluatorTest#typepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers: the
		// emitted %typep-runtime/%subtypep-runtime preambles replace a metaobject
		// argument with its class name before the tag/ancestor tables are scanned.
		assertThat(compileAndRun("""
				(defclass mo-super () ())
				(defclass mo-sub (mo-super) ())
				(print (list (subtypep (find-class 'mo-sub) (find-class 'mo-super))
				             (subtypep (find-class 'mo-super) (find-class 'mo-sub))
				             (subtypep (find-class 'mo-sub) 'mo-super)
				             (subtypep 'mo-sub (find-class 'mo-super))
				             (typep (find-class 'mo-sub) 'class)
				             (typep (find-class 'mo-sub) 'standard-class)
				             (typep 42 'class)
				             (typep (make-instance 'mo-sub) (find-class 'mo-super))
				             (typep (make-instance 'mo-super) (find-class 'mo-sub))
				             (typep 42 (find-class 'integer))
				             (typep 42 (find-class 't))
				             (typep (make-instance 'mo-sub) (find-class 't))))
				""")).isEqualTo("(T NIL T T T T NIL T NIL T T T)");
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
	void compileDefclassMetaclassEnsureClassUsingClassAndInitargMunging() throws Exception {
		// Mirrors
		// LispEvaluatorTest#defclassMetaclassEnsureClassUsingClassAndInitargMunging
		// on the compile path (the mito shape): e-c-u-c routing +
		// chain-fill initarg munging + initfunction + same-name redefinition (the
		// second defclass reinitializes the SAME metaobject at runtime while the
		// static tables keep the last definition).
		assertThat(compileAndRun(am.ik.rontolisp.MopWideningFixture.MITO_SHAPE_SOURCE + """
				(print (list *mt-first*
				             (let ((c (find-class 'mt-user)))
				               (list *mt-ecuc*
				                     (slot-value c 'table-name)
				                     (mapcar (lambda (s) (%obj-ref s 0)) (%obj-ref c 2))
				                     (slot-value c 'col-count)
				                     (mt-user-id (make-instance 'mt-user :id 1))))))
				""")).isEqualTo(am.ik.rontolisp.MopWideningFixture.MITO_SHAPE_EXPECTED);
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
	void compileAndRunNoApplicableMethodIsCatchableAndReportsTheSameText() throws Exception {
		// The last resort signals a typed condition whose :report renders lazily; a
		// handler that PRINTS it must see exactly the old eager message.
		assertThat(compileAndRun("""
				(defclass nam-box () ((v :initarg :v :reader nam-box-v)))
				(print (handler-case (nam-box-v 42) (error (e) (princ-to-string e))))
				""")).isEqualTo("\"No applicable method: NAM-BOX-V on INTEGER\"");
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
	void compileAndRunAnInnerHandlerCaseShadowsAnEnclosingHandlerBind() throws Exception {
		// CLHS 9.1.4.1: handlers run MOST RECENT FIRST and handler-case transfers
		// control, so the nearer handler-case handles the condition and the enclosing
		// handler-bind handler never runs.
		assertThat(compileAndRun("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (error "boom") (error () :caught)))))
				""")).isEqualTo(":CAUGHT");
		assertThat(compileAndRun("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (ignore-errors (error "boom")))))
				""")).isEqualTo("NIL");
	}

	@Test
	void compileAndRunAnInnerHandlerCaseWhoseClausesDoNotMatchStillLetsTheHandlerBindRun() throws Exception {
		assertThat(compileAndRun("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (error "boom") (end-of-file () :caught)))))
				""")).isEqualTo(":OUTER-RAN");
	}

	@Test
	void compileAndRunAHandlerCaseClauseBodyDoesNotCatchWhatItSignals() throws Exception {
		assertThat(compileAndRun("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (error "boom") (error () (error "again"))))))
				""")).isEqualTo(":OUTER-RAN");
	}

	@Test
	void compileAndRunAnInnerHandlerCaseShadowsAnEnclosingHandlerBindForABuiltInError() throws Exception {
		assertThat(compileAndRun("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (car 1) (error () :caught)))))
				""")).isEqualTo(":CAUGHT");
	}

	@Test
	void compileAndRunHandlerBindSeesTheErrorABuiltInRaises() throws Exception {
		// The rove shape: a raw runtime failure (a bad car, an index out
		// of bounds) lands in the handler-bind expansion's %hb-guard pad, which runs
		// the cluster stack and rethrows.
		assertThat(compileAndRun(
				"(print (block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (car 1))))"))
			.isEqualTo(":CAUGHT");
		assertThat(compileAndRun(
				"(print (block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (aref (vector 1 2) 5))))"))
			.isEqualTo(":CAUGHT");
	}

	@Test
	void compileAndRunHandlerBindSeesAnUndefinedFunctionError() throws Exception {
		assertThat(compileAndRun(
				"(print (block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (no-such-function-xyz 1))))"))
			.isEqualTo(":CAUGHT");
	}

	@Test
	void compileAndRunHandlerBindRunsEachClusterOnceForABuiltInErrorInnermostFirst() throws Exception {
		assertThat(compileAndRun("""
				(print (let ((log nil))
				         (handler-case
				             (handler-bind ((error (lambda (c) (setq log (cons :outer log)))))
				               (handler-bind ((error (lambda (c) (setq log (cons :inner log)))))
				                 (car 1)))
				           (error (e) (cons :caught log)))))
				""")).isEqualTo("(:CAUGHT :OUTER :INNER)");
	}

	@Test
	void compileAndRunHandlerBindHandlerAndHandlerCaseSeeTheSameInstance() throws Exception {
		// The signal path attaches the instance %run-handlers saw to the throw, so
		// the handlers run once and handler-case dispatches on the identical
		// condition.
		assertThat(compileAndRun("""
				(print (let ((seen nil) (n 0))
				         (handler-case
				             (handler-bind ((error (lambda (c) (setq n (+ n 1)) (setq seen c))))
				               (error "boom"))
				           (error (e) (list :caught (eq e seen) n)))))
				""")).isEqualTo("(:CAUGHT T 1)");
	}

	@Test
	void compileAndRunStandardConditionTypeNamesAreClSymbols() throws Exception {
		// cl owns the condition type names, so a (:use #:cl) package
		// spells them bare -- which is also what makes the RUNTIME type test below
		// match the registry's plain class name.
		assertThat(compileAndRun("""
				(defpackage #:ct-pkg (:use #:cl))
				(in-package #:ct-pkg)
				(print (list 'type-error 'condition 'warning 'division-by-zero 'undefined-function 'end-of-file))
				""")).isEqualTo("(TYPE-ERROR CONDITION WARNING DIVISION-BY-ZERO UNDEFINED-FUNCTION END-OF-FILE)");
	}

	@Test
	void compileAndRunRuntimeTypeSpecifierNamingASeededConditionClass() throws Exception {
		assertThat(compileAndRun("""
				(print (list (let ((ty 'type-error)) (typep (make-condition 'type-error) ty))
				             (let ((ty 'condition)) (typep (make-condition 'simple-warning) ty))
				             (let ((ty 'type-error)) (typep (make-condition 'simple-warning) ty))))
				""")).isEqualTo("(T T NIL)");
	}

	@Test
	void compileAndRunBuiltInErrorCarriesItsConditionClass() throws Exception {
		// The classification the landing pad emits must agree with the interpreter's
		// throw-site classes (LispEvaluatorTest#aBuiltInErrorCarriesItsConditionClass).
		assertThat(compileAndRun("(print (handler-case (car 1) (type-error (e) :type-error) (error (e) :plain)))"))
			.isEqualTo(":TYPE-ERROR");
		assertThat(compileAndRun("(print (handler-case (/ 1 0) (division-by-zero (e) :dbz) (error (e) :plain)))"))
			.isEqualTo(":DBZ");
		assertThat(compileAndRun(
				"(print (handler-case (aref (vector 1 2) 5) (type-error (e) :type-error) (error (e) :plain)))"))
			.isEqualTo(":TYPE-ERROR");
		assertThat(compileAndRun(
				"(print (handler-case (no-such-function-xyz 1) (undefined-function (e) :undefined) (error (e) :plain)))"))
			.isEqualTo(":UNDEFINED");
		assertThat(compileAndRun(
				"(print (handler-case (symbol-value 'no-such-var-xyz) (unbound-variable (e) :unbound) (error (e) :plain)))"))
			.isEqualTo(":UNBOUND");
		assertThat(compileAndRun(
				"(print (handler-case (error \"boom\") (type-error (e) :wrong) (simple-error (e) :simple)))"))
			.isEqualTo(":SIMPLE");
	}

	@Test
	void compileAndRunASynthesizedBuiltInConditionReportsARontolispMessage() throws Exception {
		// A cast failure's host text names Java classes and an out-of-range index's
		// counts the layout cell; both are replaced at the pad.
		assertThat(compileAndRun("(print (handler-case (car 1) (type-error (e) (princ-to-string e))))"))
			.isEqualTo("\"the value is not of the expected type\"");
		assertThat(compileAndRun("(print (handler-case (aref (vector 1 2) 5) (type-error (e) (princ-to-string e))))"))
			.isEqualTo("\"index out of bounds\"");
		assertThat(compileAndRun("(print (handler-case (/ 1 0) (division-by-zero (e) (princ-to-string e))))"))
			.isEqualTo("\"Division by zero\"");
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
	void compileAndRunSignalFallsThroughAHandlerCaseWhoseClausesDoNotMatch() throws Exception {
		// CLHS 9.1.4.1: signal runs the applicable handlers and, if none transfers
		// control, returns nil. A handler-case whose clauses do not match the
		// condition is not an applicable handler, so it must decline and the forms
		// after the signal still run (cl-mustache's read-partial shape).
		assertThat(compileAndRun("""
				(define-condition note () ())
				(defun boom () (signal 'note) :returned)
				(print (handler-case (boom) (error (e) :err)))
				(print (handler-case (progn (signal 'note) :after) (type-error (e) :te)))
				""")).isEqualTo(":RETURNED\n:AFTER");
	}

	@Test
	void compileAndRunAnUnmatchedSignalLeavesTheHandlerCaseArmedForALaterCondition() throws Exception {
		assertThat(compileAndRun("""
				(define-condition note () ())
				(print (handler-case (progn (signal 'note) (error "boom")) (error (e) :err)))
				(print (handler-case (progn (signal 'note) :after) (note (e) :caught)))
				""")).isEqualTo(":ERR\n:CAUGHT");
	}

	@Test
	void compileAndRunHandlerBindHandlersStillRunWhenAnUnmatchedHandlerCaseDeclines() throws Exception {
		// Restart mode: the intervening handler-bind handler runs at the signal
		// point and declines; the unmatched handler-case then declines too, so the
		// form after the signal still runs.
		assertThat(compileAndRun("""
				(define-condition note () ())
				(defvar *log* nil)
				(print (handler-bind ((note (lambda (c) (setq *log* (cons :hb *log*)))))
				         (handler-case (progn (signal 'note) (setq *log* (cons :after *log*)) :done)
				           (error (e) :err))))
				(print *log*)
				""")).isEqualTo(":DONE\n(:AFTER :HB)");
	}

	@Test
	void compileAndRunAnErrorStillUnwindsThroughAnUnmatchedHandlerCase() throws Exception {
		// The decline applies to signal only: error (and cerror, here real under
		// restart mode) must keep unwinding through a handler-case that does not
		// match, and a restart-case arm changes nothing about the decline.
		assertThat(compileAndRun("""
				(define-condition note () ())
				(print (handler-case
				           (handler-case (cerror "Continue." "boom") (type-error (e) :te))
				         (error (e) :outer)))
				(print (handler-case (restart-case (progn (signal 'note) :after) (continue () :c))
				         (error (e) :err)))
				""")).isEqualTo(":OUTER\n:AFTER");
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
	void compileAndRunABodyPastTheOneByteLocalSlotIndex() throws Exception {
		// A straight-line run of statements burns a local slot per temporary, so a few
		// hundred of them walk past slot 255 -- which a one-byte load/store operand
		// cannot name. Wrapping used to alias a live `let` variable SILENTLY: `keep`
		// sits in a low slot, the temporary whose number wrapped onto it overwrote it,
		// and the compiled program answered 0 where the interpreter answers 7.
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			body.append("             (setq acc (logxor acc (car (list 1 0))))\n");
		}
		String source = """
				(defun slot-overflow ()
				  (flet ((run ()
				           (let ((keep (list 7)) (acc 0))
				%s             (list (car keep) acc))))
				    (run)))
				(print (slot-overflow))
				""".formatted(body);
		assertThat(compileAndRun(source)).isEqualTo("(7 0)");
	}

	@Test
	void compileAndRunABodyPastTheOneByteLocalSlotIndexUnderAnUnsplittableTail() throws Exception {
		// The same overflow where the frame walk notices instead of the answer being
		// silently wrong: `flet` wraps its body in a `block`, so JvmBodyOutliner finds
		// no tail spine to cut and the whole run lands in one frame. The truncated
		// `astore 0` overwrote the closure environment, which surfaced as "aaload on
		// non-array type" out of StackMapAugmenter.
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			body.append("                  (setq acc (+ acc 1))\n");
			body.append("                  (setq acc (logxor acc (car (list 1 0))))\n");
		}
		String source = """
				(defun tree (x)
				  (let ((acc 0) (hit nil))
				    (block done
				      (tagbody
				         (flet ((a0 ()
				%s                  (go finish)))
				           (a0))
				       finish
				         (return-from done (list acc hit))))))
				(print (tree 1))
				""".formatted(body);
		assertThat(compileAndRun(source)).isEqualTo("(0 NIL)");
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
	void compileAndRunUsesTheStandardOperatorWhenAProgramRedefinesACommonLispFunction() throws Exception {
		// The compiled call site is the standard LENGTH, not the definition above it --
		// the interpreter would answer 42. That divergence is CL-legal (CLHS 11.1.2.1.2)
		// and stays; what changed is that the compile paths no longer do it in silence.
		// #'LENGTH still names the definition, which is why the warning says so.
		String source = """
				(defun length (&rest args) (declare (ignore args)) 42)
				(print (length '(1 2 3)))
				(print (funcall #'length '(1 2 3)))
				""";
		assertThat(compileAndRun(source)).isEqualTo("3\n42");
		assertThat(compileAndRunCapturingErr(source)).contains("redefines the COMMON-LISP function LENGTH");
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
	void compileAndRunSymbolMacrolet() throws Exception {
		// Basic substitution and let-shadowing (trivia's match expansions rely on it).
		assertThat(compileAndRun("(symbol-macrolet ((x 42)) (print x))")).isEqualTo("42");
		assertThat(compileAndRun("(symbol-macrolet ((x 42)) (print (list (let ((x 1)) x) x)))")).isEqualTo("(1 42)");
		// Nested symbol-macrolet: the inner binding shadows the outer one.
		assertThat(compileAndRun("(symbol-macrolet ((x 1)) (symbol-macrolet ((x 2)) (print x)))")).isEqualTo("2");
		// setq of a symbol-macro target writes through the expansion place.
		assertThat(compileAndRun(
				"(let ((cell (list 1 2))) (symbol-macrolet ((head (car cell))) (setq head 99) (print cell)))"))
			.isEqualTo("(99 2)");
		// A reference inside a lambda body substitutes (closure over the expansion).
		assertThat(compileAndRun("(let ((n 10)) (symbol-macrolet ((big (* n n))) (print (funcall (lambda () big)))))"))
			.isEqualTo("100");
		// The same shape inside a defun. The lambda body spells only the macro name, so
		// the capture of n is visible only THROUGH the substitution: the capture walk
		// expands it (FreeVarAnalyzer.collectCapturedVars), or the binding is left with
		// no cell for the closure to load.
		assertThat(compileAndRun("""
				(defun sm-square () (let ((n 10)) (symbol-macrolet ((big (* n n))) (funcall (lambda () big)))))
				(print (sm-square))
				""")).isEqualTo("100");
	}

	@Test
	void compileAndRunSymbolMacroletSetfThroughSlotValuePlace() throws Exception {
		// The dbi driver shape: the body assigns through a slot-value expansion.
		assertThat(compileAndRun("""
				(defclass conn () ((auto-commit :initform nil)))
				(defvar *c* (make-instance 'conn))
				(symbol-macrolet ((auto-commit (slot-value *c* 'auto-commit)))
				  (setf auto-commit 'on)
				  (print auto-commit))
				""")).isEqualTo("ON");
	}

	@Test
	void compileAndRunDefineSymbolMacro() throws Exception {
		// The global symbol macro is a COMPILE-TIME fact: UserMacroExpander drops the
		// definition and substitutes every later top-level form, so the backend never
		// sees the form. A reference reads the expansion and setq/setf/incf write
		// through it as a place.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defvar *buf* (make-array 3 :initial-element 0))
				(define-symbol-macro *slot0* (aref *buf* 0))
				(setf *slot0* 42)
				(setq *slot0* (+ *slot0* 1))
				(defun bump () (incf *slot0*))
				(bump)
				(print (list *slot0* *buf*))
				(print (let ((*slot0* 7)) *slot0*))
				"""));
		assertThat(compileAndRun(program)).isEqualTo("(44 #(44 0 0))\n7");
	}

	@Test
	void compileAndRunDefineSymbolMacroEmittedByUserMacro() throws Exception {
		// The cffi defcvar shape: a user macro expands into a progn whose eval-when
		// carries the definition, and the accessor defun it generates is what the
		// reference resolves to.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defvar *cell* (list 1 2))
				(defmacro defhead (name place)
				  `(progn (defun ,(intern (concatenate 'string "%ACCESS-" (symbol-name name))) () ,place)
				          (defun (setf ,(intern (concatenate 'string "%ACCESS-" (symbol-name name)))) (value)
				            (setf ,place value))
				          (eval-when (:compile-toplevel :load-toplevel :execute)
				            (define-symbol-macro ,name
				              (,(intern (concatenate 'string "%ACCESS-" (symbol-name name))))))))
				(defhead head (car *cell*))
				(setf head 99)
				(print (list head *cell*))
				"""));
		assertThat(compileAndRun(program)).isEqualTo("(99 (99 2))");
	}

	@Test
	void compileAndRunSymbolMacroletEmittedByUserMacro() throws Exception {
		// The trivia shape: a user macro EXPANDS INTO symbol-macrolet, and user macro
		// calls inside the binding expansions and the body are expanded away by
		// UserMacroExpander before the compilers run the substitution.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro sq (x) `(* ,x ,x))
				(defmacro with-head (var place &body body) `(symbol-macrolet ((,var (car ,place))) ,@body))
				(let ((cell (list 3 0)))
				  (with-head h cell
				    (setq h (sq h))
				    (print cell)))
				(symbol-macrolet ((s (sq 4))) (print s))
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
			assertThat(baos.toString().trim()).isEqualTo("(9 0)\n16");
		}
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
		// :async t (the host may suspend; WASM answers a future) compiles unchanged
		// here: the host does not exist off WASM, so the stub is the same either way.
		assertThat(compileAndRun(
				"(rontolisp:wasm-import 'pull :params '(:string) :returns :string :async t)" + "(print (+ 1 2))"))
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
	void compileAndRunWasmImportStubCarriesTheBytesReceiveBufferArity() {
		// A :returns :bytes import takes one extra trailing argument (the caller-passed
		// receive buffer), so the stub must load with that arity: the call below reaches
		// the host-function error, not an arity mismatch.
		assertThatThrownBy(() -> compileAndRun("(rontolisp:wasm-import 'pull :params '() :returns :bytes)"
				+ "(print (pull (make-array 3 :element-type '(unsigned-byte 8))))"))
			.hasRootCauseMessage(
					"PULL is a host function declared by rontolisp:wasm-import; it can only be called from a compiled WASM module");
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

	// macro-function / special-operator-p partition the operators with no function value
	// between them on the compile path too: the pass appends the program's own
	// macro-function over its macro names and the prelude carries the built-in half, so
	// the answers match the interpreter's (LispEvaluatorTest, SBCL-checked) name for
	// name. The expander VALUE is a stub here -- non-nil for the predicate, a signal when
	// called -- because a compiled program has no macro table left.
	@Test
	void compileAndRunMacroFunctionAndSpecialOperatorP() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defmacro mfp-mac (x) `(list ,x))
					(defun mfp-probe (form)
					  (cond ((special-operator-p (first form)) :special)
					        ((macro-function (first form)) :macro)
					        (t :function)))
					(print (list (mfp-probe '(if a b)) (mfp-probe '(quote a)) (mfp-probe '(mfp-mac 1))
					             (mfp-probe '(when a b)) (mfp-probe '(handler-case a)) (mfp-probe '(car x))
					             (mfp-probe '(+ 1 2))))
					(print (list (special-operator-p 'defun) (and (macro-function 'defun) t)
					             (and (macro-function (intern "WHEN")) t)
					             (macro-function 'car) (macro-function 'if)))
					(print (list (multiple-value-list (macroexpand-1 '(mfp-mac 1)))
					             (multiple-value-list (macroexpand-1 '(+ 1 2)))))
					""")));
		assertThat(compileAndRun(program)).isEqualTo("(:SPECIAL :SPECIAL :MACRO :MACRO :MACRO :FUNCTION :FUNCTION)\n"
				+ "(NIL T T NIL NIL)\n" + "(((LIST 1) T) ((+ 1 2) NIL))");
	}

	// A COMPUTED macroexpand-1 argument is the one shape the fold cannot decide, and the
	// answer has to agree with macro-function's: a macro call signals (a compiled program
	// has no macro table left), anything else comes back unchanged with expanded-p nil.
	// Answering a macro call with ITSELF would spin the standard "expand until it stops
	// expanding" loop forever, since macro-function keeps saying "macro".
	@Test
	void compileAndRunMacroexpandOfAComputedArgument() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defmacro mxc-mac (x) `(list ,x))
					(defun mxc-steps (form)
					  (do ((step form (macroexpand-1 step)))
					      ((or (special-operator-p (first step)) (not (macro-function (first step)))) step)))
					(print (mxc-steps (list 'car 'x)))
					(print (handler-case (mxc-steps (list 'mxc-mac 9)) (error (e) :no-runtime-macro-table)))
					(print (handler-case (mxc-steps (list 'when 'a 'b)) (error (e) :no-runtime-macro-table)))
					(print (multiple-value-list (macroexpand-1 (list '+ 1 2))))
					""")));
		assertThat(compileAndRun(program))
			.isEqualTo("(CAR X)\n" + ":NO-RUNTIME-MACRO-TABLE\n" + ":NO-RUNTIME-MACRO-TABLE\n" + "((+ 1 2) NIL)");
	}

	// (setf (macro-function 'new) (macro-function 'existing)) is a macro-TABLE write, so
	// the same pass carries it out and drops the form: the compiler sees only the
	// expanded call site, exactly as for the defmacro it aliases (lisp-namespace's
	// nslet / namespace-let).
	@Test
	void compileAndRunSetfMacroFunctionAliasAfterExpansionPass() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro sfmf-greet (x) `(list :hello ,x))
				(setf (macro-function 'sfmf-hi) (macro-function 'sfmf-greet))
				(print (sfmf-hi 1))
				(print (sfmf-greet 2))
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
			assertThat(baos.toString().trim()).isEqualTo("(:HELLO 1)\n(:HELLO 2)");
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
	void compileAndRunSharpLAndCommaDotAndWithHashTableIterator() throws Exception {
		// The three iterate-shaped surfaces that are pure front-end work: #L lowers in
		// the reader, ",." is ",@", and with-hash-table-iterator expands to a snapshot
		// alist plus an flet, so the backend sees only ordinary forms.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (mapcar #L(* !1 !1) '(1 2 3)))
				(print (funcall #L(list !2 !3) 'a 'b 'c))
				(let ((xs '(1 2))) (print `(f ,.xs g)))
				(print (ldiff '(1 2 3 4) nil))
				(print (sublis '((a . 1)) '(a b)))
				(let ((h (make-hash-table)))
				  (setf (gethash 'a h) 1)
				  (with-hash-table-iterator (next h)
				    (multiple-value-bind (more k v) (next)
				      (print (list more k v)))))
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
			assertThat(baos.toString().trim()).isEqualTo("(1 4 9)\n(B C)\n(F 1 2 G)\n(1 2 3 4)\n(1 B)\n(T A 1)");
		}
	}

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
		// spelling build the 2-arg find-symbol lowering already uses: an
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
		// dispatchers resolve through the _lookup registry.
		assertThat(compileAndRun("(defun sf-f (x) (* x 2)) (setq s (intern \"SF-F\"))"
				+ "(print (funcall (symbol-function s) 21)) (print (funcall (fdefinition s) 5))"))
			.isEqualTo("42\n10");
	}

	@Test
	void compileAndRunUiopSymbolCall() throws Exception {
		// REAL on the compile paths (used to be a call-time error, see
		// .kb/asdf.md): lowers to (funcall (intern (string name) (find-package pkg))
		// args...), late-bound through the registry like the interpreter's
		// resolveFunction.
		assertThat(compileAndRun("(defpackage :usc-pkg (:use :cl) (:export :usc-fn))"
				+ "(in-package :usc-pkg) (defun usc-fn (a b) (+ a b)) (in-package :cl-user)"
				+ "(print (uiop:symbol-call :usc-pkg :usc-fn 40 2))" + "(print (uiop:symbol-call :cl :list 1 2))"))
			.isEqualTo("42\n(1 2)");
	}

	@Test
	void compileAndRunUiopSymbolCallAsAFirstClassValueOverUninternedDesignators() throws Exception {
		// dexador's backend dispatch, verbatim in shape: the operator is a VALUE
		// (#'uiop:symbol-call applied to a runtime argument list) and both designators
		// are uninterned symbols. The value needs uiop's own definition of symbol-call
		// (the fold only covers call position); the '#:name designator needs the
		// dispatch gate to probe the "#:member" spelling, or the registry has no row
		// for the target and the call dies undefined.
		assertThat(compileAndRun("(defpackage :dex-usocket (:use :cl) (:export :request))"
				+ "(in-package :dex-usocket) (defun request (uri &rest args) (list uri args))"
				+ "(in-package :cl-user) (defvar *backend* :usocket)"
				+ "(defun dex-request (uri &rest args) (ecase *backend*"
				+ "  (:usocket (apply #'uiop:symbol-call '#:dex-usocket '#:request uri args))"
				+ "  (:winhttp (apply #'uiop:symbol-call '#:dex-winhttp '#:request uri args))))"
				+ "(print (dex-request \"http://x\" :method :get))"
				+ "(print (uiop:symbol-call '#:dex-usocket '#:request \"u\"))"))
			.isEqualTo("(\"http://x\" (:METHOD :GET))\n(\"u\" NIL)");
	}

	@Test
	void compileAndRunUiopBindingMacrosAndWithDeprecation() throws Exception {
		// with-deprecation wraps top-level defuns in the wild, so its expansion has to
		// SPLICE at top level -- burying them in an expression would stop Pass 1 from
		// collecting them at all.
		assertThat(compileAndRun("""
				(uiop:with-deprecation (:style-warning)
				  (defun dep-a (x) (* x 2))
				  (defun dep-b (x) (+ x 1)))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (uiop:with-deprecation (:style-warning)
				    (defun dep-c (x) (- x 1))))
				(print (list (uiop:if-let ((a 1) (b 2)) (list a b) 0)
				             (uiop:if-let ((a 1) (b nil)) (list a b) 0)
				             (uiop:if-let (x (+ 1 2)) (* x 10) 0)
				             (uiop:if-let ((a nil)) 7)
				             (uiop:when-let ((a 3) (b 4)) (+ a b) (* a b))
				             (uiop:when-let ((a 3) (b nil)) (+ a 1))
				             (uiop:when-let* ((a 5) (b (* a 2))) (+ a b))
				             (uiop:when-let* ((a nil) (b (error "no"))) b)
				             (dep-a 3) (dep-b 3) (dep-c 3)))
				""")).isEqualTo("((1 2) 0 30 NIL 12 NIL 15 NIL 6 4 2)");
	}

	@Test
	void compileAndRunUiopUtilityHelpers() throws Exception {
		// The four uiop/utility members with real codegen shape: strcat (a &rest call
		// into the spliced reduce/strcat), string-prefix-p (string= with :end2), nest (a
		// pure syntactic rearrangement) and while-collecting (a let + flet whose
		// collector functions mutate the accumulators they close over). Selection is the
		// other half of what this pins: the compile path splices only the definitions the
		// program reaches, so a missing edge shows up here as "the function
		// UIOP/UTILITY:X is undefined".
		assertThat(compileAndRun("""
				(print (uiop:strcat "a" nil #\\b "c"))
				(print (list (uiop:string-prefix-p "ab" "abc") (uiop:string-prefix-p "b" "abc")))
				(print (uiop:nest (list 1) (list 2) (list 3)))
				(print (multiple-value-list
				        (uiop:while-collecting (foo bar)
				          (dolist (x (list (list 'a 1) (list 'b 2)))
				            (foo (first x))
				            (bar (second x))))))
				(print (uiop:access-at (list :a (list 10 20)) (list :a 1)))
				(print (list (uiop:timestamp< 1 2) (uiop:latest-timestamp 3 1 2)))
				(print (let ((l (list 1))) (uiop:appendf l (list 2 3)) l))
				""")).isEqualTo("""
				"abc"
				(T NIL)
				(1 (2 (3)))
				((A B) (1 2))
				20
				(T 3)
				(1 2 3)""");
	}

	@Test
	void compileAndRunUiopPathnameAlgebra() throws Exception {
		// The uiop/pathname family is pure computation over the flat namestring
		// (uiop-pathname.lisp), so the compiled program must answer exactly what the
		// interpreter does -- including the two macros (with-pathname-defaults binds
		// the *default-pathname-defaults* special, with-enough-pathname lowers onto
		// call-with-enough-pathname) and get-pathname-defaults reading the special.
		assertThat(compileAndRun("""
				(print (namestring (uiop:subpathname #P"/tmp/foo/" "bar/baz.txt")))
				(print (namestring (uiop:subpathp (pathname "/tmp/foo/bar.txt") (pathname "/tmp/"))))
				(print (uiop:subpathp #P"/other/x" #P"/tmp/"))
				(print (namestring (uiop:enough-pathname #P"/tmp/a/b.txt" #P"/tmp/")))
				(print (namestring (uiop:parse-unix-namestring "a//b/./c.txt")))
				(print (namestring (uiop:parse-unix-namestring "foo/bar" :type "lisp")))
				(print (list (if (uiop:absolute-pathname-p "/a/b") t nil)
				             (if (uiop:relative-pathname-p "a/b") t nil)
				             (if (uiop:directory-pathname-p "/a/b/") t nil)
				             (if (uiop:file-pathname-p "/a/b") t nil)
				             (uiop:hidden-pathname-p ".gitignore")
				             (uiop:pathname-equal "/a/b" #P"/a/b")))
				(print (namestring (uiop:pathname-parent-directory-pathname #P"/a/b/c.txt")))
				(print (multiple-value-list (uiop:split-name-type "foo.lisp")))
				(print (namestring (uiop:wilden #P"/tmp/foo")))
				(print (namestring (uiop:translate-pathname* #P"/src/a/b.lisp" #P"/src/**/*.*" #P"/out/**/*.*")))
				(print (namestring (uiop:ensure-pathname "a/b" :ensure-directory t)))
				(print (handler-case (uiop:ensure-pathname "/a/b" :want-relative t) (error () :err)))
				(print (namestring (uiop:get-pathname-defaults)))
				(uiop:with-pathname-defaults (#P"/wpd/") (print (namestring *default-pathname-defaults*)))
				(let ((p #P"/tmp/a/b.txt"))
				  (uiop:with-enough-pathname (p :defaults #P"/tmp/") (print (namestring p))))
				(print (namestring uiop:*wild-path*))
				""")).isEqualTo("""
				"/tmp/foo/bar/baz.txt"
				"foo/bar.txt"
				NIL
				"a/b.txt"
				"a/b/c.txt"
				"foo/bar.lisp"
				(T T T T T T)
				"/a/"
				("foo" "lisp")
				"/tmp/**/*.*"
				"/out/a/b.lisp"
				"a/b/"
				:ERR
				""
				"/wpd/"
				"a/b.txt"
				"**/*.*\"""");
	}

	@Test
	void compileAndRunUiopOsHostIdentityAndGetenvOverride() throws Exception {
		// The whole uiop/os family on the JVM. The pipeline mirrors the CLI's: the
		// program AND the uiop resources are read with the TARGET feature set, which is
		// what makes featurep -- and architecture / implementation-identifier, derived
		// from it -- answer for the backend being compiled instead of for the
		// interpreter. getenv is a Lisp definition over the %host-getenv primitive here,
		// so the override map a (setf (uiop:getenv x) v) writes is read back on this
		// backend exactly as on the interpreter; getcwd is real (user.dir) and chdir
		// signals.
		assertThat(compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (list (uiop:featurep :rontolisp) (uiop:featurep :rontolisp-jvm)
				             (uiop:featurep :rontolisp-interpreter)
				             (uiop:featurep '(:and :rontolisp :unicode)) (uiop:featurep '(:not :nope))))
				(print (list (uiop:os-unix-p) (uiop:os-macosx-p) (uiop:os-windows-p) (uiop:os-genera-p)))
				(print (list (uiop:detect-os) (uiop:operating-system) (uiop:implementation-type)
				             uiop:*implementation-type* (uiop:architecture) (uiop:hostname)))
				(print (uiop:os-cond ((uiop:os-windows-p) :win) ((uiop:os-unix-p) :unix) (t :other)))
				(setf (uiop:getenv "CI_JVM_UIOP_OS") "one")
				(print (list (uiop:getenv "CI_JVM_UIOP_OS") (uiop:getenvp "CI_JVM_UIOP_OS")))
				(setf (uiop:getenv "CI_JVM_UIOP_OS") nil)
				(print (list (uiop:getenv "CI_JVM_UIOP_OS") (uiop:getenvp "CI_JVM_UIOP_OS")))
				(print (list (stringp (uiop:getenv "PATH")) (pathnamep (uiop:getcwd))))
				(print (handler-case (uiop:chdir "/tmp") (uiop:not-implemented-error () :chdir-signals)))
				(print (handler-case (uiop:parse-windows-shortcut "x.lnk")
				         (uiop:not-implemented-error () :lnk-not-implemented)))
				""", am.ik.rontolisp.reader.Features.JVM), am.ik.rontolisp.reader.Features.JVM))).isEqualTo("""
				(T T NIL T T)
				(T NIL NIL NIL)
				(:OS-UNIX :UNIX :RONTOLISP :RONTOLISP :JVM NIL)
				:UNIX
				("one" "one")
				(NIL NIL)
				(T T)
				:CHDIR-SIGNALS
				:LNK-NOT-IMPLEMENTED""");
	}

	@Test
	void compileAndRunUiopImageHooksBacktracesAndTheImageItself() throws Exception {
		// The uiop/image family that does not exit, on the JVM. The hooks are real lists
		// (only the act of DUMPING is impossible), fatal-condition is a deftype over
		// serious-condition that a runtime typep matches, the backtrace family prints the
		// condition and no frames -- no backend carries a Lisp-level call stack -- and
		// the
		// three image operations name what is missing instead of half-doing it.
		assertThat(compileAndRun(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(uiop:register-image-dump-hook 'a)
				(uiop:register-image-dump-hook 'b)
				(uiop:register-image-dump-hook 'a)
				(defvar *ran* nil)
				(uiop:register-image-restore-hook (lambda () (push :restored *ran*)) nil)
				(uiop:call-image-restore-hook)
				(print (list uiop:*image-dump-hook* *ran* uiop:*image-dumped-p* uiop:*lisp-interaction*))
				(print (list (uiop:fatal-condition-p (make-condition 'error))
				             (uiop:fatal-condition-p (make-condition 'warning))
				             (uiop:fatal-condition-p 42)))
				(print (with-output-to-string (s)
				         (uiop:print-condition-backtrace (make-condition 'simple-error :format-control "boom")
				                                         :stream s)
				         (uiop:print-backtrace :stream s :condition "second")
				         (uiop:raw-print-backtrace :stream s)))
				(print (list (handler-case (uiop:dump-image "x.img")
				               (uiop:not-implemented-error () :dump-image))
				             (handler-case (uiop:restore-image) (uiop:not-implemented-error () :restore-image))
				             (handler-case (uiop:create-image "x" nil)
				               (uiop:not-implemented-error () :create-image))))
				""", am.ik.rontolisp.reader.Features.JVM), am.ik.rontolisp.reader.Features.JVM))).isEqualTo("""
				((B A) (:RESTORED) NIL NIL)
				(T NIL NIL)
				"boom
				second
				"
				(:DUMP-IMAGE :RESTORE-IMAGE :CREATE-IMAGE)""");
	}

	@Test
	void compileAndRunUiopImageQuitEndsTheProcessWithItsCode() throws Exception {
		// quit is System.exit on this backend -- the one place a generated class ends the
		// JVM it runs in, and deliberately so: the uncaught-condition handler rethrows
		// because the program did NOT ask to stop, while quit is the program asking for
		// exactly that. Which is why this case runs in a CHILD JVM and every other case
		// here is reflective.
		//
		// Nothing runs after it: not the handler-case around it (it is not a condition)
		// and not the unwind-protect cleanup (System.exit / proc_exit / wasi:cli/exit all
		// end the process where they stand). All four backends print :BEFORE and exit 3.
		Run quit = compileAndRunInChildJvm("""
				(print :before)
				(unwind-protect
				     (handler-case (uiop:quit 3) (error (e) (print :caught)))
				  (print :cleanup))
				(print :after)
				""");
		assertThat(quit.out()).isEqualTo(":BEFORE");
		assertThat(quit.code()).isEqualTo(3);
		// die reports on standard error and quits with the code it was given;
		// shell-boolean-exit is 0 for true and 1 for false.
		Run die = compileAndRunInChildJvm("(uiop:die 7 \"no such thing: ~A\" :widget)");
		assertThat(die.out()).isEqualTo("no such thing: WIDGET");
		assertThat(die.code()).isEqualTo(7);
		assertThat(compileAndRunInChildJvm("(uiop:shell-boolean-exit nil)").code()).isEqualTo(1);
		assertThat(compileAndRunInChildJvm("(uiop:shell-boolean-exit :yes)").code()).isEqualTo(0);
		// The code is masked to eight bits, which is what a POSIX host does with it
		// anyway and what wasi:cli/exit's u8 accepts.
		assertThat(compileAndRunInChildJvm("(uiop:quit 300)").code()).isEqualTo(44);
		// handle-fatal-condition reports and dies with upstream's own status 99; there is
		// no debugger to enter, which is why *lisp-interaction* is nil here.
		Run fatal = compileAndRunInChildJvm("""
				(print :start)
				(uiop:with-fatal-condition-handler () (error "the sky is falling"))
				(print :unreachable)
				""");
		assertThat(fatal.code()).isEqualTo(99);
		assertThat(fatal.out()).isEqualTo("""
				:START
				Fatal condition:
				the sky is falling
				the sky is falling
				the sky is falling""");
	}

	/**
	 * What a compiled class printed (stdout and stderr merged) and the code it exited
	 * with.
	 */
	private record Run(String out, int code) {
	}

	// Runs the compiled class in a CHILD JVM: a program calling uiop:quit ends the JVM it
	// runs in, so the reflective compileAndRun above would take the test runner with it.
	private Run compileAndRunInChildJvm(String lispCode, String... arguments) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(
				LispReader.readAllFromString(lispCode, am.ik.rontolisp.reader.Features.JVM),
				am.ik.rontolisp.reader.Features.JVM);
		Files.write(this.tempDir.resolve("Test.class"), new JvmLispCompiler("Test").compile(program));
		List<String> command = new java.util.ArrayList<>(
				List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
						this.tempDir.toString(), "Test"));
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new Run(out.trim(), process.waitFor());
	}

	@Test
	void compileAndRunUiopImageTheCommandLine() throws Exception {
		// main(String[]) carries the user arguments and no argv0, so the CLASS NAME is
		// prepended: it is what stood on the command line, and it makes the vector the
		// same (program-name user-arg ...) shape the other three backends answer. The
		// arguments have to come from a REAL command line, so this case runs in a child
		// JVM like the quitting ones do.
		Run run = compileAndRunInChildJvm("""
				(print (uiop:raw-command-line-arguments))
				(print (uiop:argv0))
				(print (uiop:command-line-arguments))
				(print uiop:*command-line-arguments*)
				""", "alpha", "beta");
		assertThat(run.out()).isEqualTo("""
				("Test" "alpha" "beta")
				"Test"
				("alpha" "beta")
				("alpha" "beta")""");
		assertThat(run.code()).isZero();
		// No arguments is the vector with argv0 alone, not nil: the program was still
		// started from a command line.
		assertThat(compileAndRunInChildJvm("(print (uiop:command-line-arguments))").out()).isEqualTo("NIL");
	}

	@Test
	void compileAndRunUiopOsOctetReaders() throws Exception {
		// read-little-endian / read-null-terminated-string are portable stream work over
		// read-byte, so the compiled program must answer what the interpreter does. The
		// stream is flexi-streams' in-memory octet stream, the one binary input every
		// backend can build without a filesystem.
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "flexi-streams")
				(let* ((v (make-array 7 :element-type '(unsigned-byte 8)
				                        :initial-contents '(1 2 0 0 104 105 0)))
				       (s (flex:make-in-memory-input-stream v)))
				  (print (uiop:read-little-endian s))
				  (print (uiop:read-null-terminated-string s)))
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		});
		// GrayStreamsLibrary in the CLI's order: read-byte on a CLOS instance stream --
		// which flexi-streams' in-memory octet stream is -- reaches the Gray dispatch
		// only through that rewrite; without it the call compiles to the raw stdin read.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))).isEqualTo("513\n\"hi\"");
	}

	@Test
	void compileAndRunUiopUnimplementedMacroDropsItsArgumentForms() throws Exception {
		// A macro nothing implements yet must not evaluate what it was handed. Its
		// synthesized stub is a real variadic defun (the name has to be fboundp), so
		// the ordinary call path FINDS it and compiles the argument forms first --
		// which is why the lowering has to happen in the expression compiler's uiop
		// branch, ahead of the call path. The spec list here is non-empty on purpose:
		// with (), the arguments are the nil literal and the bug is invisible.
		assertThat(compileAndRun("""
				(print (handler-case (uiop:with-current-directory ("/tmp") (defun um-probe () 1))
				         (uiop:not-implemented-error () :signalled)))
				(print (fboundp 'um-probe))
				""")).isEqualTo("""
				:SIGNALLED
				NIL""");
	}

	@Test
	void compileAndRunUiopWithUpgradabilitySplicesItsDefinitions() throws Exception {
		// Upstream wraps every one of its definitions in with-upgradability; rontolisp
		// lowers it to progn, and the top-level flattening has to splice that progn or
		// Pass 1 never collects the defuns. Both spellings, and one nested in an
		// eval-when, which is how a real uiop-shaped file writes it.
		assertThat(compileAndRun("""
				(uiop:with-upgradability ()
				  (defun up-a (x) (* x 2))
				  (defvar *up-v* 5))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (uiop/utility:with-upgradability ()
				    (defun up-b (x) (+ x 1))))
				(print (list (up-a 3) (up-b 3) *up-v*))
				""")).isEqualTo("(6 4 5)");
	}

	@Test
	void compileAndRunUiopMuffledConditionsAndStyleWarn() throws Exception {
		// with-muffled-conditions expands into call-with-muffled-conditions, a spliced
		// defun the program never names -- the surface-form rule in UiopLibrary is what
		// selects it, and without that this compiled to a call-time "undefined function".
		// style-warn signals uiop's own simple-style-warning, which really is a
		// style-warning, so a handler for the CL supertype catches it.
		assertThat(compileAndRun("""
				(print (uiop:with-muffled-conditions ('(warning)) (warn "quiet") :muffled))
				(print (handler-bind ((style-warning (lambda (c) (muffle-warning c))))
				         (uiop:style-warn "styled ~A" 2)
				         :sw-done))
				(print (handler-case (uiop:register-hook-function '*h* (lambda () 1))
				         (uiop:not-implemented-error (c) :nie)))
				""")).isEqualTo("""
				:MUFFLED
				:SW-DONE
				:NIE""");
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
	void compileAndRunFindSymbolStatusSecondValue() throws Exception {
		// Same answers as the interpreter, folded at compile time (the literal name and
		// the literal designator are what the fold reads, so the mv lowering must not
		// hide either behind a temporary).
		assertThat(compileAndRun("(print (multiple-value-list (find-symbol \"CAR\" 'common-lisp)))"
				+ "(print (multiple-value-list (find-symbol \"CAR\" \"COMMON-LISP\")))"
				+ "(print (multiple-value-list (find-symbol \"NO-SUCH-NAME\" 'common-lisp)))"
				+ "(print (multiple-value-list (find-symbol \"CAR\")))"
				+ "(print (multiple-value-list (find-symbol \"FOO\" :keyword)))"
				+ "(print (multiple-value-list (intern \"CAR\" 'common-lisp)))"))
			.isEqualTo("(CAR :EXTERNAL)\n(CAR :EXTERNAL)\n(NIL NIL)\n(CAR :INHERITED)\n"
					+ "(:FOO :EXTERNAL)\n(CAR :EXTERNAL)");
	}

	@Test
	void compileAndRunSymbolPlist() throws Exception {
		assertThat(compileAndRun("(print (symbol-plist 'sp-none))(setf (get 'sp-x 'a) 1)(print (symbol-plist 'sp-x))"))
			.isEqualTo("NIL\n(A 1)");
	}

	@Test
	void compileAndRunBoundp() throws Exception {
		assertThat(compileAndRun("(defvar *bp-var* 1) (print (boundp '*bp-var*)) (print (boundp '*bp-nope*))"
				+ "(print (boundp :kw)) (print (boundp T)) (print (boundp nil))"
				+ "(let ((lex 1)) (print (boundp 'lex)))"))
			.isEqualTo("T\nNIL\nT\nT\nT\nNIL");
	}

	@Test
	void theDefineConstantGuardCompilesToTheBareDefinition() {
		// The portable define-constant guard compiles to exactly the class the bare
		// definition compiles to: the probe was decided at compile time
		// (compiler/CompileTimeBoundp), so the boundp its usesEval OR-chain arm reads is
		// not in the program any more, and neither is the guard that wrapped it.
		byte[] guarded = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(unless (boundp '+bpk+) (defconstant +bpk+ 5)) (print +bpk+)"));
		byte[] bare = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(defconstant +bpk+ 5) (print +bpk+)"));
		assertThat(guarded).isEqualTo(bare);
		// A computed designator is not decidable and keeps its probe, so the two programs
		// stay different classes. What that costs is NOT readable here: this backend's
		// gate is deliberately wide (the injected wrapper bodies reference _apply) and
		// the class shaker is what trims the runtime, so the size pin lives on the WASM
		// side, whose gate is exact -- .kb/eval-runtime.md.
		byte[] computed = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(defconstant +bpk+ 5) (print (boundp (intern \"+BPK+\")))"));
		assertThat(computed).isNotEqualTo(bare);
	}

	@Test
	void compileAndRunSymbolValue() throws Exception {
		assertThat(compileAndRun("(defvar *sv-var* 42) (print (symbol-value '*sv-var*))"
				+ "(setq *sv-var2* 7) (print (symbol-value (intern \"*SV-VAR2*\")))"
				+ "(print (symbol-value :kw)) (print (symbol-value T)) (print (symbol-value nil))"))
			.isEqualTo("42\n7\n:KW\nT\nNIL");
	}

	@Test
	void standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi() throws Exception {
		// The two homes of a special -- the global field a direct read uses and the eval
		// runtime's _genv mirror symbol-value/boundp probe -- seed from one table, so the
		// three stream variables answer here exactly what the interpreter answers.
		assertThat(compileAndRun("(print (boundp '*error-output*)) (print (symbol-value '*error-output*))"
				+ "(print (boundp '*standard-output*)) (print (symbol-value '*standard-output*))"
				+ "(print (boundp '*standard-input*)) (print (symbol-value '*standard-input*))"))
			.isEqualTo("T\n#<STREAM :HANDLE 2 :KIND :STANDARD>\nT\nT\nT\nT");
	}

	@Test
	void standardStreamVariablesResolveThroughAComputedSymbolAndAfterAssignment() throws Exception {
		// lack's backtrace middleware carries the SYMBOL '*error-output* in a variable,
		// reporting through (symbol-value output). A top-level assignment must still
		// win over the seeded default: the seed IS the binding cell _store mutates.
		assertThat(compileAndRun("(defvar *sv-stream-name* '*error-output*)"
				+ "(print (boundp *sv-stream-name*)) (print (symbol-value *sv-stream-name*))"
				+ "(setq *error-output* 7) (print (symbol-value '*error-output*))"))
			.isEqualTo("T\n#<STREAM :HANDLE 2 :KIND :STANDARD>\n7");
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
	void aDefinerWhoseValueIsReadStillYieldsTheNameSymbol() throws Exception {
		// A TOP-LEVEL definer is compiled for effect -- main() pops what it returns, so
		// the name symbol is never built (compiler/ToplevelStatements). That is a
		// statement-position decision and nothing else: read the value and the form is
		// still the CL form that answers its own name.
		assertThat(compileAndRun("(print (defparameter *nested-name* 7))")).isEqualTo("*NESTED-NAME*");
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
	void aBranchArmPastTheMethodSizeBudgetBecomesItsOwnMethod() throws Exception {
		// The shape the tail-spine splitter cannot cut: the whole body is one BRANCH, so
		// there are never two items to split between (.kb/hot-path-method-size.md).
		// compiler/AstOutliner moves the arm into a local function instead, and the two
		// splitters then COMPOSE -- the arm is a run of statements, which is exactly
		// what JvmBodyOutliner cuts once the arm is a method's own tail spine. The
		// program pins what the move has to preserve: the arm ASSIGNS a variable of the
		// enclosing frame that is read after the arm returns (it travels as a cell, not
		// a value), and it LEAVES through a go to a label of the tagbody it sits in,
		// which stops being a jump and becomes a non-local exit.
		int rounds = 200;
		StringBuilder sb = new StringBuilder();
		sb.append("(defun tree (x)\n  (let ((acc 0) (hit nil))\n    (block done\n      (tagbody\n");
		sb.append("         (if (= x 0)\n             (progn\n");
		long acc = 0;
		for (int k = 1; k <= rounds; k++) {
			sb.append("               (setq acc (+ acc ").append(k).append("))\n");
			sb.append("               (setq acc (logxor acc (car (list ").append(k * 7).append(" 0))))\n");
			acc = (acc + k) ^ (k * 7L);
		}
		sb.append("               (setq hit 'zero)\n               (go finish))\n");
		sb.append("             (progn\n               (setq acc 42)\n");
		sb.append("               (setq hit 'other)\n               (go finish)))\n");
		sb.append("       finish\n         (return-from done (list acc hit))))))\n");
		sb.append("(print (tree 0))\n(print (tree 1))\n");
		List<LispVal> forms = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(sb.toString()));
		byte[] classBytes = new JvmLispCompiler("Test").compile(forms);
		java.util.Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
		for (java.lang.classfile.MethodModel method : java.lang.classfile.ClassFile.of().parse(classBytes).methods()) {
			sizes.put(method.methodName().stringValue(),
					method.findAttribute(java.lang.classfile.Attributes.code()).orElseThrow().codeLength());
		}
		assertThat(sizes.get("TREE")).as("the branch was cut, so the function fits under the cliff").isLessThan(8000);
		// The source builds no closure of its own, so a local function in the class is
		// one this pass introduced.
		assertThat(sizes.keySet()).as("the arm became a method of its own").anyMatch(n -> n.startsWith("_lambda_"));
		assertThat(sizes.entrySet()
			.stream()
			.filter(e -> !e.getKey().equals("main") && !e.getKey().startsWith("_top$") && !e.getKey().equals("<clinit>")
					&& e.getValue() > 8000)
			.toList()).as("no method that runs per evaluated form crosses the limit").isEmpty();
		assertThat(compileAndRun(forms)).isEqualTo("(" + acc + " ZERO)\n(42 OTHER)");
	}

	@Test
	void aFunctionBodyPastTheMethodSizeBudgetSplitsIntoTailContinuations() throws Exception {
		// A defun body that would compile past HotSpot's 8000-bytecode HugeMethodLimit
		// is split into _k$N tail continuations (.kb/hot-path-method-size.md). The
		// program pins what the split has to preserve, all of it ACROSS the boundary:
		// the running values of the let variables (they travel as the continuation's
		// arguments), a closure built BEFORE the split reading variables mutated AFTER
		// it (a captured variable travels as its cell, not its value), and a special
		// binding established on the spine whose restore is emitted in the caller, past
		// the continuation's return.
		int rounds = 400;
		StringBuilder sb = new StringBuilder("""
				(defvar *depth* 0)
				(defun spine (n)
				  (let ((a 0) (b 1) (peek nil))
				    (setq peek (lambda () (+ (* 1000 a) b)))
				    (let ((*depth* (+ n 100)))
				      (setq a (+ a *depth*))
				""");
		long a = 107;
		long b = 1;
		for (int k = 1; k <= rounds; k++) {
			sb.append("      (setq a (+ a ").append(k).append("))\n");
			sb.append("      (setq b (logxor b (car (list ").append(k).append(" 0))))\n");
			a += k;
			b ^= k;
		}
		sb.append("""
				      (list a b *depth* (funcall peek)))))
				(print (spine 7))
				(print *depth*)
				""");
		String program = sb.toString();
		List<LispVal> forms = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(program));
		byte[] classBytes = new JvmLispCompiler("Test").compile(forms);
		java.util.Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
		for (java.lang.classfile.MethodModel method : java.lang.classfile.ClassFile.of().parse(classBytes).methods()) {
			sizes.put(method.methodName().stringValue(),
					method.findAttribute(java.lang.classfile.Attributes.code()).orElseThrow().codeLength());
		}
		assertThat(sizes).as("the split actually happened").containsKey("_k$0");
		assertThat(sizes.get("SPINE")).as("the body kept the callable half under the cliff").isLessThan(8000);
		assertThat(sizes.get("_k$0")).isLessThan(8000);
		assertThat(compileAndRun(forms)).isEqualTo("(" + a + " " + b + " 107 " + (1000 * a + b) + ")\n0");
	}

	@Test
	void aSplitFunctionBodyCarriesItsUnboxedLocalsAcross() throws Exception {
		// The other representation a live local can have at a split point: an unboxed
		// dual-representation local (.kb/jvm-int-fusion.md), whose raw slot has no
		// parameter to travel in. It crosses boxed and lands in the continuation as an
		// ordinary local, so its value -- and every later assignment to it -- has to
		// come out the same. The padding forms allocate rather than assign, so the
		// let body stays under the raw-local assignment-site caps while its bytecode
		// crosses the budget.
		StringBuilder sb = new StringBuilder("""
				(defun counted (n)
				  (let ((c n))
				""");
		for (int k = 1; k <= 90; k++) {
			sb.append("    (nth 3 (list n 2 ").append(k).append(" 4 5 6 7 8 9 10 n 12 13 14 15 16))\n");
			if (k % 10 == 0) {
				sb.append("    (setq c (+ c ").append(k).append("))\n");
			}
		}
		sb.append("""
				    c))
				(print (counted 5))
				""");
		// 10 + 20 + ... + 90 = 450, plus the initial 5.
		assertThat(compileAndRun(sb.toString())).isEqualTo("455");
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
	void nestedDefunsShareTheEnclosingLetsBindingRatherThanEachTakingACopy() throws Exception {
		// The CL closure-over-let idiom (cl-ppcre spells its scanner caches this way).
		// A nested defun is NOT a definition on this backend: it lowers to
		// (setq name (lambda ...)), a closure over the let variables
		// (LispMacroExpander.expandCallThroughVariable). The capture analysis used to
		// SKIP defun, so the binding stayed unboxed and every nested definition's
		// closure was handed a FRESH one-element cell -- a private copy. bump's
		// increments then never reached peek and retag's write reached neither: the
		// compiled answer was (0 START) where the interpreter says (2 LATER).
		assertThat(compileAndRun("(let ((counter 0) (tag 'start))" + "  (defun bump () (setq counter (+ counter 1)))"
				+ "  (defun retag (v) (setq tag v))" + "  (defun peek () (list counter tag)))"
				+ "(bump) (bump) (retag 'later) (print (peek))"))
			.isEqualTo("(2 LATER)");
	}

	@Test
	void aDefunNestedInADefunBodyIsReachableByName() throws Exception {
		// The same lowering as the closure-over-let idiom above, one level deeper: the
		// nested defun becomes (setq read-seed (lambda () seed)) inside install's body,
		// so the NAME needs the same global backing store for a call site to dispatch
		// through it. Collecting it only from top-level non-defun forms left the call
		// compiled as "The function READ-SEED is undefined".
		assertThat(compileAndRun("(defun install (seed)" + "  (defun read-seed () seed)"
				+ "  (lambda () (setq seed (+ seed 1)) seed))" + "(setq *step* (install 10))" + "(funcall *step*)"
				+ "(funcall *step*)" + "(print (list (read-seed) (funcall *step*)))"))
			.isEqualTo("(12 13)");
	}

	@Test
	void aDefunNestedInADefunBodyRedefinesAnExistingTopLevelDefun() throws Exception {
		// The NAME half's last spelling: the redefined name has BOTH a top-level defun
		// and a nested one, and every call site resolved the compiled function first --
		// so the nested definition was written to a store nothing read and the call
		// after (redefiner) still answered TOP. The top-level definition is renamed and
		// its function value assigned to the global in its place, so the one variable
		// carries both answers in order (.kb/core-representation.md, "The NAME half").
		assertThat(compileAndRun("(defun over () 'top)" + "(defun redefiner () (defun over () 'nested) 'done)"
				+ "(print (over)) (print (redefiner)) (print (over))"))
			.isEqualTo("TOP\nDONE\nNESTED");
	}

	@Test
	void aRedefinedDefunIsAlsoRedefinedForFunctionReferencesAndCallers() throws Exception {
		// #'over and a caller compiled BEFORE the redefinition must see it too: both
		// read the same global variable, and the call is a funcall rather than a fixed
		// direct call, so the two definitions need not share an arity.
		assertThat(compileAndRun("(defun over (x) (list 'top x))" + "(defun call-it (x) (over x))"
				+ "(defun redefiner () (defun over (x) (list 'nested x)) 'done)"
				+ "(print (call-it 1)) (print (funcall #'over 2)) (redefiner)"
				+ "(print (call-it 3)) (print (funcall #'over 4))"))
			.isEqualTo("(TOP 1)\n(TOP 2)\n(NESTED 3)\n(NESTED 4)");
	}

	@Test
	void aNestedDefunIsReachableByNameUnderDynamicMode() throws Exception {
		// --dynamic defers an unresolved name to the runtime FUNCTION namespace, which a
		// nested defun never enters (it assigns the global VARIABLE): the call answered
		// nil instead of the closure's value. The variable is asked first for exactly
		// the names that are non-top-level defuns.
		assertThat(compileAndRunDynamic(
				"(defun install (seed) (defun read-seed () seed) 'ok)" + "(print (install 7)) (print (read-seed))"))
			.isEqualTo("OK\n7");
	}

	@Test
	void aTopLevelDefunRedefinedByANestedOneMayNotAlsoBeAGlobalVariable() {
		// One cell cannot hold both the function value the redefinition needs and the
		// variable's own value, and picking either silently is what this whole shape was
		// about -- so the compile says which name and which declaration.
		assertThatThrownBy(() -> compileAndRun("(defvar over 1)" + "(defun over () 'top)"
				+ "(defun redefiner () (defun over () 'nested) 'done)" + "(print (over))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("OVER");
	}

	@Test
	void aTopLevelDefunRedefinedByANestedOneMayNotBeExported() {
		// An export binds one static definition, which a name resolved through a global
		// variable does not have. Refused HERE so the message names the real conflict:
		// the export directive's own check would have said GREET is not a top-level
		// defun, about a name that plainly is one.
		assertThatThrownBy(() -> compileAndRun(
				"(defun greet (n) n)" + "(rontolisp:jvm-export 'greet :params '(:string) :returns :string)"
						+ "(defun swap () (defun greet (n) n) 'done)" + "(print (greet \"a\"))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("GREET")
			.hasMessageContaining("exported");
	}

	@Test
	void aCapturedLetVariableAssignedInlineInASiblingBranchKeepsOneCell() throws Exception {
		// One arm builds a closure over acc/hit, the sibling arm assigns them INLINE in
		// the enclosing frame -- the shape compiler/AstOutliner creates on purpose when
		// it cuts a branch, at a size that crosses both the tail-spine body splitter and
		// that cut. Both arms must reach the one cell, and the unboxed
		// dual-representation local must never be chosen for a name a closure reads
		// (.kb/jvm-int-fusion.md).
		StringBuilder inline = new StringBuilder();
		StringBuilder captured = new StringBuilder();
		for (int i = 0; i < 90; i++) {
			inline.append("(setq acc (+ acc 2)) (setq acc (logxor acc (car (list 1 0)))) ");
			captured.append("(setq acc (+ acc 1)) (setq acc (logxor acc (car (list 1 0)))) ");
		}
		String program = "(defun tree (x)" + "  (let ((acc 0) (hit nil))" + "    (block done" + "      (tagbody"
				+ "         (if (= x 0)" + "             (let ((a0 (lambda () " + captured
				+ " (setq hit 'zero) (go finish))))" + "               (funcall a0))" + "             (progn " + inline
				+ " (setq hit 'other) (go finish)))" + "       finish"
				+ "         (return-from done (list acc hit))))))" + "(print (tree 0)) (print (tree 1))";
		assertThat(compileAndRun(program)).isEqualTo("(0 ZERO)\n(180 OTHER)");
	}

	@Test
	void anInlineLambdaCallBoxesAParameterItsBodyClosesOver() throws Exception {
		// ((lambda (n) ...) 0) binds n in the CALLER's frame, and that binder never
		// asked whether the body closes over it -- so the nested lambda was handed a
		// snapshot cell and its assignments never reached n. Answered 0, not 2.
		assertThat(compileAndRun(
				"(print ((lambda (n)" + " (let ((g (lambda () (setq n (+ n 1))))) (funcall g) (funcall g) n)) 0))"))
			.isEqualTo("2");
	}

	@Test
	void aCapturedLetVariableAssignedInlineInASiblingBranchPastTheSlotCeiling() throws Exception {
		// todo-561's reproducer. One arm closes over acc/hit, the SIBLING arm assigns
		// them inline -- and because both are captured, every inline setq mints a temp
		// that ctx.nextLocal never reuses, so the frame passes 255 slots at ~4 KB of
		// code, far under the HugeMethodLimit that would have made AstOutliner cut it.
		// It was filed as a raw-store bug in the dual-representation machinery; it is
		// the one-byte local index (astore 256/257/258 -> 0/1/2, over the parameter and
		// the two capture cells, so the next read of hit's cell found a Long). Fusion
		// off reproduced it byte for byte, which is what ruled the raw store out.
		// .kb/jvm-method-size-limits.md carries the measurement.
		StringBuilder inline = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			inline.append("(setq hit ").append(i % 5).append(") ");
		}
		assertThat(compileAndRun("(defun tree (x)" + "  (let ((acc 0) (hit nil))" + "    (if (= x 0)"
				+ "        (let ((a0 (lambda () (setq acc (+ acc 1)) (setq hit 'zero))))" + "          (funcall a0))"
				+ "        (progn " + inline + " (setq acc (+ acc 2)) (setq hit 'other)))" + "    (list acc hit)))"
				+ "(print (tree 0)) (print (tree 1))"))
			.isEqualTo("(1 ZERO)\n(2 OTHER)");
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

	// The four primitives alexandria forced, each one a CL conformance gap that failed
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

	@Test
	void compileAndRunWithInputFromStringOverAMutableCharacterVector() throws Exception {
		// The string handed to with-input-from-string may be a MUTABLE character vector
		// (make-string + setf char), which is a different runtime representation here --
		// trivial-utf-8's utf-8-bytes-to-string builds every string that way, so jose
		// fed one straight into cl-json's decode-json-from-string.
		assertThat(compileAndRun("""
				(defun mk ()
				  (let ((s (make-string 3 :element-type 'character)))
				    (setf (char s 0) #\\a)
				    (setf (char s 1) #\\b)
				    (setf (char s 2) #\\c)
				    s))
				(print (with-input-from-string (in (mk)) (read-line in)))
				""")).isEqualTo("\"abc\"");
	}

	// The REST of the family over more than one list. Originally only mapcar walked
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
	// one-list result for a list-of-lists.
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

	// A designator the compiler can READ is called directly by funcall / the map family /
	// reduce / sort instead of through the arity dispatcher (JvmDesignatorCall). Every
	// answer here has to be the one the dispatching route gives, including the shapes the
	// direct call has to build itself: a variadic callee reached at exactly its required
	// count (the empty rest list) and wider than it (the surplus linked into one).
	@Test
	void compileAndRunLiteralFunctionDesignators() throws Exception {
		String defs = "(defun dbl (x) (* x 2)) (defun addall (&rest xs) (reduce #'+ xs :initial-value 0)) ";
		assertThat(compileAndRun(defs + "(print (mapcar #'dbl '(1 2 3)))")).isEqualTo("(2 4 6)");
		assertThat(compileAndRun(defs + "(print (mapcar 'dbl '(1 2 3)))")).isEqualTo("(2 4 6)");
		assertThat(compileAndRun(defs + "(print (mapcar #'addall '(1 2) '(10 20)))")).isEqualTo("(11 22)");
		assertThat(compileAndRun(defs + "(print (mapcar #'addall '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(defs + "(print (mapc #'dbl '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(defs + "(print (mapcan #'list '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun(defs + "(print (reduce #'+ '(1 2 3 4)))")).isEqualTo("10");
		assertThat(compileAndRun(defs + "(print (reduce #'addall '(1 2 3) :initial-value 10))")).isEqualTo("16");
		assertThat(compileAndRun(defs + "(print (sort (list 3 1 2) #'<))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun(defs + "(print (funcall #'dbl 21))")).isEqualTo("42");
		assertThat(compileAndRun(defs + "(print (funcall #'addall 1 2 3))")).isEqualTo("6");
		assertThat(compileAndRun(defs + "(print (funcall #'addall))")).isEqualTo("0");
		// The same designator through a variable keeps the dispatching route, and both
		// routes answer alike.
		assertThat(compileAndRun(defs + "(let ((f #'dbl)) (print (mapcar f '(1 2 3))))")).isEqualTo("(2 4 6)");
		assertThat(compileAndRun(defs + "(let ((f #'addall)) (print (funcall f 1 2 3)))")).isEqualTo("6");
	}

	// An arity the callee cannot take is NOT a compile error at these sites: the arity
	// contract of funcall and the map family is a RUN-time one, so such a site keeps the
	// dispatcher and behaves exactly as the computed-designator spelling of it does --
	// which on this backend means the ladder's default arm, nil.
	@Test
	void compileAndRunLiteralDesignatorOfTheWrongArityKeepsTheDispatcher() throws Exception {
		String defs = "(defun dbl (x) (* x 2)) ";
		assertThat(compileAndRun(defs + "(print (funcall #'dbl 1 2))"))
			.isEqualTo(compileAndRun(defs + "(let ((f #'dbl)) (print (funcall f 1 2)))"));
		assertThat(compileAndRun("(print (mapcar #'cons '(1 2)))"))
			.isEqualTo(compileAndRun("(let ((f #'cons)) (print (mapcar f '(1 2))))"));
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
	void compileAndRunRowMajorArefReadsAndWritesAString() throws Exception {
		// A string is a rank-1 array of characters in CL, so row-major-aref reads it
		// like aref does -- the JVM already had the string arm (it shares aref's rank-1
		// helper); this pins it and the write spelling
		// (setf (row-major-aref v i) c), which is the same %schar-set place aref/char/elt
		// use: in place for a mutable buffer, a rebind that leaves the source constant
		// for
		// a literal (.kb/string-write-runtime.md, todo 587).
		assertThat(compileAndRun("""
				(defun %rmar-lit () "abc")
				(print (row-major-aref (%rmar-lit) 1))
				(let ((a (%rmar-lit))) (setf (row-major-aref a 0) #\\Z) (print a))
				(print (%rmar-lit))
				(let ((b (make-string 3 :initial-element #\\a)))
				  (setf (row-major-aref b 1) #\\Z)
				  (print b))
				""")).isEqualTo("#\\b\n\"Zbc\"\n\"abc\"\n\"aZa\"");
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
	void compileAndRunLoopAnaphoricItOutsideClUser() throws Exception {
		// Read outside cl-user the anaphor arrives package-qualified; missing it here
		// fails at COMPILE time, not at run time.
		assertThat(compileAndRun("""
				(defpackage :zzit (:use :cl))
				(in-package :zzit)
				(print (list (loop for x in '(nil nil 3 4) when x return it)
				             (loop for x in '(1 nil 3 nil 5) when x collect it)
				             (let ((acc nil)) (loop for x in '(1 nil 2) when x do (push it acc)) (nreverse acc))
				             (loop for x in '(1 nil 3) when x collect it else collect 0)
				             (loop for x in '(4 nil 6) when x when (* x 10) collect it)
				             (loop for x in '(1 nil) when x collect (loop for y in '(5 nil 6) when y collect it))
				             (loop for i from 1 collect i into xs do (when (>= i 3) (loop-finish))
				                   finally (return (length xs)))))
				""")).isEqualTo("(3 (1 3 5) (1 2) (1 0 3) (40 60) ((5 6)) 3)");
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
	void compileAndRunExportAfterTheDefinitions() throws Exception {
		// export grants ACCESSIBILITY and never re-keys the symbol, so the function, the
		// value and the setf-function cells defined BEFORE the export are all reachable
		// through the external spelling afterwards.
		assertThat(compileAndRun("""
				(defpackage :latepkg (:use :cl))
				(defun latepkg::my-fn (x) (* x 2))
				(defvar latepkg::*v* 7)
				(defun (setf latepkg::slot) (v c) (rplaca c v) v)
				(export '(latepkg::my-fn latepkg::*v* latepkg::slot) :latepkg)
				(print (latepkg:my-fn 21))
				(print latepkg:*v*)
				(let ((c (list 1 2)))
				  (setf (latepkg:slot c) 9)
				  (print (car c)))
				""")).isEqualTo("42\n7\n9");
	}

	@Test
	void compileAndRunImportMakesASymbolAccessibleUnqualified() throws Exception {
		// Like export: a literal top-level import is consumed by the PackageResolver,
		// which is what makes it work in output that has no package registry.
		assertThat(compileAndRun("""
				(defpackage :impkg (:use :cl) (:export #:pub))
				(in-package :impkg)
				(defun pub () 1)
				(defun priv () 2)
				(in-package :cl-user)
				(import 'impkg:pub)
				(import '(impkg::priv))
				(print (+ (pub) (priv)))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunPackageRegistryQueries() throws Exception {
		// Answered from the use table baked in at compile time -- same values the
		// interpreter reads off its live registry.
		assertThat(compileAndRun("""
				(defpackage :pql-a (:use :cl) (:export #:hi))
				(defpackage :pql-b (:use :cl :pql-a))
				(print (package-use-list :pql-b))
				(print (package-used-by-list :pql-a))
				(print (package-use-list :cl))
				(print (package-shadowing-symbols :cl-user))
				(print (car (member :pql-a (list-all-packages))))
				(defun q (p) (package-use-list p))
				(print (q (find-package "CL-USER")))
				(print (mapcar #'package-name (funcall #'package-use-list :cl-user)))
				(print (handler-case (package-use-list :no-such-package) (error (e) :signalled)))
				""")).isEqualTo("(:CL :PQL-A)\n(:PQL-B)\nNIL\nNIL\n:PQL-A\n(:CL)\n(\"CL\")\n:SIGNALLED");
	}

	@Test
	void compileAndRunRempropDropsOnePropertyFromThePlist() throws Exception {
		assertThat(compileAndRun("""
				(setf (get 'rp 'a) 1)
				(setf (get 'rp 'b) 2)
				(setf (get 'rp 'c) 3)
				(print (list (remprop 'rp 'b) (symbol-plist 'rp) (remprop 'rp 'zz)
				             (remprop 'rp 'c) (symbol-plist 'rp) (get 'rp 'a)))
				(print (remprop 'nothing 'x))
				""")).isEqualTo("(T (C 3 A 1) NIL T (A 1) 1)\nNIL");
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
	void compileAndRunASplicedFileGetsItsOwnLoadContext() throws Exception {
		// The (%begin-file PATHNAME TRUENAME) brackets LoadInliner puts around every
		// spliced file (LoadInlinerTest) lower to assignments of the two variables here,
		// with the enclosing file's values assigned back at the end -- so a top-level
		// form of a spliced file reads its own file, a nested load reads the nested
		// file, and a function called after the load reads nil, exactly like the
		// interpreter's dynamic binding around the same file.
		assertThat(compileAndRun("""
				(defun ctx () (list *load-pathname* *load-truename*))
				(%begin-file "a.lisp" "/tmp/a.lisp")
				(print (ctx))
				(%begin-file "b.lisp" "/tmp/b.lisp")
				(print (ctx))
				(%end-file)
				(print (ctx))
				(%end-file)
				(print (ctx))
				""")).isEqualTo("""
				("a.lisp" "/tmp/a.lisp")
				("b.lisp" "/tmp/b.lisp")
				("a.lisp" "/tmp/a.lisp")
				(NIL NIL)""");
	}

	@Test
	void aLoadContextBracketCostsNothingWhenTheProgramNeverReadsIt() throws Exception {
		// The gate: a program that mentions neither variable has the brackets dropped
		// and is byte-identical to the same program without them, so splicing a file
		// into a program that does not care about its own path changes no output.
		byte[] bracketed = new JvmLispCompiler("Test").compile(LispReader.readAllFromString("""
				(%begin-file "a.lisp" "/tmp/a.lisp")
				(defun f () 42)
				(%end-file)
				(print (f))
				"""));
		byte[] plain = new JvmLispCompiler("Test").compile(LispReader.readAllFromString("""
				(defun f () 42)
				(print (f))
				"""));
		assertThat(bracketed).isEqualTo(plain);
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
			.isEqualTo("#P\"" + tempDir.resolve("probe.txt") + "\"\nNIL\nNO");
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
			.isEqualTo("(#P\"" + tempDir.resolve("fc.txt") + "\" NIL)\n#P\"" + tempDir.resolve("fc.txt") + "\"\nNIL");
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
			.isEqualTo(("(#P\"%sa.txt\" #P\"%sb.txt\" #P\"%ssub/\")\n" + "(#P\"%sa.txt\" #P\"%sb.txt\")\n"
					+ "(#P\"%ssub/\")\n" + "(#P\"%s\")\n" + "NIL\n" + "#P\"%s\"\n" + "NIL\n"
					+ "(#P\"%sa.txt\" #P\"%sb.txt\")\n" + "(#P\"%ssub/\")\n" + "(#P\"%s\" #P\"%ssub/\")")
				.formatted(d, d, d, d, d, d, d, d, d, d, d, d, d));
	}

	@Test
	void wildPathnameComponentsBuildMatchTranslateAndWalk() throws Exception {
		// Same shape and the same SBCL-checked expectations as the interpreter test.
		java.nio.file.Path root = java.nio.file.Files.createDirectory(tempDir.resolve("tree"));
		java.nio.file.Files.createDirectories(root.resolve("a/b/c"));
		java.nio.file.Files.createDirectories(root.resolve("a/d"));
		java.nio.file.Files.writeString(root.resolve("a/top.lisp"), "1\n");
		java.nio.file.Files.writeString(root.resolve("a/b/mid.lisp"), "2\n");
		java.nio.file.Files.writeString(root.resolve("a/b/c/deep.lisp"), "3\n");
		java.nio.file.Files.writeString(root.resolve("a/d/x.txt"), "4\n");
		String dir = root.toString().replace("\\", "\\\\");
		String d = root + "/";
		assertThat(compileAndRun("""
				(print (namestring (make-pathname :directory '(:absolute "a" :wild-inferiors)
				                                  :name :wild :type "lisp")))
				(print (namestring (make-pathname :name :wild :type :wild)))
				(print (list (pathname-directory "/a/**/x.lisp") (pathname-directory "/a/*/x.lisp")
				             (pathname-directory "../a/")))
				(print (list (pathname-name "/a/**/*.lisp") (pathname-type "/a/**/*.lisp")))
				(print (list (wild-pathname-p "/a/**/x.lisp" :directory)
				             (wild-pathname-p "/a/**/x.lisp" :name)))
				(print (namestring (translate-pathname "/a/b/d/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
				(print (namestring (translate-pathname "/a/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
				(print (directory "%s/a/**/*.lisp"))
				(print (directory "%s/a/*/*.lisp"))
				(print (directory "%s/a/**/"))
				(print (directory "%s/nope/**/*.lisp"))
				""".formatted(dir, dir, dir, dir))).isEqualTo(("\"/a/**/*.lisp\"\n" + "\"*.*\"\n"
				+ "((:ABSOLUTE \"a\" :WILD-INFERIORS)" + " (:ABSOLUTE \"a\" :WILD) (:RELATIVE :UP \"a\"))\n"
				+ "(:WILD \"lisp\")\n" + "(T NIL)\n" + "\"/x/b/d/c.fasl\"\n" + "\"/x/c.fasl\"\n"
				+ "(#P\"%sa/b/c/deep.lisp\" #P\"%sa/b/mid.lisp\" #P\"%sa/top.lisp\")\n" + "(#P\"%sa/b/mid.lisp\")\n"
				+ "(#P\"%sa/\" #P\"%sa/b/\" #P\"%sa/b/c/\" #P\"%sa/d/\")\n" + "NIL")
			.formatted(d, d, d, d, d, d, d, d));
	}

	@Test
	void pathnameAlgebraOverTheFlatNamestring() throws Exception {
		// The pathname operators that are pure computation over the namestring: the
		// three components rontolisp does not model, wild-pathname-p per field,
		// enough-namestring (with the *default-pathname-defaults* special BOUND) and
		// translate-pathname. Same expectations as the interpreter suite, SBCL-checked.
		assertThat(compileAndRun("""
				(print (list (pathname-host "d/a.txt") (pathname-device #P"d/a.txt")
				             (pathname-version #P"d/a.txt")))
				(print (list (wild-pathname-p "d/*.txt") (wild-pathname-p "d/a.txt")
				             (wild-pathname-p "d/*.txt" :name) (wild-pathname-p "d/*.txt" :type)
				             (wild-pathname-p "*/a.txt" :directory) (wild-pathname-p "d/*.txt" :host)))
				(print (enough-namestring "/a/b/c.lisp" "/a/"))
				(print (enough-namestring "/a/b/c.lisp" "/x/"))
				(print (namestring *default-pathname-defaults*))
				(print (let ((*default-pathname-defaults* #P"/a/b/")) (enough-namestring "/a/b/c.lisp")))
				(print (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
				(print (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
				(print (translate-logical-pathname "d/a.txt"))
				(print (handler-case (logical-pathname "SYS:SRC;") (error () :signalled)))
				""")).isEqualTo("""
				(NIL NIL NIL)
				(T NIL T NIL T NIL)
				"b/c.lisp"
				"/a/b/c.lisp"
				""
				"c.lisp"
				#P"build/foo.fasl"
				"x/a-y.b"
				#P"d/a.txt"
				:SIGNALLED""");
	}

	@Test
	void namestringHalvesNstringCaseAndEnvironmentEnquiry() throws Exception {
		// The three prelude-Lisp families: the string-valued halves of
		// a namestring, the destructive case family, and the environment-enquiry
		// constants. Same expectations as the interpreter suite except the two noted
		// below, which are the machine-type target name and the compile-path-only
		// immutable-string deviation every indexed write has
		// (.kb/string-write-runtime.md).
		assertThat(compileAndRun("""
				(print (list (file-namestring #P"/a/b/c.txt") (directory-namestring #P"/a/b/c.txt")
				             (host-namestring #P"/a/b/c.txt")))
				(print (list (file-namestring "a.txt") (directory-namestring "a.txt")))
				(print (list (file-namestring "/a/b/") (directory-namestring "/a/b/")))
				(print (list (file-namestring "/a/.bashrc") (directory-namestring "/a/.bashrc")))
				(print (nstring-upcase (copy-seq "hello world")))
				(print (nstring-downcase (copy-seq "ABC")))
				(print (nstring-capitalize (copy-seq "hello world")))
				(print (funcall #'nstring-upcase (copy-seq "ab")))
				;; A mutable character vector IS written in place, on every backend.
				(print (let ((s (make-string 3 :initial-element #\\a)))
				         (list (eq s (nstring-upcase s)) s)))
				;; An IMMUTABLE string is not: the write rebuilds and setqs back, so the
				;; caller's own reference is unchanged here where the interpreter's is
				;; upcased. The compile-path deviation the whole mutation family has.
				(print (let ((s (copy-seq "ab"))) (nstring-upcase s) s))
				(print (list (lisp-implementation-type) (software-type) (software-version)))
				(print (list (machine-type) (machine-version) (machine-instance)))
				(print (list (short-site-name) (long-site-name)))
				(print (equal (lisp-implementation-version) (getf (rontolisp:version) :version)))
				""")).isEqualTo("""
				("c.txt" "/a/b/" "")
				("a.txt" "")
				("" "/a/b/")
				(".bashrc" "/a/")
				"HELLO WORLD"
				"abc"
				"Hello World"
				"AB"
				(T "AAA")
				"ab"
				("rontolisp" "Unix" NIL)
				("JVM" NIL NIL)
				(NIL NIL)
				T""");
	}

	@Test
	void renameFileMovesTheFileOnDisk() throws Exception {
		java.nio.file.Path root = java.nio.file.Files.createDirectory(tempDir.resolve("rn"));
		java.nio.file.Files.writeString(root.resolve("a.txt"), "hi\n");
		String dir = root.toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(print (rename-file "%s/a.txt" "b.txt"))
				(print (probe-file "%s/a.txt"))
				(print (probe-file "%s/b.txt"))
				(print (handler-case (rename-file "%s/nope.txt" "c.txt") (error () :signalled)))
				""".formatted(dir, dir, dir, dir)))
			.isEqualTo(("#P\"%s/b.txt\"\nNIL\n#P\"%s/b.txt\"\n:SIGNALLED").formatted(root, root));
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
	void formatWriteDirectiveRendersLikePrin1OnBothPaths() throws Exception {
		// ~W is `write` of the argument, and it consumes exactly one -- rove's assertion
		// description ends in ~:[...~], which reads the WRONG argument if the ~W before
		// it is copied out verbatim (.kb/format.md).
		assertThat(compileAndRun("""
				(princ (format nil "Expect ~W to be ~:[true~;false~]." '(= (add 1 2) 3) nil))
				(terpri)
				(let ((c "Expect ~W to be ~:[true~;false~]."))
				  (princ (format nil c '(= (add 1 2) 3) t)))
				(terpri)
				(princ (format nil "~w|~:w|~@w|~a" "s" 'a nil "s"))
				""")).isEqualTo("""
				Expect (= (ADD 1 2) 3) to be true.
				Expect (= (ADD 1 2) 3) to be false.
				"s"|A|NIL|s""");
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

	// The ~/name/ arm of the renderer is injected only for a program whose control
	// strings SPELL the directive (.kb/format.md): it resolves a function out of the
	// control string at run time, which otherwise keeps every function dispatchable for
	// --optimize. The literal here is all the compile needs, wherever it sits -- here it
	// is bound to a local and never appears in the format call itself.
	@Test
	void formatUserFunctionDirectiveRendersThroughARuntimeControl() throws Exception {
		assertThat(compileAndRun("""
				(defun fmt-slash-brackets (s x c a) (princ (if c "[" "<") s) (princ x s) (princ (if a "]" ">") s))
				(let ((c "~/fmt-slash-brackets/ ~:@/fmt-slash-brackets/"))
				  (princ (format nil c 1 2)))
				""")).isEqualTo("<1> [2]");
	}

	// ...and a control ASSEMBLED at run time gets the stub, which says so instead of
	// silently dropping the directive's output. The narrowing is compile-path only: the
	// interpreter always carries the real arm. Reporting the condition renders its
	// message as a control string again, so this also pins that the stub's own message
	// carries no directive to re-enter it with.
	@Test
	void formatUserFunctionDirectiveSignalsWhenTheCompileNeverSawTheDirective() throws Exception {
		assertThat(compileAndRun("""
				(defun fmt-slash-brackets (s x c a) (princ (if c "[" "<") s) (princ x s) (princ (if a "]" ">") s))
				; The control must be built from a value the compile path cannot know, or the
				; pure-builtin literal fold reduces it back to a literal directive.
				(defun fmt-control (tilde) (concatenate 'string tilde "/fmt-slash-brackets/"))
				(princ (handler-case (format nil (fmt-control "~") 1)
				         (error (e) (if (search "--dynamic" (format nil "~a" e)) "signalled" "no-hint"))))
				""")).isEqualTo("signalled");
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
	void stringInputStreamReadsWithoutWithInputFromString() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (make-string-input-stream "ab
				cd")))
				  (princ (read-line s))
				  (princ (read-line s))
				  (princ (read-line s nil 'eof)))""")).isEqualTo("abcdEOF");
	}

	@Test
	void stringInputStreamHonoursStartAndEnd() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (make-string-input-stream "xxhixx" 2 4)))
				  (princ (read-char s))
				  (princ (read-char s))
				  (princ (read-char s nil 'eof)))""")).isEqualTo("hiEOF");
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
	void makeSynonymStreamIsAStreamValue() throws Exception {
		// A synonym stream is a VALUE, not the nil designator: it answers true, it is a
		// stream in both directions, close is a no-op t, and it prints the symbol it
		// forwards to.
		assertThat(compileAndRun("""
				(defvar *sink* (make-synonym-stream '*standard-output*))
				(princ (if *sink* "yes" "no"))
				(princ (streamp *sink*))
				(princ (input-stream-p *sink*))
				(princ (output-stream-p *sink*))
				(princ (close *sink*))
				(princ (synonym-stream-symbol *sink*))
				(princ *sink*)""")).isEqualTo("yesTTTT*STANDARD-OUTPUT*#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>");
	}

	@Test
	void synonymStreamOverAUserSpecialFollowsALaterBinding() throws Exception {
		// The re-evaluation trigger the lite lowering left behind: a synonym over a
		// NON-standard symbol forwards per operation too, so a binding established after
		// the synonym was built redirects it. A Gray stream on either side of the
		// synonym is carried as well (rove's reporter shape).
		assertThat(compileAndRunGray("""
				(defvar *port* t)
				(defvar *syn* (make-synonym-stream '*port*))
				(defclass upcaser (rontolisp:fundamental-character-output-stream)
				  ((target :initarg :target :reader upcaser-target)))
				(defmethod rontolisp:stream-write-string ((s upcaser) str)
				  (write-string (string-upcase str) (upcaser-target s))
				  str)
				(princ (with-output-to-string (s) (let ((*port* s)) (write-string "user" *syn*))))
				(princ "|")
				(princ (with-output-to-string (s)
				  (let ((*port* s)) (write-string "wrap" (make-instance 'upcaser :target *syn*)))))
				(princ "|")
				(princ (with-output-to-string (s)
				  (let ((*port* (make-instance 'upcaser :target s))) (write-string "under" *syn*))))"""))
			.isEqualTo("user|WRAP|UNDER");
	}

	@Test
	void synonymStreamOverStandardOutputFollowsALaterBinding() throws Exception {
		// The synonym stream resolves its symbol at WRITE time, so a binding
		// established after it was built still captures.
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
	void makeSynonymStreamOverStandardInputFollowsALaterBinding() throws Exception {
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
	void binaryStandardStreamsAreByteTransparent() throws Exception {
		// The compiled twin of
		// LispEvaluatorTest#evalBinaryStandardStreamsAreByteTransparent:
		// a NUL, a high byte and a newline survive stdin -> stdout unchanged, and the
		// trailing 'z' (no newline after it) is what pins the end-of-main flush -- an
		// auto-flushing PrintStream drains a single-byte write only on '\n'.
		byte[] octets = { 'h', 'i', 0, (byte) 0xE6, (byte) 0x97, (byte) 0xA5, (byte) 0xFF, '\n', 'z' };
		assertThat(compileAndRunBinary("""
				(let ((b (read-byte *standard-input* nil nil)))
				  (while b
				    (write-byte b *standard-output*)
				    (setq b (read-byte *standard-input* nil nil))))
				""", octets)).isEqualTo(octets);
	}

	@Test
	void binaryStandardStreamDesignators() throws Exception {
		assertThat(compileAndRunBinary("""
				(setq buf (make-array 4 :initial-element 0))
				(setq filled (read-sequence buf t))
				(write-byte 62 nil)
				(write-sequence buf t :end filled)
				(write-byte 60 *standard-output*)
				(print (read-byte *standard-input* nil :eof))
				""", new byte[] { 65, 66, 67 })).isEqualTo(">ABC<:EOF\n".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void aNumericStreamHandleIsNeverAStandardStream() throws Exception {
		// The table reserves handles 0/1/2 only for a program that names
		// *error-output*, so here the string stream IS handle 0 and the file handle 1.
		// A byte helper that read 0 as standard input or wrote 1 to standard output
		// would hijack both -- which is what it did to cl-postgres' handshake, whose
		// socket is the first stream its program opens.
		String file = tempDir.resolve("handles.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(print (with-output-to-string (s) (princ "captured" s)))
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 65 out)
				  (write-byte 66 out))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in nil :eof)))
				""".formatted(file, file))).isEqualTo("\"captured\"\n65\n66\n:EOF");
	}

	/** Runs a compiled program over RAW stdin bytes and answers its raw stdout bytes. */
	private byte[] compileAndRunBinary(String lispCode, byte[] stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, compiler.compile(program));
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method main = loader.loadClass("Test").getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			InputStream oldIn = System.in;
			System.setOut(new PrintStream(baos));
			System.setIn(new ByteArrayInputStream(stdin));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
				System.setIn(oldIn);
			}
			return baos.toByteArray();
		}
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
	void readWriteSequencePackedBuffersMoveRawLittleEndianElements() throws Exception {
		// The _readSeqPacked / _writeSeqPacked helpers (.kb/binary-sequence-io.md): a
		// packed float array of any rank and a packed (unsigned-byte 8|16|32) vector move
		// as raw little-endian elements in one bulk transfer; a general vector still
		// receives one byte per element.
		String file = tempDir.resolve("pk.dat").toString().replace("\\", "\\\\");
		assertThat(compileAndRun(
				"""
						(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
						  (write-sequence #f(1.5 -2.25 3.0e10) out)
						  (write-sequence #d((0.5 -0.0) (0.1 42.0)) out)
						  (write-sequence (make-array 3 :element-type '(unsigned-byte 16) :initial-contents '(1 65535 258)) out)
						  (write-sequence (make-array 2 :element-type '(unsigned-byte 32) :initial-contents '(65536 4294967295)) out))
						(with-open-file (in "%s" :element-type '(unsigned-byte 8))
						  (let ((f (make-array 3 :element-type 'single-float :initial-element 0.0))
						        (d (make-array '(2 2) :element-type 'double-float :initial-element 0.0))
						        (u16 (make-array 3 :element-type '(unsigned-byte 16)))
						        (u32 (make-array 2 :element-type '(unsigned-byte 32))))
						    (print (list (read-sequence f in) (read-sequence d in) (read-sequence u16 in) (read-sequence u32 in)))
						    (print f) (print d) (print u16) (print u32)))
						(with-open-file (in "%s" :element-type '(unsigned-byte 8))
						  (let ((b (make-array 4 :element-type '(unsigned-byte 8))))
						    (read-sequence b in)
						    (print b)))
						(with-open-file (in "%s" :element-type '(unsigned-byte 8))
						  (let ((f (make-array 6 :element-type 'single-float :initial-element 9.0)))
						    (print (read-sequence f in :start 1 :end 3))
						    (print f)))
						(with-open-file (in "%s" :element-type '(unsigned-byte 8))
						  (let ((g (make-array 4)))
						    (print (read-sequence g in))
						    (print g)))
						"""
					.formatted(file, file, file, file, file)))
			.isEqualTo(
					"(3 4 3 2)\n#f(1.5 -2.25 3.0e10)\n#d((0.5 -0.0) (0.1 42.0))\n#(1 65535 258)\n#(65536 4294967295)\n#(0 0 192 63)\n3\n#f(9.0 1.5 -2.25 9.0 9.0 9.0)\n4\n#(0 0 192 63)");
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
	void aBignumLiteralIsBuiltOnceAndLoadedFromAField() throws Exception {
		// A BigInteger is immutable, so every use of one literal is the same value: the
		// compiler interns it into a static field built once in <clinit> and each use
		// site is a GETSTATIC, not a fresh allocation plus a decimal-string parse. Two
		// uses of the SAME literal share one field; a second, different literal gets its
		// own.
		String source = """
				(defun mask (x) (logand x 18446744073709551615))
				(defun mask2 (x) (logand x 18446744073709551615))
				(print (mask 12345678901234567890))
				(print (mask2 99999999999999999999))
				""";
		byte[] classBytes = compileToBytes(source);
		assertThat(bignumPoolFieldNames(classBytes)).containsExactly("_bi$0", "_bi$1", "_bi$2");
		assertThat(compileAndRun(source)).isEqualTo("""
				12345678901234567890
				7766279631452241919""");
	}

	@Test
	void aProgramWithoutABignumLiteralGetsNoPoolAndNoClassInitializer() throws Exception {
		// The pool must not perturb a program that has no bignum literal: no field, and
		// -- the part that would otherwise change every emitted class -- no <clinit>
		// where there was none.
		byte[] classBytes = compileToBytes("(print (+ 1 2))");
		assertThat(bignumPoolFieldNames(classBytes)).isEmpty();
		assertThat(countOccurrences(classBytes, "_bi$")).isZero();
		assertThat(countOccurrences(classBytes, "<clinit>")).isZero();
	}

	private byte[] compileToBytes(String lispCode) {
		return new JvmLispCompiler("Test")
			.compile(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	// The pooled bignum field names, in emission order, read back off the class file
	// through the class loader (the fields are private static, so reflection sees them).
	private List<String> bignumPoolFieldNames(byte[] classBytes) throws Exception {
		Path dir = Files.createDirectories(this.tempDir.resolve("pool"));
		Files.write(dir.resolve("Test.class"), classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			return java.util.Arrays.stream(loader.loadClass("Test").getDeclaredFields())
				.filter(f -> f.getName().startsWith("_bi$"))
				.map(java.lang.reflect.Field::getName)
				.toList();
		}
	}

	private static int countOccurrences(byte[] classBytes, String needle) {
		byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
		int count = 0;
		outer: for (int i = 0; i + pattern.length <= classBytes.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (classBytes[i + j] != pattern[j]) {
					continue outer;
				}
			}
			count++;
		}
		return count;
	}

	@Test
	void compileAndRunModFloat() throws Exception {
		assertThat(compileAndRun("(print (mod -5.5 2.0))")).isEqualTo("0.5");
	}

	// ANSI defines mod/rem over any REAL. A ratio operand used to fall through the Long
	// fast path into _big and die casting the BigInteger[2] representation; it now takes
	// the rational path, sign rules and exact-division demotion included. The variable
	// binding matters: it is what stops the emitter from seeing the operand type.
	@Test
	void compileAndRunModAndRemOfRatio() throws Exception {
		assertThat(compileAndRun("(let ((r 7/2)) (print (mod r 3)) (print (rem r 3)))")).isEqualTo("""
				1/2
				1/2""");
		assertThat(compileAndRun("(print (mod -7/2 3))")).isEqualTo("5/2");
		assertThat(compileAndRun("(print (rem -7/2 3))")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print (mod 7/2 -3))")).isEqualTo("-5/2");
		assertThat(compileAndRun("(print (rem 7/2 -3))")).isEqualTo("1/2");
		assertThat(compileAndRun("(print (mod 5 3/4))")).isEqualTo("1/2");
		assertThat(compileAndRun("(print (rem 5 3/4))")).isEqualTo("1/2");
		assertThat(compileAndRun("(print (mod -7/3 -2/5))")).isEqualTo("-1/3");
		assertThat(compileAndRun("(print (rem -7/3 -2/5))")).isEqualTo("-1/3");
		assertThat(compileAndRun("(print (mod 7/2 1/2))")).isEqualTo("0");
		assertThat(compileAndRun("(print (rem 22/7 22/7))")).isEqualTo("0");
		assertThat(compileAndRun("(handler-case (mod 7/2 0) (error (e) (print 'divzero)))")).isEqualTo("DIVZERO");
		assertThat(compileAndRun("(handler-case (rem 7/2 0) (error (e) (print 'divzero)))")).isEqualTo("DIVZERO");
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
	void compileAndRunFeaturesIsAnOrdinarySpecialVariable() throws Exception {
		// *features* is a VARIABLE on this backend too: seeded with the set the frontend
		// read the program with, pushed onto and bound like any other special. The
		// reader used to substitute the symbol with the quoted list, which made every
		// push a no-op -- and in a BINDING position put a cons where the variable name
		// goes, so clack:clackup's (let* ((*features* (cons :clackup *features*))) ...)
		// failed to compile at all.
		assertThat(compileAndRun("(print (car *features*))")).isEqualTo(":RONTOLISP");
		assertThat(
				compileAndRun("(pushnew :my-feature *features*)" + "(print (and (member :my-feature *features*) t))"))
			.isEqualTo("T");
		assertThat(compileAndRun("(defun f (a) (let ((*features* (cons :inner *features*))) (list a (car *features*))))"
				+ "(print (f 1))(print (car *features*))"))
			.isEqualTo("(1 :INNER)\n:RONTOLISP");
		// ... and the binding is DYNAMIC, so it reaches a callee reading the variable
		// -- the shape upstream uiop:featurep's own parameter list invites.
		assertThat(compileAndRun("(defun g (&optional (fs *features*)) (car fs))"
				+ "(print (let ((*features* '(:rebound))) (g)))(print (g))"))
			.isEqualTo(":REBOUND\n:RONTOLISP");
	}

	@Test
	void compileAndRunOwnFeaturePushIsVisibleToTheSameSourcesConditionals() throws Exception {
		// The announcement idiom, decided in the READER so every backend agrees
		// (reader.FeaturePushes): the #+ below the push sees it, and the run-time list
		// grows when the push itself runs.
		assertThat(compileAndRun("""
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (pushnew :announced *features*))
				(print #+announced :saw-it #-announced :missed-it)
				(print (and (member :announced *features*) t))
				""")).isEqualTo(":SAW-IT\nT");
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
	void compileAndRunMultiPairSetqBuildsAClosureInALaterPair() throws Exception {
		// setq takes place/value PAIRS, and a lambda in a pair after the first is a
		// closure like any other. The free-variable walk used to look at the first pair
		// only, so every later pair's captures went unrecorded: this printed 0 (the
		// closure wrote a copy) instead of 42. cl-json's set-custom-vars expands to a
		// multi-pair setq whose values ARE lambdas, which is how json-rpc found it.
		assertThat(compileAndRun("""
				(defvar *a* nil)
				(defvar *b* nil)
				(defun f ()
				  (let ((g 0))
				    (setq *a* 1 *b* (lambda (v) (setq g v) nil))
				    (funcall *b* 42)
				    g))
				(print (f))
				""")).isEqualTo("42");
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
	void compileAndRunPrintOfACyclicInstanceGraphIsFinite() throws Exception {
		// Two instances pointing at each other used to StackOverflowError the emitted
		// _instToString: the default instance renderer had no cycle guard. An instance
		// already on the current rendering path -- or past the 256-frame depth cap --
		// prints as "#", CL's *print-level* cutoff marker, byte-identical to the
		// interpreter (LispEvaluatorTest.evalPrintOfACyclicInstanceGraphIsFinite).
		assertThat(compileAndRun("""
				(defclass cyc () ((next :initform nil :accessor cyc-next)
				                  (tag :initarg :tag :reader cyc-tag)))
				(let ((p (make-instance 'cyc :tag 1)) (q (make-instance 'cyc :tag 2)))
				  (setf (cyc-next p) q)
				  (setf (cyc-next q) p)
				  (print p)
				  (princ p))
				(terpri)
				(defstruct knot next)
				(let ((k (make-knot)))
				  (setf (knot-next k) k)
				  (prin1 k))
				""")).isEqualTo("""
				#<CYC :NEXT #<CYC :NEXT # :TAG 2> :TAG 1>
				#<CYC :NEXT #<CYC :NEXT # :TAG 2> :TAG 1>
				#S(KNOT :NEXT #)""");
	}

	@Test
	void compileAndRunPrintOfACyclicConsIsFinite() throws Exception {
		// A cons whose cdr chain re-enters itself used to loop the emitted
		// _consToString without end; a car cycle overflowed the stack. The chain's
		// cycle-start cell prints as the improper tail " . #" on its second arrival
		// (every element exactly once), a cons or vector already on the current
		// rendering path prints as "#" -- byte-identical to the interpreter
		// (LispEvaluatorTest.evalPrintOfACyclicConsIsFinite).
		assertThat(compileAndRun("""
				(let ((x (list 1)))
				  (setf (cdr x) x)
				  (prin1 x)
				  (terpri)
				  (princ x))
				(terpri)
				(let ((x (list 1 2 3)))
				  (setf (cdr (cdr (cdr x))) (cdr x))
				  (prin1 x))
				(terpri)
				(let ((x (list 1 2)))
				  (setf (car x) x)
				  (prin1 x))
				(terpri)
				(let ((v (vector 1 2)))
				  (setf (aref v 0) v)
				  (prin1 v))
				(terpri)
				(let ((s (list 9)))
				  (prin1 (list s s)))
				""")).isEqualTo("""
				(1 . #)
				(1 . #)
				(1 2 3 . #)
				(# 2)
				#(# 2)
				((9) (9))""");
	}

	@Test
	void compileAndRunPrintOfACyclicConsIsFiniteThroughTheWalks() throws Exception {
		// With a print-object method defined the operators render through the
		// %print-object-str walk; a program that binds *print-case* without methods
		// renders through %print-cased. Both walks carry the same guard as the raw
		// renderers, so the text is identical.
		assertThat(compileAndRun("""
				(defclass tagged () ())
				(defmethod print-object ((o tagged) s) (write-string "#<TAGGED>" s))
				(let ((x (list 1 (make-instance 'tagged))))
				  (setf (cdr (cdr x)) x)
				  (prin1 x))
				""")).isEqualTo("(1 #<TAGGED> . #)");
		assertThat(compileAndRun("""
				(let ((*print-case* :downcase))
				  (let ((x (list 'a)))
				    (setf (cdr x) x)
				    (prin1 x)))
				""")).isEqualTo("(a . #)");
	}

	@Test
	void compileAndRunPrintCase() throws Exception {
		// *print-case* converts the case of every SYMBOL the printer spells, on the
		// compile path as on the interpreter (the shared %print-cased renderer, spliced
		// because the program mentions the variable). Values verified against SBCL 2.2.9.
		assertThat(compileAndRun("""
				(dolist (m (list :upcase :downcase :capitalize))
				  (let ((*print-case* m))
				    (princ (princ-to-string 'add-test)) (princ "|")
				    (princ (prin1-to-string '(foo "Str" nil t 1))) (princ "|")
				    (princ (write-to-string (vector 'a 'b))) (terpri)))
				(let ((*print-case* :downcase))
				  (princ (format nil "~a ~s ~a" 'foo :foo (princ-to-string nil))) (terpri)
				  (princ 'foo) (princ "|") (prin1 :foo) (terpri))
				(let ((*print-case* :capitalize))
				  (princ (princ-to-string (intern "*FOO*"))) (princ "|")
				  (princ (princ-to-string (intern "foo-BAR"))) (terpri))
				""")).isEqualTo("""
				ADD-TEST|(FOO "Str" NIL T 1)|#(A B)
				add-test|(foo "Str" nil t 1)|#(a b)
				Add-Test|(Foo "Str" Nil T 1)|#(A B)
				foo :foo nil
				foo|:foo
				*Foo*|foo-Bar""");
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
	void compileAndRunARedefinedDefunKeepsTheLastDefinition() throws Exception {
		// A class may not hold two methods of the same name and descriptor, so a
		// redefined defun (fast-http redefines 11 struct readers as plain defuns)
		// emits only its LAST definition -- which every by-name call resolves to
		// anyway on the compile backends.
		assertThat(compileAndRun("""
				(defun f (x) x)
				(defun f (x) (+ x 1))
				(print (f 41))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunABranchSpanningPastTheSigned16BitOffset() throws Exception {
		// A single if whose then-branch outgrows the signed 16-bit branch offset:
		// fast-http's generated parse-header-field-and-value state machine is the
		// real-world shape (36 KB body). The relaxation pass rewrites each
		// out-of-range branch as an inverted-condition short branch over a goto_w
		// (.kb/jvm-method-size-limits.md); a method with only in-range branches keeps
		// byte-identical output.
		StringBuilder wide = new StringBuilder();
		for (int i = 0; i < 2800; i++) {
			wide.append("(setq acc (+ acc 1))");
		}
		String program = """
				(defun big (flag)
				  (let ((acc 0))
				    (if flag (progn %s acc) -1)))
				(print (list (big t) (big nil)))
				""".formatted(wide);
		assertThat(compileAndRun(program)).isEqualTo("(2800 -1)");
	}

	@Test
	void compileAndRunConcatenateWithADeftypeAliasResultType() throws Exception {
		// fast-http's multipart parser concatenates into 'simple-byte-vector, its own
		// deftype alias of the packed octet vector: the registered deftype expansion
		// resolves the designator to the vector family at compile time.
		assertThat(compileAndRun("""
				(deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
				(print (concatenate 'octet-vector #(1) #(2 3)))
				""")).isEqualTo("#(1 2 3)");
	}

	@Test
	void compileAndRunConcatenateKeepsThePackedElementType() throws Exception {
		// An (unsigned-byte 8|16|32) result element type builds the PACKED vector, on the
		// compile paths through the injected %seq-int-vector helper. The
		// deftype alias carries the element type through as well, and any other element
		// type -- or a spelling whose second element is a SIZE -- stays general.
		// The zero-parameter deftype shape, like
		// compileAndRunConcatenateWithADeftypeAlias
		// ResultType: this harness does not run the CLI's UserMacroExpander, which is
		// what folds a parameterized deftype into the registrable form (the parameterized
		// shape is pinned end to end by the ci-spec case and LackEcosystemE2eTest).
		assertThat(compileAndRun("""
				(deftype simple-byte-vector () '(simple-array (unsigned-byte 8) (*)))
				(let ((v (concatenate '(vector (unsigned-byte 8)) #(1) '(2 260))))
				  (print (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v)))
				(print (array-element-type (concatenate '(simple-array (unsigned-byte 16) (*)) #(1))))
				(print (array-element-type (concatenate '(vector (unsigned-byte 32) *))))
				(print (array-element-type (concatenate 'simple-byte-vector #(1 2))))
				(print (array-element-type (concatenate '(simple-vector 2) '(1 2))))
				(print (array-element-type (concatenate '(vector character) "ab")))
				""")).isEqualTo("""
				((UNSIGNED-BYTE 8) T #(1 2 4))
				(UNSIGNED-BYTE 16)
				(UNSIGNED-BYTE 32)
				(UNSIGNED-BYTE 8)
				T
				T""");
	}

	@Test
	void compileAndRunArrayElementTypeOnAStringAnswersCharacter() throws Exception {
		// A string is a vector of characters, so it answers the one character type -- the
		// general (macro) path and the packed (runtime-helper) path must agree, and both
		// must match the interpreter. A general vector still answers t.
		assertThat(compileAndRun("""
				(print (array-element-type "abc"))
				(print (array-element-type #(1 2 3)))
				""")).isEqualTo("""
				CHARACTER
				T""");
		// With a packed representation in play the same call goes through the runtime
		// helper; a string still answers character there.
		assertThat(compileAndRun("""
				(print (array-element-type (make-array 3 :element-type '(unsigned-byte 8))))
				(print (array-element-type "abc"))
				""")).isEqualTo("""
				(UNSIGNED-BYTE 8)
				CHARACTER""");
	}

	@Test
	void compileAndRunCoerceKeepsThePackedElementType() throws Exception {
		// coerce reads the SAME result-type designator concatenate does, so a packed
		// element type means a packed vector there too. Both routes are exercised: a
		// LITERAL sequence, which PureBuiltinFolder reduces to the packed literal at
		// compile time, and one behind a function parameter, which goes through the
		// injected %seq-int-vector at run time. They must agree.
		assertThat(compileAndRun("""
				(defun %id (x) x)
				(let ((v (coerce '(1 2 260) '(vector (unsigned-byte 8)))))
				  (print (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v)))
				(let ((v (coerce (%id '(1 2 260)) '(vector (unsigned-byte 8)))))
				  (print (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v)))
				(print (array-element-type (coerce #(1) '(simple-array (unsigned-byte 16) (*)))))
				(print (array-element-type (coerce '(1) '(vector (unsigned-byte 32) *))))
				(print (array-element-type (coerce '(1 2) '(simple-vector 2))))
				(print (array-element-type (coerce '(1 2) 'vector)))
				""")).isEqualTo("""
				((UNSIGNED-BYTE 8) T #(1 2 4))
				((UNSIGNED-BYTE 8) T #(1 2 4))
				(UNSIGNED-BYTE 16)
				(UNSIGNED-BYTE 32)
				T
				T""");
	}

	@Test
	void compileAndRunFoldsALiteralLookupTableToItsPackedLiteral() throws Exception {
		// The whole point of folding the table: it is DATA at compile time, so the
		// backends bake it instead of consing the element list at run time. What the
		// program may still do to it is unchanged -- each evaluation of the literal
		// yields a FRESH, independently mutable vector, exactly as the coerce call did.
		assertThat(compileAndRun("""
				(defun table () (coerce '(10 20 30 40) '(vector (unsigned-byte 16))))
				(let ((a (table)) (b (table)))
				  (setf (aref a 0) 99)
				  (print (list a b (eq a b))))
				(print (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(7 8 9)))
				""")).isEqualTo("""
				(#(99 20 30 40) #(10 20 30 40) NIL)
				#(7 8 9)""");
	}

	@Test
	void compileAndRunConcatenateAsAFunctionValueKeepsThePackedElementType() throws Exception {
		// The #'concatenate wrapper's designator is a RUNTIME value, so it re-does the
		// width dispatch there -- http-body spells exactly this
		// (apply #'concatenate '(simple-array (unsigned-byte 8) (*)) ...), and a
		// designator must not mean two different things depending on the call form.
		assertThat(compileAndRun("""
				(print (array-element-type (apply #'concatenate '(simple-array (unsigned-byte 8) (*))
				                                  (list '(1 2) #(3)))))
				(print (funcall #'concatenate '(vector (unsigned-byte 8)) '(1 260)))
				(print (array-element-type (funcall #'concatenate 'vector '(1))))
				(print (array-element-type (funcall #'concatenate '(simple-vector 1) '(1))))
				""")).isEqualTo("""
				(UNSIGNED-BYTE 8)
				#(1 4)
				T
				T""");
	}

	@Test
	void compileAndRunTheLoweredOnlyBuiltinsAsFunctionValues() throws Exception {
		// The sweep: every CL FUNCTION the expression compiler lowers in operator
		// position must also have a wrapper, or #'name is undefined here while the
		// interpreter answers. Each of these used to be "The function NAME is
		// undefined" on all four backends.
		assertThat(compileAndRun(LoweredBuiltinValues.PROGRAM)).isEqualTo(LoweredBuiltinValues.OUTPUT);
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
	void compileAndRunStringCaseOpsAreFullUnicodeAndLengthPreserving() throws Exception {
		// Same contract as the interpreter: char-upcase / char-downcase per character,
		// so sharp s stays one character and a final sigma is not context-folded.
		assertThat(compileAndRun("(princ (string-downcase \"ÉΛΩ\"))")).isEqualTo("éλω");
		assertThat(compileAndRun("(princ (string-upcase \"éλω\"))")).isEqualTo("ÉΛΩ");
		assertThat(compileAndRun("(princ (string-upcase \"straße\"))")).isEqualTo("STRAßE");
		assertThat(compileAndRun("(princ (length (string-upcase \"straße\")))")).isEqualTo("6");
		assertThat(compileAndRun("(princ (string-downcase \"ΑΣ\"))")).isEqualTo("ασ");
		assertThat(compileAndRun("(princ (string-upcase \"𐐨𐐩\"))")).isEqualTo("𐐀𐐁");
		assertThat(compileAndRun("(princ (string-downcase \"𐐀𐐁\"))")).isEqualTo("𐐨𐐩");
		assertThat(compileAndRun("(princ (string-capitalize \"élan vital\"))")).isEqualTo("Élan Vital");
		assertThat(compileAndRun("(princ (string-capitalize \"aあb 42x\"))")).isEqualTo("Aあb 42x");
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
	void compileAndRunReplaceIntoAList() throws Exception {
		// A list destination is rewritten through its cons cells, like the interpreter's
		// native replace. It used to fall through to the immutable-string rebuild here
		// and die with a ClassCastException -- and (setf (subseq l ...)) on a list with
		// it (.kb/sequence-op-runtimes.md).
		assertThat(compileAndRun("(print (replace (list 1 2 3 4 5) '(9 9) :start1 1))")).isEqualTo("(1 9 9 4 5)");
		assertThat(compileAndRun("(print (replace (list 0 0 0 0) #(7 8 9) :start1 1 :end1 3 :start2 1))"))
			.isEqualTo("(0 8 9 0)");
		assertThat(compileAndRun("(print (replace (list 1 2) '(5 6 7 8)))")).isEqualTo("(5 6)");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4 5))) (setf (subseq l 1) '(9 9)) (print l))"))
			.isEqualTo("(1 9 9 4 5)");
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
	void compileAndRunStringDesignators() throws Exception {
		// CL coerces a string DESIGNATOR -- a string, a symbol (nil and t included) or a
		// character -- wherever a string is expected. A LITERAL designator folds to a
		// constant; a computed one goes through the shared, type-checking (string ...).
		assertThat(compileAndRun("(princ (string-trim \"*\" '*foo*))")).isEqualTo("FOO");
		assertThat(compileAndRun("(princ (string-left-trim \"F\" '|FOO|))")).isEqualTo("OO");
		assertThat(compileAndRun("(princ (string-right-trim \"O\" '|FOO|))")).isEqualTo("F");
		assertThat(compileAndRun("(princ (string-trim \"N\" nil))")).isEqualTo("IL");
		assertThat(compileAndRun("(princ (string-trim \"K\" :key))")).isEqualTo("EY");
		assertThat(compileAndRun("(princ (string-trim \"A\" #\\a))")).isEqualTo("a");
		assertThat(compileAndRun("(defun id (x) x) (princ (string-trim \"*\" (id '*foo*)))")).isEqualTo("FOO");
		assertThat(compileAndRun("(defun id (x) x) (princ (string-upcase (id #\\a)))")).isEqualTo("A");
		assertThat(compileAndRun("(princ (string-capitalize nil))")).isEqualTo("Nil");
		// A non-designator still signals: the widening is per POSITION, not "stringify
		// anything". Before this, the compile paths took the symbol's runtime spelling
		// for a quoted string and answered a silently wrong substring.
		assertThat(compileAndRun(
				"(defun id (x) x) (princ (handler-case (string-trim \"*\" (id 42)) (error () :signalled)))"))
			.isEqualTo("SIGNALLED");
		assertThat(
				compileAndRun("(defun id (x) x) (princ (handler-case (string-upcase (id 42)) (error () :signalled)))"))
			.isEqualTo("SIGNALLED");
		assertThat(compileAndRun(
				"(defun id (x) x) (princ (handler-case (string-trim (id #\\*) \"*x*\") (error () :signalled)))"))
			.isEqualTo("SIGNALLED");
		// ... but ANY sequence of characters is a bag, a general vector included.
		assertThat(compileAndRun("(defun id (x) x) (princ (string-trim (id (vector #\\x)) \"xhellox\"))"))
			.isEqualTo("hello");
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
	void compileAndRunReduceOverAnEmptySequence() throws Exception {
		// CL calls the function with ZERO arguments; #'append's wrapper must therefore be
		// variadic, not binary (esrap's parse-error report reaches both).
		assertThat(compileAndRun("(print (reduce #'append '()))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (reduce #'+ '()))")).isEqualTo("0");
		assertThat(compileAndRun("(print (funcall #'append))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (reduce #'append '((1 2) (3))))")).isEqualTo("(1 2 3)");
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
	void compileAndRunDeleteDuplicatesAndFromEnd() throws Exception {
		// delete-duplicates shares remove-duplicates' lowering; :from-end t keeps
		// the FIRST occurrence (sxql's group-by / select-statement ordering).
		assertThat(compileAndRun(
				"(print (delete-duplicates '(1 2 1 3 2))) (print (delete-duplicates '(1 2 1 3 2) :from-end t)) (print (remove-duplicates '(1 2 1 3 2) :from-end t)) (print (funcall #'delete-duplicates '(a b a a c)))"))
			.isEqualTo("(1 3 2)\n(1 2 3)\n(1 2 3)\n(B A C)");
	}

	@Test
	void compileAndRunCallNextMethodOverAStructIncludeChain() throws Exception {
		// A method on an :include PARENT struct joins the child's chain, and an
		// :around on t wraps struct-specialized primaries (sxql's yield tree).
		assertThat(compileAndRun("""
				(defstruct cnm-base (name "b"))
				(defstruct (cnm-child (:include cnm-base)))
				(defgeneric cnm-render (x))
				(defmethod cnm-render ((x cnm-base)) (list :base (cnm-base-name x) (next-method-p)))
				(defmethod cnm-render ((x cnm-child)) (cons :child (call-next-method)))
				(defmethod cnm-render :around ((x t)) (cons :around (call-next-method)))
				(defgeneric cnm-kind (x))
				(defmethod cnm-kind ((x structure-object)) :struct)
				(defmethod cnm-kind ((x t)) :other)
				(defstruct cnm-late)
				(print (cnm-render (make-cnm-child)))
				(print (list (cnm-kind (make-cnm-late)) (cnm-kind 42)))
				""")).isEqualTo("(:AROUND :CHILD :BASE \"b\" NIL)\n(:STRUCT :OTHER)");
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
	void compileAndRunNconcOfAListOntoItself() throws Exception {
		// (nconc s s) builds a circular list: the last argument is left untouched, so the
		// splice must not walk into the cycle it has just created.
		assertThat(compileAndRun("(let ((s (list 1 2 3))) (nconc s s) (print (nth 4 s)) (print (nth 6 s)))"))
			.isEqualTo("2\n1");
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
	void compileAndRunSubsetp() throws Exception {
		assertThat(compileAndRun(
				"(print (subsetp '(1 2) '(1 2 3))) (print (subsetp '(1 2) '(2 3 4))) (print (subsetp nil '(1 2))) "
						+ "(print (subsetp '(\"a\" \"z\") '(\"a\" \"b\") :test #'string=)) "
						+ "(print (subsetp '((1 x)) '((1 a) (2 b)) :key #'car)) "
						+ "(print (funcall #'subsetp '(1 2) '(1 2 3)))"))
			.isEqualTo("T\nNIL\nT\nNIL\nT\nT");
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
	void compileAndRunLogtest() throws Exception {
		assertThat(compileAndRun(
				"(print (logtest 1 2)) (print (logtest 1 3)) (print (logtest -1 5)) (print (logtest (expt 2 100) (expt 2 100))) (print (funcall #'logtest 1 3))"))
			.isEqualTo("NIL\nT\nT\nT\nT");
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
	void compileAndRunReturnInAHandlerBindHandlerExitsTheLexicalNilBlock() throws Exception {
		// rove's SIGNALS shape: the handler's plain (return c) names the (block nil ...)
		// that LEXICALLY encloses it, whatever iteration form -- each of which
		// establishes an implicit nil block of its own -- the SIGNALLING function is
		// running. The lexical answer was already this backend's; the interpreter's
		// dynamic lookup is what moved to match it.
		assertThat(compileAndRun("""
				(define-condition my-error (error) ())
				(defun raise () (error 'my-error))
				(defun sig-nil (thunk)
				  (block nil
				    (handler-bind ((condition (lambda (c) (return c))))
				      (funcall thunk)
				      nil)))
				(print (type-of (sig-nil (lambda () (raise)))))
				(print (type-of (sig-nil (lambda () (loop :for i :from 1 :to 3 :collect (raise))))))
				(print (type-of (sig-nil (lambda () (dolist (x (list 1 2)) (raise))))))
				(print (type-of (sig-nil (lambda () (dotimes (i 2) (raise))))))
				""")).isEqualTo("MY-ERROR\nMY-ERROR\nMY-ERROR\nMY-ERROR");
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
	void compileAndRunNestedNonLocalExitInACleanupDoesNotLoseTheOuterOne() throws Exception {
		// An unwind-protect cleanup that itself completes a non-local exit runs WHILE the
		// outer exit is still travelling, so the two share the exit channel. The inner
		// one must not consume the outer one's entry: after the cleanup returns, the
		// outer exit still has to reach its own block, and it must not be intercepted as
		// a synthesized condition by an intervening handler-case/handler-bind.
		assertThat(compileAndRun("""
				(defun run-protected (thunk cleanup)
				  (unwind-protect (funcall thunk) (funcall cleanup)))
				(defun inner-block-exit ()
				  (block in (mapcar (lambda (x) (return-from in x)) '(:inner))))
				(defun catch-throw-cleanup () (catch 'tag (throw 'tag :cleaned)))
				(defun probe (cleanup)
				  (block done
				    (run-protected (lambda () (return-from done :from-inner)) cleanup)))
				(print (probe #'catch-throw-cleanup))
				(print (probe #'inner-block-exit))
				(print (handler-case (probe #'catch-throw-cleanup) (error (e) :swallowed)))
				(print (block done
				         (handler-bind ((error (lambda (e) e)))
				           (run-protected (lambda () (return-from done :past-guard))
				                          #'catch-throw-cleanup))))
				""")).isEqualTo(":FROM-INNER\n:FROM-INNER\n:FROM-INNER\n:PAST-GUARD");
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
	void compileAndRunSequenceAndSetExtensions() throws Exception {
		// The four prelude defuns of the sequence/set family: same one definition the
		// interpreter lazy-loads, spliced by LispPreludeLibrary.process here.
		assertThat(compileAndRun("""
				(print (tree-equal '(1 (2 3)) '(1 (2 3))))
				(print (tree-equal '(1 (2 3)) '(1 (2 4))))
				(print (tree-equal '(1 (2)) '(1 2)))
				(print (tree-equal '("a" ("b")) '("A" ("B")) :test #'string-equal))
				(print (tree-equal '(1 2) '(1 2) :test-not #'eql))
				(print (count-if-not #'evenp '(1 2 3 4 5)))
				(print (count-if-not #'evenp #(1 2 3 4 5)))
				(print (count-if-not #'alpha-char-p "ab1c2"))
				(print (count-if-not #'evenp '(1 2 3 4 5) :start 1 :end 4))
				(print (count-if-not #'oddp '((1) (2) (3)) :key #'car))
				(print (set-exclusive-or '(1 2 3) '(2 3 4)))
				(print (set-exclusive-or '("a" "b") '("B" "c") :test #'string-equal))
				(print (set-exclusive-or '((1 a) (2 b)) '((2 x)) :key #'car))
				(print (merge 'list (list 1 3 5) (list 2 4 6) #'<))
				(print (merge 'vector (vector 1 3) (vector 2 4) #'<))
				(print (merge 'string "ac" "bd" #'char<))
				(print (merge 'list (list '(1 a)) (list '(1 b) '(2 c)) #'< :key #'car))
				(print (funcall #'tree-equal '(1 2) '(1 2)))
				""")).isEqualTo("""
				T
				NIL
				NIL
				T
				NIL
				3
				3
				2
				1
				1
				(1 4)
				("a" "c")
				((1 A))
				(1 2 3 4 5 6)
				#(1 2 3 4)
				"abcd"
				((1 A) (1 B) (2 C))
				T""");
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
	void compileAndRunCtypecase() throws Exception {
		assertThat(compileAndRun(
				"(print (ctypecase 42 (string \"s\") (integer \"i\"))) (print (ctypecase \"x\" (string \"s\") (integer \"i\")))"))
			.isEqualTo("\"i\"\n\"s\"");
		assertThatThrownBy(() -> compileAndRun("(print (ctypecase 'sym (string \"s\") (integer \"i\")))"))
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
	void compileAndRunCtypecaseInsideACapturingLambda() throws Exception {
		// The ctypecase twin of the etypecase shape above: the free/captured-variable
		// analysis must read the clause HEADS as type specifiers, not variable
		// references.
		assertThat(compileAndRun("""
				(defparameter *mw*
				  (lambda (app &key (output '*error-output*) result-on-error)
				    (check-type output (or symbol stream pathname string))
				    (flet ((classify ()
				            (ctypecase output
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
	void compileAndRunSyntacticMvProducerTailPublishesThroughAFunctionReturn() throws Exception {
		// A syntactic producer (gethash / floor family / find-symbol / intern /
		// array-displacement) in a defun's tail publishes through %mv-spill, so the
		// secondary value survives the function return -- including a defmethod, the
		// shape that found this (cl-mustache's context-get IS a gethash). Shared
		// verbatim with LispEvaluatorTest / WasmLispCompilerIntegrationTest and the
		// mv-producer-function-return ci-spec case.
		assertThat(compileAndRun("""
				(defun f-gethash (h) (gethash "K" h))
				(defun f-floor (a b) (floor a b))
				(defun f-disp (a) (array-displacement a))
				(defmethod ctx-get ((key string) (context hash-table))
				  (gethash (string-upcase key) context))
				(defun f-cond (h k) (cond (k (gethash "K" h)) (t nil)))
				(defun my-user-fn (x) x)
				(defun f-find (n) (find-symbol n))
				(defun f-intern (n) (intern n))
				(defun f-nontail (h) (let ((v (gethash "K" h))) v))
				(setq mv427-tbl (make-hash-table :test 'equal))
				(setf (gethash "K" mv427-tbl) "V")
				(print (multiple-value-list (f-gethash mv427-tbl)))
				(print (multiple-value-list (f-gethash (make-hash-table))))
				(print (multiple-value-list (f-floor 7 2)))
				(print (multiple-value-list (f-find "MY-USER-FN")))
				(print (multiple-value-list (f-intern "MY-USER-FN")))
				(print (multiple-value-list (f-disp (make-array 3))))
				(print (multiple-value-list (ctx-get "k" mv427-tbl)))
				(print (multiple-value-list (f-cond mv427-tbl t)))
				(print (multiple-value-list (f-cond mv427-tbl nil)))
				(multiple-value-bind (v f) (ctx-get "k" mv427-tbl) (print (list v f)))
				(print (multiple-value-list (f-nontail mv427-tbl)))
				""")).isEqualTo("(\"V\" T)\n(NIL NIL)\n(3 1)\n(MY-USER-FN :INTERNAL)\n(MY-USER-FN :INTERNAL)\n(NIL 0)\n"
				+ "(\"V\" T)\n(\"V\" T)\n(NIL)\n(\"V\" T)\n(\"V\")");
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
			.isEqualTo("8.98846567431158e307");
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
		// pathnamep answers T only for a pathname VALUE -- a string is NOT
		// one -- and it agrees with (typep x 'pathname) as CL requires.
		assertThat(compileAndRun("(print (file-position t)) (print (file-length t)) (print (pathnamep \"/tmp/x\"))"
				+ " (print (pathnamep #P\"/tmp/x\")) (print (stream-element-type t))"))
			.isEqualTo("NIL\nNIL\nNIL\nT\nCHARACTER");
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
	void compileAndRunPrintObjectForANestedObject() throws Exception {
		// The method decides the text wherever the instance sits, and the walk that makes
		// that true reproduces the raw renderer exactly for everything it does not route.
		assertThat(compileAndRun("""
				(defclass jpn-c () ((x :initarg :x)))
				(defmethod print-object ((o jpn-c) s) (format s "#<C custom>"))
				(let ((i (make-instance 'jpn-c :x 1)))
				  (print i)
				  (print (list i))
				  (format t "~S~%" (list i))
				  (print (vector i))
				  (print (list 1 "s" (list i) 2))
				  (print (cons 1 i))
				  (print '(1 2 . 3))
				  (princ (list 1 "s" 'sym))
				  (terpri))
				""")).isEqualTo("#<C custom>\n(#<C custom>)\n(#<C custom>)\n#(#<C custom>)\n(1 \"s\" (#<C custom>) 2)\n"
				+ "(1 . #<C custom>)\n(1 2 . 3)\n(1 s SYM)");
	}

	@Test
	void compileAndRunSlotBoundpAndSlotMakunbound() throws Exception {
		// Real unboundness: a slot written with no :initform starts UNBOUND,
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
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
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
					"""))))).isEqualTo("\"HELLO!\"\nstill-works");
	}

	@Test
	void compileAndRunGrayOutputProtocolWidening() throws Exception {
		// the line-oriented and print-family operators reach a Gray
		// instance on the compile path too. A class defining ONLY stream-write-char
		// (rove's indent-stream shape) answers all of them, and its column protocol
		// is what fresh-line consults.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gw-col (rontolisp:fundamental-character-output-stream)
					  ((acc :initform "") (col :initform 0)))
					(defmethod rontolisp:stream-write-char ((s gw-col) c)
					  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
					  (setf (slot-value s 'col) (if (char= c #\\Newline) 0 (+ (slot-value s 'col) 1)))
					  c)
					(defmethod rontolisp:stream-line-column ((s gw-col)) (slot-value s 'col))
					(let ((s (make-instance 'gw-col)))
					  (write-char #\\a s)
					  (write-string "bc" s)
					  (princ "-" s)
					  (prin1 :k s)
					  (fresh-line s)
					  (fresh-line s)
					  (terpri s)
					  (write-line "l" s)
					  (print 7 s)
					  (format s "f~a" 1)
					  (force-output s)
					  (finish-output s)
					  (clear-output s)
					  (print (list (slot-value s 'acc) (close s))))
					(write-line "past" t)
					"""))))).isEqualTo("(\"abc-:K\n\nl\n7\nf1\" T)\npast");
	}

	@Test
	void compileAndRunGrayCloseStandsDownForAProgramThatMethodsClose() throws Exception {
		// close is CL's own generic: a program that defines a method on it owns the
		// operator, so the Gray rewrite leaves every close call site alone and the
		// shadowed-built-in dispatcher answers -- for the Gray class AND for a
		// handle.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gc-s (rontolisp:fundamental-character-output-stream)
					  ((acc :initform "")))
					(defmethod rontolisp:stream-write-char ((s gc-s) c) c)
					(defmethod close ((s gc-s) &key abort)
					  (declare (ignore abort))
					  :by-method)
					(print (close (make-instance 'gc-s)))
					"""))))).isEqualTo(":BY-METHOD");
	}

	@Test
	void grayRewriteLeavesASlotNamedAfterAStreamBuiltinAlone() throws Exception {
		// A defclass/define-condition SLOT SPEC is data whose head is the slot name.
		// chipz's (format :initarg :format :reader invalid-format) slot was read as a
		// (format stream ctrl args...) call and handed back as the format rewrite's
		// let, which defclass then rejected with "expects a keyword slot option, got
		// ((__gray_fmt_stream :INITARG))". The :report body after the slot list is
		// ordinary code and still rewrites -- it prints through this very protocol.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gs-sink (rontolisp:fundamental-character-output-stream)
					  ((format :initarg :format :reader sink-format)
					   (acc :initform "")))
					(defmethod rontolisp:stream-write-string ((s gs-sink) str)
					  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) str))
					  str)
					(define-condition bad-format (error)
					  ((format :initarg :format :reader bad-format-name))
					  (:report (lambda (condition stream)
					             (format stream "Invalid format ~a" (bad-format-name condition)))))
					(let ((s (make-instance 'gs-sink :format :gzip)))
					  (format s "to ~a" (sink-format s))
					  (print (list (sink-format s) (slot-value s 'acc))))
					(print (handler-case (error 'bad-format :format :bzip2) (error (e) (princ-to-string e))))
					"""))))).isEqualTo("(:GZIP \"to GZIP\")\n\"Invalid format BZIP2\"");
	}

	@Test
	void compileAndRunGrayBinaryStreamDispatchAndFilePosition() throws Exception {
		// The read side of the Gray pre-pass: read-byte/write-byte and
		// file-position call sites with a non-literal stream rewrite onto the
		// %gray-*-dispatch helpers; only the helpers a rewrite produced are spliced.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gbs-sink (rontolisp:fundamental-binary-output-stream)
					  ((bytes :initform nil)))
					(defmethod rontolisp:stream-write-byte ((s gbs-sink) byte)
					  (setf (slot-value s 'bytes) (cons byte (slot-value s 'bytes)))
					  byte)
					(defclass gbs-source (rontolisp:fundamental-binary-input-stream)
					  ((items :initarg :items) (pos :initform 0)))
					(defmethod rontolisp:stream-read-byte ((s gbs-source))
					  (let ((items (slot-value s 'items)) (pos (slot-value s 'pos)))
					    (if (>= pos (length items))
					        :eof
					        (progn (setf (slot-value s 'pos) (+ pos 1)) (nth pos items)))))
					(defmethod rontolisp:stream-file-position ((s gbs-source)) (slot-value s 'pos))
					(defmethod (setf rontolisp:stream-file-position) (position (s gbs-source))
					  (setf (slot-value s 'pos) position))
					(let ((out (make-instance 'gbs-sink)))
					  (write-byte 7 out)
					  (write-byte 250 out)
					  (print (reverse (slot-value out 'bytes))))
					(let ((in (make-instance 'gbs-source :items (list 10 20 30))))
					  (print (read-byte in))
					  (print (file-position in))
					  (file-position in 0)
					  (print (read-byte in))
					  (print (read-byte in nil :done))
					  (print (read-byte in nil :done))
					  (print (read-byte in nil :done)))
					"""))))).isEqualTo("(7 250)\n10\n1\n10\n20\n30\n:DONE");
	}

	@Test
	void compileAndRunGrayInputStreamReadLineAndSequenceDefaults() throws Exception {
		// read-char/read-line and the sequence built-ins on a character input
		// class defining only stream-read-char: the default element loops of
		// gray.lisp answer read-line (nil at EOF, the lite default) and
		// read-sequence, exactly like the interpreter.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gcs-source (rontolisp:fundamental-character-input-stream)
					  ((text :initarg :text) (pos :initform 0)))
					(defmethod rontolisp:stream-read-char ((s gcs-source))
					  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
					    (if (>= pos (length text))
					        :eof
					        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
					(let ((in (make-instance 'gcs-source :text (format nil "ab~%cd")))
					      (buf (make-string 2)))
					  (print (read-char in))
					  (print (read-line in))
					  (print (read-line in))
					  (print (read-line in))
					  (print (read-line in nil :end))
					  (setf (slot-value in 'pos) 0)
					  (print (read-sequence buf in))
					  (print buf))
					"""))))).isEqualTo("#\\a\n\"b\"\n\"cd\"\nNIL\n:END\n2\n\"ab\"");
	}

	@Test
	void compileAndRunGrayInputStreamPeekUnreadAndStreamQueries() throws Exception {
		// The rest of the input protocol on the compile path: peek-char (all three
		// peek-type forms, looped inside the dispatch helper), unread-char through
		// the protocol's pushback, read-char-no-hang, open-stream-p and
		// stream-element-type (character, octets for a binary base class, and
		// character again for a BIVALENT class subclassing both). Same answers as
		// the interpreter, character by character.
		assertThat(compileAndRun(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gin-source (rontolisp:fundamental-character-input-stream)
					  ((text :initarg :text) (pos :initform 0)))
					(defmethod rontolisp:stream-read-char ((s gin-source))
					  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
					    (if (>= pos (length text))
					        :eof
					        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
					(defclass gin-bytes (rontolisp:fundamental-binary-input-stream) ())
					(defclass gin-both (rontolisp:fundamental-binary-input-stream
					                    rontolisp:fundamental-character-input-stream)
					  ())
					(let ((in (make-instance 'gin-source :text (format nil "ab~%  cd"))))
					  (print (peek-char nil in))
					  (print (read-char in))
					  (print (unread-char #\\a in))
					  (print (read-char in))
					  (print (read-char-no-hang in))
					  (print (read-line in))
					  (print (peek-char t in))
					  (print (peek-char #\\d in))
					  (print (read-line in))
					  (print (peek-char nil in nil :done))
					  (print (open-stream-p in))
					  (print (stream-element-type in))
					  (print (stream-element-type (make-instance 'gin-bytes)))
					  (print (stream-element-type (make-instance 'gin-both))))
					"""))))).isEqualTo(
					"#\\a\n#\\a\nNIL\n#\\a\n#\\b\n\"\"\n#\\c\n#\\d\n\"d\"\n:DONE\nT\nCHARACTER\n(UNSIGNED-BYTE 8)\nCHARACTER");
	}

	@Test
	void compileAndRunGrayStreamDirectionPredicates() throws Exception {
		// input-stream-p / output-stream-p reach a Gray instance on the compile path
		// too, through the same typep-against-the-base-class helpers the interpreter
		// wraps. A stream HANDLE keeps the bidirectional-lite answer.
		assertThat(compileAndRunGray("""
				(defclass gdp-in (rontolisp:fundamental-character-input-stream) ())
				(defclass gdp-out (rontolisp:fundamental-character-output-stream) ())
				(defmethod rontolisp:stream-read-char ((s gdp-in)) :eof)
				(defmethod rontolisp:stream-write-string ((s gdp-out) str) str)
				(let ((in (make-instance 'gdp-in)) (out (make-instance 'gdp-out))
				      (handle (make-string-input-stream "z")))
				  (print (list (input-stream-p in) (output-stream-p in)))
				  (print (list (input-stream-p out) (output-stream-p out)))
				  (print (list (input-stream-p handle) (output-stream-p handle)))
				  (print (list (input-stream-p 3) (output-stream-p 3))))
				""")).isEqualTo("""
				(T NIL)
				(NIL T)
				(T T)
				(NIL NIL)""");
	}

	@Test
	void compileAndRunGrayStreamIsAStream() throws Exception {
		// streamp / (typep x 'stream) answer t for a Gray instance on the compile path
		// too: the lowering gains an instance arm over the registry's
		// fundamental-stream descendants, so an etypecase that splits "file descriptor"
		// from "Lisp stream" picks the same arm the interpreter picks.
		assertThat(compileAndRunGray("""
				(defclass gsp-out (rontolisp:fundamental-character-output-stream) ())
				(defclass gsp-in (rontolisp:fundamental-character-input-stream) ())
				(defclass gsp-other () ())
				(defmethod rontolisp:stream-write-string ((s gsp-out) str) str)
				(defmethod rontolisp:stream-read-char ((s gsp-in)) :eof)
				(defun gsp-typep (x ty) (typep x ty))
				(let ((out (make-instance 'gsp-out)) (in (make-instance 'gsp-in))
				      (other (make-instance 'gsp-other)))
				  (print (list (streamp out) (streamp in) (streamp other)))
				  (print (list (typep out 'stream) (typep in 'stream) (typep other 'stream)))
				  (print (mapcar #'streamp (list out other 3 t nil)))
				  (print (etypecase out (integer :fd) (stream :lisp-stream)))
				  (print (list (gsp-typep out 'stream) (gsp-typep other 'stream) (gsp-typep 3 'stream))))
				""")).isEqualTo("""
				(T T NIL)
				(T T NIL)
				(T NIL NIL T NIL)
				:LISP-STREAM
				(T NIL NIL)""");
	}

	@Test
	void compileAndRunUnreadCharOnAStreamHandleRoundTrips() throws Exception {
		// The handle-side pushback of unread-char is ordinary Lisp
		// (unread-char.lisp), spliced and wired to the call sites by the
		// eval/UnreadCharLibrary pre-pass the CLI pipeline runs -- so read-char,
		// peek-char and read-line all drain the same one-slot cell, and a second
		// unread with the cell still full signals.
		assertThat(compileAndRunUnread("""
				(let ((s (make-string-input-stream (format nil "ab~%cd"))))
				  (print (read-char s))
				  (print (unread-char #\\a s))
				  (print (peek-char nil s))
				  (print (read-char s))
				  (print (read-line s))
				  (print (read-line s)))
				(print (handler-case
				           (let ((s (make-string-input-stream "xy")))
				             (read-char s)
				             (unread-char #\\x s)
				             (unread-char #\\x s))
				         (error (e) (princ-to-string e))))
				""")).isEqualTo("""
				#\\a
				NIL
				#\\a
				#\\a
				"b"
				"cd"
				"UNREAD-CHAR without an intervening READ-CHAR\"""");
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
	void compileAndRunReadTimeEvalGeneratedDefconstants() throws Exception {
		// fast-http's multipart-parser idiom: #. generates the state defconstants via
		// a backquoted eval-when whose datum value is CODE, evaluated in place.
		assertThat(compileAndRun(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllWithReadEvalMarkers("""
				#.`(eval-when (:compile-toplevel :load-toplevel :execute)
				     ,@(loop for i from 0
				             for state in '(re-state-alpha re-state-beta re-state-gamma)
				             collect `(defconstant ,(intern (format nil "+~A+" state)) ,i)))
				(print +re-state-beta+)
				(print +re-state-gamma+)
				""", am.ik.rontolisp.reader.Features.JVM)))).isEqualTo("1\n2");
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
	void compileAndRunUnwindProtectKeepsTheProtectedFormsValues() throws Exception {
		// A cleanup runs for effect: its values -- and its value COUNT -- are discarded,
		// so the whole form answers the protected form's values, all of them
		// (.kb/multiple-values.md). Cleanup shapes x exit shapes; the same matrix the
		// interpreter and the wasm backends pin, and the unwind-protect-values ci-spec
		// case runs on all four.
		assertThat(compileAndRun("""
				(setq uwp-log nil)
				(defun uwp-zero () (values))
				(defun uwp-one () (values 7))
				(defun uwp-two () (values 7 8))
				(defun uwp-release () (setq uwp-log (cons 'released uwp-log)) (values))
				(defun uwp-compute () (values 1 2 3))
				(defun uwp-nil () (unwind-protect (values 1 2 3) nil))
				(defun uwp-v0 () (unwind-protect (values 1 2 3) (uwp-zero)))
				(defun uwp-v1 () (unwind-protect (values 1 2 3) (uwp-one)))
				(defun uwp-v2 () (unwind-protect (values 1 2 3) (values 7 8)))
				(defun uwp-call () (unwind-protect (uwp-compute) (uwp-release)))
				(defun uwp-nested ()
				  (unwind-protect (values 1 2 3) (unwind-protect (uwp-two) (uwp-zero))))
				(defun uwp-return ()
				  (block b (unwind-protect (return-from b (values 1 2 3)) (uwp-two))))
				(defun uwp-return-call ()
				  (block b (unwind-protect (return-from b (uwp-compute)) (uwp-release))))
				(defun uwp-go ()
				  (let ((r 0))
				    (block b (tagbody (unwind-protect (go done) (uwp-two)) done))
				    (values r 2 3)))
				(defun uwp-signal ()
				  (handler-case (unwind-protect (error "boom") (uwp-release))
				    (error (e) (values 1 2 3))))
				(defun uwp-signal-plain ()
				  (handler-case (unwind-protect (error "boom") (uwp-two)) (error (e) 'caught)))
				(print (multiple-value-list (uwp-nil)))
				(print (multiple-value-list (uwp-v0)))
				(print (multiple-value-list (uwp-v1)))
				(print (multiple-value-list (uwp-v2)))
				(print (multiple-value-list (uwp-call)))
				(print (multiple-value-list (uwp-nested)))
				(print (multiple-value-list (uwp-return)))
				(print (multiple-value-list (uwp-return-call)))
				(print (multiple-value-list (uwp-go)))
				(print (multiple-value-list (uwp-signal)))
				(print (multiple-value-list (uwp-signal-plain)))
				(print (multiple-value-list (unwind-protect (values 1 2 3) (uwp-zero))))
				(print uwp-log)
				""")).isEqualTo("""
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(0 2 3)
				(1 2 3)
				(CAUGHT)
				(1 2 3)
				(RELEASED RELEASED RELEASED)""");
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
	void compileAndRunSortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend() throws Exception {
		// The site calls the shared %sort-runtime merge sort (.kb/sort.md): 50,000
		// elements are instant where the selection sort this replaced took minutes, and
		// elements the predicate calls equal come out in input order -- the same
		// permutation the interpreter and both wasm backends answer.
		assertThat(compileAndRun("""
				(let ((data nil) (s 42))
				  (dotimes (i 50000)
				    (setq s (mod (+ (* s 1103515245) 12345) 2147483648))
				    (setq data (cons s data)))
				  (let ((sorted (sort data #'<)) (ordered t) (n 0))
				    (do ((c sorted (cdr c))) ((null (cdr c)) nil)
				      (if (> (car c) (car (cdr c))) (setq ordered nil)))
				    (do ((c sorted (cdr c))) ((null c) nil) (setq n (+ n 1)))
				    (print (list ordered n))))
				(print (sort (list (cons 1 'a) (cons 1 'b) (cons 0 'c) (cons 1 'd) (cons 0 'e))
				             (lambda (x y) (< (car x) (car y)))))
				""")).isEqualTo("(T 50000)\n((0 . C) (0 . E) (1 . A) (1 . B) (1 . D))");
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
	void compileAndRunApplyAlignedVariadicTarget() throws Exception {
		// The aligned fast path: a literal #'f target whose required parameters are all
		// covered by the leading arguments takes them directly -- the rest parameter is
		// the tail verbatim (same list object) or the excess consed onto it. Fewer
		// leading arguments than required parameters keeps the build-then-unpack path.
		assertThat(compileAndRun("(defun g (a b &rest r) (list a b r)) (print (apply #'g 1 2 '(3 4)))"))
			.isEqualTo("(1 2 (3 4))");
		assertThat(compileAndRun("(defun g (a b &rest r) (list a b r)) (print (apply #'g 1 2 3 '(4)))"))
			.isEqualTo("(1 2 (3 4))");
		assertThat(compileAndRun("(defun g (a b &rest r) (list a b r)) (print (apply #'g 1 '(2 3)))"))
			.isEqualTo("(1 2 (3))");
		assertThat(compileAndRun(
				"(defun tailof (a &rest r) r) (defvar *l* (list 1 2)) (print (eq *l* (apply #'tailof 0 *l*)))"))
			.isEqualTo("T");
		assertThat(compileAndRun(
				"(defvar *o* nil) (defun n (x) (setq *o* (cons x *o*)) x)" + " (defun g (a &rest r) (cons a r))"
						+ " (print (apply #'g (n 1) (n 2) (list (n 3)))) (print (reverse *o*))"))
			.isEqualTo("(1 2 3)\n(1 2 3)");
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
		// The value is the package KEYWORD find-package answers, so the two are eq.
		assertThat(compileAndRun("(print *package*)")).isEqualTo(":CL-USER");
		assertThat(compileAndRun("(print (eq *package* (find-package \"CL-USER\")))")).isEqualTo("T");
	}

	@Test
	void compileAndRunPackageVarIsReadWhenTheFormRuns() throws Exception {
		// CL's *package* is dynamic: a defun reads the package current at CALL time,
		// (in-package P) assigns it, a let of it binds it for the extent and
		// with-standard-io-syntax binds it to CL-USER. The rove shape: a registry keyed
		// on *package* read inside set-test, filled from a test file in its own package
		// (the pre-2026-08-15 fold registered everything under set-test's package).
		assertThat(compileAndRun(
				"""
						(defvar *suites* (make-hash-table :test 'eq))
						(defun package-suite (package) (or (gethash package *suites*) (setf (gethash package *suites*) (list :suite (package-name package)))))
						(defun set-test (name) (push name (cdr (package-suite *package*))) name)
						(defun cur () *package*)
						(defpackage :my-app/tests (:use :cl))
						(in-package :my-app/tests)
						(cl-user::set-test 'test-a)
						(cl-user::set-test 'test-b)
						(print (cl-user::cur))
						(print (let ((*package* (find-package :cl-user))) (cl-user::cur)))
						(print (cl-user::cur))
						(print (with-standard-io-syntax (cl-user::cur)))
						(print (gethash (find-package "MY-APP/TESTS") cl-user::*suites*))
						(print (eq *package* (find-package :my-app/tests)))
						(in-package :cl-user)
						(print (cur))
						(print (package-name *package*))
						"""))
			.isEqualTo("""
					:MY-APP/TESTS
					:CL-USER
					:MY-APP/TESTS
					:CL-USER
					(:SUITE MY-APP/TESTS::TEST-B MY-APP/TESTS::TEST-A "MY-APP/TESTS")
					T
					:CL-USER
					"CL-USER\"""");
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
				""";
		assertThat(compileAndRun(code)).isEqualTo("\"hello, world\"\n(2 4 6)\n42");
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
	void compileAndRunFetchSendsADefaultUserAgent() throws Exception {
		// The emitted _fetch sets the agent EXPLICITLY, overriding the JDK's
		// Java-http-client/<jdk> default, so a compiled program sends the same request as
		// the interpreter and the component
		// (LispEvaluatorTest#fetchSendsADefaultUserAgent).
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/agent", exchange -> {
			java.util.List<String> received = exchange.getRequestHeaders().get("User-Agent");
			byte[] body = String.join("|", (received == null) ? java.util.List.<String>of() : received)
				.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/agent";
			assertThat(compileAndRun("(print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await"
					+ " (rontolisp:fetch \"" + url + "\")) :body))))"))
				.isEqualTo("\"" + am.ik.rontolisp.compiler.FetchResponseShape.defaultUserAgent() + "\"");
			// The caller's own spelling wins, and only the caller's value is sent.
			assertThat(compileAndRun(
					"(print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await" + " (rontolisp:fetch \"" + url
							+ "\" (list :headers (list (cons \"user-agent\" \"custom/1\")))))" + " :body))))"))
				.isEqualTo("\"custom/1\"");
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
	// generated class itself implements RontoHttpServer.Handler (like the
	// tls-connect X509TrustManager trick) so RontoHttpServer.serve can drive it.
	@Test
	void compileHttpHandlerImplementsHandlerInterface() throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		// The injected handle() calls the compiled %http-normalize-response, so this
		// harness must splice http-server.lisp like the CLI (and every other
		// compiler-driving serve harness) does.
		byte[] classBytes = compiler.compile(am.ik.rontolisp.eval.HttpServerLibrary.process(LispReader
			.readAllFromString("(defun h (env) (list 200 nil (list \"ok\"))) (rontolisp:http-handler 'h)"), false));
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			assertThat(am.ik.rontolisp.runtime.RontoHttpServer.Handler.class).isAssignableFrom(clazz);
			assertThat(clazz.getMethod("handle", am.ik.rontolisp.runtime.RontoHttpServer.Request.class)).isNotNull();
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
	void compileAndRunTcpCharacterOpsOnSocket() throws Exception {
		// write-string / write-char / read-char on a socket handle: the
		// _writeString / _readChar stream helpers grow the socket arm the byte and line
		// ops always had. Same program and same answers as
		// LispEvaluatorTest#tcpCharacterOpsOnSocket and the component's
		// componentTcpBinaryBytesAreWireTransparent -- 199 184 is the ONE code point
		// U+01F8 (504), not two.
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (write-string "AÇB" client)
				  (let* ((b1 (read-byte server))
				         (b2 (read-byte server))
				         (b3 (read-byte server))
				         (b4 (read-byte server)))
				    (write-byte 199 client)
				    (write-byte 184 client)
				    (let ((multi (char-code (read-char server))))
				      (write-char #\\Z client)
				      (write-string "xyz" client :start 1 :end 2)
				      (let* ((ch (char-code (read-char server)))
				             (bounded (char-code (read-char server))))
				        (close client)
				        (close server)
				        (close listener)
				        (print (list b1 b2 b3 b4 multi ch bounded))))))
				""")).isEqualTo("(65 195 135 66 504 90 121)");
	}

	@Test
	void compileAndRunTcpReadCharAtPeerCloseHonoursTheEofArguments() throws Exception {
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (let ((c (read-char server nil :eof)))
				    (close server)
				    (close listener)
				    (print c)))
				""")).isEqualTo(":EOF");
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (print (handler-case (read-char server)
				           (end-of-file () :signalled))))
				""")).isEqualTo(":SIGNALLED");
		// The bare read-byte carries the same eof-error-p t default, and the component
		// answers the same (componentTcpBareReadCharSignalsAtPeerClose).
		assertThat(compileAndRun("""
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (print (handler-case (read-byte server)
				           (end-of-file () :signalled))))
				""")).isEqualTo(":SIGNALLED");
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
	void compileAndRunUsocketSocketOptionReceiveTimeoutIsARealReadDeadline() throws Exception {
		// The interpreter twin is usocketSocketOptionReceiveTimeoutIsARealReadDeadline:
		// the setf place rides the emitted _tcpSetTimeout (Socket.setSoTimeout), the
		// deadline FIRES on a silent peer as a catchable error, nil clears it, and an
		// unsupported option signals loudly.
		assertThat(compileAndRunUsocket("""
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port)))
				  (setf (usocket:socket-option client :receive-timeout) 0.2)
				  (print (usocket:socket-option client :receive-timeout))
				  (print (handler-case (progn (read-line client) :read)
				           (error (e) :timed-out)))
				  (setf (usocket:socket-option client :receive-timeout) nil)
				  (print (usocket:socket-option client :receive-timeout))
				  (print (handler-case (setf (usocket:socket-option client :tcp-nodelay) t)
				           (error (e) :unsupported)))
				  (usocket:socket-close client)
				  (usocket:socket-close listener))
				""")).isEqualTo("0.2\n:TIMED-OUT\nNIL\n:UNSUPPORTED");
	}

	@Test
	void compileAndRunUsocketWaitForInputPollsThroughListen() throws Exception {
		// The interpreter twin is usocketWaitForInputPollsThroughListen: a real
		// listen-based poll on this backend -- empty polls to the timeout, buffered
		// data comes back ready with time remaining, and without :ready-only the
		// original argument returns.
		assertThat(compileAndRunUsocket("""
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port))
				       (server (usocket:socket-accept listener)))
				  (print (usocket:wait-for-input (list client) :timeout 0 :ready-only t))
				  (write-line "ping" server)
				  (multiple-value-bind (ready remaining)
				      (usocket:wait-for-input (list client) :timeout 5 :ready-only t)
				    (print (equal ready (list client)))
				    (print (if remaining t nil)))
				  (print (eql (usocket:wait-for-input client :timeout 1) client))
				  (print (read-line client))
				  (usocket:socket-close server)
				  (usocket:socket-close client)
				  (usocket:socket-close listener))
				""")).isEqualTo("NIL\nT\nT\nT\n\"ping\"");
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
	void compileAndRunTlsUpgradeEchoRoundTripOnLoopback() throws Exception {
		// _tlsUpgrade wraps an ALREADY-CONNECTED _streams entry (the cl+ssl
		// make-ssl-client-stream shape): plain _tcpConnect first, then the upgrade
		// handshakes over that connection and stores the SSLSocket as a NEW entry.
		am.ik.rontolisp.TlsTestSupport.withTrustStore(() -> {
			try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
				Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
				assertThat(compileAndRun("""
						(let* ((sock (rontolisp:tcp-connect "127.0.0.1" %d))
						       (tls (rontolisp:tls-upgrade sock "127.0.0.1")))
						  (write-line "hello over upgraded tls" tls)
						  (let ((reply (read-line tls)))
						    (close tls)
						    (print reply)))
						""".formatted(server.getLocalPort()))).isEqualTo("\"hello over upgraded tls\"");
				echo.join();
			}
		});
	}

	@Test
	void compileAndRunTlsUpgradeInsecureSkipsCertificateVerification() throws Exception {
		// No trust store here; :insecure t makes _tlsUpgrade install the generated
		// class as the trust-all X509TrustManager (the _tlsConnect mechanism).
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			assertThat(compileAndRun("""
					(let* ((sock (rontolisp:tcp-connect "127.0.0.1" %d))
					       (tls (rontolisp:tls-upgrade sock "127.0.0.1" :insecure t)))
					  (write-line "hello insecurely upgraded" tls)
					  (let ((reply (read-line tls)))
					    (close tls)
					    (print reply)))
					""".formatted(server.getLocalPort()))).isEqualTo("\"hello insecurely upgraded\"");
			echo.join();
		}
	}

	@Test
	void compileTlsUpgradeRejectsWrongArgCount() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tls-upgrade 99)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("TLS-UPGRADE expects 2 or 4 arguments");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:tls-upgrade 99 \"h\" :verify t)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects :insecure");
	}

	@Test
	void compileAndRunClSslShimHttpsRequestAgainstLocalTlsServer() throws Exception {
		// The cl+ssl shim on the compile path, the dexador shape: the shim is
		// spliced by LoadInliner (like the closer-mop test above), usocket by the
		// UsocketLibrary pre-pass, and make-ssl-client-stream upgrades the
		// connected stream over _tlsUpgrade.
		am.ik.rontolisp.TlsTestSupport.withTrustStore(() -> {
			try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
				Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
				List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
						(asdf:load-system "cl+ssl")
						(cl+ssl:with-global-context ((cl+ssl:make-context) :auto-free-p t)
						  (let* ((sock (usocket:socket-connect "127.0.0.1" %d))
						         (tls (cl+ssl:make-ssl-client-stream (usocket:socket-stream sock)
						                                             :hostname "127.0.0.1"
						                                             :verify (cl+ssl:ssl-check-verify-p))))
						    (write-line "GET / HTTP/1.1" tls)
						    (let ((reply (read-line tls)))
						      (close tls)
						      (print reply))))
						""".formatted(server.getLocalPort())), path -> {
					throw new java.io.FileNotFoundException(path);
				});
				// The CLI pre-pass order: UserMacroExpander first (the shim's
				// with-global-context is a defmacro), then prelude, then usocket.
				assertThat(compileAndRun(
						am.ik.rontolisp.eval.UsocketLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
							.process(am.ik.rontolisp.eval.UserMacroExpander.expand(program)))))
					.isEqualTo("\"GET / HTTP/1.1\"");
				echo.join();
			}
		});
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
			byte[] classBytes = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
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

	// A character index costs the same wherever it lands: scanning ONE long string costs
	// what scanning the SAME characters in short chunks costs. Before, the index was
	// turned into a UTF-16 code-unit offset with String.offsetByCodePoints(1, i), which
	// walks -- so a left-to-right scan was quadratic and the whole-string half below was
	// 64x the chunked one (893 ms vs 14 ms over 131,072 characters). The comparison is
	// against the chunked half rather than a wall-clock constant so the bound does not
	// depend on the machine.
	@Test
	void compileACharacterIndexDoesNotWalkFromTheStartOfTheString() throws Exception {
		assertScanIsFlat(compileAndRun(SCAN_PROGRAM.formatted("\"0123456789abcdef\"")), "ASCII");
	}

	// Same, for a string the JVM cannot store as LATIN1: its content decides the storage
	// (Hiragana is two bytes a character), and the cheap "no surrogates" probe is only
	// O(1) on the LATIN1 half. The verdict per string is what carries the non-LATIN1 one.
	@Test
	void compileACharacterIndexDoesNotWalkForANonLatin1String() throws Exception {
		assertScanIsFlat(compileAndRun(SCAN_PROGRAM.formatted("\"あいうえおかきくけこさしすせそた\"")), "Hiragana");
	}

	// Builds a 1,024-character string and the 131,072-character string that IS that
	// string 128 times over, scans each, and prints the two elapsed times in ms. Both
	// grow from the same literal rather than the shorter (defvar *long* *short*), which
	// the compile paths get wrong.
	private static final String SCAN_PROGRAM = """
			(defun scan-sum (s)
			  (let ((total 0))
			    (dotimes (i (length s))
			      (setq total (+ total (char-code (char s i)))))
			    total))
			(defvar *short* %1$s)
			(dotimes (i 6) (setq *short* (concatenate 'string *short* *short*)))
			(defvar *long* %1$s)
			(dotimes (i 13) (setq *long* (concatenate 'string *long* *long*)))
			(scan-sum "warm")
			(defvar *t0* (get-internal-real-time))
			(defvar *whole* (scan-sum *long*))
			(defvar *t1* (get-internal-real-time))
			(defvar *chunked* 0)
			(dotimes (k 128) (setq *chunked* (+ *chunked* (scan-sum *short*))))
			(defvar *t2* (get-internal-real-time))
			(print (= *whole* *chunked*))
			(princ (- *t1* *t0*)) (terpri)
			(princ (- *t2* *t1*)) (terpri)
			""";

	private static void assertScanIsFlat(String output, String label) {
		String[] lines = output.split("\n");
		assertThat(lines[0]).as("the two halves must scan the same characters").isEqualTo("T");
		long whole = Long.parseLong(lines[1].trim());
		long chunked = Long.parseLong(lines[2].trim());
		assertThat(whole)
			.as("%s: scanning 131,072 characters as one string (%d ms) against the same "
					+ "characters in 1,024-character chunks (%d ms)", label, whole, chunked)
			.isLessThanOrEqualTo(500 + 6 * chunked);
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
	void compileHashTablePrintsAsUnreadableTagWithCount() throws Exception {
		// The compiled table is a host map, so without a printer arm of its own it used
		// to
		// reach Object.toString and print Java's container syntax -- including an
		// IDENTITY
		// HASH, i.e. different text on two runs (todo 430,
		// .kb/emitted-output-determinism.md).
		// It prints the interpreter's unreadable tag instead, nested positions included,
		// with the LIVE ENTRY COUNT read from the same size() call _hashCount makes.
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(princ *h*)
				(terpri)
				(prin1 *h*)
				(terpri)
				(format t "~a ~s~%" *h* *h*)
				(print (list *h*))
				(remhash "a" *h*)
				(print *h*)
				""")).isEqualTo("""
				#<HASH-TABLE :TEST EQUAL :COUNT 2>
				#<HASH-TABLE :TEST EQUAL :COUNT 2>
				#<HASH-TABLE :TEST EQUAL :COUNT 2> #<HASH-TABLE :TEST EQUAL :COUNT 2>
				(#<HASH-TABLE :TEST EQUAL :COUNT 2>)
				#<HASH-TABLE :TEST EQUAL :COUNT 1>""");
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
	// A key is placed by a DEPTH-CAPPED structural hash and decided by equal, not by
	// the key's printed text: a cyclic key stores and retrieves (printing one never
	// terminated), two equal keys deeper than the cap are still ONE key, and a general
	// vector key is compared by identity -- which is what equal does to a vector.
	void compileHashTableCyclicDeepAndVectorKeys() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (make-hash-table)) (k (list 1 2)))
				  (setf (cdr (last k)) k)
				  (setf (gethash k h) :cyclic)
				  (print (list (gethash k h) (hash-table-count h))))
				(let ((h (make-hash-table)) (deep (loop for i from 1 to 200 collect i)))
				  (setf (gethash deep h) :deep)
				  (print (list (gethash (loop for i from 1 to 200 collect i) h)
				               (gethash (loop for i from 1 to 199 collect i) h))))
				(let ((h (make-hash-table)) (v (vector 1 2)))
				  (setf (gethash v h) :self)
				  (print (list (gethash v h) (gethash (vector 1 2) h))))
				""")).isEqualTo("(:CYCLIC 1)\n(:DEEP NIL)\n(:SELF NIL)");
	}

	@Test
	// The depth cap bounds the hash's HEIGHT and nothing about its SIZE, which a WORK
	// budget does. Both keys here have SHARED substructure: a DAG of 60 conses holds 60
	// cells and 2^60 root-to-leaf paths, and an instance that knows its parent is worse
	// still. An un-budgeted hash could not place either one inside this suite's lifetime,
	// so a regression shows up as a HANG rather than as a slow number -- the only way to
	// pin "terminates SOON" without a flaky wall-clock assertion.
	void compileHashTableSharedGraphKeysArePlacedInBoundedWork() throws Exception {
		assertThat(compileAndRun("""
				(defun shared-dag (n)
				  (let ((node :leaf))
				    (dotimes (i n node) (setq node (cons node node)))))
				(defclass linked-node ()
				  ((up :initarg :up :initform nil)
				   (down :initform nil :accessor node-down)))
				(defun linked-chain (n)
				  (let ((node (make-instance 'linked-node)))
				    (dotimes (i n node)
				      (let ((next (make-instance 'linked-node :up node)))
				        (setf (node-down node) (cons next (node-down node)))
				        (setq node next)))))
				(let ((h (make-hash-table :test 'equal))
				      (dag (shared-dag 60))
				      (node (linked-chain 8)))
				  (setf (gethash dag h) :dag)
				  (setf (gethash node h) :node)
				  (print (list (gethash dag h) (gethash node h) (hash-table-count h))))
				(let ((e (make-hash-table :test 'eq)))
				  (print (list (gethash (shared-dag 60) e) (gethash (linked-chain 8) e))))
				(let ((p (make-hash-table :test 'equalp)))
				  (setf (gethash "cs" p) 1)
				  (print (list (gethash "CS" p) (gethash (shared-dag 60) p))))
				""")).isEqualTo("(:DAG :NODE 2)\n(NIL NIL)\n(1 NIL)");
	}

	@Test
	// An equalp table places its keys by the equalp FOLD -- upper case for a string and a
	// character, the exact rational value for a float, element-wise for a cons -- so the
	// four backends agree on which keys are one key (.kb/hash-tables.md). An equal table
	// in the same program keeps placing structurally, and the fold reaches neither.
	void compileEqualpHashTableFoldsItsKeys() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash "CS" h) 1)
				  (print (list (gethash "Cs" h) (gethash "cs" h) (hash-table-count h))))
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash 1 h) :one)
				  (setf (gethash #\\a h) :a)
				  (setf (gethash (list "x" 2) h) :pair)
				  (print (list (gethash 1.0 h) (gethash 2/2 h) (gethash #\\A h)
				               (gethash (list "X" 2.0) h) (hash-table-count h))))
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "CS" h) 1)
				  (print (list (gethash "Cs" h) (hash-table-count h))))
				(let ((h (make-hash-table :test 'equalp)) (acc nil))
				  (setf (gethash "cs" h) 1)
				  (setf (gethash #\\b h) 2)
				  (maphash (lambda (k v) (setq acc (cons (princ-to-string k) acc))) h)
				  (print (sort acc #'string<)))
				""")).isEqualTo("(1 1 1)\n(:ONE :ONE :A :PAIR 3)\n(NIL 1)\n(\"B\" \"CS\")");
	}

	@Test
	// The printed :TEST field and hash-table-test report the test lookup implements, now
	// that a table knows whether it folds.
	void compileEqualpHashTablePrintsAndReportsItsTest() throws Exception {
		assertThat(compileAndRun("""
				(let ((p (make-hash-table :test 'equalp)) (q (make-hash-table :test 'equal)))
				  (setf (gethash "a" p) 1)
				  (print (list (hash-table-test p) (hash-table-test q)))
				  (princ p)
				  (terpri)
				  (princ q))
				"""))
			.isEqualTo("(EQUALP EQUAL)\n#<HASH-TABLE :TEST EQUALP :COUNT 1>\n#<HASH-TABLE :TEST EQUAL :COUNT 0>");
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
		// consumption order used to run the side effects right to left:
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
	void compilePackedGeneralArrayWidensInvisiblyOnANonIntegerStore() throws Exception {
		// A plain (make-array n) starts PACKED (a long[] behind the header); the
		// first non-integer store widens it in place, and an alias created before the
		// widening still sees every element -- including one stored after the
		// integers -- because the ArrayList is the identity.
		assertThat(compileAndRun("""
				(defparameter *a* (make-array 6))
				(defparameter *alias* *a*)
				(dotimes (i 6) (setf (aref *a* i) (* i 10)))
				(setf (aref *a* 3) "mid")
				(setf (aref *a* 5) 'end)
				(print (list (aref *alias* 0) (aref *alias* 3) (aref *alias* 5) (aref *a* 4)))
				""")).isEqualTo("(0 \"mid\" END 40)");
	}

	@Test
	void compilePackedGeneralArrayNilAndSentinelInteger() throws Exception {
		// A fresh element reads nil (the packed sentinel), an explicit nil store
		// reads back nil, and storing the sentinel integer itself (most-negative
		// long) widens rather than aliasing nil.
		assertThat(compileAndRun("""
				(defparameter *a* (make-array 3 :initial-element 1))
				(setf (aref *a* 0) nil)
				(setf (aref *a* 1) -9223372036854775808)
				(print (list (aref *a* 0) (aref *a* 1) (aref *a* 2) (array-element-type *a*)))
				""")).isEqualTo("(NIL -9223372036854775808 1 T)");
	}

	@Test
	void compilePackedGeneralArrayRankNAndDisplacedView() throws Exception {
		// The packed store is flat, so rank-n subscripts and a displaced view over a
		// packed target read the same storage.
		assertThat(compileAndRun("""
				(defparameter *m* (make-array '(2 3) :initial-element 0))
				(setf (aref *m* 1 2) 42)
				(defparameter *row* (make-array 3 :displaced-to *m* :displaced-index-offset 3))
				(print (list (aref *row* 2) (row-major-aref *m* 5) (array-displacement *row*)))
				""")).isEqualTo("(42 42 #2A((0 0 0) (0 0 42)))");
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
		// NOTHING in the source is an array operator, yet the expansion
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
	void aQuotedDatumIsOneSharedConstantAcrossEvaluations() throws Exception {
		// quote is the CONSTANT syntax: every evaluation of one quote site answers the
		// SAME object, like the interpreter and like a real CL (CLHS leaves writes into
		// it undefined; here they reach the shared constant, as they always did on the
		// interpreter). A bare #(...) literal stays a constructor -- the freshness
		// invariant of .kb/array-literals.md is about literals OUTSIDE quote.
		assertThat(compileAndRun("""
				(defun %ql () '(1 2 3))
				(print (eq (%ql) (%ql)))
				(let ((a (%ql))) (setf (car a) 99))
				(print (%ql))
				(let ((f (lambda () '#(7 8)))) (print (eq (funcall f) (funcall f))))
				(defun %qn () '(1 #(2 3)))
				(print (eq (cadr (%qn)) (cadr (%qn))))
				(defun %qd () '#d(1.0 2.0))
				(print (eq (%qd) (%qd)))
				""")).isEqualTo("T\n(99 2 3)\nT\nT\nT");
	}

	@Test
	void aBareInstanceLiteralIsOneSharedConstantAcrossEvaluations() throws Exception {
		// A bare #P"..." / #S(...) in code position is a CONSTANT, not a constructor:
		// the interpreter's self-evaluating LispInstance arm hands the reader's own
		// instance back at every evaluation, and cannot be moved (the same arm carries
		// every live instance spliced back through (quote <value>)), so the site
		// memoizes into the lazy _qd$N field a quoted datum uses (.kb/quoted-data.md).
		assertThat(compileAndRun("""
				(defun %fp () #P"a/b.txt")
				(print (eq (%fp) (%fp)))
				(print (namestring (%fp)))
				(defstruct %pt x y)
				(defun %fs () #S(%pt :x 1 :y 2))
				(print (eq (%fs) (%fs)))
				(print (%pt-x (%fs)))
				""")).isEqualTo("T\n\"a/b.txt\"\nT\n1");
	}

	@Test
	void anArrayFreeProgramReferencesNoArrayRuntimeHelper() {
		// the injected built-in wrappers used to carry an array arm each
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
	void aProgramThatNeverNamesAnArrayOperatorCarriesNoArrayRuntime() throws Exception {
		// fill/coerce/vector/read-sequence/write-sequence/svref/array-rank/
		// array-dimension/array-total-size/array-row-major-index each has an injected
		// wrapper whose body reaches _aset1/_charVecMake/_arrayMake/_aref1/_arrayDims --
		// so every program carried those ten wrappers regardless of its own source, the
		// finished class called methods it never declared, and the post-compile
		// self-check answered that by forcing the array gate on and re-running the whole
		// compile, for a program with no array in it (.todo history: 13,654 B for this
		// exact program before the fix).
		byte[] classBytes = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(print (mapcar (lambda (x) (* x x)) '(1 2 3)))"));
		assertThat(declaredMethodNames(classBytes)).doesNotContain("_aset1", "_aref1", "_arrayMake", "_charVecMake",
				"_arrayDims");
		assertThat(classBytes.length).isLessThan(8_000);
		assertThat(runClass(classBytes)).isEqualTo("(1 4 9)");
	}

	@Test
	void namingOneOfTheArrayWrappersBringsTheArrayRuntimeBack() throws Exception {
		// The wrappers are gated on the reference, not deleted: a program that takes
		// one of the ten as a value still works, and its wrapper's array arm is real.
		byte[] classBytes = new JvmLispCompiler("Test")
			.compile(LispReader.readAllFromString("(print (funcall #'fill (make-array 3 :initial-element 0) 9))"));
		assertThat(declaredMethodNames(classBytes)).contains("_aset1");
		assertThat(runClass(classBytes)).isEqualTo("#(9 9 9)");
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
	void compileDisplacedStringView() throws Exception {
		// Displacing onto a STRING answers a string VIEW, not a bare array view: it is
		// stringp, prints and measures as a string, and aliases the target's characters
		// in both directions -- including a view of a view.
		assertThat(compileAndRun("""
				(defparameter *s* (make-string 6 :initial-element #\\a))
				(dotimes (i 6) (setf (char *s* i) (char "abcdef" i)))
				(defparameter *v* (make-array 3 :element-type 'character :displaced-to *s*
				                                :displaced-index-offset 1))
				(print (list (stringp *v*) (length *v*) *v* (char *v* 0) (subseq *v* 1)))
				(setf (char *v* 0) #\\X)
				(print (list *v* *s*))
				(setf (char *s* 3) #\\Y)
				(print (list *v* *s* (string= *v* "XcY")))
				(multiple-value-bind (tgt off) (array-displacement *v*)
				  (print (list (eq tgt *s*) off)))
				(defparameter *w* (make-array 2 :element-type 'character :displaced-to *v*
				                                :displaced-index-offset 1))
				(setf (char *w* 1) #\\Q)
				(print (list *w* *v* *s*))
				""")).isEqualTo("""
				(T 3 "bcd" #\\b "cd")
				("Xcd" "aXcdef")
				("XcY" "aXcYef" T)
				(T 1)
				("cQ" "XcQ" "aXcQef")""");
	}

	@Test
	void compileDisplacedStringViewOverAnImmutableStringPromotesOnWrite() throws Exception {
		// A string this backend represents as an immutable runtime string (anything but
		// a character vector -- here a copy-seq result) can be VIEWED without a copy,
		// and reads alias it. A write cannot reach it, so the view promotes its target
		// to a character vector once and mutates that: the view is a mutable string
		// from then on, and the original value is untouched -- which is what
		// (setf (char s i) c) on that same string already does. The interpreter, whose
		// strings are all mutable, writes through to the target instead (`.todo/559`).
		assertThat(compileAndRun("""
				(defparameter *s* (copy-seq "abcdef"))
				(defparameter *v* (make-array 3 :element-type 'character :displaced-to *s*
				                                :displaced-index-offset 1))
				(print (list *v* (length *v*) (string= *v* "bcd")))
				(setf (char *v* 0) #\\X)
				(print (list *v* *s*))
				""")).isEqualTo("(\"bcd\" 3 T)\n(\"Xcd\" \"abcdef\")");
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
	void compileExptWithRuntimeFloatOrRatioExponent() throws Exception {
		// hasDoubleLiteral only sees literals: a float or ratio EXPONENT arriving
		// through a variable or a call used to be unboxed as a Long and die
		// (ClassCastException). _pow now dispatches on the run-time
		// exponent: a non-Long takes Math.pow over the float contagion, exactly the
		// interpreter's answers.
		assertThat(compileAndRun("""
				(defun give (x) x)
				(print (expt 4 (give 1/2)))
				(print (expt 2 (give 0.5)))
				(print (expt (give 10000.0) (give 0.75)))
				(print (expt 2 (give 3.0)))
				(print (expt (give -2.0) (give 0.5)))
				(print (expt (give 0.0) (give -1.0)))
				(print (* 1.5 (expt 10 (give 0.0))))
				(print (expt (give 2) (give 10)))
				""")).isEqualTo("2.0\n1.4142135623730951\n1000.0\n8.0\nNaN\nInfinity\n1.5\n1024");
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
				(print (linalg:sum *m* :axis 0))
				(print (linalg:sum *m* :axis -1 :keepdims t))
				(print (linalg:mean *m* :axis 0))
				(print (linalg:amax *m* :axis 1))
				(print (linalg:amin *m* :axis 0 :keepdims t))
				(print (linalg:argmax *m* :axis 1))
				(print (linalg:argmin *m* :axis 0))
				(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) :axis 1))
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
				(print (linalg:equal (linalg:argmax *m* :axis 1) #(2 2)))
				(print (linalg:zeros-like (linalg:ones 2 :element-type 'single-float)))
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
		assertThat(compileAndRunLinalg(
				"""
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
						(print (array-element-type (linalg:transpose (linalg:ones '(2 2 2) :element-type 'single-float) '(2 1 0))))
						(print (array-element-type (linalg:pad (linalg:ones 2 :element-type 'single-float) 1)))
						"""))
			.isEqualTo("(3 2 4)\n#d((1.0 3.0) (2.0 4.0))\nT\n"
					+ "#d((0.0 0.0 0.0 0.0 0.0 0.0) (0.0 0.0 1.0 2.0 0.0 0.0) (0.0 0.0 3.0 4.0 0.0 0.0)"
					+ " (0.0 0.0 0.0 0.0 0.0 0.0))\n#d(0.0 1.0 2.0 0.0)\n"
					+ "#d((0.0 1.0 4.0 5.0) (2.0 3.0 6.0 7.0) (8.0 9.0 12.0 13.0) (10.0 11.0 14.0 15.0))\nT\n"
					+ "#d((((1.0 2.0 1.0) (2.0 4.0 2.0) (1.0 2.0 1.0))))\nSINGLE-FLOAT\nSINGLE-FLOAT");
	}

	@Test
	void compileAndRunLinalgRankNShapesAndStackedMatmul() throws Exception {
		// The rank-N round: expand-dims / squeeze / concatenate / stack / slice /
		// triu / tril, the stacked (rank >= 3) matmul, var / std / power / where and
		// the softmax pair. The last softmax is the masked-attention idiom -- an
		// -infinity through where -> amax -> exp -> div must weigh exactly 0.0.
		assertThat(compileAndRunLinalg("""
				(defparameter *m* (linalg:reshape (linalg:arange 6) '(2 3)))
				(print (linalg:expand-dims #(1 2 3) 0))
				(print (linalg:squeeze #2A((1 2 3))))
				(print (linalg:concatenate (list *m* *m*) :axis 1))
				(print (linalg:stack (list #(1 2) #(3 4)) :axis 1))
				(print (linalg:slice *m* '(nil (0 2))))
				(print (linalg:slice #(0 1 2 3 4 5) '((nil nil -1))))
				(print (linalg:triu (linalg:ones '(3 3)) :k 1))
				(print (linalg:tril #2A((1 2 3) (4 5 6) (7 8 9)) :k -1))
				(print (linalg:matmul (linalg:reshape (linalg:arange 12) '(2 2 3))
				                      (linalg:reshape (linalg:arange 12) '(2 3 2))))
				(print (linalg:matmul (linalg:reshape (linalg:arange 12) '(2 2 3)) #(1 1 1)))
				(print (linalg:shape (linalg:matmul (linalg:zeros '(2 1 3 4)) (linalg:zeros '(5 4 2)))))
				(print (linalg:var #(1 2 3 4)))
				(print (linalg:std *m* :axis 0 :keepdims t))
				(print (linalg:power 2 #(1 2 3)))
				(print (linalg:where #(1 0 1) 10 20))
				(print (linalg:softmax #(1 1 1 1)))
				(print (linalg:softmax #2A((0 0) (1 1)) :axis 1))
				(print (linalg:log-softmax #(0 0)))
				(print (linalg:softmax (linalg:where (linalg:from-list '((1 0) (0 1)))
				                                     (linalg:from-list '((1.0 2.0) (3.0 4.0)))
				                                     (/ -1.0 0.0))
				                       :axis 1))
				(print (array-element-type (linalg:slice (linalg:ones 4 :element-type 'single-float) '((0 2)))))
				"""))
			.isEqualTo("#d((1.0 2.0 3.0))\n#d(1.0 2.0 3.0)\n#d((0.0 1.0 2.0 0.0 1.0 2.0) (3.0 4.0 5.0 3.0 4.0 5.0))\n"
					+ "#d((1.0 3.0) (2.0 4.0))\n#d((0.0 1.0) (3.0 4.0))\n#d(5.0 4.0 3.0 2.0 1.0 0.0)\n"
					+ "#d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))\n#d((0.0 0.0 0.0) (4.0 0.0 0.0) (7.0 8.0 0.0))\n"
					+ "#d(((10.0 13.0) (28.0 40.0)) ((172.0 193.0) (244.0 274.0)))\n#d((3.0 12.0) (21.0 30.0))\n"
					+ "(2 5 3 2)\n1.25\n#d((1.5 1.5 1.5))\n#d(2.0 4.0 8.0)\n#d(10.0 20.0 10.0)\n"
					+ "#d(0.25 0.25 0.25 0.25)\n#d((0.5 0.5) (0.5 0.5))\n#d(-0.6931471805599453 -0.6931471805599453)\n"
					+ "#d((1.0 0.0) (0.0 1.0))\nSINGLE-FLOAT");
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
				(print (linalg:mul (linalg:from-list '((1 2) (3 4)) :element-type 'single-float) #(10 20)))
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
				(print (linalg:diff #(1 2 4 7 0) :n 2))
				(print (linalg:diff #2A((1 3 6) (0 5 6))))
				(print (linalg:gradient #(0 1 4 9 16)))
				(print (linalg:gradient #(0 1 4 9 16) 2))
				(print (linalg:gradient #(0 1 9) #(0 1 3)))
				(print (array-element-type (linalg:gradient (linalg:arange 0 4 :element-type 'single-float))))
				""")).isEqualTo("#d(1.0 2.0 3.0 -7.0)\n#d(1.0 1.0 -10.0)\n#d((2.0 3.0) (5.0 1.0))\n"
				+ "#d(1.0 2.0 4.0 6.0 7.0)\n#d(0.5 1.0 2.0 3.0 3.5)\n#d(1.0 2.0 4.0)\nSINGLE-FLOAT");
	}

	@Test
	void compileAndRunLinalgSingleFloatWidthPolymorphism() throws Exception {
		// linalg is width-polymorphic -- a constructor opts into single-float
		// (#f) with an :element-type keyword, and every transform PRESERVES the input
		// width, so a #f value is never silently widened to double (which would force a
		// mixed-width --simd error on a following vec:matvec). Double stays the default.
		// f32-exact values (integers / halves) so the printed form is byte-identical to
		// the interpreter and WASM.
		assertThat(compileAndRunLinalg("""
				(print (linalg:zeros '(2 2) :element-type 'single-float))
				(print (linalg:zeros '(2 2)))
				(print (linalg:arange 0 8 2 :element-type 'single-float))
				(print (linalg:from-list '((1 2) (3 4)) :element-type 'single-float))
				(let ((w (linalg:from-list '((1 2) (3 4)) :element-type 'single-float)))
				  (print (linalg:sub w (linalg:mul (linalg:ones '(2 2) :element-type 'single-float) 0.5)))
				  (print (array-element-type (linalg:transpose w)))
				  (print (linalg:matmul w (linalg:eye 2 :element-type 'single-float))))
				(print (array-element-type (linalg:add (linalg:from-list '(1 2 3)) 10)))
				(print (linalg:dot (linalg:from-list '(1 2 3) :element-type 'single-float)
				                   (linalg:from-list '(1 2 3) :element-type 'single-float)))
				""")).isEqualTo("#f((0.0 0.0) (0.0 0.0))\n#d((0.0 0.0) (0.0 0.0))\n#f(0.0 2.0 4.0 6.0)\n"
				+ "#f((1.0 2.0) (3.0 4.0))\n#f((0.5 1.5) (2.5 3.5))\nSINGLE-FLOAT\n"
				+ "#f((1.0 2.0) (3.0 4.0))\nDOUBLE-FLOAT\n14.0");
	}

	@Test
	void compileAndRunTorchAutogradSmallGraph() throws Exception {
		// A multi-step graph (matmul -> broadcasting add -> mul -> sum) with backward:
		// the broadcast bias gets its unbroadcast (summed) gradient, and torch:no-grad
		// keeps a result off the tape. All values exact, so the output is
		// byte-identical on every backend.
		assertThat(compileAndRunTorch("""
				(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
				(defparameter *b* (torch:tensor 0.5 :requires-grad t))
				(defparameter *x* (torch:tensor #2A((1.0 2.0) (3.0 4.0))))
				(defparameter *y* (torch:add (torch:matmul *x* *w*) *b*))
				(print (torch:data *y*))
				(defparameter *loss* (torch:sum (torch:mul *y* *y*)))
				(print (torch:item *loss*))
				(torch:backward *loss*)
				(print (torch:grad *w*))
				(print (torch:grad *b*))
				(torch:no-grad
				  (print (torch:requires-grad-p (torch:mul *w* 2))))
				(print (torch:requires-grad-p (torch:mul *w* 2)))
				""")).isEqualTo("#f(5.5 11.5)\n162.5\n#f(80.0 114.0)\n34.0\nNIL\nT");
	}

	@Test
	void compileAndRunTorchTrainingLoopWithNoGrad() throws Exception {
		// Fit y = 2x by gradient descent on the MSE: ten forward/backward/update
		// steps, the update inside torch:no-grad. The tensors are single-float
		// (torch's default width, .kb/torch.md), and a #f element prints at its
		// stored width, so the printed result is byte-identical on every backend
		// (also the ci-spec torch-fit-cross-backend case).
		assertThat(compileAndRunTorch("""
				(defparameter *w* (torch:tensor '(0.0) :requires-grad t))
				(defparameter *x* (torch:tensor '(1.0 2.0)))
				(defparameter *y* (torch:tensor '(2.0 4.0)))
				(dotimes (i 10)
				  (let* ((diff (torch:sub (torch:mul *x* *w*) *y*))
				         (loss (torch:mean (torch:mul diff diff))))
				    (torch:backward loss)
				    (torch:no-grad
				      (setq *w* (torch:tensor (linalg:sub (torch:data *w*)
				                                          (linalg:mul 0.125 (torch:grad *w*)))
				                              :requires-grad t)))))
				(print (torch:data *w*))
				""")).isEqualTo("#f(1.9998901)");
	}

	@Test
	void compileAndRunTorchGradcheckTable() throws Exception {
		// The shared table-driven gradient check (TorchGradcheck): the analytic
		// gradient of every differentiable op matches central differences. The
		// closures the tape is made of are the part most likely to diverge between
		// backends, so the whole table runs compiled too.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.EXPECTED);
	}

	@Test
	void compileAndRunTorchModuleTrainingLoop() throws Exception {
		// The nn acceptance program (TorchGradcheck.NN_TRAINING_PROGRAM): a
		// torch:sequential MLP trained for 200 SGD steps over torch:parameters. It
		// exercises the whole module layer compiled -- the fields walk, the forward
		// closures, torch:zero-grad on a module and the in-place torch:set-data
		// update inside torch:no-grad.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.NN_TRAINING_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.NN_TRAINING_EXPECTED);
	}

	@Test
	void compileAndRunTorchRecordPrinting() throws Exception {
		// TorchGradcheck.RECORD_PRINT_PROGRAM, shared verbatim with the interpreter and
		// the WASM backends: the records are defstructs carrying a (:print-object ...),
		// so a tensor renders identically everywhere -- which the tagged vectors they
		// replaced could not do (one slot is a backward closure). The identity lines
		// pin eq/eql/member on a record instance.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.RECORD_PRINT_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.RECORD_PRINT_EXPECTED);
	}

	@Test
	void compileAndRunTorchElementTypes() throws Exception {
		// TorchGradcheck.ELEMENT_TYPE_PROGRAM: torch originates every array at
		// torch::*default-element-type* (single-float), and the width is inherited
		// through a full forward and backward pass, while linalg keeps its double
		// default. Compiled, the packed representation is picked statically, so a
		// missed origination site shows up as a DOUBLE-FLOAT line here.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.ELEMENT_TYPE_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.ELEMENT_TYPE_EXPECTED);
	}

	@Test
	void compileAndRunTorchOptimizerRules() throws Exception {
		// The optimizer acceptance program (TorchGradcheck.OPTIMIZER_PROGRAM): the
		// SGD/Adam rules against hand-computed values, the batching and mask helpers,
		// and the residual-vs-plain identity-learning experiment -- the in-place
		// element-wise parameter update compiled, plus the step-fn closure the
		// optimizer record dispatches through.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.OPTIMIZER_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.OPTIMIZER_EXPECTED);
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

	// CLHS 3.2.4.4: a literal object a macro spliced into its expansion is dumped
	// through its own make-load-form method, not by structure -- which is the only way
	// an object with an unspellable slot (the hash table here, a cffi foreign type's
	// keyword map in the real case) can travel into compiled code at all.
	@Test
	void compileAndRunLiteralObjectDumpedByMakeLoadForm() throws Exception {
		assertThat(compileAndRunExpanded("""
				(defclass box () ((name :initarg :name :accessor box-name) (cache :initarg :cache)))
				(defmethod make-load-form ((b box) &optional env)
				  (declare (ignore env))
				  (list 'make-instance ''box :name (box-name b)))
				(defparameter *box* (make-instance 'box :name "dumped" :cache (make-hash-table)))
				(defmacro splice-box () *box*)
				(princ (box-name (splice-box)))
				""")).isEqualTo("dumped");
	}

	// The built-in default is unchanged: a type nobody wrote a method for is still
	// dumped by STRUCTURE, so an unspellable slot still fails the compile. This is the
	// half that keeps every #S(...) literal in every other program travelling as before.
	@Test
	void compileRefusesALiteralObjectWithNoMakeLoadFormMethod() {
		assertThatThrownBy(() -> compileAndRunExpanded("""
				(defclass box () ((name :initarg :name :accessor box-name) (cache :initarg :cache)))
				(defparameter *box* (make-instance 'box :name "dumped" :cache (make-hash-table)))
				(defmacro splice-box () *box*)
				(princ (box-name (splice-box)))
				""")).hasMessageContaining("Cannot quote");
	}

	// make-load-form-saving-slots answers the same creation form the quote compilers
	// dump an instance as, so a method delegating to it (cl-ppcre's charmap/charset)
	// gets the built-in answer rather than an error.
	@Test
	void compileAndRunMakeLoadFormSavingSlots() throws Exception {
		assertThat(compileAndRunExpanded("""
				(defstruct pt x y)
				(defmethod make-load-form ((p pt) &optional env)
				  (make-load-form-saving-slots p :environment env))
				(defparameter *p* (make-pt :x 3 :y "four"))
				(defmacro splice-pt () *p*)
				(princ (make-load-form *p*))
				(terpri)
				(princ (list (pt-x (splice-pt)) (pt-y (splice-pt))))
				""")).isEqualTo("(%OBJ-NEW (QUOTE %struct-PT) (QUOTE 3) (QUOTE four))\n(3 four)");
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
	void compileAndRunAPrunedBundledDefstructThroughTheRegistrationMarker() throws Exception {
		// The pruner expands a bundled-library defstruct into its defuns ahead of
		// pruning and leaves a %struct-definition marker in the stream; this pass
		// re-runs the registration from the marker, so setf places, the predicate and
		// construction still compile with the unreferenced accessors pruned.
		java.util.List<LispVal> pruned = am.ik.rontolisp.eval.LibraryDefunPruner.prune(LispReader.readAllFromString("""
				(defstruct torch::rec a b c)
				(setq r (torch::make-rec :a 1 :b 2 :c 3))
				(setf (torch::rec-a r) 10)
				(print (torch::rec-a r))
				(print (torch::rec-p r))
				"""));
		assertThat(compileAndRun(pruned)).isEqualTo("10\nT");
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
	void compileAndRunDefmethodEqlSpecializerNamingAConstant() throws Exception {
		assertThat(compileAndRun("""
				(defconstant +eql-spec-utf8+ 12)
				(defconstant +eql-spec-tag+ :tag)
				(defgeneric decode-it (a b))
				(defmethod decode-it (a b) (list :default a b))
				(defmethod decode-it (a (b (eql +eql-spec-utf8+))) (list :utf8 a b))
				(defmethod decode-it (a (b (eql +eql-spec-tag+))) (list :tag a b))
				(print (list (decode-it 1 12) (decode-it 1 :tag) (decode-it 1 99)))
				""")).isEqualTo("((:UTF8 1 12) (:TAG 1 :TAG) (:DEFAULT 1 99))");
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
	void compileAndRunSetfSymbolFunctionAliasAndRedefinition() throws Exception {
		// jsf-alias is bound ONLY by setf, so a forwarder defun carries its direct
		// call sites to the runtime _fenv binding; the second setf re-binds it.
		assertThat(compileAndRun("""
				(defun jsf-orig (x) (* x 2))
				(setf (symbol-function 'jsf-alias) #'jsf-orig)
				(print (jsf-alias 21))
				(print (funcall #'jsf-alias 3))
				(setf (fdefinition 'jsf-alias) (lambda (x) (list :new x)))
				(print (jsf-alias 5))
				(print (fboundp 'jsf-alias))
				""")).isEqualTo("42\n6\n(:NEW 5)\nT");
	}

	@Test
	void compileAndRunSetfSymbolFunctionBeforeAssignmentSignals() throws Exception {
		assertThat(compileAndRun("""
				(defun jsf2-target (x) x)
				(print (handler-case (jsf2-late 1) (error (e) :undefined)))
				(setf (symbol-function 'jsf2-late) #'jsf2-target)
				(print (jsf2-late :ok))
				""")).isEqualTo(":UNDEFINED\n:OK");
	}

	@Test
	void compileAndRunReinitializeInstanceWithNoUserMethod() throws Exception {
		// CL supplies the system default (chaining into shared-initialize's initarg
		// fill), so a bare call compiles and fills the supplied initargs -- upstream
		// ASDF's (apply 'reinitialize-instance system keys).
		assertThat(compileAndRun("""
				(defclass jri-p () ((n :initarg :n :reader jri-n) (m :initarg :m :initform 10)))
				(let ((o (make-instance 'jri-p :n 1)))
				  (reinitialize-instance o :n 2)
				  (print (list (jri-n o) (slot-value o 'm))))
				""")).isEqualTo("(2 10)");
	}

	@Test
	void compileAndRunTheMissingStandardNames() throws Exception {
		// The batch on the JVM: with-compilation-unit is a progn, the load
		// switches are bound nil, a cl:-qualified read-time constant answers the
		// constant, the three new type names resolve, and the prelude-backed names
		// exist (the four unsupported ones signal rather than answering a fabrication).
		assertThat(compileAndRun("""
				(print (with-compilation-unit (:override t) 1 2 3))
				(print (list *load-verbose* *load-print*))
				(print (> cl:most-positive-fixnum 1000000))
				(print (list (typep (make-synonym-stream '*standard-output*) 'synonym-stream)
				             (typep *readtable* 'readtable)
				             (typep 3 'file-stream)
				             (typep "s" 'file-stream)))
				(print (let ((s (make-string-input-stream "z")))
				         (list (typep s 'file-stream) (typep s 'string-stream) (typep s 'stream))))
				(print (symbol-name (copy-symbol 'jcs-name)))
				(print (let ((h (user-homedir-pathname))) (or (null h) (pathnamep h))))
				(print (list (handler-case (compile-file "x") (error (e) :cf))
				             (handler-case (compile-file-pathname "x") (error (e) :cfp))
				             (handler-case (remove-method 1 2) (error (e) :rm))
				             (handler-case (invoke-debugger (make-condition 'simple-error :format-control "b"))
				               (error (e) :dbg))))
				""")).isEqualTo("3\n(NIL NIL)\nT\n(T T NIL NIL)\n(NIL T T)\n\"JCS-NAME\"\nT\n(:CF :CFP :RM :DBG)");
	}

	@Test
	void compileAndRunPrintObjectCalledDirectly() throws Exception {
		// print-object is callable directly with no user method anywhere (CL supplies a
		// system method for every object), and defining one on a class must not lose
		// the printer for every other class.
		assertThat(compileAndRun("(print-object 42 *standard-output*)")).isEqualTo("42");
		assertThat(compileAndRun("""
				(defclass jpo-p () ())
				(defmethod print-object ((x jpo-p) s) (write-string "#<JPO!>" s))
				(print-object 42 *standard-output*)
				(terpri)
				(print-object (make-instance 'jpo-p) *standard-output*)
				(terpri)
				(print (make-instance 'jpo-p))
				""")).isEqualTo("42\n#<JPO!>\n#<JPO!>");
	}

	@Test
	void compileAndRunChangeClassWithComputedDesignator() throws Exception {
		// A runtime class designator (symbol or metaobject) routes through the
		// generated %change-class-runtime dispatch; identity, shared slots, initform
		// fill and supplied initargs match the literal spelling.
		assertThat(compileAndRun("""
				(defclass jcc-base () ((n :initarg :n)))
				(defclass jcc-sub (jcc-base) ((extra :initform 42)))
				(let ((o (make-instance 'jcc-base :n 1)) (cls 'jcc-sub))
				  (change-class o cls)
				  (let ((p (make-instance 'jcc-base :n 5)))
				    (change-class p (find-class 'jcc-sub) :n 7)
				    (print (list (class-name (class-of o)) (slot-value o 'n) (slot-value o 'extra)
				                 (slot-value p 'n)))))
				""")).isEqualTo("(JCC-SUB 1 42 7)");
	}

	@Test
	void compileAndRunDefclassWriterAndClassAllocation() throws Exception {
		// :writer (setf name) / :writer sym define the write-side generics; a
		// :allocation :class slot lives in one shared cell per declaring class, read
		// and written through every access path, with a re-declaring subclass owning
		// a cell of its own; standard-class works as a typecase specifier.
		assertThat(compileAndRun("""
				(defclass jwr-q () ((a :writer (setf jwr-q-a) :reader jwr-q-a :initform 1)
				                    (b :writer jwr-set-b :reader jwr-get-b :initform 0)))
				(let ((o (make-instance 'jwr-q)))
				  (setf (jwr-q-a o) 5)
				  (jwr-set-b 7 o)
				  (print (list (jwr-q-a o) (jwr-get-b o))))
				(defclass jca-op () ((selfward :initform 'base-op :allocation :class :reader jca-selfward)))
				(defclass jca-load-op (jca-op) ((selfward :initform 'load-dep :allocation :class)))
				(defclass jca-r () ((a :initform 1 :allocation :class :accessor jca-r-a)))
				(let ((x (make-instance 'jca-r)) (y (make-instance 'jca-r)) (nm 'a))
				  (setf (jca-r-a x) 9)
				  (print (list (jca-selfward (make-instance 'jca-op))
				               (jca-selfward (make-instance 'jca-load-op))
				               (jca-r-a y) (slot-value y 'a) (slot-value y nm))))
				(print (typecase (find-class 'jca-op) (standard-class :meta) (t :other)))
				""")).isEqualTo("(5 7)\n(BASE-OP LOAD-DEP 9 9 9)\n:META");
	}

	@Test
	void compileAndRunDefclassMultipleInheritance() throws Exception {
		// Slots merge across supers; the second super's accessor (whose slot shifts
		// from index 0 to 1) is overridden per class; a diamond keeps ONE copy of the
		// shared slot and the more specific re-declared initform wins by CPL.
		assertThat(compileAndRun("""
				(defclass jmi-a () ((x :initarg :x :accessor jmi-x)))
				(defclass jmi-b () ((y :initarg :y :accessor jmi-y)))
				(defclass jmi-c (jmi-a jmi-b) ((z :initarg :z :accessor jmi-z)))
				(let ((b (make-instance 'jmi-b :y 7))
				      (c (make-instance 'jmi-c :x 1 :y 2 :z 3)))
				  (setf (jmi-y c) 9)
				  (print (list (jmi-x c) (jmi-y c) (jmi-z c) (jmi-y b))))
				(defclass jdi-base () ((v :initform :base :reader jdi-v)))
				(defclass jdi-l (jdi-base) ())
				(defclass jdi-r (jdi-base) ((v :initform :right)))
				(defclass jdi-d (jdi-l jdi-r) ())
				(let ((d (make-instance 'jdi-d)))
				  (print (list (jdi-v d) (length (%obj-slots d)) (typep d 'jdi-l) (typep d 'jdi-r))))
				""")).isEqualTo("(1 9 3 7)\n(:RIGHT 1 T T)");
	}

	@Test
	void compileAndRunDefclassMultipleInheritanceDispatchFollowsCpl() throws Exception {
		// Method selection and call-next-method both follow the instance class's
		// precedence list: (a b) reaches a's method first, (b a) reaches b's.
		assertThat(compileAndRun("""
				(defclass jlp-a () ())
				(defclass jlp-b () ())
				(defgeneric jlp-who (x))
				(defmethod jlp-who ((x jlp-a)) :a)
				(defmethod jlp-who ((x jlp-b)) :b)
				(defclass jlp-ab (jlp-a jlp-b) ())
				(defclass jlp-ba (jlp-b jlp-a) ())
				(print (list (jlp-who (make-instance 'jlp-ab)) (jlp-who (make-instance 'jlp-ba))))
				(defgeneric jcn-trace (x))
				(defmethod jcn-trace ((x jlp-a)) (cons :a (if (next-method-p) (call-next-method) nil)))
				(defmethod jcn-trace ((x jlp-b)) (cons :b (if (next-method-p) (call-next-method) nil)))
				(defmethod jcn-trace (x) (list :default))
				(print (jcn-trace (make-instance 'jlp-ab)))
				(print (jcn-trace (make-instance 'jlp-ba)))
				""")).isEqualTo("(:A :B)\n(:A :B :DEFAULT)\n(:B :A :DEFAULT)");
	}

	@Test
	void compileAndRunSetfMethodDispatchesPerClass() throws Exception {
		// (defmethod (setf name) ...) merges with a defclass :accessor writer on the
		// same generic; (defgeneric (setf name) ...) with an inline method works too.
		assertThat(compileAndRun("""
				(defclass jsm-a () ((x :initarg :x :accessor jsm-val)))
				(defclass jsm-b () ((log :initform nil :reader jsm-log)))
				(defmethod (setf jsm-val) (new (b jsm-b)) (setf (slot-value b 'log) (list :wrote new)) new)
				(let ((a (make-instance 'jsm-a :x 1))
				      (b (make-instance 'jsm-b)))
				  (setf (jsm-val a) 2)
				  (setf (jsm-val b) 3)
				  (print (list (jsm-val a) (jsm-log b))))
				(defclass jsm-box () ((v :initform 0 :reader jsm-content)))
				(defgeneric (setf jsm-content) (new box)
				  (:method (new (b jsm-box)) (setf (slot-value b 'v) new)))
				(let ((b (make-instance 'jsm-box)))
				  (setf (jsm-content b) 9)
				  (funcall #'(setf jsm-content) 11 b)
				  (print (jsm-content b)))
				""")).isEqualTo("(2 (:WROTE 3))\n11");
	}

	@Test
	void compileAndRunDefmethodOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod() throws Exception {
		// (close X) is compiler-lowered, so the generated dispatcher defun used to be
		// dead under its own name and the user method was silently ignored
		// (ClassCastException on an instance). ShadowedBuiltins renames the dispatcher,
		// rewrites the call sites and keeps the built-in as the default method.
		assertThat(compileAndRun("""
				(defclass sbm-cls () ())
				(defmethod length ((x sbm-cls)) 42)
				(print (list (length (make-instance 'sbm-cls)) (length "abc") (length '(1 2 3))))
				""")).isEqualTo("(42 3 3)");
	}

	@Test
	void compileAndRunDefmethodOnAVariadicBuiltinNameForwardsThroughTheDispatcher() throws Exception {
		// fast-io's gray.lisp shape: a (close (s cls) &key abort) method must not
		// poison close for real stream handles -- with-open-file (whose expansion
		// spells (close f)) still closes, and an explicit (close s :abort t) reaches
		// the built-in through the dispatcher's %gf-rest tail.
		String file = tempDir.resolve("sbm-closed.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(defclass sbm-stream () ())
				(defmethod close ((s sbm-stream) &key abort) (declare (ignore abort)) :closed)
				(print (close (make-instance 'sbm-stream)))
				(with-open-file (out "%s" :direction :output) (write-line "hi" out))
				(print (with-open-file (in "%s") (read-line in)))
				(let ((s (open "%s"))) (print (close s :abort t)))
				""".formatted(file, file, file))).isEqualTo(":CLOSED\n\"hi\"\nT");
	}

	@Test
	void compileAndRunDefgenericOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod() throws Exception {
		assertThat(compileAndRun("""
				(defgeneric length (x))
				(print (length "abc"))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunDefmethodOnABuiltinNameQualifiersAndFirstClassReference() throws Exception {
		// A :before-only branch composes with the built-in default method instead of
		// "No applicable primary method", call-next-method out of the least specific
		// user primary reaches the built-in, and #'length is rewritten onto the
		// dispatcher so a funcall dispatches too.
		assertThat(compileAndRun("""
				(defclass sbq-cls () ())
				(defparameter *sbq-log* nil)
				(defmethod length :before ((x string)) (push :before *sbq-log*))
				(defmethod length ((x sbq-cls)) (call-next-method))
				(print (list (length "abc") (reverse *sbq-log*)))
				(print (funcall #'length "abcd"))
				""")).isEqualTo("(3 (:BEFORE))\n4");
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
	void compileAndRunWithSlotsWriteOnlyUnboundSlot() throws Exception {
		// with-slots binds, it never reads: a body that only ASSIGNS a slot declared
		// without an :initform must not signal on entry (fast-io's initialize-instance).
		assertThat(compileAndRun("""
				(defclass wsu-box () ((buffer)))
				(defmethod initialize-instance ((self wsu-box) &key)
				  (call-next-method)
				  (with-slots (buffer) self
				    (setf buffer (list 1 2))))
				(print (slot-value (make-instance 'wsu-box) 'buffer))
				""")).isEqualTo("(1 2)");
	}

	@Test
	void compileAndRunSlotValueOnAnUndeclaredSlotSignalsAtRunTime() throws Exception {
		// The eagerly expanding compile path must not fail the BUILD over a slot read
		// that may never execute (fast-io's open-stream-p reads a typo'd slot name):
		// it lowers to a run-time error, like the interpreter and like slot-missing.
		assertThat(compileAndRun("""
				(defclass ms-box () ((a :initform 1)))
				(print (handler-case (slot-value (make-instance 'ms-box) 'nope)
				         (error (e) (princ-to-string e))))
				(print (slot-value (make-instance 'ms-box) 'a))
				""")).isEqualTo("\"The slot NOPE is missing\"\n1");
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
	void compileAndRunDefstructPrintObjectAndPrintFunctionOptions() throws Exception {
		// Both printer options lower to a synthesized print-object defmethod, which has
		// to go through the same registration + dispatcher placement a top-level
		// defmethod gets -- the compile path is the half ci-spec cannot check without
		// the native binary. The CLtL1 :print-function spelling takes the extra depth.
		assertThat(compileAndRun("""
				(defstruct (dpo-pt (:print-object dpo-pt-printer)) (x 1) (y 2))
				(defun dpo-pt-printer (obj stream)
				  (format stream "<~D,~D>" (dpo-pt-x obj) (dpo-pt-y obj)))
				(defstruct (dpf-set (:print-function (lambda (obj stream depth)
				                                       (print-unreadable-object (obj stream :type t)
				                                         (format stream "of ~D element~:P at ~D"
				                                                 (dpf-set-size obj) depth)))))
				  (size 1))
				(print (list (princ-to-string (make-dpo-pt)) (prin1-to-string (make-dpo-pt :y 9))
				             (princ-to-string (make-dpf-set)) (princ-to-string (make-dpf-set :size 3))))
				"""))
			.isEqualTo("(\"<1,2>\" \"<1,9>\" \"#<DPF-SET of 1 element at 0>\" \"#<DPF-SET of 3 elements at 0>\")");
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
	void compileAndRunPackageIsADefmethodSpecializer() throws Exception {
		// rove's find-suite: a (package) method beside an unspecialized DESIGNATOR
		// method that calls find-package and recurses. The specializer shares the
		// package TYPE test, and ranks ahead of keyword/symbol -- misordered, the
		// designator method would recurse forever.
		assertThat(compileAndRun("""
				(defpackage :rov-suite (:use :cl))
				(defvar *package-suites* (make-hash-table :test 'equal))
				(defgeneric find-suite (package)
				  (:method ((package package))
				    (values (gethash package *package-suites*)))
				  (:method (package-name)
				    (check-type package-name string-designator)
				    (let ((package (find-package package-name)))
				      (unless package (error "No package '~A' found" package-name))
				      (find-suite package))))
				(setf (gethash :rov-suite *package-suites*) "suite")
				(print (list (find-suite :rov-suite) (find-suite "ROV-SUITE") (find-suite 'rov-suite)))
				""")).isEqualTo("(\"suite\" \"suite\" \"suite\")");
	}

	@Test
	void compileAndRunPackageSpecializerOutranksKeywordAndSymbol() throws Exception {
		// A package IS a keyword in this value model, so the package branch must be
		// tested first; a keyword naming no package falls through to the keyword method.
		assertThat(compileAndRun("""
				(defpackage :psk-pkg (:use :cl))
				(defgeneric psk-kind (x))
				(defmethod psk-kind ((x symbol)) :symbol)
				(defmethod psk-kind ((x keyword)) :keyword)
				(defmethod psk-kind ((x package)) :package)
				(defmethod psk-kind (x) :other)
				(print (list (psk-kind :psk-pkg) (psk-kind :no-such-pkg-xyz) (psk-kind 'psk-pkg) (psk-kind 42)))
				""")).isEqualTo("(:PACKAGE :KEYWORD :SYMBOL :OTHER)");
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
	void progvBindsRestoresAndNests() throws Exception {
		// Interpreter parity for the progv lowering (.kb/dynamic-special-variables.md):
		// a declared special gets a true dynamic binding visible through a called defun,
		// nested binds stack, an UNDECLARED name is readable via symbol-value for the
		// extent (and unbound again after), and extra symbols bind to nil.
		assertThat(compileAndRun("""
				(defvar *a* 1)
				(defvar *b* 2)
				(defun peek () (list *a* *b*))
				(progv '(*a* *b*) '(10 20) (print (peek)))
				(print (peek))
				(print (progv (list '*a*) (list 5)
				         (progv (list '*a* '*c*) (list (* *a* 2) 7)
				           (list *a* (symbol-value '*c*)))))
				(print (list *a* (boundp '*c*)))
				(print (progv '(*a*) '() *a*))
				""")).isEqualTo("""
				(10 20)
				(1 2)
				(10 7)
				(1 NIL)
				NIL""");
	}

	@Test
	void progvRestoresOnEveryExit() throws Exception {
		// The lowering rides the unwind-protect cleanup emitter, so a return-from, a go
		// and an error caught OUTSIDE the progv all restore the binding -- the exits
		// limitation 1 of .kb/dynamic-special-variables.md still leaves open for a
		// special `let`.
		assertThat(compileAndRun("""
				(defvar *x* :top)
				(defun f ()
				  (progv '(*x*) '(:in)
				    (return-from f *x*)))
				(print (list (f) *x*))
				(defun g ()
				  (let ((r nil))
				    (tagbody
				       (progv '(*x*) '(:go)
				         (setq r *x*)
				         (go out))
				     out)
				    (list r *x*)))
				(print (g))
				(print (handler-case (progv '(*x*) '(:err) (error "boom"))
				         (error () *x*)))
				""")).isEqualTo("""
				(:IN :TOP)
				(:GO :TOP)
				:TOP""");
	}

	@Test
	void progvSymbolValueSeesTheSetqInsideTheExtent() throws Exception {
		// The cl-json aggregate-scope shape: (progv vars (mapcar #'symbol-value vars))
		// re-binds each scope variable to its CURRENT value, where "current" includes a
		// setq made inside an enclosing progv extent -- symbol-value compiles
		// dynamic-first in a progv-using program.
		assertThat(compileAndRun("""
				(defvar *acc* nil)
				(progv '(*acc*) (list (symbol-value '*acc*))
				  (setq *acc* (cons 1 *acc*))
				  (progv '(*acc*) (list (symbol-value '*acc*))
				    (setq *acc* (cons 2 *acc*))
				    (print *acc*))
				  (print *acc*))
				(print *acc*)
				""")).isEqualTo("""
				(2 1)
				(1)
				NIL""");
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
		// evaluate right-to-left, so the store must be ordered explicitly.
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
	void compileRank2InitialContentsFillsRowMajor() throws Exception {
		// A literal rank >= 2 :initial-contents used to be refused outright on
		// the compiled backends; it now lowers to a nested row-major fill, matching the
		// interpreter -- for the general boxed representation and for the packed
		// float-array one (the silent-zero bug the interpreter fix addressed).
		assertThat(compileAndRun("(print (aref (make-array '(2 2) :initial-contents '((1 2) (3 4))) 1 1))"))
			.isEqualTo("4");
		assertThat(compileAndRun("(print (aref (make-array '(2 3) :initial-contents '((1 2 3) (4 5 6))) 1 0))"))
			.isEqualTo("4");
		assertThat(compileAndRun("""
				(print (make-array '(2 2) :element-type 'double-float
				                    :initial-contents '((1.0 2.0) (3.0 4.0))))
				""")).isEqualTo("#d((1.0 2.0) (3.0 4.0))");
		assertThat(compileAndRun("""
				(print (make-array '(2 2 2) :initial-contents '(((1 2) (3 4)) ((5 6) (7 8)))))
				""")).isEqualTo("#3A(((1 2) (3 4)) ((5 6) (7 8)))");
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
	void compileFillWritesEveryElementInRange() throws Exception {
		// Same contract as the interpreter's fillWritesEveryElementInRange.
		assertThat(compileAndRun("""
				(let* ((a (make-array 5 :element-type '(unsigned-byte 8) :initial-element 9))
				       (same (eq a (fill a 300 :start 1 :end 4))))
				  (print (list a same (array-element-type a))))
				""")).isEqualTo("(#(9 44 44 44 9) T (UNSIGNED-BYTE 8))");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4 5))) (fill l 0 :start 1 :end 3) (print l))"))
			.isEqualTo("(1 0 0 4 5)");
		assertThat(compileAndRun(
				"(print (fill (make-array 5 :element-type 'character :initial-element #\\a) #\\z :start 2))"))
			.isEqualTo("\"aazzz\"");
		assertThat(compileAndRun("(print (funcall #'fill (make-array 2 :initial-element 1) 8))")).isEqualTo("#(8 8)");
	}

	@Test
	void compileMakeArrayElementTypeResolvesADeftypeAlias() throws Exception {
		// Same contract as the interpreter's makeArrayElementTypeResolvesADeftypeAlias:
		// an alias picks the representation its expansion designates. On this backend
		// the program-level usesIntArray gate has to resolve it too, or the _iv*
		// helpers would not be emitted and the call would fall to the general path.
		assertThat(compileAndRun("""
				(deftype octet () '(unsigned-byte 8))
				(deftype byte-buffer () 'octet)
				(deftype real-double () 'double-float)
				(deftype char-buf () 'character)
				(let ((a (make-array 3 :element-type 'octet))
				      (b (make-array 2 :element-type 'byte-buffer :initial-contents '(1 300)))
				      (c (make-array 2 :element-type 'real-double))
				      (s (make-array 3 :element-type 'char-buf :initial-element #\\x)))
				  (print (list (array-element-type a) (aref a 0) b (array-element-type c) (aref c 0) (stringp s) s)))
				""")).isEqualTo("((UNSIGNED-BYTE 8) 0 #(1 44) DOUBLE-FLOAT 0.0 T \"xxx\")");
		assertThat(compileAndRun("(print (array-element-type (make-array 2 :element-type 'not-a-type)))"))
			.isEqualTo("T");
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

	@Test
	void compileAndRunShortFormMethodCombination() throws Exception {
		// yason's encode-slots hook: the operator over EVERY applicable qualified
		// method, in specificity order, with :most-specific-last reversing it.
		assertThat(compileAndRun("""
				(defclass mc-base () ())
				(defclass mc-leaf (mc-base) ())
				(defgeneric mc-trace (x) (:method-combination progn :most-specific-last))
				(defmethod mc-trace progn ((x mc-base)) (print :base))
				(defmethod mc-trace progn ((x mc-leaf)) (print :leaf))
				(defgeneric mc-sum (x) (:method-combination +))
				(defmethod mc-sum + ((x mc-base)) 1)
				(defmethod mc-sum + ((x mc-leaf)) 100)
				(mc-trace (make-instance 'mc-leaf))
				(print (mc-sum (make-instance 'mc-leaf)))
				(print (mc-sum (make-instance 'mc-base)))
				""")).isEqualTo(":BASE\n:LEAF\n101\n1");
	}

	@Test
	void compileAndRunOpenAppend(@TempDir Path tempDir) throws Exception {
		String file = tempDir.resolve("append.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output) (write-string "one" out))
				(with-open-file (out "%s" :direction :output :if-exists :append) (write-string "two" out))
				(with-open-file (in "%s") (print (read-line in)))
				""".formatted(file, file, file))).isEqualTo("\"onetwo\"");
	}

	@Test
	void compileAndRunComputedOpenOptions(@TempDir Path tempDir) throws Exception {
		String file = tempDir.resolve("computed.txt").toString().replace("\\", "\\\\");
		// The options arrive as ARGUMENTS -- the shape a portable file wrapper has --
		// so the mode cannot be folded; the compiled code dispatches onto the literal
		// open shapes instead. Byte-identical output for a literal spec is what
		// compileAndRunOpenAppend above pins.
		assertThat(compileAndRun("""
				(defun wr (path text dir ie)
				  (with-open-file (out path :direction dir :if-exists ie) (write-string text out)))
				(defun rd (path et)
				  (with-open-file (in path :element-type et) (read-line in)))
				(defun rd1 (path et)
				  (with-open-file (in path :element-type et) (read-byte in)))
				(wr "%s" "one" :output :supersede)
				(wr "%s" "two" :output :append)
				(print (rd "%s" 'character))
				(print (rd1 "%s" (list 'unsigned-byte 8)))
				""".formatted(file, file, file, file))).isEqualTo("\"onetwo\"\n111");
	}

	@Test
	void aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore(@TempDir Path tempDir) {
		// The runtime dispatch must cost a LITERAL spec nothing: the fold is what every
		// existing program and every size-report number was measured on. A spec of
		// literals and the positional open it folds to must therefore emit the same
		// class.
		List<LispVal> keyword = LispReader.readAllFromString("""
				(with-open-file (s "f.txt" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 65 s))
				""");
		List<LispVal> positional = LispReader.readAllFromString("""
				(let ((s (open "f.txt" :output '(unsigned-byte 8))))
				  (unwind-protect (write-byte 65 s) (close s)))
				""");
		byte[] folded = new JvmLispCompiler("SameOpen", false, OptimizeLevel.DEFAULT).compile(keyword);
		assertThat(folded).isEqualTo(new JvmLispCompiler("SameOpen", false, OptimizeLevel.DEFAULT).compile(positional));
	}

	@Test
	void theSizeLevelChangesNothingWithoutASpeedForSizeTrade() {
		// --optimize=size is accepted everywhere so a build script need not be
		// backend-specific. The emissions it declines on this backend are the typed
		// numeric loop (JvmTypedLoopCompiler) and integer expression-tree fusion
		// (JvmIntFusionCompiler); a program with neither shape compiles to the same
		// class at both levels, which the docs say -- this is what makes that
		// statement checkable, and what fails the day someone gives the JVM backend
		// another speed-for-size trade without saying so.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun pair (x) (list x x))
				(print (pair 'hey))
				""");
		byte[] fast = new JvmLispCompiler("Same", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Same", false, OptimizeLevel.SIZE).compile(program);
		assertThat(small).isEqualTo(fast);
	}

	@Test
	void theFlaglessBuildIsTheOptimizedOneAndOffIsTheWayBack() {
		// The whole promise of --optimize being on by default: a build that passes no
		// flag compiles what --optimize compiles, and the unoptimized shape has to be
		// asked for by name. One half is OptimizeLevel.parse(null) -> DEFAULT (pinned
		// in OptimizeLevelTest); the other half is here, because "the levels agree"
		// would satisfy the first half just as well and would mean the flip changed
		// nothing.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun never-called (x) (* x x))
				(print (+ 1 2))
				""");
		byte[] flagless = new JvmLispCompiler("Same", false, OptimizeLevel.parse(null)).compile(program);
		byte[] optimized = new JvmLispCompiler("Same", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] off = new JvmLispCompiler("Same", false, OptimizeLevel.parse("off")).compile(program);
		assertThat(flagless).isEqualTo(optimized);
		assertThat(off).isNotEqualTo(optimized);
		assertThat(off.length).isGreaterThan(optimized.length);
	}

	/**
	 * The typed-loop program: every shape {@code JvmTypedLoopCompiler} takes (a softmax
	 * with assigned accumulators, rank-2 stores, a nested rope-shaped loop whose index
	 * math goes through let temporaries, a result form, a zero-trip loop, a store whose
	 * value is used) and the guard failures it must survive (a general array, a Long in a
	 * double-speculated slot, NaN under when/unless, an out-of-bounds access caught by
	 * handler-case with the accumulators written back).
	 */
	private static final String TYPED_LOOP_PROGRAM = """
			(defun tl-softmax (att scores pos inv)
			  (let ((top -1e30) (z 0.0))
			    (dotimes (u (+ pos 1))
			      (let ((sc (* (aref scores u) inv)))
			        (setf (aref att u) sc)
			        (when (> sc top) (setq top sc))))
			    (dotimes (u (+ pos 1))
			      (let ((e (exp (- (aref att u) top))))
			        (setf (aref att u) e)
			        (setq z (+ z e))))
			    (dotimes (u (+ pos 1)) (setf (aref att u) (/ (aref att u) z)))
			    (list top z)))
			(let ((att (make-array 8 :element-type 'single-float :initial-element 0.0))
			      (scores (make-array 8 :element-type 'single-float :initial-element 0.0)))
			  (dotimes (i 8) (setf (aref scores i) (* i 0.7)))
			  (print (tl-softmax att scores 5 0.25))
			  (print att)
			  (print (tl-softmax att scores 7 2)))
			(let ((kc (make-array '(4 3) :element-type 'single-float :initial-element 0.0))
			      (vt (make-array '(3 4) :element-type 'double-float :initial-element 0.0))
			      (k (make-array 6 :element-type 'single-float :initial-element 1.5))
			      (hs 3) (pos 2) (base 3))
			  (dotimes (i hs) (setf (aref kc pos i) (aref k (+ base i))))
			  (print kc)
			  (dotimes (i hs) (setf (aref vt i pos) (* 2 (aref k (+ base i)))))
			  (print vt)
			  (let ((v (make-array 6 :element-type 'single-float :initial-element 0.0))
			        (rc (make-array '(4 2) :element-type 'single-float :initial-element 0.5))
			        (rs (make-array '(4 2) :element-type 'single-float :initial-element 0.25))
			        (half 1) (n-heads 3))
			    (dotimes (i 6) (setf (aref v i) (+ i 1)))
			    (dotimes (h n-heads)
			      (dotimes (i half)
			        (let* ((j (+ (* h 2) (* 2 i)))
			               (fcr (aref rc pos i)) (fci (aref rs pos i))
			               (v0 (aref v j)) (v1 (aref v (+ j 1))))
			          (setf (aref v j) (- (* v0 fcr) (* v1 fci)))
			          (setf (aref v (+ j 1)) (+ (* v0 fci) (* v1 fcr))))))
			    (print v)))
			(let ((cnt 0) (acc 0.0) (a (make-array 4 :element-type 'double-float :initial-element 2.0)) (n 0))
			  (print (dotimes (i 4 i) (setq acc (+ acc (aref a i)))))
			  (print acc)
			  (print (dotimes (i n) (setq acc (+ acc 1.0))))
			  (print acc)
			  (print (dotimes (i 3 cnt) (setq cnt (+ cnt 2))))
			  (print (dotimes (i 2) (setq acc (setf (aref a i) 0.1))))
			  (print acc))
			(let ((g (make-array 3 :initial-element 1)) (s 0.0))
			  (dotimes (i 3) (setq s (+ s (aref g i))))
			  (print s))
			(let ((a (make-array 3 :element-type 'double-float :initial-element 0.0)) (hits 0.0))
			  (setf (aref a 1) (/ 0.0 0.0))
			  (dotimes (i 3) (unless (< (aref a i) 1.0) (setq hits (+ hits 1.0))))
			  (print hits)
			  (dotimes (i 3) (when (> (aref a i) -1.0) (setq hits (+ hits 10.0))))
			  (print hits))
			(let ((z 0.0) (n 0) (a (make-array 3 :element-type 'single-float :initial-element 1.0)))
			  (handler-case (dotimes (i 10) (setq z (+ z (aref a i))) (setq n (+ n 1)))
			    (error (e) (print 'caught)))
			  (print z)
			  (print n))
			(defun tl-dsum (v n)
			  (let ((sum 0.0d0))
			    (declare (type double-float sum))
			    (dotimes (i n)
			      (setq sum (+ sum (* (aref v i) (aref v i)))))
			    sum))
			(let ((dv (make-array 4 :element-type 'double-float :initial-element 0.0d0))
			      (sv (make-array 4 :element-type 'single-float :initial-element 0.0)))
			  (dotimes (i 4) (setf (aref dv i) (+ i 1.5d0)))
			  (dotimes (i 4) (setf (aref sv i) (+ i 1.5)))
			  (print (tl-dsum dv 4))
			  (print (tl-dsum sv 4)))
			(let ((a (make-array 3 :element-type 'double-float :initial-element 1.0d0)))
			  (let ((acc 0.0d0) (scale 2.0d0))
			    (declare (type double-float acc scale))
			    (dotimes (i 3) (setq acc (+ acc (* scale (aref a i)))))
			    (print acc)
			    (dotimes (i 3) (setq acc (+ acc 1)))
			    (print acc)
			    (handler-case (dotimes (i 10) (setq acc (+ acc (* scale (aref a i)))))
			      (error (e) (print 'caught-raw)))
			    (print acc)))
			""";

	private static final String TYPED_LOOP_EXPECTED = """
			(0.875 4.049147766510589)
			#f(0.10295056 0.12263946 0.1460938 0.17403367 0.20731696 0.24696554 0.0 0.0)
			(9.800000190734863 1.32729250931335)
			#f((0.0 0.0 0.0) (0.0 0.0 0.0) (1.5 1.5 1.5) (0.0 0.0 0.0))
			#d((0.0 0.0 3.0 0.0) (0.0 0.0 3.0 0.0) (0.0 0.0 3.0 0.0))
			#f(0.0 1.25 0.5 2.75 1.0 4.25)
			4
			8.0
			NIL
			8.0
			6
			NIL
			0.1
			3.0
			1.0
			21.0
			CAUGHT
			3.0
			3
			41.0
			41.0
			6.0
			9.0
			CAUGHT-RAW
			15.0""";

	@Test
	void typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem() throws Exception {
		// The typed dotimes emission is a speculation with the ordinary expansion as its
		// fallback; this pins that it answers what the fallback answers, bit for bit,
		// across the shapes it takes and the guards it fails -- and that --optimize=size
		// (which declines the speed-for-size trades) compiles the same program to
		// different bytes, i.e. the default build really did emit the typed loops.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(TYPED_LOOP_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(small).isNotEqualTo(fast);
		assertThat(runClass(fast)).isEqualTo(TYPED_LOOP_EXPECTED);
		assertThat(runClass(small)).isEqualTo(TYPED_LOOP_EXPECTED);
	}

	/**
	 * The integer-fusion program: every shape {@code JvmIntFusionCompiler} takes (the
	 * masked-wrap mod32+/rol32 pair through inlinable-defun substitution, a fused loop
	 * over them, overflow promotion past {@code long}, float and nil leaves bailing to
	 * the generic path, the mod/rem/ash sign semantics and strength reductions, a fused
	 * flet-lambda call, side-effects-once under substitution, the raw-local nil re-read
	 * that pins the sentinel design, packed integer-vector raw reads and their
	 * out-of-range error shape, NaN comparisons in condition and value position, the
	 * zero-divisor error shape, 1+/1- normalization, a literal ldb, and a loop head's
	 * fused compare).
	 */
	private static final String DOUBLE_ARITH_PROGRAM = """
			(let ((x 3.0d0) (y 2.0d0) (z 1.0d0))
			  (print (+ (* 2.0d0 x y) z))
			  (print (- (/ 1.0d0 x) y))
			  (print (- (* x y) z))
			  (print (+ (- x y) z))
			  (print (> (+ (* x x) (* y y)) 4.0d0))
			  (print (< (+ (* x x) (* y y)) 4.0d0)))
			(print (* 3 1.5d0))
			(print (+ 1/2 1.0d0))
			(print (- 0.0d0))
			(print (+ -0.0d0 0.0d0))
			(let ((nan (/ 0.0d0 0.0d0)))
			  (print (list (> nan 4.0d0) (< nan 4.0d0) (= nan nan) (>= nan nan))))
			(let ((a 5) (b 2) (c 1.5d0))
			  (print (+ (- a b) c)))
			(let ((big 100000000000000000000) (f 1.0d0))
			  (print (+ (* big 2) 1))
			  (print (+ big f)))
			(let ((z 0.0d0))
			  (dotimes (i 3) (setq z (+ z 1.5d0)))
			  (print z))
			(let ((n 0))
			  (dotimes (i 5) (setq n (+ n i)))
			  (print n))
			(let ((a 0) (b 0))
			  (dotimes (i 3) (setq a (+ a 1) b (+ b 2)))
			  (print (list a b)))
			(let ((q 0))
			  (print (progn (setq q 1) (setq q 2)))
			  (print q))
			(print (max 1.5d0 (+ 1 1.0d0)))
			(print (min 1.5d0 (* 2 1.0d0)))
			(print (abs (- 0.0d0 2.5d0)))
			(print (sqrt (* 2.0d0 8.0d0)))
			(print (expt (+ 1.0d0 1.0d0) 10))
			(let ((a 7.5d0) (b -7.5d0))
			  (print (mod a 2))
			  (print (mod b 2))
			  (print (rem a 2))
			  (print (rem b 2)))
			(print (mod (+ 7.0d0 0.0d0) 3))
			(print (rem -7.5d0 2))
			""";

	private static final String DOUBLE_ARITH_EXPECTED = """
			13.0
			-1.6666666666666667
			5.0
			2.0
			T
			NIL
			4.5
			1.5
			-0.0
			0.0
			(NIL NIL NIL NIL)
			4.5
			200000000000000000001
			1.0e20
			4.5
			10
			(3 6)
			2
			2
			2.0
			1.5
			2.5
			4.0
			1024.0
			1.5
			0.5
			1.5
			-1.5
			1.0
			-1.5""";

	@Test
	void doubleArithmeticMatchesTheInterpreterOnBothOptimizeLevels() throws Exception {
		// The double-literal path emits raw IEEE arithmetic and the fused methods carry
		// an all-Double fast path beside the all-Long one; both are optimizations whose
		// fallback is the generic helper, so every shape here must answer exactly what
		// the interpreter and the wasm backends answer -- literal widening, -0.0, the
		// NaN comparison rule, a mixed Long/Double tree that BAILS off both fast paths,
		// a bignum leaf, and mod/rem over floats (the generic helpers' own double
		// branch, which the fused fallback lands in).
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(DOUBLE_ARITH_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(runClass(fast)).isEqualTo(DOUBLE_ARITH_EXPECTED);
		assertThat(runClass(small)).isEqualTo(DOUBLE_ARITH_EXPECTED);
	}

	private static final String DECLARED_FLOAT_PROGRAM = """
			(defun dcl-acc (a b)
			  (declare (type double-float a b))
			  (let ((acc 0.0d0) (tmp 0.0d0))
			    (declare (type double-float acc tmp))
			    (dotimes (i 5)
			      (setq tmp (* a b))
			      (setq acc (+ acc tmp (- a b) (/ a b) (mod a b))))
			    acc))
			(defun udl-acc (a b)
			  (let ((acc 0.0d0) (tmp 0.0d0))
			    (dotimes (i 5)
			      (setq tmp (* a b))
			      (setq acc (+ acc tmp (- a b) (/ a b) (mod a b))))
			    acc))
			(print (dcl-acc 3.5d0 -1.25d0))
			(print (= (dcl-acc 3.5d0 -1.25d0) (udl-acc 3.5d0 -1.25d0)))
			(let ((z 0.0d0))
			  (declare (type double-float z))
			  (print (- z)))
			(let ((nan (/ 0.0d0 0.0d0)) (c 0))
			  (declare (type double-float nan))
			  (if (< nan 1.0d0) (setq c 1) (setq c 2))
			  (print c)
			  (print (>= nan 0.0d0))
			  (print (= nan nan)))
			(let ((x 1.5d0))
			  (declare (type double-float x))
			  (print (+ x 1/2))
			  (print (+ x 200000000000000000000))
			  (print (* x 4)))
			(defun dcl-capture (x)
			  (declare (type double-float x))
			  (let ((s x))
			    (declare (type double-float s))
			    (let ((fn (lambda () s)))
			      (setq s (+ s 1.0d0))
			      (funcall fn))))
			(print (dcl-capture 2.0d0))
			(defvar *dspec* 1.5d0)
			(defun dspec-read () *dspec*)
			(print (let ((*dspec* 2.5d0)) (declare (type double-float *dspec*)) (dspec-read)))
			(let ((v 1.0d0))
			  (declare (type double-float v))
			  (let ((v "str")) (print (length v)))
			  (print v))
			(let ((w 3.0d0))
			  (declare (type double-float w))
			  (print ((lambda (w) (length w)) "abcd"))
			  (print w))
			(let* ((p 1.5d0) (q (+ p 1.0d0)))
			  (declare (type double-float p q))
			  (print (+ p q)))
			(let ((m 2.0d0) (n 0.0d0))
			  (declare (type double-float m n))
			  (print (setq m 4.5d0 n (* m 2.0d0)))
			  (print m))
			(let ((acc 1.0d0))
			  (declare (type double-float acc))
			  (print (dotimes (i 10 acc) (when (> acc 5.0d0) (return acc)) (setq acc (* acc 2.0d0)))))
			(let ((acc 0.0d0))
			  (declare (type double-float acc))
			  (print (handler-case (progn (setq acc 5.5d0) (error "boom")) (error (e) acc))))
			(let ((acc 1.0d0))
			  (declare (type double-float acc))
			  (incf acc 1.5d0)
			  (setf acc (* acc 2.0d0))
			  (print acc))
			(let ((r 0.5d0))
			  (declare (type single-float r))
			  (print (* r 2)))
			(defun dcl-widen ()
			  (let ((x 2.0d0)) (declare (type double-float x)) (setq x 1) x))
			(print (dcl-widen))
			(defun dcl-store-trap ()
			  (let ((x 1.0d0)) (declare (type double-float x)) (setq x (car (list 5))) x))
			(print (handler-case (dcl-store-trap) (error (e) :store-trap)))
			(print (handler-case
			         (let ((tlx 1.0d0)) (declare (type double-float tlx)) (setq tlx (car (list 5))) tlx)
			         (error (e) :top-level-store-trap)))
			(defun dcl-read-trap (x) (declare (type double-float x)) (* x x))
			(print (handler-case (dcl-read-trap (car (list 3))) (error (e) :read-trap)))
			(print (dcl-read-trap 3.0d0))
			(let ((k 2.5d0))
			  (declare (type double-float k))
			  (print (round (* k 2)))
			  (print (sqrt (* k k))))
			(defun dcl-hc-shadow ()
			  (let ((d 1.0d0))
			    (declare (type double-float d))
			    (setq d 2.0d0)
			    (handler-case (error "boom") (error (d) (format nil "~a" d)))))
			(print (dcl-hc-shadow))
			(defun int-hc-shadow ()
			  (let ((n 1))
			    (setq n 2)
			    (handler-case (error "boom") (error (n) (format nil "~a" n)))))
			(print (int-hc-shadow))
			""";

	private static final String DECLARED_FLOAT_EXPECTED = """
			-13.375
			T
			-0.0
			2
			NIL
			NIL
			2.0
			2.0e20
			6.0
			3.0
			2.5
			3
			1.0
			4
			3.0
			4.0
			9.0
			4.5
			8.0
			5.5
			5.0
			1.0
			1.0
			:STORE-TRAP
			5
			:READ-TRAP
			9.0
			5
			2.5
			"boom"
			"boom"\
			""";

	@Test
	void declaredFloatLocalsMatchTheUndeclaredEmissionOnBothOptimizeLevels() throws Exception {
		// A (declare (type double-float ...)) routes the emitters the double-literal
		// path routes and keeps the declared let locals in raw double slots
		// (.kb/jvm-double-arithmetic.md). A TRUE declaration never changes an answer:
		// the declared accumulator equals its undeclared twin, -0.0 and the NaN rules
		// survive, a captured or special or shadowed declared name keeps the boxed
		// semantics, and ratio/bignum operands keep float contagion. A FALSE
		// declaration is the pinned UB shape (.kb/declarations-type-checks.md): a
		// deterministic catchable error at the declared store or routed read inside a
		// function (:STORE-TRAP / :READ-TRAP), the interpreter's answer at top level
		// (bindings there keep the boxed representation), and compile-time widening for
		// an integer LITERAL assigned to a declared float.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(DECLARED_FLOAT_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(runClass(fast)).isEqualTo(DECLARED_FLOAT_EXPECTED);
		assertThat(runClass(small)).isEqualTo(DECLARED_FLOAT_EXPECTED);
	}

	private static final String INT_FUSION_PROGRAM = """
			(defun mod32+ (a b) (logand (+ a b) 4294967295))
			(defun rol32 (x s) (logand (logior (ash x s) (ash x (- s 32))) 4294967295))
			(print (mod32+ 4294967295 10))
			(print (rol32 2882400001 8))
			(let ((s 0))
			  (dotimes (i 64) (setq s (mod32+ s (rol32 (+ i 12345) (mod i 31)))))
			  (print s))
			(let ((big 3037000500))
			  (print (* big big))
			  (print (+ (* big big) 1)))
			(let ((x 1.5))
			  (print (+ x 2))
			  (print (< x 2)))
			(let ((v nil))
			  (setq v 7)
			  (setq v nil)
			  (print v))
			(print (mod -7 3))
			(print (mod 7 -3))
			(print (rem -7 3))
			(print (rem 7 -3))
			(print (ash -17 -2))
			(print (ash 3 62))
			(print (lognot 0))
			(flet ((mix (a b) (logand (+ (* a 31) b) 65535)))
			  (let ((h 0))
			    (dotimes (i 20) (setq h (mix h i)))
			    (print h)))
			(defvar *calls* 0)
			(defun bump () (setq *calls* (+ *calls* 1)) *calls*)
			(print (mod32+ (bump) (bump)))
			(print *calls*)
			(let ((v (make-array 8 :element-type '(unsigned-byte 32))))
			  (dotimes (i 8) (setf (aref v i) (* i 1000000007)))
			  (let ((s 0))
			    (dotimes (i 8) (setq s (+ s (aref v i))))
			    (print s))
			  (handler-case (print (+ (aref v 100) 1)) (error (e) (print 'oob))))
			(let ((nan (/ 0.0 0.0)) (c 0))
			  (if (< nan 1) (setq c 1) (setq c 2))
			  (print c)
			  (print (>= nan 0)))
			(let ((z 0))
			  (handler-case (print (mod (+ z 5) z)) (error (e) (print 'divzero))))
			(let ((k 10))
			  (print (1+ (* k k)))
			  (print (1- (* k k))))
			(print (ldb (byte 8 8) 305419896))
			(print (loop for i from 1 to 10 sum i))
			(let ((a (make-array 5)))
			  (dotimes (i 5) (setf (aref a i) (* i 7)))
			  (print (+ (aref a 3) 1))
			  (print (+ (aref a (random 1)) 100))
			  (let ((k 4)) (print (+ (aref a k) 2)))
			  (handler-case (print (+ (aref a 9) 1)) (error (e) (print 'aref-oob)))
			  (handler-case (print (+ (aref a 1.0) 1)) (error (e) (print 'aref-float))))
			(let ((a (make-array 3)))
			  (handler-case (print (+ (aref a 0) 1)) (error (e) (print 'aref-nil))))
			(let ((a (make-array 3)))
			  (setf (aref a 0) "s")
			  (setf (aref a 1) 5)
			  (print (+ (aref a 1) 1)))
			(let ((base (make-array 5)))
			  (dotimes (i 5) (setf (aref base i) (* i 3)))
			  (let ((d (make-array 2 :displaced-to base :displaced-index-offset 1)))
			    (print (+ (aref d 1) 0))))
			(handler-case (print (+ 1 (aref "abc" 1))) (error (e) (print 'aref-string)))
			(print (+ 100 (random 1)))
			(let ((lim 1)) (print (+ 200 (random lim))))
			(let ((lim 1.0)) (print (floatp (random lim))))
			(let ((r (random 1000))) (print (and (>= r 0) (< r 1000))))
			(print (* 4611686018427387904 (+ 2 (random 1))))
			(let ((v (make-array 4 :element-type '(unsigned-byte 8))))
			  (dotimes (i 4) (setf (aref v i) (* i 3)))
			  (print (+ (aref v (random 1)) 50)))
			(defun fused-dif (x) (- x x))
			(let ((flim 1000000.0)) (print (fused-dif (random flim))))
			(let ((ilim 1000000)) (print (fused-dif (random ilim))))
			""";

	private static final String INT_FUSION_EXPECTED = """
			9
			3454992811
			2147508531
			9223372037000250000
			9223372037000250001
			3.5
			T
			NIL
			2
			-2
			-1
			1
			-5
			13835058055282163712
			-1
			46090
			3
			2
			15115098308
			OOB
			2
			NIL
			DIVZERO
			101
			99
			86
			55
			22
			100
			30
			AREF-OOB
			AREF-FLOAT
			AREF-NIL
			6
			6
			AREF-STRING
			100
			200
			T
			T
			9223372036854775808
			50
			0.0
			0""";

	private static final String RAW_GLOBAL_PROGRAM = """
			(defparameter s 0)
			(defvar v 10)
			(defvar v 99)
			(dotimes (i 5) (setq s (+ s i)))
			(print s)
			(print v)
			(setq s 1.5d0)
			(print s)
			(setq s (+ s 1))
			(print s)
			(setq s "str")
			(print s)
			(setq s nil)
			(print s)
			(setq s 7)
			(print s)
			(print (let ((s 100)) (setq s (+ s 1)) s))
			(print s)
			(defparameter big 0)
			(setq big (* 4611686018427387904 4))
			(print big)
			(setq big (+ big 1))
			(print big)
			(defun bump () (setq s (+ s 10)) s)
			(print (bump))
			(print s)
			(defparameter arr (make-array 3))
			(defparameter acc 0)
			(dotimes (i 3) (setf (aref arr i) (* i i)))
			(dotimes (i 3) (setq acc (+ acc (aref arr i))))
			(print acc)
			(setq g 0)
			(dotimes (i 3) (setq g (+ g i)))
			(print g)
			(print (let ((g 100)) (setq g (+ g 1)) g))
			(print g)
			""";

	private static final String RAW_GLOBAL_EXPECTED = """
			10
			10
			1.5
			2.5
			"str"
			NIL
			7
			101
			7
			18446744073709551616
			18446744073709551617
			17
			17
			5
			3
			101
			3""";

	@Test
	void unboxedTopLevelGlobalsAnswerWhatTheBoxedStaticFieldAnswers() throws Exception {
		// A promoted top-level global carrying the dual representation
		// (.kb/jvm-int-fusion.md): a raw long field and an int flag beside the _g$
		// field, which stays the boxed shadow. Every tier the shadow used to hold has
		// to read back unchanged -- a float, a string, nil, a bignum promotion out of
		// long range -- and a LEXICAL binding of the same name (g, promoted by a
		// top-level setq, so not special) must still win over the global, both to read
		// and to assign. In the same program s declines, because the let that names it
		// binds it DYNAMICALLY (a defparameter name is special), which pins that one
		// global's representation does not follow another's. --optimize=size declines
		// the trade, so the same program compiles without the extra fields and answers
		// the same.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(RAW_GLOBAL_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(declaredFieldNames(fast)).contains("_gr$G", "_gk$G", "_gr$ACC", "_gr$BIG", "_g$S");
		assertThat(declaredFieldNames(fast)).noneMatch(name -> name.equals("_gr$S"));
		assertThat(declaredFieldNames(small)).noneMatch(name -> name.startsWith("_gr$"));
		assertThat(runClass(fast)).isEqualTo(RAW_GLOBAL_EXPECTED);
		assertThat(runClass(small)).isEqualTo(RAW_GLOBAL_EXPECTED);
	}

	@Test
	void aDynamicallyBoundSpecialAndAnEvaldGlobalDeclineTheUnboxedRepresentation() throws Exception {
		// The two seams a let local does not have. A special some let binds keeps its
		// save/restore over the ONE _g$ field, and an eval runtime mirrors the BOX into
		// _genv -- so neither name may split into a triple. Both programs assign an
		// integer in a loop, which is the shape that would otherwise qualify.
		List<LispVal> dynamicBinding = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(defparameter *n* 0)
				(defun show () *n*)
				(setq *n* (+ *n* 1))
				(print (show))
				(let ((*n* 100))
				  (print (show))
				  (setq *n* (+ *n* 1))
				  (print (show)))
				(print (show))
				"""));
		byte[] dynamicBindingClass = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(dynamicBinding);
		assertThat(declaredFieldNames(dynamicBindingClass)).noneMatch(name -> name.startsWith("_gr$"));
		assertThat(runClass(dynamicBindingClass)).isEqualTo("""
				1
				100
				101
				1""");
		List<LispVal> evaluated = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(defparameter s 0)
				(dotimes (i 5) (setq s (+ s i)))
				(print (eval 's))
				(setq s (+ s 100))
				(print (eval 's))
				"""));
		byte[] evaluatedClass = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(evaluated);
		assertThat(declaredFieldNames(evaluatedClass)).noneMatch(name -> name.startsWith("_gr$"));
		assertThat(runClass(evaluatedClass)).isEqualTo("""
				10
				110""");
	}

	@Test
	void fusedIntegerExpressionTreesMatchTheGenericPath() throws Exception {
		// The fused emission is an optimization with a total fallback; this pins that
		// it answers what the generic per-op path answers, bit for bit, across the
		// shapes it takes and the bails it survives -- and that --optimize=size (which
		// declines the speed-for-size trades) compiles the same program to different
		// bytes, i.e. the default build really did emit the fused methods.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(INT_FUSION_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(small).isNotEqualTo(fast);
		assertThat(declaredMethodNames(fast)).anyMatch(name -> name.startsWith("_fx$"));
		assertThat(declaredMethodNames(small)).noneMatch(name -> name.startsWith("_fx$"));
		assertThat(runClass(fast)).isEqualTo(INT_FUSION_EXPECTED);
		assertThat(runClass(small)).isEqualTo(INT_FUSION_EXPECTED);
	}

	private static final String GENERAL_ARRAY_LEAF_PROGRAM = """
			(defparameter *a* (make-array 6))
			(dotimes (i 6) (setf (aref *a* i) (* i 11)))
			(let ((s 0))
			  (dotimes (i 6) (setq s (+ s (aref *a* i))))
			  (print s))
			(print (+ (aref *a* 5) (aref *a* (random 1))))
			(let ((fp (make-array 4 :fill-pointer 0)))
			  (vector-push 3 fp)
			  (print (+ (aref fp 0) 1)))
			(let ((adj (make-array 2 :adjustable t :initial-element 4)))
			  (print (+ (aref adj 1) 1)))
			(print (+ 7 (random 1)))
			""";

	private static final String GENERAL_ARRAY_LEAF_EXPECTED = """
			165
			55
			4
			5
			7""";

	@Test
	void fusedArefLeavesReadTheGeneralArraysPackedShapeAndBailForEveryOther() throws Exception {
		// The packed-aref leaf's OTHER representation: the general array's length-6
		// header over a flat long[] (.kb/adjustable-arrays.md), in a program that holds
		// no packed integer vector at all -- so the leaf emits only the ArrayList
		// dispatch. A fill-pointered, an adjustable and a non-packed array all take the
		// bail into the same _aref1 the unfused emission would have called, and answer
		// what the size level (which emits no fused site) answers.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(GENERAL_ARRAY_LEAF_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(declaredMethodNames(fast)).anyMatch(name -> name.startsWith("_fx$"));
		assertThat(declaredMethodNames(small)).noneMatch(name -> name.startsWith("_fx$"));
		assertThat(runClass(fast)).isEqualTo(GENERAL_ARRAY_LEAF_EXPECTED);
		assertThat(runClass(small)).isEqualTo(GENERAL_ARRAY_LEAF_EXPECTED);
	}

	private static final String COUNTED_STEP_PROGRAM = """
			(defun step-up (n)
			  (let ((i (- 9223372036854775807 2)))
			    (dotimes (k n) (setq i (+ i 1)))
			    i))
			(defun step-down (n)
			  (let ((i (+ -9223372036854775808 2)))
			    (dotimes (k n) (setq i (- i 1)))
			    i))
			(print (step-up 2))
			(print (step-up 3))
			(print (step-up 5))
			(print (step-down 2))
			(print (step-down 3))
			(print (step-down 5))
			(let ((i 1))
			  (dotimes (k 5) (setq i (* i 1000000000000)))
			  (setq i (+ i 1))
			  (print i))
			(print (loop for i from 1 to 5 collect (1+ i)))
			(print (loop for i from 5 downto 1 collect (1- i)))
			(let ((i 0))
			  (setq i (+ i 3))
			  (setq i (- i 5))
			  (setq i (+ 7 i))
			  (print i))
			(let ((i 0))
			  (dotimes (k 3) (setq i (+ i 1)) (setq i (- i 2)))
			  (print i))
			(defparameter *g* (- 9223372036854775807 2))
			(dotimes (k 5) (setq *g* (+ *g* 1)))
			(print *g*)
			(defparameter *h* 0)
			(dotimes (k 4) (setq *h* (- *h* 3)))
			(print *h*)
			""";

	private static final String COUNTED_STEP_EXPECTED = """
			9223372036854775807
			9223372036854775808
			9223372036854775810
			-9223372036854775808
			-9223372036854775809
			-9223372036854775811
			1000000000000000000000000000000000000000000000000000000000001
			(2 3 4 5 6)
			(4 3 2 1 0)
			5
			-3
			9223372036854775810
			-12""";

	@Test
	void aCountedLoopStepPromotesAtTheFixnumBoundaryAndKeepsSteppingOnABignum() throws Exception {
		// The counted-loop step (+ i c) / (- i c) into an unboxed local is emitted
		// inline as raw long arithmetic, with the outlined fused method kept as the
		// fallback for the two cases the inline form declines: a raw slot the flag says
		// is stale, and a step that would overflow (.kb/jvm-int-fusion.md). Both are
		// crossed here -- a counter walked ACROSS most-positive-fixnum and
		// most-negative-fixnum in both directions, and a local that already holds a
		// bignum when the next step runs -- and the answer must stay what the generic
		// path (--optimize=size, which emits no fused site at all) answers. The last
		// two loops step a PROMOTED GLOBAL, whose triple lives in class fields rather
		// than local slots: the inline step must read the flag and the raw half through
		// the field-aware emitters, not as slots.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(COUNTED_STEP_PROGRAM));
		byte[] fast = new JvmLispCompiler("Test", false, OptimizeLevel.DEFAULT).compile(program);
		byte[] small = new JvmLispCompiler("Test", false, OptimizeLevel.SIZE).compile(program);
		assertThat(runClass(fast)).isEqualTo(COUNTED_STEP_EXPECTED);
		assertThat(runClass(small)).isEqualTo(COUNTED_STEP_EXPECTED);
	}

	private String runClass(byte[] classBytes) throws Exception {
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
	void aTopLevelLexicalIsNotMirroredIntoTheEvalGlobalEnv() throws Exception {
		// The eval mirror (_store into _genv) exists so an eval'd form can read a
		// variable the compiled program assigned at top level. A LEXICAL of a top-level
		// form is not one: CL's eval resolves against the null lexical environment, so
		// no eval'd form can name a top-level let/loop variable -- nor the temporaries
		// the macro expanders generate, which are symbols in no package at all. Each
		// mirror costs an _envLookup, a linear walk of the eval global alist, per
		// assignment -- so a top-level loop paid one per iteration per assigned name.
		String program = """
				(setq mirrored-global 0)
				(let ((probe-lexical 0)) (setq probe-lexical 1) (print probe-lexical))
				(print (loop for probe-counter from 1 to 3 sum probe-counter))
				(eval '(print mirrored-global))
				""";
		byte[] classBytes = new JvmLispCompiler("Test").compile(LispReader.readAllFromString(program));
		// Exactly one: the top-level (setq mirrored-global 0). The let variable, the
		// loop counter and the loop's accumulator temporary emit none.
		assertThat(evalStoreCallsInTopLevel(classBytes)).isEqualTo(1);
		assertThat(runClass(classBytes)).isEqualTo("1\n6\n0");
	}

	@Test
	void aProgramThatNeverMentionsEvalCarriesNoEvalRuntime() throws Exception {
		// The map*/every/some wrapper bodies are (apply f ...), so injecting them into
		// every program left the finished class calling an _apply it had not declared --
		// and the post-compile self-check answered that by forcing the eval runtime on.
		// Free while --optimize shook the unreferenced wrappers back out, and anything
		// but free once the program had a top-level global: the forced gate made the
		// mirror real, so one setq turned a 4 KB class into a 34 KB one.
		String program = """
				(defvar *counter* 0)
				(setq *counter* 1)
				(print *counter*)
				""";
		byte[] classBytes = new JvmLispCompiler("Test").compile(LispReader.readAllFromString(program));
		assertThat(declaredMethodNames(classBytes)).doesNotContain("_eval", "_apply", "_store", "_envLookup",
				"_lookup");
		assertThat(classBytes.length).isLessThan(8_000);
		assertThat(runClass(classBytes)).isEqualTo("1");
	}

	@Test
	void namingOneOfTheApplyingWrappersBringsTheEvalRuntimeBack() throws Exception {
		// The wrappers are gated on the reference, not deleted: both spellings of the
		// designator inject the wrapper AND force the runtime its body calls, so a
		// program that takes one as a value still works. #'name is the one the reader
		// writes; 'name is the one FunctionDesignators normalizes into it.
		for (String designator : List.of("#'mapcar", "'mapcar")) {
			byte[] classBytes = new JvmLispCompiler("Test")
				.compile(LispReader.readAllFromString("(print (funcall " + designator + " #'1+ '(1 2 3)))"));
			assertThat(declaredMethodNames(classBytes)).contains("_apply");
			assertThat(runClass(classBytes)).isEqualTo("(2 3 4)");
		}
	}

	@Test
	void aComputedDesignatorStillResolvesASymbolThroughTheRegistry() throws Exception {
		// The name registry used to ride along on the always-on eval gate. It is now
		// gated on the arities Pass 2 dispatched through, because an operator that calls
		// a designator it cannot read -- mapcar here, but sort/remove-if/find-if are the
		// same site -- may be handed a SYMBOL at run time, and only _lookup answers one.
		String program = """
				(defun pred (x) (evenp x))
				(print (mapcar (car (list 'pred)) '(2 3 4)))
				""";
		byte[] classBytes = new JvmLispCompiler("Test").compile(LispReader.readAllFromString(program));
		assertThat(runClass(classBytes)).isEqualTo("(T NIL T)");
	}

	/** Every method the class declares, in declaration order. */
	private static List<String> declaredFieldNames(byte[] classBytes) {
		return java.lang.classfile.ClassFile.of()
			.parse(classBytes)
			.fields()
			.stream()
			.map(field -> field.fieldName().stringValue())
			.toList();
	}

	private static List<String> declaredMethodNames(byte[] classBytes) {
		return java.lang.classfile.ClassFile.of()
			.parse(classBytes)
			.methods()
			.stream()
			.map(method -> method.methodName().stringValue())
			.toList();
	}

	/**
	 * Counts the {@code _store} calls the top-level body emits -- the eval-global mirror.
	 * Only the top-level methods are walked ({@code main} plus the {@code _top$N} chunks
	 * it is split into): the eval runtime's own {@code _eval}/{@code _apply} bodies call
	 * {@code _store} too, and always will.
	 */
	private static int evalStoreCallsInTopLevel(byte[] classBytes) {
		int calls = 0;
		for (java.lang.classfile.MethodModel method : java.lang.classfile.ClassFile.of().parse(classBytes).methods()) {
			String name = method.methodName().stringValue();
			if (!name.equals("main") && !name.startsWith("_top$")) {
				continue;
			}
			for (java.lang.classfile.CodeElement element : method.code().orElseThrow()) {
				if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
						&& invoke.name().equalsString("_store")) {
					calls++;
				}
			}
		}
		return calls;
	}

}
