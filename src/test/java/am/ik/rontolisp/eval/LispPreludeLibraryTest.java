package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LispPreludeLibraryTest {

	private static List<String> splicedNames(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		List<LispVal> processed = LispPreludeLibrary.process(program);
		// The splice is prepended, so anything beyond the original tail is a prelude
		// defun.
		List<String> names = new ArrayList<>();
		for (int i = 0; i < processed.size() - program.size(); i++) {
			String name = definitionName(processed.get(i));
			if (name != null) {
				names.add(name);
			}
		}
		return names;
	}

	@Nullable private static String definitionName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& (op.name().equals("DEFUN") || op.name().equals("RONTOLISP:ASYNC-DEFUN"))
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	@Test
	void splicesTheReferencedPreludeDefun() {
		assertThat(splicedNames("(print (rl:alist-hash-table '((\"a\" . 1))))")).contains("RONTOLISP:ALIST-HASH-TABLE");
	}

	@Test
	void aProgramDefiningThePreludeNameItselfGetsNoSplice() {
		assertThat(splicedNames("""
				(defun rontolisp:alist-hash-table (alist) alist)
				(print (rl:alist-hash-table '(("a" . 1))))
				""")).doesNotContain("RONTOLISP:ALIST-HASH-TABLE");
	}

	@Test
	void aSameMemberDefunInAnotherPackageDoesNotSuppressTheSplice() {
		// alexandria (loaded by cl-postgres, and pulled in by many quicklisp systems)
		// defines its OWN alist-hash-table. That is ALEXANDRIA:ALIST-HASH-TABLE and says
		// nothing about RONTOLISP:ALIST-HASH-TABLE, which the user program calls.
		assertThat(splicedNames("""
				(defpackage :alexandria (:use :cl) (:export #:alist-hash-table))
				(in-package :alexandria)
				(defun alist-hash-table (alist) alist)
				(in-package :cl-user)
				(print (rl:alist-hash-table '(("a" . 1))))
				""")).contains("RONTOLISP:ALIST-HASH-TABLE");
	}

	@Test
	void aSameMemberDefunInAnotherPackageDoesNotSuppressABareClPreludeEntry() {
		// Same for the bare-CL entries: a library's own shadowing EQUALP under
		// (in-package :demo) is DEMO:EQUALP, not the CL:EQUALP the user program calls.
		assertThat(splicedNames("""
				(defpackage :demo (:use :cl) (:shadow #:equalp) (:export #:equalp))
				(in-package :demo)
				(defun equalp (a b) (eq a b))
				(in-package :cl-user)
				(print (equalp "a" "A"))
				""")).contains("EQUALP");
	}

	@Test
	void aBareReferenceInAnotherPackageDoesNotPullInThePreludeDefun() {
		// The mirror image: ALEXANDRIA:ALIST-HASH-TABLE being CALLED is not a reference
		// to the rontolisp one, so nothing is spliced for it.
		assertThat(splicedNames("""
				(defpackage :alexandria (:use :cl) (:export #:alist-hash-table))
				(in-package :alexandria)
				(defun alist-hash-table (alist) alist)
				(defun use-it (alist) (alist-hash-table alist))
				""")).isEmpty();
	}

	@Test
	void bareClNamesStillSelectTheirPreludeEntry() {
		assertThat(splicedNames("(print (equalp \"a\" \"A\"))")).contains("EQUALP");
		assertThat(splicedNames("(print (cl:equalp \"a\" \"A\"))")).contains("EQUALP");
		// A prelude defun pulled in only by ANOTHER prelude defun rides along.
		assertThat(splicedNames("(print (string< \"a\" \"b\"))")).contains("STRING<", "%STRING-COMPARE");
	}

	@Test
	void aProgramDefiningABareClPreludeNameItselfGetsNoSplice() {
		assertThat(splicedNames("""
				(defun equalp (a b) (eq a b))
				(print (equalp "a" "A"))
				""")).doesNotContain("EQUALP");
	}

	@Test
	void thePreludeOctetsToStringAgreesWithTheInterpretersNativeMirror() {
		// %octets-to-string is the lenient UTF-8 decoder read-all decodes an octet-chunk
		// body with: Lisp in the prelude for the compile paths, Java in Environment for
		// the interpreter (which finds the native first and never loads the Lisp one).
		// Evaluating the prelude defun HERE overrides the native in this evaluator, so
		// the two renderings of the same rule can be pinned against each other -- on
		// valid input, and on every malformed shape the rule names: a stray
		// continuation byte, an unpaired lead byte, a truncated sequence, an #xF8+ byte.
		//
		// The defun's first move is the NATIVE %octets-to-string-strict, so this pins
		// the fast path too: where the bytes are valid UTF-8 the platform decode has to
		// be exactly what the loop would have built, and where they are not it has to
		// answer nil so the loop still runs. The cases below therefore walk both sides
		// of every strict boundary -- the overlong forms, a surrogate, U+10FFFF and the
		// code point after it.
		LispEvaluator evaluator = new LispEvaluator(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
		for (LispVal form : LispPreludeLibrary.formsFor(am.ik.rontolisp.LispNames.OCTETS_TO_STRING_INTERNAL)) {
			evaluator.eval(form);
		}
		int[][] cases = { { 0x41, 0x42 }, { 0xE3, 0x81, 0x93, 0xE3, 0x82, 0x93 }, { 0xF0, 0x9F, 0x98, 0x80, 0x41 },
				{ 0xFF, 0xFE, 0x41 }, { 0x80, 0xBF }, { 0xE3, 0x81 }, { 0xC3 }, { 0xF8, 0x41 },
				{ 0x41, 0xE3, 0x81, 0x82, 0xFF, 0x42, 0xC3, 0xBF }, {},
				// the strict boundaries, accepted side then refused side
				{ 0xC2, 0x80 }, { 0xC1, 0xBF }, { 0xC0, 0x80 }, { 0xDF, 0xBF }, { 0xE0, 0xA0, 0x80 },
				{ 0xE0, 0x80, 0x80 }, { 0xED, 0x9F, 0xBF }, { 0xED, 0xA0, 0x80 }, { 0xEF, 0xBF, 0xBF },
				{ 0xE3, 0x81, 0xC0 }, { 0xF0, 0x90, 0x80, 0x80 }, { 0xF0, 0x80, 0x80, 0x80 },
				{ 0xF4, 0x8F, 0xBF, 0xBF }, { 0xF4, 0x90, 0x80, 0x80 }, { 0xF5, 0x80, 0x80, 0x80 } };
		for (int[] c : cases) {
			long[] data = new long[c.length];
			StringBuilder literal = new StringBuilder("(rontolisp::%octets-to-string (make-array ").append(c.length)
				.append(" :element-type '(unsigned-byte 8) :initial-contents '(");
			for (int i = 0; i < c.length; i++) {
				data[i] = c[i];
				literal.append(c[i]).append(' ');
			}
			literal.append(")))");
			LispVal actual = evaluator.eval(LispReader.readFromString(literal.toString()));
			assertThat(actual).as(literal.toString()).isInstanceOf(am.ik.rontolisp.LispString.class);
			assertThat(((am.ik.rontolisp.LispString) actual).value()).as(literal.toString())
				.isEqualTo(Environment.decodeUtf8Leniently(new am.ik.rontolisp.LispIntVector(8, data)));
		}
		// And the native itself decodes valid UTF-8 as the platform does.
		assertThat(Environment.decodeUtf8Leniently(new am.ik.rontolisp.LispIntVector(8,
				new long[] { 0xE3, 0x81, 0x93, 0xE3, 0x82, 0x93, 0xF0, 0x9F, 0x98, 0x80 })))
			.isEqualTo("こん\uD83D\uDE00");
	}

	@Test
	void theStrictDecoderTakesExactlyValidUtf8() {
		// The fast half on its own terms: a string for valid UTF-8 (which is what makes
		// it worth taking), nil for everything else -- including a value that is not a
		// packed octet vector at all, because answering nil is how it hands such an
		// input back to the general loop rather than guessing at it.
		assertThat(Environment.decodeUtf8Strict(
				new am.ik.rontolisp.LispIntVector(8, new long[] { 0xE3, 0x81, 0x93, 0xF0, 0x9F, 0x98, 0x80, 0x41 })))
			.isEqualTo("こ\uD83D\uDE00A");
		assertThat(Environment.decodeUtf8Strict(new am.ik.rontolisp.LispIntVector(8, new long[0]))).isEmpty();
		// U+10FFFF is the last code point; the four-byte form after it is not UTF-8.
		assertThat(Environment
			.decodeUtf8Strict(new am.ik.rontolisp.LispIntVector(8, new long[] { 0xF4, 0x8F, 0xBF, 0xBF }))).isNotNull();
		for (long[] refused : List.of(new long[] { 0xFF }, new long[] { 0x80 }, new long[] { 0xC3 },
				new long[] { 0xC0, 0x80 }, new long[] { 0xE0, 0x80, 0x80 }, new long[] { 0xED, 0xA0, 0x80 },
				new long[] { 0xF0, 0x80, 0x80, 0x80 }, new long[] { 0xF4, 0x90, 0x80, 0x80 },
				new long[] { 0xF5, 0x80, 0x80, 0x80 }, new long[] { 0xE3, 0x81 })) {
			assertThat(Environment.decodeUtf8Strict(new am.ik.rontolisp.LispIntVector(8, refused)))
				.as(java.util.Arrays.toString(refused))
				.isNull();
		}
		// A vector of another element width is not the shape the fast path takes.
		assertThat(Environment.decodeUtf8Strict(new am.ik.rontolisp.LispIntVector(16, new long[] { 0x41 }))).isNull();
	}

	@Test
	void thePreludeMergePathnamesAgreesWithPathnameOps() {
		// merge-pathnames lives in the prelude (one definition, all four backends) while
		// make-pathname :defaults and uiop:merge-pathnames* go through the Java
		// PathnameOps helper. The two renderings of the same merge rule are pinned
		// against each other here so neither can drift.
		LispEvaluator evaluator = new LispEvaluator(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
		String[][] cases = { { "zoneinfo/", "/opt/local-time/" }, { "b.txt", "/opt/a.txt" }, { "/abs/x", "/opt/dir/" },
				{ "x.txt", "" }, { "", "/opt/a.txt" }, { "sub/dir/f", "rel/base/" }, { "f", "nodir" }, { "d/", "" },
				{ "", "" } };
		for (String[] c : cases) {
			LispVal actual = evaluator
				.eval(LispReader.readFromString("(merge-pathnames \"" + c[0] + "\" \"" + c[1] + "\")"));
			// The value is a pathname: #P + the merged namestring.
			assertThat(actual.print()).as("(merge-pathnames %s %s)", c[0], c[1])
				.isEqualTo("#P\"" + PathnameOps.mergePathnames(c[0], c[1]) + "\"");
		}
		// The one-argument shape merges against the empty (working-directory) defaults.
		assertThat(evaluator.eval(LispReader.readFromString("(merge-pathnames \"a/b.txt\")")).print())
			.isEqualTo("#P\"a/b.txt\"");
	}

	@Test
	void thePreludeMakePathnameAgreesWithPathnameOps() {
		// Same pinning as merge-pathnames above, for the pair that replaced .todo/222's
		// compile-time-only make-pathname: the RUNTIME form is prelude Lisp (one
		// definition, all four backends) and cli/CompileTimePathnameFolder still folds
		// the literal shapes with PathnameOps.makePathname. Every case below was also
		// checked against SBCL 2.2.9, which is why :defaults is component-wise and not a
		// merge -- (:directory (:relative "m") :defaults "d/a.sql") is "m/b.sql", not
		// "d/m/b.sql".
		LispEvaluator evaluator = new LispEvaluator(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
		List<String> forms = List.of("(make-pathname :name \"b\" :defaults \"d/a.sql\")",
				"(make-pathname :name \"b\" :type nil :defaults \"d/a.sql\")",
				"(make-pathname :type \"txt\" :defaults \"d/a.sql\")",
				"(make-pathname :name \"x.up\" :type \"sql\" :defaults \"db/migrations/\")",
				"(make-pathname :name \"20260101.down\" :defaults \"db/migrations/20260101.up.sql\")",
				"(make-pathname :name nil :type nil :defaults \"d/a.sql\")",
				"(make-pathname :directory (list :relative \"m\") :name \"b\" :defaults \"d/a.sql\")",
				"(make-pathname :name \"b\" :defaults \"d/a\")", "(make-pathname :defaults \"d/a.sql\")",
				"(make-pathname :name \"b\" :type \"c\")",
				"(make-pathname :directory (list :absolute \"u\" \"s\") :name \"b\" :type \"c\")",
				"(make-pathname :directory (list :relative \"m\") :defaults \"d/a.sql\")");
		for (String form : forms) {
			LispCons call = (LispCons) LispReader.readFromString(form);
			List<LispVal> args = new java.util.ArrayList<>(call.toList().subList(1, call.toList().size()));
			// The folder sees literal arguments; the prelude sees evaluated ones. The
			// only non-self-evaluating argument shape used above is the (list ...)
			// directory, which evaluates to the same list the folder reads.
			args.replaceAll(arg -> arg instanceof LispCons listCall && listCall.car() instanceof LispSymbol head
					&& "LIST".equals(head.name()) ? listToQuotedList(listCall) : arg);
			// The value is a pathname: #P + the composed namestring.
			assertThat(evaluator.eval(LispReader.readFromString(form)).print()).as(form)
				.isEqualTo("#P\"" + PathnameOps.makePathname(args) + "\"");
		}
	}

	@Test
	void thePreludePathnameSplitAgreesWithPathnameOps() {
		// pathname-name / pathname-type read %pathname-split; PathnameOps.components is
		// the Java twin make-pathname's :defaults handling uses. Both must implement the
		// one CL rule (the LAST dot separates the type, a dot at position 0 does not) --
		// the expectations are SBCL 2.2.9's answers.
		LispEvaluator evaluator = new LispEvaluator(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
		for (String path : List.of("d/a.sql", "d/a", "d/.a", "d/a.b.c", "a.sql", ".sql", "d/")) {
			PathnameOps.Components expected = PathnameOps.components(path);
			assertThat(evaluator.eval(LispReader.readFromString("(pathname-name \"" + path + "\")")).print())
				.as("(pathname-name %s)", path)
				.isEqualTo(expected.name().isEmpty() ? "NIL" : "\"" + expected.name() + "\"");
			assertThat(evaluator.eval(LispReader.readFromString("(pathname-type \"" + path + "\")")).print())
				.as("(pathname-type %s)", path)
				.isEqualTo(expected.type().isEmpty() ? "NIL" : "\"" + expected.type() + "\"");
		}
	}

	/**
	 * {@code (list :relative "m")} -> the LIST it evaluates to, which is the value
	 * PathnameOps reads.
	 */
	private static LispVal listToQuotedList(LispCons listCall) {
		List<LispVal> elements = listCall.toList();
		LispVal data = am.ik.rontolisp.LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 1; i--) {
			data = new LispCons(elements.get(i), data);
		}
		return data;
	}

	@Test
	void anUnresolvableProgramStillGetsItsSplice() {
		// A package error is not this pass's to report -- the compiler runs the identical
		// resolution first thing -- so selection falls back to member-name matching.
		assertThat(splicedNames("""
				(in-package :no-such-package)
				(print (rl:alist-hash-table '(("a" . 1))))
				""")).contains("RONTOLISP:ALIST-HASH-TABLE");
	}

}
