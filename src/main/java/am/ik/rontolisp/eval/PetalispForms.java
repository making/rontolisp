package am.ik.rontolisp.eval;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The rontolisp arms of petalisp's native backend (tier 4, {@code .kb/asdf.md}). The
 * backend is written against an image whose arrays have raw storage: it hands its kernels
 * a static vector's address or a block of foreign memory, and loads and stores elements
 * through pointer accessors. A Lisp vector has no address on the JVM or wasm-GC, so here
 * every allocation is a simple vector of one slot per element -- the shape upstream
 * already gives an allocation of boxed elements -- a result or a constant array is its
 * own storage, and an element is read and written with {@code row-major-aref}. What
 * reaches for an address (the storage vector, the fixnum tag width read through
 * {@code cffi:mem-ref}) gets an arm that does not, so {@code static-vectors} is no longer
 * needed ({@code petalisp-native-backend-rontolisp.asd}). A worker is not pinned to a
 * core: a rontolisp thread is a virtual thread, whose carrier others share, and the pin
 * compiles C++ at load time. And {@code petalisp.utilities}' portable
 * {@code with-pinned-objects} answered the last pinned object rather than its body's
 * values, so an evaluator of two results answered one; the rontolisp arm answers the
 * body's. Everything else -- partitioning, scheduling, allocation, the worker pool and
 * its barriers, the compiled kernels -- is upstream's.
 */
final class PetalispForms {

	/** The systems the rewrite applies to. */
	static final Set<String> SYSTEMS = Set.of("petalisp.native-backend", "petalisp.utilities");

	private PetalispForms() {
	}

	/**
	 * Rewrites the five components; any other is left alone.
	 * @param componentFile the component's path relative to its system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not one of these
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		return switch (componentFile) {
			case "evaluator.lisp" -> {
				String storage = replace(componentFile, source, "  #-(or sbcl ccl) (error \"Not implemented yet.\"))",
						"  #+rontolisp array\n  #-(or sbcl ccl rontolisp) (error \"Not implemented yet.\"))");
				String pointer = replace(componentFile, storage,
						"(defun array-storage-pointer (array)\n  (let ((vector (array-storage-vector array)))",
						"(defun array-storage-pointer (array)\n  #+rontolisp (array-storage-vector array)\n"
								+ "  #-rontolisp\n  (let ((vector (array-storage-vector array)))");
				yield replace(componentFile, pointer, "(assert (cffi:pointer-eq",
						"(assert (#+rontolisp eq #-rontolisp cffi:pointer-eq");
			}
			case "allocation.lisp" -> {
				String boxed = replace(componentFile, source, "(defun ntype-unboxed-p (ntype)\n  (not (typo:ntype=",
						"(defun ntype-unboxed-p (ntype)\n  #+rontolisp (declare (ignore ntype))\n  #+rontolisp nil\n"
								+ "  #-rontolisp\n  (not (typo:ntype=");
				// One slot of a simple vector per element, counted as a byte, so the
				// allocation of a boxed buffer -- a vector as long as its byte count --
				// holds one element per slot whatever the element type.
				yield replace(componentFile, boxed, "  (petalisp.utilities:clp2\n   (typo:ntype-bits ntype)))",
						"  #+rontolisp (progn ntype 8)\n  #-rontolisp\n  (petalisp.utilities:clp2\n"
								+ "   (typo:ntype-bits ntype)))");
			}
			case "compilation.lisp" -> {
				String unpack = replace(componentFile, source,
						"          'cffi:foreign-pointer\n          'simple-vector)",
						"          'cffi:foreign-pointer\n          #+rontolisp 'array #-rontolisp 'simple-vector)");
				String load = replace(componentFile, unpack,
						"  (typo:ntype-subtypecase ntype\n    ((unsigned-byte 1) 'u1-memref)",
						"  #+rontolisp (progn ntype 'row-major-aref)\n  #-rontolisp\n"
								+ "  (typo:ntype-subtypecase ntype\n    ((unsigned-byte 1) 'u1-memref)");
				// The fixnum accessors' tag width: no load reads a fixnum through a
				// pointer.
				yield replace(componentFile, load, "(defconstant +n-fixnum-tag-bits+\n  (let ((array",
						"(defconstant +n-fixnum-tag-bits+\n  #+rontolisp 0\n  #-rontolisp\n  (let ((array");
			}
			case "pin-current-thread.lisp" -> replace(componentFile, source,
					"(defparameter *pin-current-thread-library*\n  (ignore-errors",
					"(defparameter *pin-current-thread-library*\n  #+rontolisp nil\n  #-rontolisp\n  (ignore-errors");
			case "with-pinned-objects.lisp" -> replace(componentFile, source, "  #-(or sbcl)\n  (let ((syms",
					"  #+rontolisp\n  (let ((syms (loop repeat (length objects) collect (gensym \"OBJECT\"))))\n"
							+ "    `(let ,(mapcar #'list syms objects)\n       (declare (ignorable ,@syms))\n"
							+ "       ,@body))\n  #-(or sbcl rontolisp)\n  (let ((syms");
			default -> null;
		};
	}

	private static String replace(String component, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"petalisp " + component + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ PetalispForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
