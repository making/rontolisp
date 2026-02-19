package am.ik.wasm;

/**
 * <a href="https://webassembly.github.io/spec/core/binary/types.html#types">Types</a>
 */
public enum Type implements Codable {

	I32(0x7F), //
	I64(0x7E), //
	F32(0x7D), //
	F64(0x7C), //
	V128(0x7B), //
	FUNCREF(0x70), //
	EXTERNREF(0x6F), //
	// wasm-GC heap types
	ANY(0x6E), //
	EQ(0x6D), //
	I31(0x6C), //
	STRUCT_HT(0x6B), //
	ARRAY_HT(0x6A), //
	NONE(0x71), //
	// Ref type constructors
	REF(0x64), //
	REFNULL(0x63), //
	// Composite type tags
	FUNC(0x60), //
	STRUCT_TYPE(0x5F), //
	ARRAY_TYPE(0x5E), //
	// Type group
	REC(0x4E), //
	SUB(0x4F), //
	SUB_FINAL(0x50) //
	;

	private final int code;

	Type(int code) {
		this.code = code;
	}

	@Override
	public int code() {
		return code;
	}

}
