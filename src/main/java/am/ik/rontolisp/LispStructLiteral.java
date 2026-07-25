package am.ik.rontolisp;

import java.util.List;

/**
 * The unresolved carrier a {@code #S(NAME :SLOT value ...)} source literal reads into:
 * the type name and the slot name/value pairs exactly as written.
 *
 * <p>
 * Common Lisp's reader builds the structure itself, because {@code load} reads and
 * evaluates one form at a time and so the {@code defstruct} of an earlier form is already
 * in effect. rontolisp reads a whole source file up front and its reader knows nothing
 * about the {@link ClosRegistry}, so the literal is carried through the AST and folded
 * into a {@link LispInstance} by {@link StructLiteralFolder} at the first point that has
 * a registry: per top-level form on both the compile path and the interpreter, which
 * preserves CL's rule that the {@code defstruct} must PRECEDE the literal.
 *
 * <p>
 * The carrier is a first-class {@link LispVal} rather than a {@code (%read-struct ...)}
 * marker cons deliberately: being neither a symbol nor a cons it rides through
 * {@code quote}, backquote templates and {@code #(...)} vector literals with no special
 * case anywhere, exactly as the {@link LispInstance} it folds into does.
 *
 * @param typeName the structure type name as spelled in the literal
 * @param slotNames the slot names as spelled, in source order (duplicates kept: the fold
 * applies CL's leftmost-wins rule)
 * @param slotValues the slot values as data, in the same order as {@code slotNames}
 */
public record LispStructLiteral(String typeName, List<String> slotNames, List<LispVal> slotValues) implements LispVal {

	/**
	 * Canonicalizes the collections so a literal is deeply immutable.
	 * @param typeName the structure type name as spelled
	 * @param slotNames the slot names as spelled
	 * @param slotValues the slot values
	 */
	public LispStructLiteral {
		slotNames = List.copyOf(slotNames);
		slotValues = List.copyOf(slotValues);
		if (slotNames.size() != slotValues.size()) {
			throw new IllegalArgumentException("#S literal: slot name/value counts differ");
		}
	}

	@Override
	public String print() {
		return render(true);
	}

	@Override
	public String display() {
		return render(false);
	}

	/**
	 * Renders the literal back in {@code #S(...)} syntax. An unfolded literal is not
	 * supposed to reach a printer -- the fold runs before evaluation -- so this exists so
	 * that error messages naming the offending literal read like the source that produced
	 * it.
	 */
	private String render(boolean escape) {
		StringBuilder sb = new StringBuilder("#S(").append(this.typeName);
		for (int i = 0; i < this.slotNames.size(); i++) {
			sb.append(' ')
				.append(this.slotNames.get(i))
				.append(' ')
				.append(escape ? this.slotValues.get(i).print() : this.slotValues.get(i).display());
		}
		return sb.append(')').toString();
	}

}
