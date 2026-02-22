package am.ik.wasm;

/**
 * WASM limit types for memories and tables.
 */
public enum Limit implements Codable {

	/** Limit with minimum only. */
	MIN(0),
	/** Limit with minimum and maximum. */
	MINMAX(1);

	private final int code;

	Limit(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return this.code;
	}

}
