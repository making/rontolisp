package am.ik.artifact;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link GitCommand}: the browser sandbox starts no process and
 * has no filesystem for a checkout, so every git run is refused. {@link GitCommand#exec}
 * is the only path to {@link ProcessBuilder} in the package, and {@link GitCommand#run}
 * goes through it, so this one refusal covers every clone, fetch and checkout; the
 * consumer prefixes it with its own operation. Compiled only under the {@code web} Maven
 * profile.
 */
@TargetClass(GitCommand.class)
final class Target_GitCommand {

	@Substitute
	GitCommand.Result exec(Map<String, String> env, List<String> args) throws IOException {
		throw new IOException("running git is not available in the browser playground (no processes or filesystem)");
	}

}
