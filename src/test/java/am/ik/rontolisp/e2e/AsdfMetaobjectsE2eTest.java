package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

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
			(print asdf:*user-cache*)
			(print (asdf:registered-systems))
			(defun nested-load () (asdf:load-system "meta-demo"))
			(nested-load)
			(print :nested-load-ok)
			(asdf:test-system "meta-demo")
			(print (one-fn))
			""";

	private static final List<String> EXPECTED = List.of("T", "T", "NIL", "T", "\"meta-demo\"",
			"(\"src/one\" \"src/m/two\")", "NIL", "T", "NIL", "T", "T", "(\"meta-demo\")", "\"1.2.3\"", "NIL", ":UNIX",
			":RELATIVE-OK", ":SYSTEM", ":FILE", "NIL", "NIL",
			"(\"meta-demo\" \"meta-demo/tests\" \"trivial-features\")", ":NESTED-LOAD-OK",
			"\"testing meta-demo/tests\"", "3", "1");

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
