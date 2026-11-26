package am.ik.wasm;

/**
 * WASM binary module <a href=
 * "https://webassembly.github.io/spec/core/binary/modules.html#sections">section</a>
 * identifiers.
 */
public enum Section implements Codable {

	/** Custom section (0). */
	CUSTOM(0), //
	/** Type section (1). */
	TYPE(1), //
	/** Import section (2). */
	IMPORT(2), //
	/** Function section (3). */
	FUNCTION(3), //
	/** Table section (4). */
	TABLE(4), //
	/** Memory section (5). */
	MEMORY(5), //
	/** Global section (6). */
	GLOBAL(6), //
	/** Export section (7). */
	EXPORT(7), //
	/** Start section (8). */
	START(8), //
	/** Element section (9). */
	ELEMENT(9), //
	/** Code section (10). */
	CODE(10), //
	/** Data section (11). */
	DATA(11), //
	/** Data count section (12). */
	DATA_COUNT(12), //
	/**
	 * Tag section (13), from the exception-handling proposal. Placed between the memory
	 * section (5) and the global section (6) in the binary encoding.
	 */
	TAG(13) //
	;

	private final int code;

	Section(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return code;
	}

}
