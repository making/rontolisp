package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL contextl sources (vendored unmodified under
 * {@code src/test/resources/contextl}, MIT; quicklisp dist contextl-20241012-git, with
 * its lw-compat dependency beside it) load through {@code asdf:load-system}, layer GC
 * included, and run on ALL FOUR backends via {@link AsdfLibraryE2eSupport}, in
 * {@code contextl-user}.
 *
 * <p>
 * The exercise is the layer activation contextl is built around: {@code deflayer} (a
 * sublayer included), {@code define-layered-function}/{@code define-layered-method} with
 * {@code :in} a layer, an {@code :around}, {@code call-next-method},
 * {@code with-active-layers} / {@code with-inactive-layers} in every order demo3 asserts,
 * {@code ensure-active-layer} / {@code ensure-inactive-layer}, {@code active-layers},
 * {@code layer-active-p}, the layer context cache (an {@code :after} method on
 * {@code adjoin-layer-using-class} runs once per new context), and contextl's own
 * {@code test/grouped-layers.lisp}: a {@code standard-layer-class} subclass whose
 * {@code adjoin-layer-using-class}/{@code remove-layer-using-class} methods read layer
 * slots the singleton slot definition makes class-allocated, and special slots (a
 * {@code special-class} instance's {@code dletf}, captured and re-entered dynamic
 * environments, {@code slot-boundp}/{@code slot-makunbound}) through the slot-access
 * protocol ({@code .kb/clos.md}), as its {@code test/dynenv.lisp} does, and special layer
 * slots bound by a layer activation's initargs, singleton classes and layers redefined
 * with and without {@code :reinitialize t} slots, and a layer forgotten with
 * {@code layer-makunbound}, as its {@code test/spx.lisp} does. Every activation makes an
 * anonymous {@code standard-layer-class} at RUN time and dispatches on its prototype,
 * which prints through contextl's {@code print-object} method ({@code .kb/clos.md},
 * "Classes made at run time"). The last line prints {@code contextl-user} symbols inside
 * a form that activates a layer, which the interpreter used to spell qualified
 * ({@code .kb/pretty-printer.md}, "The package qualifier follows `*package*`
 * accessibility"). Then contextl's {@code test/demo3.lisp} and figure editor:
 * {@code define-layered-class} with layered accessors, defined again {@code :in} a layer
 * (partial classes, one gaining a superclass, one giving an existing instance a special
 * slot), {@code slot-value-using-layer} over a {@code :layered} slot, and
 * {@code call-next-method} a macro builds or a method passes as a value
 * ({@code .kb/clos.md}, "Layered classes").
 */
class ContextlE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "contextl")
		.toAbsolutePath()
		.toString();

	private static final String LW_COMPAT_DIR = Path.of("src", "test", "resources", "lw-compat")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system "contextl")
			(in-package :contextl-user)
			(define-layered-function test ())
			(define-layered-method test :in t () (list :root))
			(deflayer info-layer)
			(deflayer employment-layer)
			(define-layered-method test :in info-layer () (list* :info (call-next-method)))
			(define-layered-method test :in employment-layer () (list* :employment (call-next-method)))
			(print (test))
			(print (with-active-layers (info-layer) (test)))
			(print (with-active-layers (info-layer employment-layer) (test)))
			(print (with-active-layers (employment-layer info-layer) (test)))
			(print (with-active-layers (info-layer employment-layer info-layer) (test)))
			(print (with-active-layers (info-layer employment-layer) (with-inactive-layers (info-layer) (test))))
			(print (with-active-layers (info-layer employment-layer) (with-inactive-layers (employment-layer) (test))))
			(print (with-active-layers (employment-layer employment-layer) (test)))
			(print (with-active-layers (info-layer employment-layer)
			         (mapcar (lambda (layer) (string (layer-name layer))) (active-layers))))
			(print (list (layer-active-p 'info-layer) (with-active-layers (info-layer) (layer-active-p 'info-layer))))
			(define-layered-function greet (who))
			(define-layered-method greet (who) (format nil "Hello, ~A" who))
			(deflayer polite)
			(deflayer very-polite (polite))
			(define-layered-method greet :in polite :around (who) (concatenate 'string (call-next-method) ", please"))
			(define-layered-method greet :in very-polite (who) (concatenate 'string "Dear " (call-next-method)))
			(print (list (greet "Ann") (with-active-layers (polite) (greet "Bob"))
			             (with-active-layers (very-polite) (greet "Cy"))))
			(defvar *adjoined* nil)
			(defclass counted-layer (standard-layer-class) ())
			(define-layered-method adjoin-layer-using-class :after ((class counted-layer) active-context)
			  (push :adjoined *adjoined*))
			(deflayer counted () () (:metaclass counted-layer))
			(dotimes (i 3) (with-active-layers (counted) nil))
			(print *adjoined*)
			(ensure-active-layer 'info-layer)
			(print (test))
			(ensure-inactive-layer 'info-layer)
			(print (test))
			(defclass grouped-layer (standard-layer-class) ())
			(defgeneric group-root (layer))
			(defgeneric default-layer (layer))
			(define-layered-method adjoin-layer-using-class ((to-add grouped-layer) active-layers)
			  (call-next-layered-method to-add (remove-layer (group-root (find-layer to-add)) active-layers)))
			(define-layered-method remove-layer-using-class ((to-remove grouped-layer) active-layers)
			  (declare (ignore active-layers))
			  (multiple-value-bind (new-layers cacheablep) (call-next-method)
			    (values (adjoin-layer (default-layer (find-layer to-remove)) new-layers) cacheablep)))
			(deflayer output ()
			  ((group-root :initform 'output :reader group-root)
			   (default-layer :initform 'plain-output :reader default-layer)))
			(deflayer plain-output (output) () (:metaclass grouped-layer))
			(deflayer html-output (output) () (:metaclass grouped-layer))
			(deflayer xml-output (output) () (:metaclass grouped-layer))
			(define-layered-function make-output ()
			  (:method () (list :output))
			  (:method :in plain-output () (list* :plain (call-next-method)))
			  (:method :in html-output () (list* :html (call-next-method)))
			  (:method :in xml-output () (list* :xml (call-next-method))))
			(print (mapcar #'slot-definition-allocation (class-direct-slots (find-layer-class 'output))))
			(print (list (eq (group-root (find-layer 'xml-output)) 'output)
			             (eq (default-layer (find-layer 'html-output)) 'plain-output)))
			(print (list (make-output)
			             (with-active-layers (plain-output)
			               (list (make-output)
			                     (with-active-layers (html-output)
			                       (list (make-output)
			                             (with-active-layers (xml-output)
			                               (list (make-output)
			                                     (with-inactive-layers (xml-output) (make-output))))))))))
			(defclass dummy ()
			  ((x :special t :accessor x) (y :special t :accessor y))
			  (:metaclass special-class))
			(defparameter *obj* (make-instance 'dummy))
			(defparameter *env* (dletf (((x *obj*) 1) ((y *obj*) 2)) (capture-dynamic-environment)))
			(print (list (slot-boundp *obj* 'x)
			             (with-dynamic-environment (*env*) (list (x *obj*) (y *obj*)))
			             (with-dynamic-environment (*env*) (list (incf (x *obj*)) (incf (y *obj*))))
			             (with-dynamic-environment (*env*) (list (x *obj*) (y *obj*)))
			             (slot-boundp *obj* 'y)))
			(setf (x *obj*) 10)
			(print (list (x *obj*) (dletf (((x *obj*) 20)) (list (x *obj*) (slot-value *obj* 'x)))
			             (slot-value *obj* 'x) (progn (slot-makunbound *obj* 'x) (slot-boundp *obj* 'x))))
			(print (with-active-layers (info-layer)
			         (princ-to-string (contextl::layer-context-prototype (current-layer-context)))))
			(print (with-active-layers (info-layer)
			         (list 'info-layer (layer-name (find-layer 'info-layer)) (mapcar #'layer-name (active-layers)))))
			(deflayer test-layer ()
			  ((slot0 :initarg :slot0 :initform 'foo :reader slot0 :special t)
			   (slot1 :initarg :slot1 :initform 'bar :reader slot1 :special t)))
			(print (list (slot0 (find-layer 'test-layer)) (slot1 (find-layer 'test-layer))
			             (with-active-layers ((test-layer :slot0 4711))
			               (list (slot0 (find-layer 'test-layer)) (slot1 (find-layer 'test-layer))))
			             (slot0 (find-layer 'test-layer))))
			(defparameter *counter* 0)
			(defclass class1 () ((some-slot :initform (incf *counter*) :reader some-slot)) (:metaclass singleton-class))
			(ensure-finalized (find-class 'class1))
			(defclass class1 () ((some-slot :initform (incf *counter*) :reader some-slot)) (:metaclass singleton-class))
			(print (list (some-slot (class-prototype (find-class 'class1))) *counter*))
			(defclass class1 ()
			  ((some-slot :initform (incf *counter*) :reader some-slot :reinitialize t))
			  (:metaclass singleton-class))
			(print (list (some-slot (class-prototype (find-class 'class1))) *counter*))
			(deflayer layer1 () ((some-slot :initform (incf *counter*) :reader some-slot)))
			(print (some-slot (find-layer 'layer1)))
			(deflayer layer1 () ((some-slot :initform (incf *counter*) :reader some-slot :reinitialize t)))
			(deflayer layer3 () ((some-slot :initform (incf *counter*) :reader some-slot :special t)))
			(print (list (some-slot (find-layer 'layer1)) (some-slot (find-layer 'layer3)) *counter*))
			(deflayer layer3 () ((some-slot :initform (incf *counter*) :reader some-slot :special t :reinitialize t)))
			(print (list (dletf (((some-slot (find-layer 'layer3)) 'foo)) (some-slot (find-layer 'layer3)))
			             (some-slot (find-layer 'layer3)) *counter*))
			(layer-makunbound 'layer1)
			(print (list (find-layer-class 'layer1 nil) (layer-name (find-layer 'layer3))))
			(defvar *adjoined*)
			(defvar *removed*)
			(defvar *default-context*)
			(defvar *new-context*)
			(defclass my-layer-class (standard-layer-class) ())
			(clear-layer-caches)
			(defclass my-layer-class (standard-layer-class) ())
			(define-layered-method adjoin-layer-using-class :after
			  ((class my-layer-class) (active-context t))
			  (setf *adjoined* t))
			(define-layered-method remove-layer-using-class :after
			  ((class my-layer-class) (active-context t))
			  (setf *removed* t))
			(deflayer foo () () (:metaclass my-layer-class))
			(deflayer bar () () (:metaclass my-layer-class))
			(deflayer baz (bar) () (:metaclass my-layer-class))
			(setf *default-context* (current-layer-context))
			(print 1)
			(adjoin-layer 'foo *default-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'foo *default-context*)
			(assert (not *adjoined*))
			(setf *adjoined* nil)
			(adjoin-layer 'foo *default-context*)
			(assert (not *adjoined*))
			(reinitialize-instance (find-layer-class 'foo))
			(makunbound '*adjoined*)
			(adjoin-layer 'foo *default-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'foo *default-context*)
			(assert (not *adjoined*))
			(setf *adjoined* nil)
			(adjoin-layer 'foo *default-context*)
			(assert (not *adjoined*))
			(print 2)
			(remove-layer 'foo *default-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'foo *default-context*)
			(assert (not *removed*))
			(setf *removed* nil)
			(remove-layer 'foo *default-context*)
			(assert (not *removed*))
			(reinitialize-instance (find-layer-class 'foo))
			(makunbound '*removed*)
			(remove-layer 'foo *default-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'foo *default-context*)
			(assert (not *removed*))
			(setf *removed* nil)
			(remove-layer 'foo *default-context*)
			(assert (not *removed*))
			(print 3)
			(setf *new-context* (adjoin-layer 'foo *default-context*))
			(makunbound '*adjoined*)
			(adjoin-layer 'baz *new-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'baz *new-context*)
			(assert (not *adjoined*))
			(setf *adjoined* nil)
			(adjoin-layer 'baz *new-context*)
			(assert (not *adjoined*))
			(reinitialize-instance (find-layer-class 'bar))
			(setf *adjoined* nil)
			(adjoin-layer 'baz *new-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'baz *new-context*)
			(assert (not *adjoined*))
			(setf *adjoined* nil)
			(adjoin-layer 'baz *new-context*)
			(assert (not *adjoined*))
			(print 4)
			(setf *new-context* (remove-layer 'foo *default-context*))
			(makunbound '*removed*)
			(remove-layer 'baz *new-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'baz *new-context*)
			(assert (not *removed*))
			(setf *removed* nil)
			(remove-layer 'baz *new-context*)
			(assert (not *removed*))
			(reinitialize-instance (find-layer-class 'bar))
			(setf *removed* nil)
			(remove-layer 'baz *new-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'baz *new-context*)
			(assert (not *removed*))
			(setf *removed* nil)
			(remove-layer 'baz *new-context*)
			(assert (not *removed*))
			(print 5)
			(setf *new-context* (adjoin-layer 'foo *default-context*))
			(makunbound '*adjoined*)
			(adjoin-layer 'bar *new-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'bar *new-context*)
			(assert (not *adjoined*))
			(define-layered-method adjoin-layer-using-class :before
			  ((class my-layer-class) (active-context t))
			  '())
			(setf *adjoined* nil)
			(adjoin-layer 'bar *new-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'bar *new-context*)
			(assert (not *adjoined*))
			(print 6)
			(setf *new-context* (remove-layer 'foo *default-context*))
			(makunbound '*removed*)
			(remove-layer 'bar *new-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'bar *new-context*)
			(assert (not *removed*))
			(define-layered-method remove-layer-using-class :before
			  ((class my-layer-class) (active-context t))
			  '())
			(setf *removed* nil)
			(remove-layer 'bar *new-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'bar *new-context*)
			(assert (not *removed*))
			(print 7)
			(setf *new-context* (adjoin-layer 'foo *default-context*))
			(makunbound '*adjoined*)
			(adjoin-layer 'bar *new-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'bar *new-context*)
			(assert (not *adjoined*))
			(define-layered-method adjoin-layer-using-class :before
			  ((class (eql (find-layer-class 'bar))) (active-context t))
			  '())
			(setf *adjoined* nil)
			(adjoin-layer 'bar *new-context*)
			(assert *adjoined*)
			(setf *adjoined* nil)
			(adjoin-layer 'bar *new-context*)
			(assert (not *adjoined*))
			(print 8)
			(clear-layer-caches)
			(setf *new-context* (remove-layer 'foo *default-context*))
			(makunbound '*removed*)
			(remove-layer 'bar *new-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'bar *new-context*)
			(assert (not *removed*))
			(define-layered-method remove-layer-using-class :before
			  ((class (eql (find-layer-class 'bar))) (active-context t))
			  '())
			(setf *removed* nil)
			(remove-layer 'bar *new-context*)
			(assert *removed*)
			(setf *removed* nil)
			(remove-layer 'bar *new-context*)
			(assert (not *removed*))
			(define-layered-class person ()
			  ((name :initarg :name :layered-accessor person-name)))
			(define-layered-function display-object (object))
			(define-layered-method display-object ((object person))
			  (list 'person :name (person-name object)))
			(define-layered-class employer :in employment-layer ()
			  ((name :initarg :name :layered-accessor employer-name)))
			(define-layered-method display-object :in employment-layer ((object employer))
			  (list 'employer :name (employer-name object)))
			(define-layered-class person :in employment-layer ()
			  ((employer :initarg :employer :layered-accessor person-employer)))
			(define-layered-method display-object :in employment-layer :around ((object person))
			  (append (call-next-method) (list :employer (display-object (person-employer object)))))
			(define-layered-class info-mixin :in info-layer ()
			  ((city :initarg :city :layered-accessor city)))
			(define-layered-method display-object :in info-layer :around ((object info-mixin))
			  (append (call-next-method) (list :city (city object))))
			(define-layered-class person :in info-layer (info-mixin) ())
			(define-layered-class employer :in info-layer (info-mixin) ())
			(defparameter *rob*
			  (make-instance 'person :name 'rob :city 'ilm
			                         :employer (make-instance 'employer :name 'doc :city 'muc)))
			(print (display-object *rob*))
			(print (with-active-layers (employment-layer info-layer) (display-object *rob*)))
			(deflayer display-layer)
			(define-layered-class displayed-slots-mixin :in display-layer ()
			  ((displayed-slots :special t :initform '() :accessor displayed-slots)))
			(define-layered-class person :in display-layer (displayed-slots-mixin) ())
			(print (list (displayed-slots *rob*)
			             (dletf (((displayed-slots *rob*) '(name city))) (displayed-slots *rob*))
			             (displayed-slots *rob*)))
			(deflayer slot-access-layer)
			(define-layered-method slot-value-using-layer :in slot-access-layer (class (object person) slot reader)
			  (declare (ignorable class slot reader))
			  (list (call-next-method) :slot-access))
			(define-layered-class person :in slot-access-layer () ((name :layered t)))
			(print (list (person-name *rob*) (with-active-layers (slot-access-layer) (person-name *rob*))))
			(defmacro call-twice (function) `(list (,function) (,function)))
			(defun call-thunk (thunk) (list :thunk (funcall thunk)))
			(define-layered-function twice (x))
			(define-layered-method twice (x) x)
			(define-layered-method twice :in info-layer :around (x) (call-twice call-next-method))
			(define-layered-method twice :in employment-layer :around (x) (call-thunk #'call-next-method))
			(print (list (with-active-layers (info-layer) (twice 1)) (with-active-layers (employment-layer) (twice 2))))
			(print :done)
			""";

	private static final List<String> EXPECTED = List.of("(:ROOT)", "(:INFO :ROOT)", "(:EMPLOYMENT :INFO :ROOT)",
			"(:INFO :EMPLOYMENT :ROOT)", "(:EMPLOYMENT :INFO :ROOT)", "(:EMPLOYMENT :ROOT)", "(:INFO :ROOT)",
			"(:EMPLOYMENT :ROOT)", "(\"EMPLOYMENT-LAYER\" \"INFO-LAYER\" \"T\")", "(NIL T)",
			"(\"Hello, Ann\" \"Hello, Bob, please\" \"Dear Hello, Cy, please\")", "(:ADJOINED)", "(:INFO :ROOT)",
			"(:ROOT)", "(:CLASS :CLASS)", "(T T)",
			"((:OUTPUT) ((:PLAIN :OUTPUT) ((:HTML :OUTPUT) ((:XML :OUTPUT) (:PLAIN :OUTPUT)))))",
			"(NIL (1 2) (2 3) (2 3) NIL)", "(10 (20 20) 10 NIL)", "\"#<LAYER NIL>\"",
			"(INFO-LAYER INFO-LAYER (INFO-LAYER T))", "(FOO BAR (4711 BAR) FOO)", "(1 1)", "(2 2)", "3", "(4 5 5)",
			"(FOO 6 6)", "(NIL LAYER3)", "1", "2", "3", "4", "5", "6", "7", "8", "(PERSON :NAME ROB)",
			"(PERSON :NAME ROB :CITY ILM :EMPLOYER (EMPLOYER :NAME DOC :CITY MUC))", "(NIL (NAME CITY) NIL)",
			"(ROB (ROB :SLOT-ACCESS))", "((1 1) (:THUNK 2))", ":DONE");

	/**
	 * A handler {@code eval}s a redefinition that makes the slot of an existing instance
	 * special, and the {@code dletf} it continues then binds it, as contextl's
	 * {@code test/special-slots-1.lisp} does: the redefinition makes the instance
	 * obsolete and updates it ({@code .kb/clos.md}, "Instance updating"). Interpreter
	 * only -- a compiled {@code eval} defines no class.
	 */
	@Test
	void anEvaluatedRedefinitionMakesAnExistingInstancesSlotSpecial() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(SYSTEM_DIR, LW_COMPAT_DIR));
		for (LispVal expr : LispReader.readAllFromString("""
				(asdf:load-system "contextl")
				(in-package :contextl-user)
				(define-layered-class person1 () ((name1 :initarg :name :accessor person-name1)))
				(defparameter *p* (make-instance 'person1 :name "Dr. Jekyll"))
				(defparameter *errors* 0)
				(symbol-macrolet ((safe-special-symbol-progv t))
				  (handler-bind ((error (lambda (error)
				                          (incf *errors*)
				                          (eval '(define-layered-class person1 ()
				                                  ((name1 :initarg :name :special t :accessor person-name1))))
				                          (continue error))))
				    (print (dletf (((person-name1 *p*) "Mr. Hide")) (person-name1 *p*)))))
				(print (list *errors* (person-name1 *p*)))
				""")) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactly("\"Mr. Hide\"", "(1 \"Dr. Jekyll\")");
	}

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(LW_COMPAT_DIR);
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
		return "TestContextl";
	}

}
