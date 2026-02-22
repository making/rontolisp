package am.ik.wasm;

/**
 * WASM global mutability values.
 */
public enum Mutability implements Codable {

	/** Immutable global. */
	CONST(0),
	/** Mutable global. */
	VAR(1);

	private final int code;

	Mutability(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return this.code;
	}

}
