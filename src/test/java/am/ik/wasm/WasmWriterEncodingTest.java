package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emitter must write the SHORTEST LEGAL encoding of every field, not merely a legal
 * one. Both non-minimal forms this pins were once emitted everywhere and cost 2.4%-5.7%
 * of every module the project produces, at no semantic difference whatever -- which is
 * exactly why nothing caught them: the modules validated and ran.
 * <ol>
 * <li>a nullable reference to an abstract heap type has a one-byte shorthand (the heap
 * type's own code IS the value type), and
 * <li>every index, count and length is a {@code u32}, so a SIGNED LEB pads any value in
 * [64, 127] (and every 64th block above it) with a redundant continuation byte.
 * </ol>
 */
class WasmWriterEncodingTest {

	private static byte[] enc(Consumer<WasmWriter> consumer) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		consumer.accept(new WasmWriter(out));
		return out.toByteArray();
	}

	@Test
	void aNullableAbstractReferenceIsOneByte() {
		// 0x6D IS `eqref`; writing `63 6D` for it says the same thing one byte longer.
		assertThat(enc(w -> w.writeRefType(true, Type.EQ.code()))).containsExactly(0x6D);
		assertThat(enc(w -> w.writeRefType(true, Type.I31.code()))).containsExactly(0x6C);
		assertThat(enc(w -> w.writeRefType(true, Type.ANY.code()))).containsExactly(0x6E);
		assertThat(enc(w -> w.writeRefType(true, Type.FUNCREF.code()))).containsExactly(0x70);
		assertThat(enc(w -> w.writeRefType(true, Type.EXNREF.code()))).containsExactly(0x69);
		// The endpoints of the contiguous abstract range, so a narrowed range fails here.
		assertThat(enc(w -> w.writeRefType(true, Type.NOEXN.code()))).containsExactly(0x74);
		assertThat(enc(w -> w.writeRefType(true, Type.ARRAY_HT.code()))).containsExactly(0x6A);
	}

	@Test
	void aNonNullableOrConcreteReferenceKeepsItsConstructorByte() {
		// The shorthand exists for nullable + abstract only: `(ref eq)` is a different
		// type, and a concrete index is an s33 that would be unreadable without the
		// constructor byte.
		assertThat(enc(w -> w.writeRefType(false, Type.EQ.code()))).containsExactly(0x64, 0x6D);
		assertThat(enc(w -> w.writeRefType(true, 3))).containsExactly(0x63, 0x03);
		assertThat(enc(w -> w.writeRefType(false, 3))).containsExactly(0x64, 0x03);
		// A concrete index whose value falls in the abstract range's numeric span is
		// still an index: writeHeapType disambiguates by range, and 0x6D as an INDEX
		// (109) is far above anything the backend allocates.
		assertThat(enc(w -> w.writeRefType(true, 40))).containsExactly(0x63, 0x28);
	}

	@Test
	void anIndexOrLengthIsAMinimalU32() {
		// 96 is the first index where the two encodings differ: `60` has bit 6 set, so
		// the signed form needs a second byte to keep the sign clear.
		assertThat(enc(w -> w.writeUnsignedLeb128(96))).containsExactly(0x60);
		assertThat(enc(w -> w.writeSignedLeb128(96))).containsExactly(0xE0, 0x00);
		assertThat(enc(w -> w.writeUnsignedLeb128(63))).containsExactly(0x3F);
		assertThat(enc(w -> w.writeUnsignedLeb128(127))).containsExactly(0x7F);
		assertThat(enc(w -> w.writeUnsignedLeb128(128))).containsExactly(0x80, 0x01);
		assertThat(enc(w -> w.writeUnsignedLeb128(8192))).containsExactly(0x80, 0x40);
		assertThat(enc(w -> w.writeSignedLeb128(8192))).containsExactly(0x80, 0xC0, 0x00);
	}

	@Test
	void aSectionCarriesAMinimalSizePrefixAndAnEmptyOneIsNotWrittenAtAll() {
		// A code section whose single body is 96 bytes long: the section size and the
		// body size are both u32, so neither may pad. The three-byte prologue is the
		// section id, the size, and the entry count.
		byte[] body = new byte[95];
		body[94] = (byte) Instruction.END;
		byte[] section = enc(w -> w.writeCode(code -> code.addFunction(body)));
		assertThat(section).startsWith((byte) Section.CODE.code(), (byte) 97, (byte) 1, (byte) 95);
		assertThat(section).hasSize(3 + 1 + body.length);

		// An absent section and a section holding zero entries mean the same thing, so
		// the empty one is not written -- the case a component core module hits, where
		// the memory is imported and the memory section has nothing to declare.
		assertThat(enc(w -> w.writeMemory(memories -> {
		}))).isEmpty();
		assertThat(enc(w -> w.writeMemory(memories -> memories.addMemory(1)))).isNotEmpty();
	}

}
