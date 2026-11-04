package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.AsdfExports;

/**
 * The ASDF component metaobjects at run time ({@code .kb/asdf.md},
 * {@code AsdfRuntimeLibrary}): on all four backends, {@code asdf:find-system} answers a
 * memoized {@code asdf:system} instance ({@code eq} across calls), the component readers
 * walk a {@code :components} system with a {@code :module} (flattened to one
 * {@code cl-source-file} per file), a {@code defmethod} specialized on
 * {@code asdf:system}/{@code asdf:cl-source-file} dispatches, {@code typep} and
 * {@code asdf:registered-systems} answer, a NESTED {@code asdf:load-system} of the
 * already-spliced system is a no-op, and {@code (asdf:test-system "meta-demo")} follows
 * the {@code :in-order-to} test-op chain into the tests system's recorded
 * {@code :perform} body. Fixture: {@code src/test/resources/asdf-metaobjects-demo}.
 *
 * <p>
 * It also pins three ASDF gaps: {@code asdf:component-version} answers the declared
 * {@code :version} (and nil for a system that declares none), the
 * {@code :defsystem-depends-on} entry on the built-in trivial-features shim is loaded
 * ahead of everything and its announced {@code :unix} is in force while the component
 * file is READ (the {@code platform} defun) without ever becoming a sideway dependency,
 * and {@code asdf:system-relative-pathname} has a runtime form on the compile paths --
 * the relative argument is a variable there, so the compile-time fold cannot answer it.
 *
 * <p>
 * The {@code .asd} runs as Lisp ({@code .kb/asdf.md}, "What a {@code .asd} may contain"):
 * a {@code defpackage} guarded by {@code find-package}, lisp-binary's gate on
 * {@code asdf::*asdf-version*}, a feature computed from {@code *features*} and pushed --
 * read by the {@code #-} below it and by {@code src/one.lisp} (the {@code build} defun)
 * -- and a {@code load-system} of {@code meta-demo-tool} from the file itself, which the
 * compile paths run in their build image and splice ahead of the systems defined after it
 * ({@code tool-fn}).
 *
 * <p>
 * And the operation protocol ({@code .kb/asdf.md}, "The operation protocol"): the
 * recorded test-op body receives a real {@code asdf:test-op}; {@code asdf/interface} and
 * the sub-packages name asdf's own symbols; {@code operate}, {@code make-operation},
 * {@code input-files}/{@code output-files}/{@code operation-done-p} answer, a library's
 * own operation class and {@code operation-done-p} method included (arnesi's); and the
 * {@code asdf:perform} methods {@code hooks.lisp} defines run around every later
 * component load -- asdf-flv's {@code :around} keeping an assignment in the file that
 * made it, a nested {@code load-system} from inside a component included -- in the same
 * order on every backend, the compile paths running each spliced component as the thunk
 * its load-op is performed with.
 *
 * <p>
 * And the options around a component's compile ({@code .kb/asdf.md}, "Options around the
 * compile"): {@code :around-compile} as a lambda, a symbol and a string, inherited and
 * switched off by {@code nil}, deciding the {@code #+} and the macro expansions of its
 * component and nothing of the program; {@code :encoding}; {@code #P} pathnames; the
 * {@code program-op} options; {@code :weakly-depends-on}.
 *
 * <p>
 * And real ASDF's whole surface ({@code .kb/asdf.md}, "The surface is the inventory"):
 * every external reads and its definition compiles on every backend, a stub taking a
 * library's method; {@code asdf:*central-registry*} finds a system no search path names;
 * {@code oos}, {@code operate}, {@code make} and {@code load-asd} at top level splice;
 * the metadata readers, {@code find-component}, {@code clear-system} and the conditions
 * answer what SBCL 2.2.9's ASDF 3.3.1 answers for the same file (where the model is flat,
 * a test of the class stands in for a name).
 *
 * <p>
 * And the classes loaded code defines ({@code .kb/asdf.md}, "Classes loaded code
 * defines"), one per consumer shape in {@code classes-tool.lisp}:
 * {@code meta-demo-classes} names them from its {@code .asd}, its
 * {@code :defsystem-depends-on} brings them in, and each instance acts as its class's
 * methods say -- the same answers as SBCL 2.2.9's ASDF 3.3.1 for the same files, but for
 * the symbol {@code asdf/interface::x} printing {@code asdf::x}.
 */
class AsdfMetaobjectsE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "asdf-metaobjects-demo")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system "meta-demo")
			(let ((sys (asdf:find-system :meta-demo)))
			  (print (typep sys 'asdf:system))
			  (print (typep sys 'asdf:module))
			  (print (typep sys 'asdf:package-inferred-system))
			  (print (eq sys (asdf:find-system "meta-demo")))
			  (print (asdf:component-name sys))
			  (print (mapcar (lambda (c) (asdf:component-name c)) (asdf:component-children sys)))
			  (print (asdf:component-sideway-dependencies sys))
			  (let ((child (car (asdf:component-children sys))))
			    (print (typep child 'asdf:cl-source-file))
			    (print (typep child 'asdf:system))
			    (print (eq (asdf:component-parent child) sys))
			    (print (eq (asdf:component-system child) sys))))
			(print (asdf:component-sideway-dependencies (asdf:find-system "meta-demo/tests")))
			(print (asdf:component-version (asdf:find-system "meta-demo")))
			(print (asdf:component-version (asdf:find-system "meta-demo/tests")))
			(print (platform))
			(print (build))
			(print (tool-fn))
			(print (if (search "asdf-metaobjects-demo/data.txt"
			                   (let ((rel "data.txt"))
			                     (asdf:system-relative-pathname "meta-demo" rel)))
			           :relative-ok
			           :relative-bad))
			(defmethod kind-of ((c asdf:system)) :system)
			(defmethod kind-of ((c asdf:cl-source-file)) :file)
			(print (kind-of (asdf:find-system "meta-demo")))
			(print (kind-of (car (asdf:component-children (asdf:find-system "meta-demo")))))
			(print (asdf:find-system :absent nil))
			(print (list asdf:*user-cache* asdf::*asdf-version*))
			;; A compile path registered what the surface below loads while it spliced.
			(print (remove-if (lambda (name)
			                    (or (search "central" name) (search "made" name) (search "late" name)
			                        (search "classes" name)))
			                  (asdf:registered-systems)))
			(defun nested-load () (asdf:load-system "meta-demo"))
			(nested-load)
			(print :nested-load-ok)
			(asdf:test-system "meta-demo")
			(print (one-fn))
			(print (eq 'asdf/interface::perform 'asdf:perform))
			(print (eq 'asdf/component:component-name 'asdf:component-name))
			(print (eq (asdf:make-operation 'asdf:load-op) (asdf:make-operation 'asdf:load-op)))
			(print (asdf:output-files 'asdf:compile-op (asdf:find-system "meta-demo")))
			(print (length (asdf:input-files 'asdf:load-op
			                                 (car (asdf:component-children (asdf:find-system "meta-demo"))))))
			(print (typep (asdf:operate 'asdf:load-op "meta-demo") 'asdf:load-op))
			(print (handler-case (asdf:operate 'asdf:compile-op "meta-demo") (error () :refused)))
			(asdf:load-system "meta-demo/perform")
			(print (list meta-hooks::*seen-in-a* meta-hooks::*seen-in-b* meta-hooks::*seen-in-c*
			             meta-hooks:*current*))
			(print (reverse meta-hooks:*log*))
			(let ((sys (asdf:find-system "meta-demo")))
			  (print (list (asdf:operation-done-p 'asdf:load-op sys)
			               (asdf:operation-done-p (make-instance 'meta-hooks::clean-op) sys)
			               (handler-case (meta-hooks::make-single-fasl "meta-demo") (error () :refused)))))
			(asdf:load-system "meta-demo/hooked")
			(print *hooked*)
			(print *unhooked*)
			(print *named-hook*)
			(print *string-hook*)
			(print (find :meta-demo-hooked *features*))
			(print (weak-fn))
			(print (asdf:component-sideway-dependencies (asdf:find-system "meta-demo/hooked")))
			""" + surface();

	/**
	 * The whole surface: naming every external here is what splices every stub.
	 */
	private static String surface() {
		String externals = AsdfExports.externals()
			.keySet()
			.stream()
			.map(name -> "'asdf:" + name.toLowerCase(java.util.Locale.ROOT))
			.collect(Collectors.joining(" "));
		String registry = SYSTEM_DIR + "/registry/";
		return "(print (length (list " + externals + ")))\n" + """
				(print (list (eq 'asdf:hostname 'uiop:hostname)
				             (eq 'asdf:*user-cache* 'uiop:*user-cache*)
				             (eq 'asdf/component:relative-pathname 'asdf::relative-pathname)
				             (eq 'asdf-action:perform 'asdf:perform)))
				(push "%1$s" asdf:*central-registry*)
				(asdf:oos 'asdf:load-op "meta-demo-central")
				(print (list (central-dep-fn) (central-fn) (inner-fn)))
				(print (asdf:load-systems "meta-demo-central"))
				(let ((s (asdf:find-system "meta-demo-central")))
				  (print (list (asdf:system-description s) (asdf:system-long-description s)
				               (asdf:system-author s) (asdf:system-maintainer s)
				               (asdf:system-license s) (asdf:system-licence s)
				               (asdf:system-homepage s) (asdf:system-bug-tracker s)
				               (asdf:system-mailto s) (asdf:system-long-name s)
				               (asdf:system-source-control s)))
				  (print (list (asdf:system-depends-on s) (asdf:system-weakly-depends-on s)
				               (asdf:system-defsystem-depends-on s)))
				  (print (asdf:component-property s :color))
				  (print (pathname-name (asdf:system-source-file s)))
				  (print (pathname-name (asdf:system-source-file "meta-demo-central")))
				  (print (list (slot-value s 'asdf::name) (slot-value s 'asdf/component:description)))
				  (print (and (search "registry/" (namestring (slot-value s 'asdf::relative-pathname))) t))
				  (print (typep (car (asdf:module-components s)) 'asdf:cl-source-file))
				  (print (list (typep (asdf:find-component s "central") 'asdf:source-file)
				               (typep (asdf:find-component s '("m" "inner")) 'asdf:source-file)
				               (asdf:find-component s "nope")
				               (asdf:find-component nil "nope")
				               (eq s (asdf:find-component nil "meta-demo-central"))
				               (eq s (asdf:find-component "meta-demo-central" nil))))
				  (print (asdf:component-depends-on 'asdf:prepare-op s))
				  (print (list (asdf:version-satisfies s "1.0") (asdf:version-satisfies s "9.0")
				               (asdf:version-satisfies s nil)))
				  (print (list (asdf:component-loaded-p s) (asdf:component-loaded-p "meta-demo-nowhere")
				               (eq s (asdf:registered-system "meta-demo-central"))
				               (asdf:registered-system "meta-demo-nowhere")))
				  (print (and (member "meta-demo-central" (asdf:already-loaded-systems) :test #'equal) t))
				  (print (let ((seen nil))
				           (asdf:map-systems (lambda (system)
				                               (when (eq system s) (setq seen t))))
				           seen))
				  (asdf:clear-system "meta-demo-central")
				  (print (list (asdf:registered-system "meta-demo-central")
				               (asdf:component-loaded-p "meta-demo-central")))
				  (print (eq s (asdf:find-system "meta-demo-central"))))
				(print (list (asdf:coerce-name :foo) (asdf:coerce-name "Foo") (asdf:primary-system-name "a/b")
				             (asdf:primary-system-name :a)))
				(print (asdf:system-description (asdf:find-system "meta-demo-central/computed")))
				(print (asdf:find-system "meta-demo-nowhere" nil))
				(print (handler-case (asdf:find-system "meta-demo-nowhere")
				         (asdf:missing-component (c) (asdf/find-component:missing-requires c))))
				(print (list (subtypep 'asdf:load-system-definition-error 'asdf:system-definition-error)
				             (subtypep 'asdf:missing-dependency 'asdf:missing-component)
				             (subtypep 'asdf:missing-component 'asdf:system-definition-error)
				             (subtypep 'asdf:compile-error 'asdf:operation-error)
				             (subtypep 'asdf:load-op 'asdf:downward-operation)
				             (subtypep 'asdf:program-op 'asdf:operation)))
				(defclass demo-op (asdf:operation) ())
				(defmethod asdf:make-plan (plan (operation demo-op) component &key)
				  (declare (ignore plan component))
				  :demo-plan)
				(print (asdf:make-plan nil (asdf:make-operation 'demo-op) nil))
				(print (handler-case (asdf:traverse 'asdf:load-op "meta-demo")
				         (uiop:not-implemented-error () :not-implemented)))
				(print (handler-case (asdf:make "meta-demo/hooked") (error () :refused)))
				(print (handler-case (asdf:compile-system "meta-demo") (error () :refused)))
				(asdf:make "meta-demo-made")
				(print (made-fn))
				(asdf:load-asd "%1$smeta-demo-late.asd")
				(asdf:load-asd "%1$sabsent.asd")
				(asdf:load-system "meta-demo-late")
				(print (late-fn))
				(asdf:operate 'asdf:test-op "meta-demo")
				(asdf:load-system "meta-demo-classes")
				(let ((s (asdf:find-system "meta-demo-classes")))
				  (print (list (class-name (class-of s)) (classes-tool::package-of s) (asdf:system-license s)))
				  (print (mapcar (lambda (c)
				                   (list (asdf:component-name c) (class-name (class-of c)) (asdf:file-type c)
				                         (file-namestring (asdf:component-pathname c))))
				                 (asdf:component-children s))))
				(print (list *classes-plain* *classes-typed*))
				(print (reverse classes-tool:*log*))
				(let ((s (asdf:find-system "meta-demo-classes")))
				  (asdf:load-system s))
				(asdf:test-system "meta-demo-classes")
				(print (car classes-tool:*log*))
				(asdf:load-system "meta-demo-classes/own")
				(let ((s (asdf:find-system "meta-demo-classes/own")))
				  (print (list (typep s 'asdf:system)
				               (typep (car (asdf:component-children s)) 'classes-tool:later-file))))
				""".formatted(registry);
	}

	private static final List<String> EXPECTED = List.of("T", "T", "NIL", "T", "\"meta-demo\"",
			"(\"src/one\" \"src/m/two\")", "NIL", "T", "NIL", "T", "T", "(\"meta-demo\")", "\"1.2.3\"", "NIL", ":UNIX",
			":SMALL", ":TOOL", ":RELATIVE-OK", ":SYSTEM", ":FILE", "NIL", "(NIL \"3.3.7\")",
			"(\"meta-demo-tool\" \"meta-demo-tool/hook\" \"meta-demo\" \"meta-demo/tests\" \"meta-demo/hooks\""
					+ " \"meta-demo/perform\" \"meta-demo/nested\" \"meta-demo/weak\" \"meta-demo/hooked\""
					+ " \"trivial-features\")",
			":NESTED-LOAD-OK", "\"testing meta-demo/tests\"", "T", "3", "1", "T", "T", "T", "NIL", "1", "T", ":REFUSED",
			"(:A :GLOBAL :B :GLOBAL)",
			"((:SYSTEM \"meta-demo/hooks\") (:COMPILE \"a\") (:LOAD \"a\") (:COMPILE \"b\") (:LOAD \"b\")"
					+ " (:COMPILE \"c\") (:LOAD \"c\") (:SYSTEM \"meta-demo/nested\") (:SYSTEM \"meta-demo/perform\"))",
			"(T :CLEAN :REFUSED)", "(:READ-WITH (:META-DEMO-HOOKED))", "(:READ-WITHOUT NIL)",
			"(:READ-WITH (:META-DEMO-NAMED-HOOK))", "(:READ-WITH (:META-DEMO-TOOL-HOOKED))", "NIL", ":WEAK",
			"(\"meta-demo/weak\")", "230", "(T T T T)", "(:CENTRAL-DEP :CENTRAL :INNER)", "NIL",
			"(\"The description\" \"The long description\" \"An Author\" \"A Maintainer\" \"MIT\" \"MIT\""
					+ " \"https://example.org/home\" \"https://example.org/bugs\" \"a@example.org\""
					+ " \"The Long Name\" (:GIT \"https://example.org/git\"))",
			"((\"meta-demo-central-dep\") (\"meta-demo-absent\") NIL)", "\"red\"", "\"meta-demo-central\"",
			"\"meta-demo-central\"", "(\"meta-demo-central\" \"The description\")", "T", "T", "(T T NIL NIL T T)",
			"((ASDF:LOAD-OP \"meta-demo-central-dep\") (ASDF:DEFINE-OP \"meta-demo-central\"))", "(T NIL T)",
			"(T NIL T NIL)", "T", "T", "(NIL NIL)", "NIL", "(\"foo\" \"Foo\" \"a\" \"a\")", "NIL", "NIL",
			"\"meta-demo-nowhere\"", "(T T T T T T)", ":DEMO-PLAN", ":NOT-IMPLEMENTED", ":REFUSED", ":REFUSED", ":MADE",
			":LATE", "\"testing meta-demo/tests\"", "T", "3",
			"(CLASSES-TOOL:TOOL-SYSTEM \"META-DEMO-CLASSES\" \"From the class\")",
			"((\"classes-plain\" CLASSES-TOOL::LOGGED-FILE \"lisp\" \"classes-plain.lisp\")"
					+ " (\"classes-typed\" ASDF::TYPED-FILE \"tl\" \"classes-typed.tl\")"
					+ " (\"classes-data\" ASDF::DATA-FILE \"dat\" \"classes-data.dat\")"
					+ " (\"classes-later\" CLASSES-TOOL:LATER-FILE \"lisp\" \"classes-later.lisp\"))",
			"(:READ-HOOKED :TYPED-LOADED)",
			"((:LOGGED-LOAD \"classes-plain\") (:COMPILE-DATA \"classes-data\" \"classes-data.out\" \"classes-data.dat\")"
					+ " (:LOAD-DATA \"classes-data\") (:COMPILE-LATER \"classes-later\")"
					+ " (:REMEMBER-LATER \"classes-later\"))",
			"(:LATER-FILE-RAN :READ-HOOKED)", "(T T)");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
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
		return "TestAsdfMetaobjects";
	}

}
