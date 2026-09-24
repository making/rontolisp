package am.ik.rontolisp.eval;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
		// The three are one group: print-condition-backtrace is written over
		// print-backtrace, which applies raw-print-backtrace -- upstream's own layering,
		// so the fixpoint pulls both in behind the name the program spelled. The order is
		// the inventory's, which is a promise the tables keep by being insertion-ordered
		// (Map.copyOf would salt it per JVM run and make the spliced prefix vary).
		assertThat(splicedNames("(uiop/image:print-condition-backtrace c :stream s)")).containsExactly(
				"UIOP/IMAGE:PRINT-BACKTRACE", "UIOP/IMAGE:PRINT-CONDITION-BACKTRACE", "UIOP/IMAGE:RAW-PRINT-BACKTRACE");
		assertThat(splicedNames("(uiop:print-condition-backtrace c :stream s)")).containsExactly(
				"UIOP/IMAGE:PRINT-BACKTRACE", "UIOP/IMAGE:PRINT-CONDITION-BACKTRACE", "UIOP/IMAGE:RAW-PRINT-BACKTRACE");
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
		// with-current-directory expands into call-with-current-directory, a spliced
		// defun the program never names -- same rule, same reason.
		assertThat(splicedNames("(uiop:with-current-directory (\"/tmp\") (print 1))"))
			.contains("UIOP/FILESYSTEM:CALL-WITH-CURRENT-DIRECTORY");
		// The five uiop/stream with-* macros (.todo/359) expand over their
		// call-with-* functions the same way.
		assertThat(splicedNames("(uiop:with-input-file (s \"x\") (print s))"))
			.contains("UIOP/STREAM:CALL-WITH-INPUT-FILE");
		assertThat(splicedNames("(uiop:with-output-file (s \"x\") (print s))"))
			.contains("UIOP/STREAM:CALL-WITH-OUTPUT-FILE");
		assertThat(splicedNames("(uiop:with-input (s \"x\") (print s))")).contains("UIOP/STREAM:CALL-WITH-INPUT-FILE");
		assertThat(splicedNames("(uiop:with-output (s nil) (print s))")).contains("UIOP/STREAM:CALL-WITH-OUTPUT-FILE");
		assertThat(splicedNames("(uiop:with-safe-io-syntax () (print 1))"))
			.contains("UIOP/STREAM:CALL-WITH-SAFE-IO-SYNTAX");
		// The three FUNCTION rows (.todo/359): eval-input, input-string and
		// output-string name the %call-with-input / %call-with-output prelude
		// entries in their own bodies, whose pathname arms open through the file
		// openers the program never names -- the same edge through the front door.
		assertThat(splicedNames("(print (uiop:eval-input \"(+ 1 2)\"))")).contains("UIOP/STREAM:CALL-WITH-INPUT-FILE");
		assertThat(splicedNames("(print (uiop:input-string \"x\"))")).contains("UIOP/STREAM:CALL-WITH-INPUT-FILE");
		assertThat(splicedNames("(print (uiop:output-string \"x\" nil))"))
			.contains("UIOP/STREAM:CALL-WITH-OUTPUT-FILE");
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
		assertThat(splicedNames("(uiop:add-package-local-nickname \"A\" \"CL\")")).isEmpty();
		assertThat(splicedNames("(uiop:remove-package-local-nickname \"A\")")).isEmpty();
	}

	@Test
	void symbolCallCarriesADefinitionBesideItsFold() {
		// The fold rewrites a CALL of symbol-call, so the definition is dead weight
		// there -- but it is what a first-class #'uiop:symbol-call resolves to, and
		// without it dexador's (apply #'uiop:symbol-call '#:pkg '#:name uri args)
		// backend dispatch did not compile at all. Same arrangement as file-exists-p.
		assertThat(splicedNames("(uiop:symbol-call :cl :list 1 2)")).contains("UIOP/PACKAGE:SYMBOL-CALL",
				"UIOP/PACKAGE:FIND-SYMBOL*", "UIOP/PACKAGE:FIND-PACKAGE*");
		assertThat(splicedNames("(mapcar #'uiop:symbol-call nil)")).contains("UIOP/PACKAGE:SYMBOL-CALL");
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

	@Test
	void aNameLookupAllocatesNothing() {
		// The interpreter asks definesName on EVERY setf evaluation (the uiop place
		// trigger, re-run because setf re-expands per evaluation), so the lookup is on
		// the hottest loop a linalg defun has: (setf (aref out i) x). It used to join the
		// feature names into a cache key per call -- measured 8% of an interpreted
		// linalg:arange. An allocation budget pins the fix without a timing.
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		boolean seen = UiopLibrary.definesName("AREF");
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 100_000; i++) {
			seen |= UiopLibrary.definesName("AREF");
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertThat(seen).isFalse();
		assertThat(allocated).as("bytes allocated by 100000 lookups").isLessThan(1_000_000);
	}

}
