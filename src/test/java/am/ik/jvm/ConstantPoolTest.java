package am.ik.jvm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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

	// A pool index is written as a u2 by every emit site, so an entry past 65534 would
	// have its index truncated: the instruction would silently reference an unrelated
	// entry, and the damage would surface far downstream (as an operand-stack model
	// failure, or a class the verifier rejects). The pool must refuse the entry that
	// crosses the limit instead.
	@Test
	void refusesTheEntryThatWouldCrossTheFormatLimit() {
		ConstantPool cp = new ConstantPool();
		while (cp.size() < ConstantPool.MAX_INDEX) {
			cp.addInteger(cp.size());
		}
		assertThat(cp.size()).isEqualTo(ConstantPool.MAX_INDEX);
		assertThatIllegalStateException().isThrownBy(() -> cp.addInteger(-1))
			.withMessageContaining("constant pool overflow");
		// The refused entry left the pool serializable, at its exact capacity.
		assertThat(cp.toByteArray()).isNotEmpty();
	}

	// A long/double straddles the limit rather than landing on it: the second slot must
	// be counted before the entry is accepted.
	@Test
	void refusesATwoSlotEntryThatWouldStraddleTheFormatLimit() {
		ConstantPool cp = new ConstantPool();
		while (cp.size() < ConstantPool.MAX_INDEX - 1) {
			cp.addInteger(cp.size());
		}
		assertThatIllegalStateException().isThrownBy(() -> cp.addLong(Long.MIN_VALUE))
			.withMessageContaining("constant pool overflow");
		// A one-slot entry still fits.
		assertThat(cp.addInteger(-1).index()).isEqualTo(ConstantPool.MAX_INDEX);
	}

	// An unbounded pool describes a program too large for one class: it keeps growing,
	// and an entry whose components sit past 65535 still names them -- a u2-encoded body
	// would have wrapped them onto unrelated entries, and then deduplicated two different
	// references into one.
	@Test
	void anUnboundedPoolKeepsFullWidthComponentIndexesPastTheFormatLimit() {
		ConstantPool cp = ConstantPool.unbounded();
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
		// The same reference added again is still the same entry.
		assertThat(cp.addMethodref(ownerClass, first).index()).isEqualTo(firstRef.index());
		// Such a pool is not one class file's pool.
		assertThatIllegalStateException().isThrownBy(cp::toByteArray).withMessageContaining("constant pool overflow");
	}

	// Entries are held as data now, so their serialization is pinned against the class
	// format directly: tag, then the u2 components or the payload, in insertion order.
	@Test
	void serializesEveryEntryKindInInsertionOrder() {
		ConstantPool cp = new ConstantPool();
		ConstantPool.Utf8Constant name = cp.addUtf8("A");
		ConstantPool.ClassConstant clazz = cp.addClass(name);
		cp.addString(name);
		cp.addInteger(0x01020304);
		cp.addLong(0x0102030405060708L);
		cp.addMethodref(clazz, cp.addNameAndType(name, name));
		assertThat(cp.toByteArray()).containsExactly(0x00, 0x09, // count = 8 entries + 1
				1, 0x00, 0x01, 'A', // #1 Utf8 "A"
				7, 0x00, 0x01, // #2 Class #1
				8, 0x00, 0x01, // #3 String #1
				3, 0x01, 0x02, 0x03, 0x04, // #4 Integer
				5, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // #5-6 Long
				12, 0x00, 0x01, 0x00, 0x01, // #7 NameAndType #1:#1
				10, 0x00, 0x02, 0x00, 0x07); // #8 Methodref #2.#7
	}

}
