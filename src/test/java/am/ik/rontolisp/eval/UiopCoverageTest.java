package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispPackage;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The uiop coverage gate. Every uiop item reports here: the target is the checked-in
 * inventory ({@code uiop-exports.txt}, uiop 3.3.7), and this test says how much of it is
 * real -- per sub-package, printed, so the number is measured rather than asserted.
 *
 * <p>
 * The three invariants that must hold whatever the number is:
 * <ol>
 * <li>every listed symbol is external in {@code uiop} AND in the sub-package the row
 * names (a library naming either spelling reaches the same symbol);</li>
 * <li>every listed symbol HAS a definition -- {@code fboundp} for a function or macro,
 * {@code boundp} for a variable, a registered type for a condition / class / type. No
 * uiop name may reach a caller as {@code The function UIOP:X is undefined}; the honest
 * answer for something not filled in yet is {@code uiop:not-implemented-error};</li>
 * <li>the qualified names {@code LispNames} hard-codes agree with the inventory's home
 * packages, so a switch label cannot drift from the table the resolver uses.</li>
 * </ol>
 *
 * @see <a href="file:../../../../../../.kb/uiop.md">.kb/uiop.md</a>
 */
class UiopCoverageTest {

	@Test
	void everyListedSymbolIsExternalInUiopAndInItsSubPackage() {
		PackageRegistry registry = new PackageRegistry();
		LispPackage uiop = registry.get(LispNames.UIOP_PKG);
		List<String> missing = new ArrayList<>();
		for (UiopExports.Entry entry : UiopExports.entries()) {
			if (!uiop.externals().contains(entry.symbol())) {
				missing.add("UIOP:" + entry.symbol());
			}
			LispPackage subPackage = registry.get(entry.subPackage());
			if (!subPackage.externals().contains(entry.symbol())) {
				missing.add(entry.subPackage() + ":" + entry.symbol());
			}
		}
		assertThat(missing).isEmpty();
	}

	@Test
	void bothSpellingsOfEveryListedSymbolNameTheOneSymbol() {
		// uiop imports each member from the sub-package that DEFINES it, so a qualified
		// occurrence of either spelling canonicalizes to the home one -- which is what
		// lets one definition serve both, instead of two functions with one member name.
		assertThat(UiopExports.qualified(LispNames.GETENV)).isEqualTo("UIOP/OS:GETENV");
		for (UiopExports.Entry entry : UiopExports.entries()) {
			String home = UiopExports.homePackage(entry.symbol());
			assertThat(home).isNotNull();
			assertThat(UiopExports.qualified(entry.symbol())).isEqualTo(home + ":" + entry.symbol());
			assertThat(UiopExports.denotes(LispNames.UIOP_PKG, entry.symbol(), entry.symbol())).isTrue();
			assertThat(UiopExports.denotes(home, entry.symbol(), entry.symbol())).isTrue();
		}
	}

	@Test
	void everyListedSymbolHasADefinition() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		for (LispVal form : UiopLibrary.forms()) {
			evaluator.eval(form);
		}
		List<String> undefined = new ArrayList<>();
		for (UiopExports.Entry entry : UiopExports.entries()) {
			String qualified = UiopExports.qualified(entry.symbol());
			boolean defined;
			if (entry.is("function") || entry.is("macro")) {
				// A built-in macro expansion is not a function binding and never will be
				// (the expansion runs before any lookup), so it answers for itself.
				defined = LispMacroExpander.hasUiopMacroExpansion(entry.symbol())
						|| truthy(evaluator, "(fboundp '" + qualified + ")");
			}
			else if (entry.is("variable") || entry.is("constant")) {
				defined = truthy(evaluator, "(boundp '" + qualified + ")");
			}
			else {
				// A condition / class / type name is neither fbound nor bound; what makes
				// it defined is that the type exists.
				defined = truthy(evaluator, "(if (find-class '" + qualified + " nil) t nil)")
						|| UiopLibrary.definesName(qualified);
			}
			if (!defined) {
				undefined.add(qualified + " (" + entry.kind() + ")");
			}
		}
		assertThat(undefined).isEmpty();
	}

	@Test
	void anUnimplementedMemberSignalsNotImplementedErrorNamingTheOperation() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal caught = evaluator.eval(LispReader.readFromString("""
				(handler-case (uiop:chdir "/tmp")
				  (uiop:not-implemented-error (c) (princ-to-string c)))
				"""));
		assertThat(caught.print()).contains("Not (currently) implemented on rontolisp", "UIOP/OS:CHDIR");
	}

	@Test
	void anUnimplementedMacroDoesNotEvaluateTheFormsItWasHanded() {
		// (uiop:with-current-directory (d) (defun f ...)) must not define f before
		// signalling: a macro that does nothing must do nothing with the forms it was
		// handed. with-upgradability used to be the example here and is a real expansion
		// now, so the probe moved to a macro of a sub-package nothing implements yet.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		for (LispVal form : LispReader.readAllFromString("""
				(handler-case (uiop:with-current-directory ("/tmp") (defun %uiop-probe () 1))
				  (uiop:not-implemented-error (c) c))
				""")) {
			evaluator.eval(form);
		}
		assertThat(evaluator.eval(LispReader.readFromString("(fboundp '%uiop-probe)"))).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void anImplementedMacroIsNeverAlsoStubbed() {
		// The two tables that must not drift: a uiop macro LispMacroExpander expands is
		// never given a not-implemented-error definition, and every macro with an
		// expansion really is a macro in the inventory.
		for (UiopExports.Entry entry : UiopExports.entries()) {
			if (LispMacroExpander.hasUiopMacroExpansion(entry.symbol())) {
				assertThat(entry.is("macro")).as(entry.symbol() + " has a macro expansion but is not a macro").isTrue();
				assertThat(UiopLibrary.formsFor(UiopExports.qualified(entry.symbol())))
					.as(entry.symbol() + " has a macro expansion and a definition")
					.isEmpty();
			}
		}
	}

	@Test
	void withUpgradabilityIsAPrognSoItsDefinitionsStayTopLevel() {
		// Upstream wraps EVERY one of its definitions in with-upgradability; rontolisp
		// has no image to upgrade, so it lowers to progn -- and the top-level flattening
		// has to splice that progn, or the wrapped defuns never become definitions on
		// the compile paths. Both spellings, since the pass runs on either side of
		// package resolution.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		for (LispVal form : LispReader.readAllFromString("""
				(uiop:with-upgradability () (defun %uiop-up () 7) (defvar *%uiop-up* 8))
				""")) {
			evaluator.eval(form);
		}
		assertThat(evaluator.eval(LispReader.readFromString("(list (%uiop-up) *%uiop-up*)")).print())
			.isEqualTo("(7 8)");
		List<LispVal> flattened = LispMacroExpander.flattenTopLevel(LispReader.readAllFromString("""
				(uiop/utility:with-upgradability () (defun a () 1) (defun b () 2))
				(uiop:with-upgradability () (defun c () 3))
				"""));
		assertThat(flattened).hasSize(3);
		assertThat(flattened.stream().map(LispVal::print)).allMatch(form -> form.startsWith("(DEFUN "));
	}

	@Test
	void theHardCodedQualifiedNamesAgreeWithTheInventory() {
		assertThat(LispNames.UIOP_GETENV).isEqualTo(UiopExports.qualified(LispNames.GETENV));
		assertThat(LispNames.UIOP_SYMBOL_CALL).isEqualTo(UiopExports.qualified(LispNames.SYMBOL_CALL));
		assertThat(LispNames.UIOP_IF_LET_QUALIFIED).isEqualTo(UiopExports.qualified(LispNames.IF_LET));
		assertThat(LispNames.UIOP_WITH_DEPRECATION_QUALIFIED)
			.isEqualTo(UiopExports.qualified(LispNames.WITH_DEPRECATION));
		assertThat(LispNames.UIOP_WITH_TEMPORARY_FILE_QUALIFIED)
			.isEqualTo(UiopExports.qualified(LispNames.WITH_TEMPORARY_FILE));
		assertThat(LispNames.UIOP_IMAGE_PKG).isEqualTo(UiopExports.homePackage(LispNames.PRINT_CONDITION_BACKTRACE));
		// when-let / when-let* are alexandria's, not uiop's: they stay owned by uiop
		// itself, so they must NOT appear in the inventory.
		assertThat(UiopExports.homePackage(LispNames.WHEN_LET)).isNull();
		assertThat(UiopExports.homePackage(LispNames.WHEN_LET_STAR)).isNull();
		assertThat(UiopExports.homePackage(LispNames.NAMESTRING)).isNull();
	}

	@Test
	void theInventoryIsUiop337() {
		assertThat(UiopExports.subPackages()).hasSize(15).startsWith("UIOP/PACKAGE").endsWith("UIOP/BACKWARD-DRIVER");
		assertThat(UiopExports.entries()).hasSize(435);
		assertThat(UiopExports.homePackages()).hasSize(429);
	}

	/**
	 * The coverage report. Not an assertion on the NUMBER -- that moves with every uiop
	 * item -- but on the two things that must stay true while it moves: an implemented
	 * name is never also stubbed, and the total is the whole inventory.
	 */
	@Test
	void printCoverage() {
		Map<String, int[]> perPackage = new LinkedHashMap<>();
		for (String subPackage : UiopExports.subPackages()) {
			perPackage.put(subPackage, new int[2]);
		}
		int implemented = 0;
		for (UiopExports.Entry entry : UiopExports.entries()) {
			int[] counts = perPackage.computeIfAbsent(entry.subPackage(), ignored -> new int[2]);
			counts[1]++;
			if (UiopLibrary.isImplemented(UiopExports.qualified(entry.symbol()))) {
				counts[0]++;
				implemented++;
			}
		}
		StringBuilder report = new StringBuilder(
				"uiop coverage: " + implemented + "/" + UiopExports.entries().size() + " exports\n");
		perPackage.forEach((subPackage, counts) -> report
			.append(String.format("  %4d/%-4d %s%n", counts[0], counts[1], subPackage)));
		System.out.print(report);
		for (UiopExports.Entry entry : UiopExports.entries()) {
			String qualified = UiopExports.qualified(entry.symbol());
			assertThat(UiopLibrary.isImplemented(qualified) || UiopLibrary.definesName(qualified))
				.as(qualified + " has neither an implementation nor a stub")
				.isTrue();
		}
		// A member the interpreter defines in Java carries no library form at all, so
		// nothing can shadow the built-in; a member implemented in Lisp carries its own.
		assertThat(UiopLibrary.formsFor(UiopExports.qualified(LispNames.SYMBOL_CALL))).isEmpty();
		assertThat(UiopLibrary.formsFor(UiopExports.qualified(LispNames.EMPTYP))).hasSize(1);
		// getenv carries TWO: the reader and the (setf getenv) writer, keyed under the
		// one member -- the reader consults the override map the writer fills, and no
		// backend can write the process environment itself (.kb/uiop.md).
		assertThat(UiopLibrary.formsFor(UiopExports.qualified(LispNames.GETENV))).hasSize(2);
		assertThat(implemented).isBetween(1, UiopExports.entries().size());
	}

	private static boolean truthy(LispEvaluator evaluator, String source) {
		return !(evaluator.eval(LispReader.readFromString(source)) instanceof LispNil);
	}

}
