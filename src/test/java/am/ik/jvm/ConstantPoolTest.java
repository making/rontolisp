package am.ik.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.DoubleEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConstantPoolTest {

	@Test
	void deduplicatesIdenticalEntries() {
		ConstantPool cp = new ConstantPool();
		assertThat(cp.utf8Entry("same")).isSameAs(cp.utf8Entry("same"));
		assertThat(cp.size()).isEqualTo(1);
	}

	@Test
	void longAndDoubleTakeTwoSlots() {
		ConstantPool cp = new ConstantPool();
		int first = cp.entries().longEntry(1L).index();
		int second = cp.entries().doubleEntry(1.0).index();
		assertThat(second).isEqualTo(first + 2);
		assertThat(cp.size()).isEqualTo(4);
	}

	// -0.0 == 0.0 in Java, but they are two different constants: a literal -0.0 must not
	// come back as 0.0 from the pool.
	@Test
	void negativeZeroIsItsOwnDoubleConstant() {
		ConstantPool cp = new ConstantPool();
		DoubleEntry zero = cp.entries().doubleEntry(0.0);
		DoubleEntry negativeZero = cp.entries().doubleEntry(-0.0);
		assertThat(negativeZero.index()).isNotEqualTo(zero.index());
		assertThat(Double.doubleToRawLongBits(((DoubleEntry) cp.entryAt(negativeZero.index())).doubleValue()))
			.isEqualTo(Double.doubleToRawLongBits(-0.0));
	}

	// The master pool describes a program too large for one class: it keeps growing past
	// the format limit, and an entry past 65535 is still the entry its content names.
	@Test
	void aPoolGrowsPastTheFormatLimit() {
		ConstantPool cp = new ConstantPool();
		while (cp.size() < 70_000) {
			cp.entries().intEntry(cp.size());
		}
		ClassEntry owner = cp.classEntry("Owner");
		MethodRefEntry first = cp.methodRef(owner, "a", "()V");
		MethodRefEntry second = cp.methodRef(owner, "b", "()V");
		assertThat(first.index()).isNotEqualTo(second.index()).isGreaterThan(0xFFFF);
		assertThat(first.owner().asInternalName()).isEqualTo("Owner");
		assertThat(cp.entryAt(second.index())).isSameAs(second);
		// The same reference minted again, from names or from entries, is the same entry.
		assertThat(cp.methodRef("Owner", "a", "()V")).isSameAs(first);
		assertThat(cp.methodRef(owner, cp.utf8Entry("a"), cp.utf8Entry("()V"))).isSameAs(first);
	}

	// A CONSTANT_Utf8 holds 65535 bytes of MODIFIED UTF-8: U+0000 takes two, a
	// supplementary character its surrogate pair of three each. The pool refuses the
	// string that crosses it where it is minted.
	@Test
	void refusesAUtf8PastItsModifiedUtf8Length() {
		ConstantPool cp = new ConstantPool();
		String fits = "\u0000".repeat(32_767) + "a";
		assertThat(cp.utf8Entry(fits).stringValue()).isEqualTo(fits);
		assertThatIllegalArgumentException().isThrownBy(() -> cp.utf8Entry("\u0000".repeat(32_768)))
			.withMessageContaining("65536");
		assertThatIllegalArgumentException().isThrownBy(() -> cp.stringEntry("💣".repeat(10_923)))
			.withMessageContaining("65538");
	}

	// A string past one CONSTANT_Utf8 is cut into the fewest pieces that each fit,
	// between
	// code points: the supplementary character whose six bytes cross the limit starts the
	// next piece whole.
	@Test
	void cutsALongStringIntoPiecesThatEachFit() {
		ConstantPool cp = new ConstantPool();
		String bomb = "💣";
		String value = "a".repeat(65_532) + bomb + "\u0000".repeat(40_000) + "b";
		assertThat(ConstantPool.fitsUtf8(value)).isFalse();
		assertThat(ConstantPool.utf8Pieces(value)).containsExactly("a".repeat(65_532), bomb + "\u0000".repeat(32_764),
				"\u0000".repeat(7_236) + "b");
		for (String piece : ConstantPool.utf8Pieces(value)) {
			assertThat(ConstantPool.fitsUtf8(piece)).isTrue();
			assertThat(cp.utf8Entry(piece).stringValue()).isEqualTo(piece);
		}
		assertThat(ConstantPool.fitsUtf8("\u0000".repeat(32_767) + "a")).isTrue();
		assertThat(ConstantPool.utf8Pieces("")).containsExactly("");
		assertThat(ConstantPool.utf8Pieces("short")).containsExactly("short");
	}

	// The facades mint one entry per content, in the one pool: from names or from Utf8
	// entries, a class, a string, a field and an interface method alike.
	@Test
	void theFacadesMintOneEntryPerContent() {
		ConstantPool cp = new ConstantPool();
		Utf8Entry map = cp.utf8Entry("java/util/Map");
		assertThat(cp.classEntry(map)).isSameAs(cp.classEntry("java/util/Map"));
		assertThat(cp.stringEntry(cp.utf8Entry("s"))).isSameAs(cp.stringEntry("s"));
		assertThat(cp.fieldRef(cp.classEntry("A"), "f", "I")).isSameAs(cp.fieldRef("A", "f", "I"));
		InterfaceMethodRefEntry size = cp.interfaceMethodRef("java/util/List", "size", "()I");
		assertThat(cp.interfaceMethodRef(cp.classEntry("java/util/List"), cp.utf8Entry("size"), cp.utf8Entry("()I")))
			.isSameAs(size);
	}

}
