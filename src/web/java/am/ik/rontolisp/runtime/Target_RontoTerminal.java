package am.ik.rontolisp.runtime;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link RontoTerminal}: the browser playground has no process
 * standard streams to ask about and no FFM linker to ask with, so every descriptor is no
 * terminal and {@code interactive-stream-p} answers nil there. Compiled only under the
 * {@code web} Maven profile.
 */
@TargetClass(RontoTerminal.class)
final class Target_RontoTerminal {

	@Substitute
	public static boolean isTerminal(int fd) {
		return false;
	}

}
