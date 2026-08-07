package am.ik.wasm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encoding tests for {@link RecTypeDef}. Every type it adds must be {@code sub final}
 * with no supertype, and must be SPELLED as the bare {@code comptype} the wasm-GC format
 * defines as exactly that ({@code subtype ::= 0x50 x* comptype | 0x4F x* comptype |
 * comptype}) -- the explicit {@code 4F 00} decodes identically and costs two bytes per
 * type. The constants below are the spec's, not {@link Type}'s, so neither a swapped enum
 * nor a re-introduced wrapper can satisfy them.
 * <p>
 * Finality matters beyond validation: engines can only replace a runtime subtype check
 * with an inline type-index equality when the target type is final (wasmtime otherwise
 * falls back to an {@code is_subtype} libcall on every {@code ref.cast} /
 * {@code call_indirect}) -- so what these tests really pin is that the emitted type is
 * never the OPEN {@code sub}, whichever spelling carries it.
 */
class RecTypeDefTest {

	private static final int SPEC_SUB_FINAL = 0x4F;

	private static final int SPEC_SUB = 0x50;

	private static final int SPEC_FUNC = 0x60;

	private static final int SPEC_STRUCT = 0x5F;

	private static final int SPEC_ARRAY = 0x5E;

	@Test
	void addSubFinalStructEmitsABareStructType() {
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I31)));
		byte[] bytes = rec.toByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(SPEC_STRUCT);
		assertThat(bytes[0] & 0xFF).isNotIn(SPEC_SUB, SPEC_SUB_FINAL);
	}

	@Test
	void addSubFinalFuncEmitsABareFuncType() {
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 });
		byte[] bytes = rec.toByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(SPEC_FUNC);
		assertThat(bytes[0] & 0xFF).isNotIn(SPEC_SUB, SPEC_SUB_FINAL);
	}

	@Test
	void addSubFinalArrayEmitsABareArrayType() {
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalArray(w -> {
			w.write(Type.I32);
			w.write(Mutability.VAR);
		});
		byte[] bytes = rec.toByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(SPEC_ARRAY);
		assertThat(bytes[0] & 0xFF).isNotIn(SPEC_SUB, SPEC_SUB_FINAL);
	}

	@Test
	void aStructOfNullableEqRefsIsAsShortAsTheFormatAllows() {
		// The two savings this class and WasmWriter own, on the smallest type that shows
		// both: no `4F 00` wrapper, and `(ref null eq)` as the one-byte `eqref`. A cons
		// cell is 0x5F (struct) 0x02 (two fields) then (0x6D 0x01) twice.
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalStruct(fields -> {
			fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
			fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
		});
		assertThat(rec.toByteArray()).containsExactly(0x5F, 0x02, 0x6D, 0x01, 0x6D, 0x01);
	}

}
