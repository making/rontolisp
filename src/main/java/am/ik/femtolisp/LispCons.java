package am.ik.femtolisp;

import java.util.ArrayList;
import java.util.List;

public record LispCons(LispVal car, LispVal cdr) implements LispVal {

	/**
	 * Converts this cons cell chain into a Java list. Assumes proper list (terminated by
	 * nil).
	 */
	public List<LispVal> toList() {
		List<LispVal> result = new ArrayList<>();
		LispVal current = this;
		while (current instanceof LispCons cons) {
			result.add(cons.car());
			current = cons.cdr();
		}
		return result;
	}

	@Override
	public String print() {
		StringBuilder sb = new StringBuilder("(");
		LispVal current = this;
		boolean first = true;
		while (current instanceof LispCons cons) {
			if (!first) {
				sb.append(' ');
			}
			sb.append(cons.car().print());
			current = cons.cdr();
			first = false;
		}
		if (!(current instanceof LispNil)) {
			sb.append(" . ").append(current.print());
		}
		sb.append(')');
		return sb.toString();
	}

}
