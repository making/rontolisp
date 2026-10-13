package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the outlining retry loop asks the next compile for: only a budget whose cut
 * differs from the one the measuring compile used, because a compile of the same forms
 * measures the same size (.kb/hot-path-method-size.md).
 */
class AstOutlinerTest {

	private static final int FIRST_TARGET = 6000;

	private static final int FLOOR_TARGET = 2000;

	/** {@code (setq acc (+ acc (car (list k 0))))}: twelve nodes, too small to move. */
	private static String statement(int k) {
		return "(setq acc (+ acc (car (list " + k + " 0))))";
	}

	/** A progn of ten statements: 122 nodes, big enough to move. */
	private static String group(int from) {
		StringBuilder sb = new StringBuilder("(progn");
		for (int k = from; k < from + 10; k++) {
			sb.append(' ').append(statement(k));
		}
		return sb.append(')').toString();
	}

	private static List<LispVal> program(String body) {
		return LispReader.readAllFromString("(defun f (x) " + body + ")");
	}

	/** A call no position of which is cut: its operator is not on the whitelist. */
	private static String opaqueCall(int arguments) {
		StringBuilder sb = new StringBuilder("(opaque");
		for (int k = 0; k < arguments; k++) {
			sb.append(' ').append(statement(k));
		}
		return sb.append(')').toString();
	}

	private static List<LispVal> topProgram(String form) {
		return LispReader.readAllFromString(form);
	}

	/** Eight big case arms under one call: the top-level f64 shape. */
	private static String topCaseForm() {
		StringBuilder sb = new StringBuilder("(print (case k");
		for (int b = 0; b < 8; b++) {
			sb.append(" (").append(b).append(' ').append(group(b * 10)).append(')');
		}
		return sb.append("))").toString();
	}

	@Test
	void aFunctionNoBudgetCanCutIsNeverAskedFor() {
		// No position the walker knows to be evaluated, so the first target cuts nothing
		// -- and the compile that would have learned that is skipped.
		AstOutliner.Result uncut = AstOutliner.outline(program(opaqueCall(300)), Map.of());
		assertThat(uncut.nextBudget("F", 20000, null, FIRST_TARGET, FLOOR_TARGET)).isNull();
	}

	@Test
	void aTighterTargetThatCutsTheSameFormsIsNotAskedFor() {
		// Measured so large that every target's node budget is the pass's minimum: the
		// first cut moves two of the four groups, and no tighter target can differ.
		List<LispVal> program = program(
				"(progn " + group(0) + " " + group(10) + " " + group(20) + " " + group(30) + ")");
		AstOutliner.Result uncut = AstOutliner.outline(program, Map.of());
		AstOutliner.Budget first = uncut.nextBudget("F", 100000, null, FIRST_TARGET, FLOOR_TARGET);
		assertThat(first).isEqualTo(new AstOutliner.Budget(100000, FIRST_TARGET));
		AstOutliner.Result cut = AstOutliner.outline(program, Map.of("F", first));
		assertThat(cut.outlined()).containsExactly("F");
		assertThat(cut.nextBudget("F", 90000, first, FIRST_TARGET, FLOOR_TARGET)).isNull();
	}

	@Test
	void aFlatCondIsCutIntoAChainOfClauseRuns() {
		// Clauses each too small to move: the clause list is cut instead, and every
		// piece but the last ends in the (t ...) clause that calls the next.
		StringBuilder cond = new StringBuilder("(cond");
		for (int k = 0; k < 100; k++) {
			cond.append(" ((eql x ").append(k).append(") ").append(statement(k)).append(')');
		}
		List<LispVal> program = program(cond.append(')').toString());
		AstOutliner.Result cut = AstOutliner.outline(program, Map.of("F", new AstOutliner.Budget(12000, FIRST_TARGET)));
		assertThat(cut.outlined()).containsExactly("F");
		String printed = cut.program().get(0).print();
		assertThat(printed).contains("(T (LET ((|__outlined_").contains("((EQL X 0)").contains("((EQL X 99)");
	}

	@Test
	void aTighterTargetThatCutsMoreIsAskedForAndTheSequenceEndsAtTheFloor() {
		// Ten movable groups: each tighter target moves more of them, until the node
		// budget reaches its own minimum and the last target changes nothing.
		StringBuilder body = new StringBuilder("(progn");
		for (int g = 0; g < 10; g++) {
			body.append(' ').append(group(g * 10));
		}
		List<LispVal> program = program(body.append(')').toString());
		AstOutliner.Budget first = AstOutliner.outline(program, Map.of())
			.nextBudget("F", 12000, null, FIRST_TARGET, FLOOR_TARGET);
		assertThat(first).isEqualTo(new AstOutliner.Budget(12000, 6000));
		AstOutliner.Budget second = AstOutliner.outline(program, Map.of("F", first))
			.nextBudget("F", 9000, first, FIRST_TARGET, FLOOR_TARGET);
		assertThat(second).isEqualTo(new AstOutliner.Budget(12000, 4000));
		AstOutliner.Budget third = AstOutliner.outline(program, Map.of("F", second))
			.nextBudget("F", 8500, second, FIRST_TARGET, FLOOR_TARGET);
		// 2666 bytes is the first target whose node budget is the floor; 1777 is the
		// same floor, so the sequence stops after it.
		assertThat(third).isEqualTo(new AstOutliner.Budget(12000, 2666));
		assertThat(AstOutliner.outline(program, Map.of("F", third))
			.nextBudget("F", 8200, third, FIRST_TARGET, FLOOR_TARGET)).isNull();
	}

	@Test
	void aTopLevelCallReachesTheBranchBuriedUnderIt() {
		// The f64 shape: one top-level (print (case ...)) whose decision tree is one
		// call's argument. The call's arguments are evaluated positions, so the case
		// -- and then its arms -- move into methods of their own.
		List<LispVal> program = topProgram(topCaseForm());
		AstOutliner.Result cut = AstOutliner.outlineTopLevel(program, Map.of(0, new AstOutliner.Budget(70000, 24000)));
		assertThat(cut.outlinedTop()).containsExactly(0);
		String printed = cut.program().get(0).print();
		assertThat(printed).contains("__outlined_").contains("(CASE K");
	}

	@Test
	void aTopLevelCallWithSmallArgumentsIsLeftWhole() {
		// Each argument below the smallest piece worth a method: wrapping one would
		// cost more than it buys, so the form is left whole and no budget asks for a
		// re-compile.
		List<LispVal> program = topProgram("(print (opaque " + statement(0) + " " + statement(1) + "))");
		AstOutliner.Result cut = AstOutliner.outlineTopLevel(program, Map.of(0, new AstOutliner.Budget(70000, 24000)));
		assertThat(cut.outlinedTop()).isEmpty();
		assertThat(cut.nextTopBudget(0, 70000, null, 24000, 8000)).isNull();
	}

	@Test
	void aTopLevelFormWithALexicalExitIsLeftWhole() {
		// The cut runs after the exit lowering, so a form a go or return-from leaves
		// cannot pay for a fresh lambda around a piece of it: it stays whole and keeps
		// the loud over-limit report it has today.
		StringBuilder sb = new StringBuilder("(block done (progn");
		for (int k = 0; k < 60; k++) {
			sb.append(' ').append(group(k * 10));
		}
		sb.append(" (return-from done 'hit)))");
		List<LispVal> program = topProgram(sb.toString());
		AstOutliner.Result cut = AstOutliner.outlineTopLevel(program, Map.of(0, new AstOutliner.Budget(70000, 24000)));
		assertThat(cut.outlinedTop()).isEmpty();
		assertThat(cut.nextTopBudget(0, 70000, null, 24000, 8000)).isNull();
	}

	@Test
	void aTopLevelQuotedDatumIsNeverReadAsCode() {
		// quote's positions are data however large: denying the head keeps a huge
		// literal from being wrapped into a lambda that would evaluate it.
		StringBuilder sb = new StringBuilder("'(a");
		for (int k = 0; k < 500; k++) {
			sb.append(" (").append(k).append(" . ").append(k + 1).append(')');
		}
		sb.append(')');
		List<LispVal> program = topProgram(sb.toString());
		AstOutliner.Result cut = AstOutliner.outlineTopLevel(program, Map.of(0, new AstOutliner.Budget(70000, 24000)));
		assertThat(cut.outlinedTop()).isEmpty();
		assertThat(cut.nextTopBudget(0, 70000, null, 24000, 8000)).isNull();
	}

	@Test
	void aTopLevelSetfValueIsCutButItsPlaceIsNot() {
		// (setf place value): the value is evaluated, the place is not. The value is
		// two uncuttable-but-large calls in a progn, so no cut inside it fits and the
		// whole value moves out; the place stays exactly where it was.
		String value = "(progn " + opaqueCall(300) + " " + opaqueCall(300) + ")";
		List<LispVal> program = topProgram("(setf (aref arr 0) " + value + ")");
		AstOutliner.Result cut = AstOutliner.outlineTopLevel(program, Map.of(0, new AstOutliner.Budget(70000, 24000)));
		assertThat(cut.outlinedTop()).containsExactly(0);
		String printed = cut.program().get(0).print();
		assertThat(printed).startsWith("(SETF (AREF ARR 0) (LET").contains("__outlined_");
	}

}
