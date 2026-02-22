package am.ik.wasm;

/**
 * WASM external kind values for import and export entries.
 */
public enum ExternalKind implements Codable {

	/** Function external kind (0). */
	FUNCTION(0), //
	/** Table external kind (1). */
	TABLE(1), //
	/** Memory external kind (2). */
	MEMORY(2), //
	/** Global external kind (3). */
	GLOBAL(3) //
	;

	private final int code;

	ExternalKind(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return code;
	}

}
