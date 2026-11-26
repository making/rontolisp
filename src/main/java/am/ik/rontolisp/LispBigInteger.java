package am.ik.rontolisp;

import java.math.BigInteger;

/**
 * An arbitrary-precision integer value. Produced automatically when a {@code long}
 * computation would overflow, so integer arithmetic never silently wraps. Values that fit
 * in a {@code long} are normalized back to {@link LispInteger}, so a
 * {@code LispBigInteger} always holds a magnitude outside the {@code long} range.
 *
 * @param value the big integer value
 */
public record LispBigInteger(BigInteger value) implements LispVal {

	@Override
	public String print() {
		return this.value.toString();
	}

}
