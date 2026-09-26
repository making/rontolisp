package am.ik.rontolisp.codegen.jvm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import org.jspecify.annotations.Nullable;

/**
 * The built-in operators one compiled class's wrong-argument-count reports name, and the
 * callee SHAPE that carries one.
 *
 * <p>
 * A built-in operator's function value names the operator in its report
 * ({@code CONS expects 2 arguments, got 1}), as the interpreter's does; every other
 * callee reports as {@code Function} ({@link BuiltinFunctionWrappers#arityOperator}
 * decides, by the callee's name). The class spells the message at run time from a shape
 * baked beside the callee -- the required count doubled, plus one for a {@code &rest}
 * tail -- so the operator travels in the same int: its 1-based index here from
 * {@link #OPERATOR_SHIFT} up, 0 for {@code Function}. Every site that bakes a shape (a
 * literal {@code apply}'s count guard, a spread dispatcher case, the {@code _arityErr}
 * table) asks {@link #shape} for it, and {@code _arityMsg} reads the names back in index
 * order once {@link #freeze} closes the registry. A site that registers a name after that
 * is a compiler bug, and fails loudly rather than reporting under a name the message
 * builder never learned.
 *
 * <p>
 * One registry per compile, shared by every compilation context of it.
 */
final class JvmArityOperators {

	/** The bit the operator index starts at; the plain shape stays below it. */
	static final int OPERATOR_SHIFT = 16;

	private final Map<String, Integer> indices = new LinkedHashMap<>();

	private boolean frozen;

	/**
	 * The shape of a callee that reports as {@code Function}: the required count doubled,
	 * plus one for a {@code &rest} tail.
	 * @param required the callee's required parameter count
	 * @param variadic whether it takes a {@code &rest} tail
	 * @return the shape
	 */
	static int plainShape(int required, boolean variadic) {
		return required * 2 + (variadic ? 1 : 0);
	}

	/**
	 * The shape a report is spelled from for a callee of this name: {@link #plainShape},
	 * with the operator's index when the callee is a built-in's.
	 * @param required the callee's required parameter count
	 * @param variadic whether it takes a {@code &rest} tail
	 * @param functionName its name, or {@code null} for an anonymous callee
	 * @return the shape
	 */
	int shape(int required, boolean variadic, @Nullable String functionName) {
		int shape = plainShape(required, variadic);
		String operator = BuiltinFunctionWrappers.arityOperator(functionName);
		if (operator == null || shape >= 1 << OPERATOR_SHIFT) {
			return shape;
		}
		Integer index = this.indices.get(operator);
		if (index == null) {
			if (this.frozen) {
				throw new IllegalStateException(
						"arity operator " + operator + " registered after the message builder read the registry");
			}
			index = this.indices.size() + 1;
			this.indices.put(operator, index);
		}
		return shape | (index << OPERATOR_SHIFT);
	}

	/**
	 * Closes the registry for the message builder.
	 * @return the registered operator names; the one at list index {@code i} is the
	 * shape's operator index {@code i + 1}
	 */
	List<String> freeze() {
		this.frozen = true;
		return List.copyOf(this.indices.keySet());
	}

}
