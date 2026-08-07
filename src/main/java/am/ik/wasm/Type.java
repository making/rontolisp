package am.ik.wasm;

/**
 * WASM value
 * <a href="https://webassembly.github.io/spec/core/binary/types.html#types">types</a> and
 * type constructors.
 */
public enum Type implements Codable {

	/** 32-bit integer type. */
	I32(0x7F), //
	/** 64-bit integer type. */
	I64(0x7E), //
	/** 32-bit float type. */
	F32(0x7D), //
	/** 64-bit float type. */
	F64(0x7C), //
	/** 128-bit SIMD vector type. */
	V128(0x7B), //
	// wasm-GC packed storage types (valid only as array/struct FIELD storage types)
	/** 8-bit packed storage type (wasm-GC field storage only). */
	I8_STORAGE(0x78), //
	/** 16-bit packed storage type (wasm-GC field storage only). */
	I16_STORAGE(0x77), //
	// The ABSTRACT heap types occupy the contiguous range 0x69-0x74, and each code is
	// also the one-byte value type for a NULLABLE reference to it -- 0x70 is `funcref`
	// as much as it is `func`. WasmWriter.writeRefType range-tests against the two
	// endpoints (EXNREF and NOEXN), so the set below has to stay complete.
	/** Function reference type / func heap type. */
	FUNCREF(0x70), //
	/** External reference type / extern heap type. */
	EXTERNREF(0x6F), //
	/** Exception reference type / exn heap type (exception-handling proposal). */
	EXNREF(0x69), //
	// wasm-GC heap types
	/** Any heap type (wasm-GC). */
	ANY(0x6E), //
	/** Eq heap type (wasm-GC). */
	EQ(0x6D), //
	/** i31 heap type (wasm-GC). */
	I31(0x6C), //
	/** Struct heap type (wasm-GC). */
	STRUCT_HT(0x6B), //
	/** Array heap type (wasm-GC). */
	ARRAY_HT(0x6A), //
	/** None (bottom) heap type (wasm-GC). */
	NONE(0x71), //
	/** Noextern (bottom external) heap type (wasm-GC). */
	NOEXTERN(0x72), //
	/** Nofunc (bottom function) heap type (wasm-GC). */
	NOFUNC(0x73), //
	/** Noexn (bottom exception) heap type (exception-handling proposal). */
	NOEXN(0x74), //
	// Ref type constructors
	/** Non-nullable reference type constructor. */
	REF(0x64), //
	/** Nullable reference type constructor. */
	REFNULL(0x63), //
	// Composite type tags
	/** Function composite type tag. */
	FUNC(0x60), //
	/** Struct composite type tag. */
	STRUCT_TYPE(0x5F), //
	/** Array composite type tag. */
	ARRAY_TYPE(0x5E), //
	// Type group
	/** Rec group marker. */
	REC(0x4E), //
	/** Sub type marker (open, may have subtypes). */
	SUB(0x50), //
	/** Sub-final type marker. */
	SUB_FINAL(0x4F) //
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
