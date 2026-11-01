package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

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
 * accessibility").
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
			""";

	private static final List<String> EXPECTED = List.of("(:ROOT)", "(:INFO :ROOT)", "(:EMPLOYMENT :INFO :ROOT)",
			"(:INFO :EMPLOYMENT :ROOT)", "(:EMPLOYMENT :INFO :ROOT)", "(:EMPLOYMENT :ROOT)", "(:INFO :ROOT)",
			"(:EMPLOYMENT :ROOT)", "(\"EMPLOYMENT-LAYER\" \"INFO-LAYER\" \"T\")", "(NIL T)",
			"(\"Hello, Ann\" \"Hello, Bob, please\" \"Dear Hello, Cy, please\")", "(:ADJOINED)", "(:INFO :ROOT)",
			"(:ROOT)", "(:CLASS :CLASS)", "(T T)",
			"((:OUTPUT) ((:PLAIN :OUTPUT) ((:HTML :OUTPUT) ((:XML :OUTPUT) (:PLAIN :OUTPUT)))))",
			"(NIL (1 2) (2 3) (2 3) NIL)", "(10 (20 20) 10 NIL)", "\"#<LAYER NIL>\"",
			"(INFO-LAYER INFO-LAYER (INFO-LAYER T))", "(FOO BAR (4711 BAR) FOO)", "(1 1)", "(2 2)", "3", "(4 5 5)",
			"(FOO 6 6)", "(NIL LAYER3)");

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
