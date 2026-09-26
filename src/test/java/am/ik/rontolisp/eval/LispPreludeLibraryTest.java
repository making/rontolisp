package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ArrayElementTypes;
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

	// The two %make-array-et* helpers turn a RUNTIME :element-type designator back into
	// literal spellings, one arm per specialized code, because every backend but the
	// interpreter decides an array's representation from the literal designator at the
	// call site. The set of arms is a fact about ArrayElementTypes, and this test asks
	// the ENUM rather than listing the widths: a hand-written list of seven would be a
	// fifth transcription with a green tick on it. Four such transcriptions existed --
	// these two helpers, the inline lowering and the program-scan mask -- all documented
	// as covering seven codes and all spelling six, so bfloat16 through a runtime
	// designator degraded to a boxed general array everywhere but the interpreter
	// (.todo/487).
	@Test
	void bothMakeArrayElementTypeHelpersCoverEverySpecializedCode() {
		// Each helper is selected by the call SHAPE: the -fp twin only by a site that
		// also spells :fill-pointer / :adjustable.
		Map<String, String> probes = Map.of("%MAKE-ARRAY-ET", "(defun f (n et) (make-array n :element-type et))",
				"%MAKE-ARRAY-ET-FP", "(defun f (n et) (make-array n :element-type et :fill-pointer 0 :adjustable t))");
		for (String helper : probes.keySet()) {
			List<LispVal> spliced = LispPreludeLibrary.process(LispReader.readAllFromString(probes.get(helper)));
			LispVal defun = spliced.stream().filter(v -> helper.equals(definitionName(v))).findFirst().orElse(null);
			assertThat(defun).as("%s must be spliced for a runtime :element-type site", helper).isNotNull();
			List<String> designators = new ArrayList<>();
			collectElementTypeDesignators(defun, designators);
			for (int code : ArrayElementTypes.specializedCodes()) {
				assertThat(designators)
					.as("%s must carry an arm allocating %s -- the arms are generated from "
							+ "ArrayElementTypes.specializedCodes(), so a missing one means the "
							+ "generator stopped agreeing with the code space", helper,
							ArrayElementTypes.valueOf(code).print())
					.contains(ArrayElementTypes.valueOf(code).print());
			}
		}
	}

	// Every (quote <designator>) that follows an :element-type keyword in the form,
	// printed. The helper's arms are the only place one appears in it.
	private static void collectElementTypeDesignators(LispVal form, List<String> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		List<LispVal> parts = cons.toList();
		for (int i = 0; i + 1 < parts.size(); i++) {
			if (parts.get(i) instanceof LispSymbol kw && ":ELEMENT-TYPE".equals(kw.name())
					&& parts.get(i + 1) instanceof LispCons quoted && quoted.car() instanceof LispSymbol q
					&& "QUOTE".equals(q.name()) && quoted.cdr() instanceof LispCons rest) {
				out.add(rest.car().print());
			}
		}
		for (LispVal part : parts) {
			collectElementTypeDesignators(part, out);
		}
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
		// The defun's first move is the NATIVE %octets-to-string-packed, which answers
		// every packed octet vector itself, so the loop is reached only by a GENERAL
		// array: each case is decoded both ways -- the general array through the Lisp
		// loop, the packed vector through the native -- and both must be the Java
		// mirror's answer. The cases walk both sides of every strict boundary (the
		// overlong forms, a surrogate, U+10FFFF and the code point after it), because
		// the native takes valid UTF-8 by a validate-then-copy and the rest by a
		// transcode, and the two must meet exactly where the validator draws the line.
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
			StringBuilder contents = new StringBuilder();
			for (int i = 0; i < c.length; i++) {
				data[i] = c[i];
				contents.append(c[i]).append(' ');
			}
			int[] expected = Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, data));
			for (String elementType : List.of("", " :element-type '(unsigned-byte 8)")) {
				String literal = "(rontolisp::%octets-to-string (make-array " + c.length + elementType
						+ " :initial-contents '(" + contents + ")))";
				LispVal actual = evaluator.eval(LispReader.readFromString(literal));
				assertThat(actual).as(literal).isInstanceOf(am.ik.rontolisp.LispString.class);
				assertThat(codePoints((am.ik.rontolisp.LispString) actual)).as(literal).containsExactly(expected);
			}
		}
		// And the native itself decodes valid UTF-8 as the platform does.
		assertThat(Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8,
				new long[] { 0xE3, 0x81, 0x93, 0xE3, 0x82, 0x93, 0xF0, 0x9F, 0x98, 0x80 })))
			.containsExactly("こん\uD83D\uDE00".codePoints().toArray());
	}

	@Test
	void theDecodeOfValidUtf8IsThePlatformsStrictDecode() {
		// The interpreter decodes in ONE lenient pass: on well-formed input every arm
		// answers what a strict decoder answers, which is what lets it skip the platform
		// decode it used to try first. Pinned on both sides of every strict boundary --
		// the valid ones must be the platform's code points exactly, and each refused one
		// must still decode (never signal) to its lenient answer.
		List<long[]> valid = List.of(new long[] { 0xE3, 0x81, 0x93, 0xF0, 0x9F, 0x98, 0x80, 0x41 }, new long[0],
				new long[] { 0xC2, 0x80 }, new long[] { 0xDF, 0xBF }, new long[] { 0xE0, 0xA0, 0x80 },
				new long[] { 0xED, 0x9F, 0xBF }, new long[] { 0xEF, 0xBF, 0xBF }, new long[] { 0xF0, 0x90, 0x80, 0x80 },
				new long[] { 0xF4, 0x8F, 0xBF, 0xBF });
		for (long[] octets : valid) {
			byte[] bytes = new byte[octets.length];
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) octets[i];
			}
			assertThat(Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, octets)))
				.as(java.util.Arrays.toString(octets))
				.containsExactly(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).codePoints().toArray());
		}
		// Refused by a strict decoder, each byte its own character here: a stray lead,
		// an unpaired continuation, a truncated sequence, and the 4-byte form past
		// U+10FFFF. An overlong form and an encoded surrogate assemble their bits.
		assertThat(Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, new long[] { 0xFF })))
			.containsExactly(0xFF);
		assertThat(Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, new long[] { 0x80 })))
			.containsExactly(0x80);
		assertThat(Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, new long[] { 0xE3, 0x81 })))
			.containsExactly(0xE3, 0x81);
		assertThat(Environment
			.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, new long[] { 0xF4, 0x90, 0x80, 0x80 })))
			.containsExactly(0xF4, 0x90, 0x80, 0x80);
		assertThat(Environment.decodeUtf8CodePoints(am.ik.rontolisp.LispIntVector.of(8, new long[] { 0xC1, 0x81 })))
			.containsExactly(0x41);
		// Two encoded surrogates stay two characters: the decoder answers code points,
		// and a pair of them is not re-read as the supplementary character a UTF-16
		// string would make of it.
		assertThat(Environment.decodeUtf8CodePoints(
				am.ik.rontolisp.LispIntVector.of(8, new long[] { 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80 })))
			.containsExactly(0xD83D, 0xDE00);
	}

	private static int[] codePoints(am.ik.rontolisp.LispString string) {
		int[] out = new int[string.length()];
		for (int i = 0; i < out.length; i++) {
			out[i] = string.charAt(i);
		}
		return out;
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
		// Same pinning as merge-pathnames above, for the pair that replaced the
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

	@Test
	void aPackageProgramThatPrin1sSplicesTheRendererWithoutTheCaseFold() {
		// The package gate: a program leaving cl-user with a prin1-style conversion in
		// reach carries %print-cased (and the qualifier strip it needs), but not the
		// case-fold and re-basing leaves only a printer-control VARIABLE can reach.
		List<String> names = splicedNames("""
				(defpackage :spa-app (:use :cl))
				(in-package :spa-app)
				(print 'x)
				""");
		assertThat(names).contains("%PRINT-CASED", "%PC-UNQUALIFIED")
			.doesNotContain("%PRINT-CASE-FOLD", "%PRINT-RADIXED");
		// princ never spells a qualifier, so a program that only princs routes nothing.
		assertThat(splicedNames("""
				(defpackage :spa-app (:use :cl))
				(in-package :spa-app)
				(princ 'x)
				""")).doesNotContain("%PRINT-CASED");
		// Naming a variable pulls the leaves.
		assertThat(splicedNames("(let ((*print-case* :downcase)) (print 'x))")).contains("%PRINT-CASED",
				"%PRINT-CASE-FOLD", "%PRINT-RADIXED");
	}

	@Test
	void everyPreludeDefunOfAClFunctionThatAnswersSeveralValuesIsKnownToTheTailDiscipline() {
		// The compile paths clear the multiple-value channel after a tail call of a cl
		// FUNCTION, by name (.kb/multiple-values.md, "A tail settles the channel"), so
		// a prelude defun of a cl name that answers other than one value -- a (values)
		// or a (values a b ...) anywhere in its body -- must be listed in
		// LispMacroExpander.passesMultipleValues, or its extra values die at every
		// caller's tail.
		List<String> unlisted = new ArrayList<>();
		for (String source : LispPreludeLibrary.sources().values()) {
			for (LispVal form : LispReader.readAllFromString(source)) {
				String name = definitionName(form);
				if (name != null && am.ik.rontolisp.PackageRegistry.isClFunctionName(name)
						&& mentionsValuesOtherThanOne(form)
						&& !am.ik.rontolisp.macro.LispMacroExpander.passesMultipleValues(name)) {
					unlisted.add(name);
				}
			}
		}
		assertThat(unlisted).isEmpty();
	}

	private static boolean mentionsValuesOtherThanOne(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol op && op.name().equals("VALUES") && cons.isProperList()
				&& cons.toList().size() != 2) {
			return true;
		}
		for (LispVal cur = cons; cur instanceof LispCons cell; cur = cell.cdr()) {
			if (mentionsValuesOtherThanOne(cell.car())) {
				return true;
			}
		}
		return false;
	}

}
