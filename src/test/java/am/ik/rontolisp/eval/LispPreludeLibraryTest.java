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
