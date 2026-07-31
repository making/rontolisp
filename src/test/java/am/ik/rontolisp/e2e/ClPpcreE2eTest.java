package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL cl-ppcre v2.1.2
 * sources (vendored unmodified under {@code src/test/resources/cl-ppcre}, BSD-2-Clause --
 * the released Quicklisp layout) load via {@code asdf:load-system} and run the whole
 * regex pipeline on the interpreter. The library exercises the widest slice of the CL
 * subset so far: local {@code (declare (special ...))} state threading through the
 * convert phase, NAMED {@code block}/{@code return-from} (a {@code return-from} exiting a
 * {@code loop} from {@code collect-char-class}, the non-function {@code (block scan
 * ...)} in the generated scanner closures), {@code &environment} macro parameters +
 * {@code get-setf-expansion} ({@code incf-after}), CLOS slot accessors as generics (the
 * {@code len} reader lives at a different slot position in unrelated classes and merges
 * with a plain {@code defmethod len} on {@code void}), {@code initialize-instance
 * :after}, {@code psetf} with place subforms, mutable-string {@code (setf (aref ...))} /
 * {@code (setf (subseq ...))}, {@code subst}/{@code search}/{@code copy-tree} and the
 * descending/case-insensitive character comparisons.
 *
 * <p>
 * All four backends run: the compilers implement named {@code block}/{@code return-from}
 * as LEXICAL exits ({@code %fn-block} function boundaries plus name-keyed goto/br
 * targets), which covers the scanner closures' {@code (block scan ...)} and the
 * {@code collect-char-class} loop-crossing returns.
 */
class ClPpcreE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-ppcre")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-ppcre)
			(print (multiple-value-list (cl-ppcre:scan "(a)*b" "xaaabd")))
			(print (multiple-value-list (cl-ppcre:scan "abc" "xyz")))
			(print (multiple-value-list (cl-ppcre:scan-to-strings "[0-9]+" "ab 123 cd")))
			(print (cl-ppcre:split "," "a,b,c"))
			(print (cl-ppcre:split "\\\\s+" "foo bar   baz"))
			(print (cl-ppcre:regex-replace "fo+" "foo bar" "frob"))
			(print (cl-ppcre:regex-replace-all "a" "banana" "o"))
			(print (cl-ppcre:all-matches-as-strings "[a-z]+" "one 2 three 4 five"))
			(print (multiple-value-list (cl-ppcre:scan-to-strings "(\\\\d+)-(\\\\d+)" "phone 03-1234")))
			(print (cl-ppcre:scan-to-strings "(?i)hello|bye" "say HELLO now"))
			(let ((acc nil))
			  (cl-ppcre:do-matches-as-strings (m "[0-9]+" "a1 b22 c333")
			    (push m acc))
			  (print (nreverse acc)))
			(print (cl-ppcre:register-groups-bind (area num)
			           ("(\\\\d+)-(\\\\d+)" "tel 03-1234 end")
			         (list area num)))
			(print (cl-ppcre:quote-meta-chars "a.b*c"))
			(print (cl-ppcre:count-matches "a" "banana"))
			(print (cl-ppcre:all-matches "an" "banana"))
			(print (cl-ppcre:scan-to-strings '(:sequence "b" (:greedy-repetition 1 nil #\\a)) "xbaaay"))
			(print (cl-ppcre:split "(,)" "a,b" :with-registers-p t))
			""";

	private static final List<String> EXPECTED = List.of("(1 5 #(3) #(4))", "(NIL NIL)", "(\"123\" #())",
			"(\"a\" \"b\" \"c\")", "(\"foo\" \"bar\" \"baz\")", "\"frob bar\"", "\"bonono\"",
			"(\"one\" \"three\" \"five\")", "(\"03-1234\" #(\"03\" \"1234\"))", "\"HELLO\"", "(\"1\" \"22\" \"333\")",
			"(\"03\" \"1234\")", "\"a\\\\.b\\\\*c\"", "3", "(1 3 3 5)", "\"baaa\"", "(\"a\" \",\" \"b\")");

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
		return "ClPpcreE2e";
	}

}
