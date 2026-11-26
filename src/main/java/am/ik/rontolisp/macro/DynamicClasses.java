package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The runtime of classes made at RUN time ({@code dynamic-classes.lisp}): a class
 * metaobject a program instantiates from a metaclass after the static class tables were
 * built, whose instances carry their class and its class precedence list
 * ({@code ClosRegistry.DYNAMIC_OBJECT_TAG}) for the dispatchers, {@code class-of} and
 * {@code subtypep} to consult.
 *
 * <p>
 * Lisp source read with the real {@link LispReader}, like {@link MopProtocol}: the
 * compile paths prepend {@link #forms()} from
 * {@code LispMacroExpander.expandTopLevelDefinitions} when a program carries a
 * {@code :metaclass} defclass and an {@code allocate-instance} reference; the interpreter
 * evaluates the same forms when it first meets a class made at run time.
 */
public final class DynamicClasses {

	@Nullable private static volatile List<LispVal> forms;

	private DynamicClasses() {
	}

	/**
	 * The runtime's definition forms, in definition order. Parsed once and cached; the
	 * list is immutable.
	 * @return the definitions
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (DynamicClasses.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource()));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = DynamicClasses.class.getResourceAsStream("dynamic-classes.lisp")) {
			if (in == null) {
				throw new IllegalStateException("dynamic-classes.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
