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

}
