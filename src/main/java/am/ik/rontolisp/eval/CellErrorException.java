package am.ik.rontolisp.eval;

import am.ik.rontolisp.ClosRegistry;

/**
 * A {@code cell-error} a built-in raised, carrying the name of the cell it found empty so
 * the instance a catching form synthesizes fills the {@code name} slot
 * {@code cell-error-name} reads -- SBCL's answer, rather than NIL.
 */
final class CellErrorException extends LispEvalException {

	/** The name's canonical spelling, as the function namespace keys it. */
	private final String spelling;

	private CellErrorException(String className, String message, String spelling) {
		super(message, null, className);
		this.spelling = spelling;
	}

	/**
	 * The {@code undefined-function} of a call of a name no function answers.
	 * @param spelling the name's canonical spelling ({@code "NIL"} and {@code "T"} for
	 * the standard symbols)
	 * @return the exception to throw
	 */
	static CellErrorException undefinedFunction(String spelling) {
		return new CellErrorException(ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME,
				ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + spelling
						+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX,
				spelling);
	}

	/**
	 * The name's canonical spelling, for the condition's {@code name} slot.
	 * @return the spelling
	 */
	String spelling() {
		return this.spelling;
	}

}
