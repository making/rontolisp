package am.ik.rontolisp.eval;

import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SpecialDeclarations;

import org.jspecify.annotations.Nullable;

/**
 * One name's binding in the global function namespace ({@link Environment}): created when
 * the name is first defined and kept when it is undefined, so a call that resolved the
 * name once reads its current binding with one field load, a redefinition included
 * ({@code .kb/interpreter-expansion-memo.md}, "The call-site note"). A cell has a fixed
 * index in its environment's table, which is how a note names it without holding it.
 */
final class FunctionCell {

	/** {@link Binding#slotRead} of a function that is no slot reader. */
	static final int NO_SLOT_READ = Integer.MIN_VALUE;

	private static final Binding UNBOUND = new Binding(null, NO_SLOT_READ, Set.of());

	private final int index;

	private volatile Binding binding = UNBOUND;

	/**
	 * Creates the cell at a table index.
	 * @param index its index in the environment's cell table
	 */
	FunctionCell(int index) {
		this.index = index;
	}

	/**
	 * The cell's index in its environment's cell table.
	 * @return the index
	 */
	int index() {
		return this.index;
	}

	/**
	 * The function the name is bound to now.
	 * @return the function, or null while the name is unbound
	 */
	@Nullable LispVal value() {
		return this.binding.value();
	}

	/**
	 * The binding now, with what was worked out about its function when it was made.
	 * @return the binding
	 */
	Binding binding() {
		return this.binding;
	}

	/**
	 * Binds the name.
	 * @param value the function, or null to unbind it
	 */
	void set(@Nullable LispVal value) {
		this.binding = value == null ? UNBOUND : new Binding(value, slotRead(value),
				value instanceof LispLambda lambda ? SpecialDeclarations.leading(lambda.body(), true) : Set.of());
	}

	/**
	 * The slot a function reads when its whole body is {@code (block NAME (%obj-ref P
	 * INDEX FAILURE))} over its one parameter {@code P} -- a {@code defstruct} accessor
	 * ({@code LispMacroExpander.checkedStructRead}) -- else {@link #NO_SLOT_READ}. A call
	 * of one on an instance can answer the slot without running the body; any other
	 * argument runs it, and so its failure form.
	 */
	private static int slotRead(LispVal value) {
		if (!(value instanceof LispLambda lambda) || lambda.params().size() != 1 || lambda.rest() != null
				|| lambda.body().size() != 1 || !(lambda.body().get(0) instanceof LispCons block)
				|| block.properLength() != 3 || !(block.car() instanceof LispSymbol blockHead)
				|| !LispNames.BLOCK.equals(blockHead.name())
				|| !(((LispCons) ((LispCons) block.cdr()).cdr()).car() instanceof LispCons read)
				|| read.properLength() != 4 || !(read.car() instanceof LispSymbol readHead)
				|| !LispNames.OBJ_REF.equals(readHead.name())) {
			return NO_SLOT_READ;
		}
		LispCons objectCell = (LispCons) read.cdr();
		LispCons indexCell = (LispCons) objectCell.cdr();
		if (!(objectCell.car() instanceof LispSymbol object) || !object.name().equals(lambda.params().get(0).name())
				|| !(indexCell.car() instanceof LispInteger index) || index.value() != (int) index.value()
				|| index.value() == NO_SLOT_READ) {
			return NO_SLOT_READ;
		}
		return (int) index.value();
	}

	/**
	 * A binding of the name: the function, the slot it reads when it is a slot reader,
	 * and the names its body's declarations declare special, which a call would read
	 * again each time.
	 *
	 * @param value the function, or null for none
	 * @param slotRead the literal slot index the function's body reads, or
	 * {@link #NO_SLOT_READ}
	 * @param declared the names the special declarations at the head of a closure's body
	 * declare ({@link SpecialDeclarations#leading}); empty for any other function
	 */
	record Binding(@Nullable LispVal value, int slotRead, Set<String> declared) {
	}

}
