package am.ik.wasm;

/**
 * Interface for WASM elements that have an integer code representation.
 */
@FunctionalInterface
public interface Codable {

	/**
	 * Return the integer code for this element.
	 * @return the code value
	 */
	int code();

}
