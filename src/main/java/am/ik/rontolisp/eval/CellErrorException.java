package am.ik.rontolisp.eval;

import am.ik.rontolisp.ClosRegistry;
import org.jspecify.annotations.Nullable;

/**
 * A {@code cell-error} a built-in raised, carrying the name of the cell it found empty so
 * the instance a catching form synthesizes fills the {@code name} slot
 * {@code cell-error-name} reads -- SBCL's answer, rather than NIL.
 */
final class CellErrorException extends LispEvalException {

	/** The name's canonical spelling, as its namespace keys it. */
	private final String spelling;

	/**
	 * The place of a {@code (setf place)} function name, whose condition names the list
	 * {@code (setf place)} rather than a symbol; null for every other cell.
	 */
	private final @Nullable String setfPlace;

	private CellErrorException(String className, String message, String spelling, @Nullable String setfPlace) {
		super(message, null, className);
		this.spelling = spelling;
		this.setfPlace = setfPlace;
	}

	/**
	 * The {@code undefined-function} of a call of a name no function answers.
	 * @param spelling the name's canonical spelling ({@code "NIL"} and {@code "T"} for
	 * the standard symbols); a {@code (setf place)} writer's internal name is reported as
	 * the {@code (setf place)} the program wrote
	 * @return the exception to throw
	 */
	static CellErrorException undefinedFunction(String spelling) {
		String reported = ClosRegistry.functionNameForReport(spelling);
		return new CellErrorException(ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME,
				ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + reported
						+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX,
				spelling, ClosRegistry.setfPlaceOfFunctionName(spelling));
	}

	/**
	 * The {@code unbound-variable} of a read of a name no binding answers.
	 * @param spelling the name's canonical spelling
	 * @return the exception to throw
	 */
	static CellErrorException unboundVariable(String spelling) {
		return new CellErrorException(ClosRegistry.UNBOUND_VARIABLE_CLASS_NAME,
				ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX + spelling + ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX,
				spelling, null);
	}

	/**
	 * The name's canonical spelling, for the condition's {@code name} slot.
	 * @return the spelling
	 */
	String spelling() {
		return this.spelling;
	}

	/**
	 * The place of the {@code (setf place)} function this condition names.
	 * @return the place's spelling, or null when the cell is no setf function
	 */
	@Nullable String setfPlace() {
		return this.setfPlace;
	}

}
