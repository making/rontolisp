package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;

/**
 * Packs a JVM compile ({@code -o app.war}) into a Servlet war: {@link JvmJarWriter} with
 * a different entry prefix and a different manifest. Layout:
 *
 * <pre>
 * META-INF/MANIFEST.MF                              no Main-Class: nobody java -jars a war
 * WEB-INF/classes/App.class                         the program
 * WEB-INF/classes/am/ik/rontolisp/runtime/*.class   the travelling closure + the servlet transport
 * WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer
 * </pre>
 *
 * <p>
 * <b>No {@code web.xml}, and no file naming the program class.</b> The service
 * declaration is the war's only non-class file, it is one line, and it is the same line
 * in every war rontolisp ever emits: the container discovers the program itself through
 * {@code RontoHttpServletInitializer}'s {@code @HandlesTypes}, because implementing
 * {@code RontoHttpServer.Handler} is already what the JVM backend emits. A user who wants
 * their own {@code web.xml} (a filter, a security constraint, a {@code <session-config>})
 * can add one afterwards and the initializer keeps working -- verified against both
 * {@code metadata-complete="true"} and {@code <absolute-ordering/>}, neither of which
 * reaches an initializer declared in {@code WEB-INF/classes} (the {@code .todo/529}
 * spike).
 *
 * <p>
 * Every entry is stamped with {@link JvmJarWriter}'s fixed timestamp and written in a
 * fixed order, so compiling the same program twice produces byte-identical wars
 * ({@code .kb/emitted-output-determinism.md}).
 */
final class JvmWarWriter {

	/**
	 * Where a war's classes live; the runtime classes keep their canonical path under it.
	 */
	private static final String CLASSES = "WEB-INF/classes/";

	/** The one-line service declaration that makes the war self-configuring. */
	private static final String SERVICE_FILE = CLASSES
			+ "META-INF/services/jakarta.servlet.ServletContainerInitializer";

	private static final String INITIALIZER = "am.ik.rontolisp.runtime.RontoHttpServletInitializer";

	private JvmWarWriter() {
	}

	/**
	 * Builds the war.
	 * @param className the emitted class's internal (slash-separated) name
	 * @param classBytes the emitted class file
	 * @param runtimeClasses the runtime class files that must travel with it (the served
	 * closure plus the servlet transport), keyed by their canonical path
	 * @param coordinates the Maven coordinates to embed (a war IS a Maven artifact), or
	 * {@code null} for none
	 * @param simd whether the class was compiled with {@code --simd}
	 * @return the war bytes
	 */
	static byte[] war(String className, byte[] classBytes, Map<String, byte[]> runtimeClasses,
			@Nullable MavenCoordinates coordinates, boolean simd) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			JvmJarWriter.write(zip, "META-INF/MANIFEST.MF",
					JvmJarWriter.manifest(className, false).getBytes(StandardCharsets.UTF_8));
			if (coordinates != null) {
				String directory = coordinates.metaInfDirectory();
				JvmJarWriter.write(zip, directory + "pom.xml",
						coordinates.pomXml(simd).getBytes(StandardCharsets.UTF_8));
				JvmJarWriter.write(zip, directory + "pom.properties",
						coordinates.pomProperties().getBytes(StandardCharsets.UTF_8));
			}
			JvmJarWriter.write(zip, CLASSES + className + ".class", classBytes);
			// Sorted rather than in the map's own order: the entry order is emitted
			// output, and the source is a Map whose iteration order promises nothing.
			for (Map.Entry<String, byte[]> runtimeClass : new TreeMap<>(runtimeClasses).entrySet()) {
				JvmJarWriter.write(zip, CLASSES + runtimeClass.getKey(), runtimeClass.getValue());
			}
			JvmJarWriter.write(zip, SERVICE_FILE, (INITIALIZER + "\n").getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

}
