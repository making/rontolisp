package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The rontolisp arms of coalton (tier 4, {@code .kb/asdf.md}). Its standard library's
 * {@code Hash} type and hash combination pick an arm per implementation and signal on any
 * other, at read time. The ccl arms are what holds here: {@code sxhash} answers a
 * non-negative fixnum, and the combination masks a 64-bit mix back to one. Its compiler
 * types a float literal by whether it is a single or a double, and here every float is
 * one binary64 ({@code single-float} the ones a binary32 holds), so the literal's
 * spelling decides: its reader keeps a {@code d} marker ({@code interpret-token}, on its
 * own client), its macros print it back ({@code print-form}), and its typechecker asks
 * first.
 */
final class CoaltonForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "coalton/library";

	/** The compiler's system, which the literal's rewrite applies to. */
	static final String COMPILER_SYSTEM = "coalton-compiler";

	private static final String CLASSES = "library/classes.lisp";

	private static final String HASH = "library/hash.lisp";

	private static final String READER = "src/parser/reader.lisp";

	private static final String MACRO_READER = "src/reader.lisp";

	private static final String DEFINE = "src/typechecker/define.lisp";

	private static final String CLIENT = "(defvar *coalton-eclector-client* (make-instance 'coalton-eclector-client))\n";

	/** What keeps a token's {@code d} marker on the float it reads as. */
	private static final String CLIENT_AND_METHOD = CLIENT + """

			(defmethod eclector.reader:interpret-token :around
			    ((client coalton-eclector-client) input-stream token escape-ranges)
			  (declare (ignore input-stream escape-ranges))
			  (let ((value (call-next-method)))
			    (if (and (floatp value) (find-if (lambda (c) (find c "dDlL")) token))
			        (rontolisp::%double-spelled value)
			        value)))
			""";

	private static final String SINGLE_ARM = "\n                (single-float tc:*single-float-type*)";

	/**
	 * The spelling's two arms: a {@code d} marker is an {@code F64}, any other float an
	 * {@code F32} -- {@code single-float} is the floats a binary32 holds, so {@code 0.1}
	 * would miss the upstream single arm, which SBCL's reader sends it to.
	 */
	private static final String SPELLED_ARM = "\n                ((satisfies rontolisp::%double-spelled-p) tc:*double-float-type*)"
			+ "\n                (float tc:*single-float-type*)";

	private CoaltonForms() {
	}

	/**
	 * Rewrites the two components; any other is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not one of these
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		return switch (componentFile) {
			case CLASSES -> {
				String arm = replace(componentFile, source,
						"#+ccl\n  (repr :native (cl:and cl:fixnum cl:unsigned-byte))",
						"#+(or ccl rontolisp)\n  (repr :native (cl:and cl:fixnum cl:unsigned-byte))");
				yield replace(componentFile, arm, "#-(or sbcl allegro ccl)\n  #.(cl:error",
						"#-(or sbcl allegro ccl rontolisp)\n  #.(cl:error");
			}
			case HASH -> replace(componentFile, source, "#+ccl (cl:logand (cl:logxor lhs",
					"#+(or ccl rontolisp) (cl:logand (cl:logxor lhs");
			case READER -> replace(componentFile, source, CLIENT, CLIENT_AND_METHOD);
			// Its macros print their forms and read the text again: the print keeps the
			// spelling the read types a literal by.
			case MACRO_READER -> replace(componentFile, source, "(prin1-to-string form)",
					"(rontolisp::%prin1-keeping-float-spelling form)");
			case DEFINE -> {
				String expression = replace(componentFile, source, "(ratio        tc:*fraction-type*)" + SINGLE_ARM,
						"(ratio        tc:*fraction-type*)" + SPELLED_ARM + SINGLE_ARM);
				yield replace(componentFile, expression, "(ratio tc:*fraction-type*)" + SINGLE_ARM,
						"(ratio tc:*fraction-type*)" + SPELLED_ARM + SINGLE_ARM);
			}
			default -> null;
		};
	}

	private static String replace(String component, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"coalton " + component + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ CoaltonForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
