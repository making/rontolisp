package am.ik.rontolisp.runtime;

import java.io.IOException;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link RontoProcess}: the browser sandbox starts no process,
 * so {@code uiop:run-program} is refused with the host's reason, like every git run
 * ({@code Target_GitCommand}). {@link RontoProcess#spawn} is the only path to
 * {@link ProcessBuilder} in the class. Compiled only under the {@code web} Maven profile.
 */
@TargetClass(RontoProcess.class)
final class Target_RontoProcess {

	@Substitute
	public static long spawn(String argv, String directory, String input, String output, String errorOutput,
			int modes) throws IOException {
		throw new IOException("starting a program is not available in the browser playground (no processes)");
	}

}
