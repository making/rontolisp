package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Folds every {@link LispStructLiteral} a {@code #S(NAME :SLOT value ...)} source literal
 * read into the {@link LispInstance} it denotes, against the {@link ClosRegistry} of the
 * running compilation or evaluator.
 *
 * <p>
 * This is the read-time construction Common Lisp performs inside the reader itself, moved
 * to the first point that knows the structure layouts. It runs PER TOP-LEVEL FORM on
 * every path -- {@code LispMacroExpander.expandTopLevelDefinitions} on the compile path,
 * the evaluator's top-level entries in the interpreter -- so CL's ordering rule survives:
 * the {@code defstruct} must precede the literal, exactly as it must in a file CL loads
 * one form at a time.
 *
 * <p>
 * The rules follow CLHS 2.4.8.13 -- a {@code #S} literal is the constructor called with
 * the given slots as keywords, each designator coerced with {@code (string slot)} (so a
 * symbol, a string and a character all name a slot; the reader stores the coerced
 * spelling):
 * <ul>
 * <li>slot values are read as DATA and are never evaluated;</li>
 * <li>a slot named more than once takes its LEFTMOST value;</li>
 * <li>a slot the literal omits takes its recorded initform, which must be a constant
 * (rontolisp cannot evaluate an initform at fold time on the compile path, so a
 * non-constant one is a clear error rather than a per-backend divergence);</li>
 * <li>{@code :allow-other-keys} is never a slot and never an error: the LEFTMOST pair
 * naming it decides, and a non-nil value licenses every other unknown slot (the same rule
 * {@code LispMacroExpander.keywordTailProblem} applies to keyword tails);</li>
 * <li>a name that is not a defined structure type, and a slot the type does not have
 * (with no licensing {@code :allow-other-keys}), are errors -- the odd-length and
 * non-symbol/string/character-name cases are already reader errors, since the reader can
 * decide those without a registry.</li>
 * </ul>
 */
public final class StructLiteralFolder {

	private StructLiteralFolder() {
	}

	/**
	 * Folds the struct literals of every form of a program, preserving identity when
	 * there is nothing to fold.
	 * @param program the top-level forms
	 * @param registry the layout registry
	 * @return the program with every {@code #S(...)} literal replaced by its instance, or
	 * {@code program} itself when it holds none
	 */
	public static List<LispVal> foldProgram(List<LispVal> program, ClosRegistry registry) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal folded = fold(program.get(i), registry);
			if (folded != program.get(i) && out == null) {
				out = new ArrayList<>(program.subList(0, i));
			}
			if (out != null) {
				out.add(folded);
			}
		}
		return out == null ? program : out;
	}

	/**
	 * Folds every struct literal in a form, however deeply nested -- inside quoted data,
	 * a backquote's list-building code, a {@code #(...)} vector literal and another
	 * literal's slot values alike. Unchanged subtrees keep their identity.
	 * @param form the form to fold
	 * @param registry the layout registry
	 * @return the folded form, or {@code form} itself when it holds no struct literal
	 */
	public static LispVal fold(LispVal form, ClosRegistry registry) {
		return fold(form, registry, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	/**
	 * The walk itself, carrying the aggregates on the CURRENT PATH by identity. A datum a
	 * {@code #n=} reader label closed into a circle (CLHS 2.4.8.3) has a back edge, and
	 * this walk REBUILDS what it changes, so a back edge can only be left alone: the node
	 * is returned as it stands the second time the path reaches it. On the path and not
	 * "ever seen", so a DAG -- the same label referenced twice, side by side -- still
	 * folds both occurrences.
	 */
	private static LispVal fold(LispVal form, ClosRegistry registry, Set<LispVal> active) {
		switch (form) {
			case LispStructLiteral literal -> {
				return foldLiteral(literal, registry, active);
			}
			case LispCons cons -> {
				return foldSpine(cons, registry, active);
			}
			case LispArray array -> {
				// A #(...) / #nA(...) literal may hold struct literals; the packed float
				// arrays cannot, their elements are numbers. Only a literal's own storage
				// is walked -- a displaced view is never reader-produced.
				if (!active.add(array)) {
					return form;
				}
				try {
					LispVal[] data = array.data();
					LispVal[] folded = null;
					for (int i = 0; i < data.length; i++) {
						LispVal element = fold(data[i], registry, active);
						if (element != data[i] && folded == null) {
							folded = data.clone();
						}
						if (folded != null) {
							folded[i] = element;
						}
					}
					return folded == null ? form : new LispArray(array.dimensions(), folded);
				}
				finally {
					active.remove(array);
				}
			}
			default -> {
				return form;
			}
		}
	}

	/**
	 * A list, walked down its cdr spine in a loop so the stack is spent per level of
	 * nesting rather than per element. Every cell of the spine stays on the path until
	 * the whole spine is done, as it did when the walk recursed on the cdr.
	 */
	private static LispVal foldSpine(LispCons head, ClosRegistry registry, Set<LispVal> active) {
		List<LispCons> cells = new ArrayList<>();
		List<LispVal> cars = new ArrayList<>();
		try {
			LispVal node = head;
			LispVal tail;
			while (true) {
				if (node instanceof LispCons cell) {
					if (!active.add(cell)) {
						tail = cell;
						break;
					}
					cells.add(cell);
					cars.add(fold(cell.car(), registry, active));
					node = cell.cdr();
				}
				else {
					tail = fold(node, registry, active);
					break;
				}
			}
			for (int i = cells.size() - 1; i >= 0; i--) {
				tail = LispCons.rebuilt(cells.get(i), cars.get(i), tail);
			}
			return tail;
		}
		finally {
			for (LispCons cell : cells) {
				active.remove(cell);
			}
		}
	}

	/** Builds the instance one {@code #S(...)} literal denotes. */
	private static LispInstance foldLiteral(LispStructLiteral literal, ClosRegistry registry, Set<LispVal> active) {
		LispLayout layout = registry.findStructLayout(literal.typeName());
		if (layout == null) {
			String hint = registry.findClassLayout(literal.typeName()) != null
					? " (it names a class; #S reads defstruct types only)" : "";
			throw new IllegalArgumentException("#S(" + literal.typeName() + " ...): " + literal.typeName()
					+ " is not a defined structure type" + hint);
		}
		// The LEFTMOST :allow-other-keys pair decides whether unknown slots are
		// licensed; the pairs themselves are never slots and never errors.
		boolean allowOtherKeys = false;
		for (int i = 0; i < literal.slotNames().size(); i++) {
			if (isAllowOtherKeys(literal.slotNames().get(i))) {
				allowOtherKeys = !(literal.slotValues().get(i) instanceof LispNil);
				break;
			}
		}
		LispVal[] slots = new LispVal[layout.slotCount()];
		for (int i = 0; i < literal.slotNames().size(); i++) {
			String spelled = literal.slotNames().get(i);
			if (isAllowOtherKeys(spelled)) {
				continue;
			}
			int index = slotIndexOf(layout, spelled);
			if (index < 0) {
				if (allowOtherKeys) {
					continue;
				}
				throw new IllegalArgumentException(
						"#S(" + literal.typeName() + " ...): " + layout.printName() + " has no slot named " + spelled);
			}
			// Leftmost wins: a repeated slot keeps the value written first.
			if (slots[index] == null) {
				slots[index] = fold(literal.slotValues().get(i), registry, active);
			}
		}
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] == null) {
				slots[i] = defaultSlotValue(literal, layout, i, registry, active);
			}
		}
		return new LispInstance(layout, slots);
	}

	/**
	 * The value of a slot the literal omits: the recorded initform, which must be a
	 * constant. An initform that has to be EVALUATED cannot be honored here -- the fold
	 * runs at compile time on three of the four backends, where there is no evaluator --
	 * so it is rejected instead of silently reading as nil on some backends and as its
	 * value on others.
	 */
	private static LispVal defaultSlotValue(LispStructLiteral literal, LispLayout layout, int index,
			ClosRegistry registry, Set<LispVal> active) {
		LispVal initform = layout.initforms().get(index);
		LispVal constant = constantValue(initform);
		if (constant == null) {
			throw new IllegalArgumentException(
					"#S(" + literal.typeName() + " ...): slot " + layout.slotNames().get(index)
							+ " is omitted and its initform " + initform.print() + " is not a constant");
		}
		return fold(constant, registry, active);
	}

	/**
	 * The value of a constant initform, or null when the initform has to be evaluated. A
	 * self-evaluating datum stands for itself and {@code (quote x)} for its datum;
	 * anything else -- a variable reference, a function call -- is not a constant.
	 */
	@org.jspecify.annotations.Nullable
	private static LispVal constantValue(LispVal initform) {
		return switch (initform) {
			case LispCons cons -> cons.car() instanceof LispSymbol head && LispNames.QUOTE.equals(head.name())
					&& cons.cdr() instanceof LispCons datum && datum.cdr() instanceof LispNil ? datum.car() : null;
			case LispSymbol ignored -> null;
			default -> initform;
		};
	}

	/**
	 * Whether a spelled slot name is the {@code :allow-other-keys} marker: the
	 * package-stripped base name with the keyword marker dropped, compared
	 * case-insensitively, so a string designator {@code "ALLOW-OTHER-KEYS"} counts the
	 * same way {@code :allow-other-keys} does (CLHS 2.4.8.13 coerces every designator
	 * with {@code string} before interning the keyword).
	 */
	private static boolean isAllowOtherKeys(String spelled) {
		String base = LispSymbol.displayName(spelled);
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(base);
		if (qn != null) {
			base = qn.member();
		}
		return base.equalsIgnoreCase("ALLOW-OTHER-KEYS");
	}

	/**
	 * The layout index of a slot named in a literal. The name is matched by its
	 * package-stripped base name with the keyword marker dropped, so {@code :X},
	 * {@code X} and {@code PKG::X} all name slot {@code X} -- Common Lisp matches
	 * {@code #S} slot names by symbol-name too. The case-flip retry mirrors
	 * {@link ClosRegistry#slotPosition}: a literal written in a case-preserving internal
	 * source still finds an upcase-read layout's slot.
	 */
	private static int slotIndexOf(LispLayout layout, String spelled) {
		String base = LispSymbol.displayName(spelled);
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(base);
		if (qn != null) {
			base = qn.member();
		}
		int exact = layout.slotIndex(base);
		if (exact >= 0) {
			return exact;
		}
		String upper = base.toUpperCase(java.util.Locale.ROOT);
		if (!upper.equals(base) && layout.slotIndex(upper) >= 0) {
			return layout.slotIndex(upper);
		}
		String lower = base.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(base)) {
			return layout.slotIndex(lower);
		}
		return -1;
	}

}
