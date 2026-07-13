package am.ik.wit;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A WIT function type: parameters and at most one result, optionally {@code async}.
 *
 * @param async whether this is an {@code async func}
 * @param params the parameters in order
 * @param result the result type, or {@code null} when the function returns nothing
 */
public record WitFunc(boolean async, List<Param> params, @Nullable WitType result) {

	/**
	 * One function parameter.
	 *
	 * @param name the parameter name
	 * @param type the parameter type
	 */
	public record Param(String name, WitType type) {
	}

}
