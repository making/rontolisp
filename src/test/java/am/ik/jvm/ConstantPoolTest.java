package am.ik.jvm;

import java.lang.classfile.constantpool.DoubleEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConstantPoolTest {

	@Test
	void deduplicatesIdenticalEntries() {
		ConstantPool cp = new ConstantPool();
		assertThat(cp.addUtf8("same").index()).isEqualTo(cp.addUtf8("same").index());
		assertThat(cp.size()).isEqualTo(1);
	}

	@Test
	void longAndDoubleTakeTwoSlots() {
		ConstantPool cp = new ConstantPool();
		int first = cp.addLong(1L).index();
		int second = cp.addDouble(1.0).index();
		assertThat(second).isEqualTo(first + 2);
		assertThat(cp.size()).isEqualTo(4);
	}

	// -0.0 == 0.0 in Java, but they are two different constants: a literal -0.0 must not
	// come back as 0.0 from the pool.
	@Test
	void negativeZeroIsItsOwnDoubleConstant() {
		ConstantPool cp = new ConstantPool();
		ConstantPool.DoubleConstant zero = cp.addDouble(0.0);
		ConstantPool.DoubleConstant negativeZero = cp.addDouble(-0.0);
		assertThat(negativeZero.index()).isNotEqualTo(zero.index());
		assertThat(Double.doubleToRawLongBits(((DoubleEntry) cp.entryAt(negativeZero.index())).doubleValue()))
			.isEqualTo(Double.doubleToRawLongBits(-0.0));
	}

	// The master pool describes a program too large for one class: it keeps growing past
	// the format limit, and an entry whose components sit past 65535 still names them.
	@Test
	void aPoolKeepsFullWidthComponentIndexesPastTheFormatLimit() {
		ConstantPool cp = new ConstantPool();
		while (cp.size() < 70_000) {
			cp.addInteger(cp.size());
		}
		ConstantPool.Utf8Constant owner = cp.addUtf8("Owner");
		ConstantPool.ClassConstant ownerClass = cp.addClass(owner);
		ConstantPool.NameAndTypeConstant first = cp.addNameAndType(cp.addUtf8("a"), cp.addUtf8("()V"));
		ConstantPool.NameAndTypeConstant second = cp.addNameAndType(cp.addUtf8("b"), cp.addUtf8("()V"));
		ConstantPool.MethodrefConstant firstRef = cp.addMethodref(ownerClass, first);
		ConstantPool.MethodrefConstant secondRef = cp.addMethodref(ownerClass, second);
		assertThat(firstRef.index()).isNotEqualTo(secondRef.index()).isGreaterThan(0xFFFF);
		assertThat(cp.typeAt(firstRef.index())).isEqualTo(ConstantType.METHODREF);
		assertThat(cp.firstComponentAt(firstRef.index())).isEqualTo(ownerClass.index());
		assertThat(cp.secondComponentAt(secondRef.index())).isEqualTo(second.index());
		assertThat(cp.utf8At(cp.firstComponentAt(ownerClass.index()))).isEqualTo("Owner");
		assertThat(cp.descriptorOf(secondRef.index())).isEqualTo("()V");
		assertThat(cp.entryAt(firstRef.index())).isInstanceOf(MethodRefEntry.class);
		// The same reference added again is still the same entry.
		assertThat(cp.addMethodref(ownerClass, first).index()).isEqualTo(firstRef.index());
	}

	// The test instrument: every index an emitter is handed is one no class file can
	// carry, and the ones before it are filler nothing references.
	@Test
	void aPoolStartedPastTheFormatLimitHandsOutOnlyIndexesPastIt() {
		ConstantPool cp = ConstantPool.startingAt(70_000);
		ConstantPool.StringConstant text = cp.addString("text");
		assertThat(cp.addUtf8("first").index()).isGreaterThanOrEqualTo(70_000);
		assertThat(text.index()).isGreaterThan(70_000);
		assertThat(cp.size()).isGreaterThan(70_000);
		assertThat(cp.typeAt(1)).as("a filler entry").isEqualTo(ConstantType.UTF8);
	}

	// A CONSTANT_Utf8 holds 65535 bytes of MODIFIED UTF-8: U+0000 takes two, a
	// supplementary character its surrogate pair of three each. The pool refuses the
	// string that crosses it where it is minted.
	@Test
	void refusesAUtf8PastItsModifiedUtf8Length() {
		ConstantPool cp = new ConstantPool();
		String fits = "\u0000".repeat(32_767) + "a";
		assertThat(cp.addUtf8(fits).entry().stringValue()).isEqualTo(fits);
		assertThatIllegalArgumentException().isThrownBy(() -> cp.addUtf8("\u0000".repeat(32_768)))
			.withMessageContaining("65536");
		assertThatIllegalArgumentException().isThrownBy(() -> cp.addUtf8("💣".repeat(10_923)))
			.withMessageContaining("65538");
	}

	@Test
	void answersWhichStringConstantsItHolds() {
		ConstantPool cp = new ConstantPool();
		cp.addUtf8("name-only");
		cp.addString("loadable");
		assertThat(cp.hasStringConstant("loadable")).isTrue();
		assertThat(cp.hasStringConstant("name-only")).as("a Utf8 alone is not a String constant").isFalse();
	}

}
