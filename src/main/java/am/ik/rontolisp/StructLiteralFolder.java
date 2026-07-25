package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;

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
 * The rules follow CLHS 2.4.8.13:
 * <ul>
 * <li>slot values are read as DATA and are never evaluated;</li>
 * <li>a slot named more than once takes its LEFTMOST value;</li>
 * <li>a slot the literal omits takes its recorded initform, which must be a constant
 * (rontolisp cannot evaluate an initform at fold time on the compile path, so a
 * non-constant one is a clear error rather than a per-backend divergence);</li>
 * <li>a name that is not a defined structure type, and a slot the type does not have, are
 * errors -- the odd-length and non-symbol-name cases are already reader errors, since the
 * reader can decide those without a registry.</li>
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
		switch (form) {
			case LispStructLiteral literal -> {
				return foldLiteral(literal, registry);
			}
			case LispCons cons -> {
				LispVal car = fold(cons.car(), registry);
				LispVal cdr = fold(cons.cdr(), registry);
				return car == cons.car() && cdr == cons.cdr() ? form : new LispCons(car, cdr);
			}
			case LispArray array -> {
				// A #(...) / #nA(...) literal may hold struct literals; the packed float
				// arrays cannot, their elements are numbers. Only a literal's own storage
				// is walked -- a displaced view is never reader-produced.
				LispVal[] data = array.data();
				LispVal[] folded = null;
				for (int i = 0; i < data.length; i++) {
					LispVal element = fold(data[i], registry);
					if (element != data[i] && folded == null) {
						folded = data.clone();
					}
					if (folded != null) {
						folded[i] = element;
					}
				}
				return folded == null ? form : new LispArray(array.dimensions(), folded);
			}
			default -> {
				return form;
			}
		}
	}

	/** Builds the instance one {@code #S(...)} literal denotes. */
	private static LispInstance foldLiteral(LispStructLiteral literal, ClosRegistry registry) {
		LispLayout layout = registry.findStructLayout(literal.typeName());
		if (layout == null) {
			String hint = registry.findClassLayout(literal.typeName()) != null
					? " (it names a class; #S reads defstruct types only)" : "";
			throw new IllegalArgumentException("#S(" + literal.typeName() + " ...): " + literal.typeName()
					+ " is not a defined structure type" + hint);
		}
		LispVal[] slots = new LispVal[layout.slotCount()];
		for (int i = 0; i < literal.slotNames().size(); i++) {
			String spelled = literal.slotNames().get(i);
			int index = slotIndexOf(layout, spelled);
			if (index < 0) {
				throw new IllegalArgumentException(
						"#S(" + literal.typeName() + " ...): " + layout.printName() + " has no slot named " + spelled);
			}
			// Leftmost wins: a repeated slot keeps the value written first.
			if (slots[index] == null) {
				slots[index] = fold(literal.slotValues().get(i), registry);
			}
		}
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] == null) {
				slots[i] = defaultSlotValue(literal, layout, i, registry);
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
			ClosRegistry registry) {
		LispVal initform = layout.initforms().get(index);
		LispVal constant = constantValue(initform);
		if (constant == null) {
			throw new IllegalArgumentException(
					"#S(" + literal.typeName() + " ...): slot " + layout.slotNames().get(index)
							+ " is omitted and its initform " + initform.print() + " is not a constant");
		}
		return fold(constant, registry);
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
