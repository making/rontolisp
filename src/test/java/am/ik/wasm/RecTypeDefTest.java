package am.ik.wasm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encoding tests for {@link RecTypeDef}. The wasm-GC binary format assigns {@code 0x4F}
 * to {@code sub final} and {@code 0x50} to plain (open) {@code sub}; the constants below
 * are taken from the spec, not from {@link Type}, so a swapped enum cannot satisfy them.
 * Finality matters beyond validation: engines can only replace a runtime subtype check
 * with an inline type-index equality when the target type is final (wasmtime otherwise
 * falls back to an {@code is_subtype} libcall on every {@code ref.cast} /
 * {@code call_indirect}).
 */
class RecTypeDefTest {

	private static final int SPEC_SUB_FINAL = 0x4F;

	private static final int SPEC_SUB = 0x50;

	@Test
	void addSubFinalStructEmitsSpecSubFinalOpcode() {
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I31)));
		byte[] bytes = rec.toByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(SPEC_SUB_FINAL);
		assertThat(bytes[0] & 0xFF).isNotEqualTo(SPEC_SUB);
	}

	@Test
	void addSubFinalFuncEmitsSpecSubFinalOpcode() {
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 });
		byte[] bytes = rec.toByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(SPEC_SUB_FINAL);
	}

	@Test
	void addSubFinalArrayEmitsSpecSubFinalOpcode() {
		RecTypeDef rec = new RecTypeDef();
		rec.addSubFinalArray(w -> {
			w.write(Type.I32);
			w.write(Mutability.VAR);
		});
		byte[] bytes = rec.toByteArray();
		assertThat(bytes[0] & 0xFF).isEqualTo(SPEC_SUB_FINAL);
	}

}
