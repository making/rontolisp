package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.compiler.ClackEnv;
import am.ik.rontolisp.runtime.RontoHttpClack;
import am.ik.rontolisp.runtime.RontoHttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one fact about the compiled hash-table representation that is asserted from
 * OUTSIDE the emitted code: its runtime class.
 * <p>
 * The emitted helpers cast to {@link JvmHashRuntimeBuilder#MAP_CLASS} exactly (rather
 * than to {@code java.util.Map}) so that {@code hash-table-p} and the printer can tell a
 * Lisp table from a host map a {@code java:} call returned. That makes the class part of
 * the contract for every hand-written runtime that builds a table for compiled code to
 * read -- today {@code RontoHttpClack}, whose {@code :headers} table would otherwise fail
 * the cast at the handler's first {@code gethash} ({@code .kb/hash-tables.md}).
 */
class JvmHashRuntimeBuilderTest {

	@Test
	@SuppressWarnings("NullAway") // buildEnv is called from bytecode, where nil IS null
	void theHandwrittenRuntimeBuildsTheSameTableClass() {
		RontoHttpServer.Request request = RontoHttpServer.Request.of("GET", "/",
				List.of(new RontoHttpServer.Header("X-Token", "secret42")), "");

		Object headers = plistGet(RontoHttpClack.buildEnv(request, null), ClackEnv.HEADERS);

		assertThat(headers).isNotNull();
		assertThat(headers.getClass().getName().replace('.', '/')).isEqualTo(JvmHashRuntimeBuilder.MAP_CLASS);
	}

	// Walks the JVM runtime representation of a plist: an Object[2] cons chain.
	private static Object plistGet(@Nullable Object plist, String key) {
		Object cursor = plist;
		while (cursor instanceof Object[] cell) {
			Object[] rest = (Object[]) cell[1];
			if (key.equals(cell[0])) {
				return rest[0];
			}
			cursor = rest[1];
		}
		throw new IllegalStateException("no " + key + " in the environment plist");
	}

}
