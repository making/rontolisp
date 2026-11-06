package am.ik.rontolisp.eval;

import java.util.AbstractList;
import java.util.RandomAccess;

import am.ik.rontolisp.LispVal;

/**
 * The arguments a compiled call evaluated, as the list a function is applied to, over the
 * array they were evaluated into: a compiled body binding exactly these parameters makes
 * the array its call scope's values ({@link CompiledBody#bind}), so the call copies
 * nothing. Nothing else holds the array once the call is made.
 */
final class ArgList extends AbstractList<LispVal> implements RandomAccess {

	private final LispVal[] values;

	/**
	 * The list over an array.
	 * @param values the arguments, in order
	 */
	ArgList(LispVal[] values) {
		this.values = values;
	}

	/**
	 * The array the arguments were evaluated into.
	 * @return the array
	 */
	LispVal[] values() {
		return this.values;
	}

	@Override
	public LispVal get(int index) {
		return this.values[index];
	}

	@Override
	public int size() {
		return this.values.length;
	}

}
