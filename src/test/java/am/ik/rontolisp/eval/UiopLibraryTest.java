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

class UiopLibraryTest {

	/** The names the definitions PREPENDED to the program define, in splice order. */
	private static List<String> splicedNames(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		List<LispVal> processed = UiopLibrary.process(program);
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
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name && op.name().startsWith("DEF")) {
			return name.name();
		}
		return null;
	}

	@Test
	void aProgramThatNamesNoUiopMemberGetsNothing() {
		List<LispVal> program = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(UiopLibrary.process(program)).isEqualTo(program);
	}

	@Test
	void bothSpellingsSelectTheOneDefinition() {
		// The definition carries print-condition-backtrace's HOME package (uiop/image,
		// as upstream); the uiop package IMPORTS the name, so a program spelling uiop:
		// resolves to the same symbol and must select the same single defun -- not miss
		// the splice and compile to a call-time undefined-function error.
		assertThat(splicedNames("(uiop/image:print-condition-backtrace c :stream s)"))
			.containsExactly("UIOP/IMAGE:PRINT-CONDITION-BACKTRACE");
		assertThat(splicedNames("(uiop:print-condition-backtrace c :stream s)"))
			.containsExactly("UIOP/IMAGE:PRINT-CONDITION-BACKTRACE");
	}

	@Test
	void selectionRunsToAFixpoint() {
		// default-temporary-directory calls ensure-directory-pathname, which the program
		// never names itself.
		assertThat(splicedNames("(print (uiop:default-temporary-directory))"))
			.contains("UIOP/PATHNAME:ENSURE-DIRECTORY-PATHNAME", "UIOP/STREAM:DEFAULT-TEMPORARY-DIRECTORY");
	}

	@Test
	void aStubDragsInTheConditionItSignals() {
		// Every synthesized stub signals uiop:not-implemented-error, so selecting one
		// must select the condition and the function that signals it -- otherwise the
		// stub compiles to a call of an undefined name, which is the very failure the
		// stubs exist to abolish.
		assertThat(splicedNames("(uiop:chdir \"/tmp\")")).contains("UIOP/OS:CHDIR",
				"UIOP/UTILITY:NOT-IMPLEMENTED-ERROR");
	}

	@Test
	void withTemporaryFileSelectsWhatItsExpansionCalls() {
		// The expansion runs inside the expression compilers, long after this pass, and
		// reaches the prelude's %temp-file-name -- which calls these two. Neither name
		// occurs in the program, so only the surface-form rule can select them.
		assertThat(splicedNames("(uiop:with-temporary-file (:pathname p) (print p))")).contains(
				"UIOP/PATHNAME:ENSURE-DIRECTORY-PATHNAME", "UIOP/STREAM:DEFAULT-TEMPORARY-DIRECTORY",
				"UIOP/FILESYSTEM:DELETE-FILE-IF-EXISTS");
	}

	@Test
	void theOtherMacrosSelectWhatTheirExpansionsCall() {
		// Same rule, same reason: none of these callees occurs in the program, so the
		// compiled program said "The function UIOP/UTILITY:X is undefined" at run time
		// while the interpreter (which lazy-loads on resolution) worked. Only the DIRECT
		// callee is listed in the table; the fixpoint pulls the rest in, which is what
		// the match-condition-p entry below checks.
		assertThat(splicedNames("(uiop:with-muffled-conditions ('(warning)) (warn \"x\"))")).contains(
				"UIOP/UTILITY:CALL-WITH-MUFFLED-CONDITIONS", "UIOP/UTILITY:MATCH-ANY-CONDITION-P",
				"UIOP/UTILITY:MATCH-CONDITION-P");
		assertThat(splicedNames("(uiop:uiop-debug)")).contains("UIOP/UTILITY:LOAD-UIOP-DEBUG-UTILITY");
		assertThat(splicedNames("(let ((s 1)) (uiop:latest-timestamp-f s 5))"))
			.contains("UIOP/UTILITY:LATEST-TIMESTAMP", "UIOP/UTILITY:TIMESTAMPS-LATEST");
		// with-pathname-defaults' no-defaults arm binds the *nil-pathname* VARIABLE --
		// the selection carries a defvar exactly like a defun.
		assertThat(splicedNames("(uiop:with-pathname-defaults () (print 1))")).contains("UIOP/PATHNAME:*NIL-PATHNAME*");
		assertThat(splicedNames("(uiop:with-enough-pathname (p :defaults #P\"/tmp/\") (print p))")).contains(
				"UIOP/PATHNAME:CALL-WITH-ENOUGH-PATHNAME", "UIOP/PATHNAME:ENOUGH-PATHNAME", "UIOP/PATHNAME:SUBPATHP");
	}

	@Test
	void aSecondRunSplicesNothingMore() {
		// LispPreludeLibrary.process drives this pass, so a pipeline that also calls it
		// directly would otherwise prepend a second copy of every definition.
		List<LispVal> once = UiopLibrary.process(LispReader.readAllFromString("(print (uiop:emptyp nil))"));
		assertThat(UiopLibrary.process(once)).isEqualTo(once);
	}

	@Test
	void aProgramDefiningAUiopMemberItselfGetsNoSplice() {
		assertThat(splicedNames("(defun uiop:emptyp (x) x) (print (uiop:emptyp nil))")).isEmpty();
	}

	@Test
	void theBuiltInMacrosAndJavaBuiltinsAreNeverSpliced() {
		// A member with a real LispMacroExpander expansion or a Java built-in carries no
		// library form at all, so nothing can shadow it.
		assertThat(splicedNames("(uiop:if-let ((x 1)) x)")).isEmpty();
		assertThat(splicedNames("(uiop:symbol-call :cl :list 1 2)")).isEmpty();
	}

	@Test
	void getenvSplicesItsDefinitionAndItsSetfWriter() {
		// getenv is NOT a Java built-in any more: the public name is Lisp over the
		// %host-getenv primitive, so that the override map a (setf (uiop:getenv x) v)
		// writes is consulted on every backend. Both forms are keyed under the one
		// member, so a program that only WRITES still gets the reader and vice versa.
		assertThat(splicedNames("(print (uiop:getenv \"HOME\"))")).containsExactly("UIOP/OS:GETENV");
		assertThat(splicedNames("(setf (uiop:getenv \"HOME\") \"/tmp\")")).containsExactly("UIOP/OS:GETENV");
	}

	@Test
	void thePreludePassDrivesThisOne() {
		// The two are one pass with a fixed order: uiop first, so the prelude selection
		// sees the prelude names the uiop bodies reach (merge-pathnames* calls
		// cl:merge-pathnames, which calls %path-ns).
		List<LispVal> out = LispPreludeLibrary
			.process(LispReader.readAllFromString("(defun m (a b) (uiop:merge-pathnames* a b))"));
		List<String> names = new ArrayList<>();
		for (LispVal form : out) {
			String name = definitionName(form);
			if (name != null) {
				names.add(name);
			}
		}
		assertThat(names).contains("UIOP/PATHNAME:MERGE-PATHNAMES*", "MERGE-PATHNAMES", "%PATH-NS");
	}

}
