package am.ik.rontolisp.eval;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.webimage.api.JSString;

/**
 * Web Image substitution for {@link LocalTimeZone}: the browser playground's local time
 * zone is the browser's ({@link BrowserTimeZone}), where the JVM's default zone would
 * answer whatever the Web Image runtime was built with. Compiled only under the
 * {@code web} Maven profile.
 */
@TargetClass(LocalTimeZone.class)
final class Target_LocalTimeZone {

	@Substitute
	static long offset(long unixSeconds) {
		return Long.parseLong(BrowserTimeZone.offset(JSString.of(Long.toString(unixSeconds))).asString());
	}

}
