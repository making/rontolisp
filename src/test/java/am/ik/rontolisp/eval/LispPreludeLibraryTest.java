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
	void bothUiopSpellingsOfPrintConditionBacktraceSelectTheOneEntry() {
		// The entry defines UIOP/IMAGE:PRINT-CONDITION-BACKTRACE (its home package,
		// as upstream); the uiop package IMPORTS the name, so a program spelling
		// uiop: resolves to the same symbol and must select the same single defun --
		// not miss the splice and compile to a call-time undefined-function error.
		assertThat(splicedNames("(uiop/image:print-condition-backtrace c :stream s)"))
			.containsExactly("UIOP/IMAGE:PRINT-CONDITION-BACKTRACE");
		assertThat(splicedNames("(uiop:print-condition-backtrace c :stream s)"))
			.containsExactly("UIOP/IMAGE:PRINT-CONDITION-BACKTRACE");
	}

	@Test
	void aProgramDefiningABareClPreludeNameItselfGetsNoSplice() {
		assertThat(splicedNames("""
				(defun equalp (a b) (eq a b))
				(print (equalp "a" "A"))
				""")).doesNotContain("EQUALP");
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
			assertThat(actual.print()).as("(merge-pathnames %s %s)", c[0], c[1])
				.isEqualTo("\"" + PathnameOps.mergePathnames(c[0], c[1]) + "\"");
		}
		// The one-argument shape merges against the empty (working-directory) defaults.
		assertThat(evaluator.eval(LispReader.readFromString("(merge-pathnames \"a/b.txt\")")).print())
			.isEqualTo("\"a/b.txt\"");
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
