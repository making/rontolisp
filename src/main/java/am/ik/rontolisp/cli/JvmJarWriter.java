package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import am.ik.rontolisp.Version;

import org.jspecify.annotations.Nullable;

/**
 * Packs a JVM compile ({@code -o out.jar}) into a jar the rest of the ecosystem can
 * consume: the class at its package path, the {@code :float-vector} handle runtime beside
 * it, a manifest, and -- with {@code --maven-coordinates} -- the embedded
 * {@code META-INF/maven} pair that makes {@code mvn install:install-file -Dfile=out.jar}
 * enough on its own.
 * <p>
 * The runtime class files are NOT optional. A {@code :float-vector} export hands out
 * {@code am.ik.rontolisp.runtime.RontoFloatArray}, and the {@code .class} output writes
 * it beside the program's class; leaving it out of the jar is a
 * {@code NoClassDefFoundError} in the consumer rather than an error here.
 * <p>
 * Every entry is stamped with one fixed timestamp and the entries are written in a fixed
 * order, so compiling the same program twice produces byte-identical jars
 * ({@code .kb/emitted-output-determinism.md}).
 */
final class JvmJarWriter {

	/** The MS-DOS epoch, which is the earliest a zip entry can express. */
	private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

	private static final String MANIFEST = "META-INF/MANIFEST.MF";

	/** A manifest line may not exceed 72 bytes; the rest continues after one space. */
	private static final int MANIFEST_LINE_WIDTH = 72;

	private JvmJarWriter() {
	}

	/**
	 * Builds the jar.
	 * @param className the emitted class's internal (slash-separated) name
	 * @param classBytes the emitted class file
	 * @param runtimeClasses the runtime class files that must travel with it, keyed by
	 * their canonical path
	 * @param mainClass whether the class has a {@code main}, i.e. whether the manifest
	 * gets a {@code Main-Class} ({@code --no-main} libraries get none: nobody should
	 * {@code java -jar} a library)
	 * @param coordinates the Maven coordinates to embed, or {@code null} for none
	 * @param simd whether the class was compiled with {@code --simd}
	 * @return the jar bytes
	 */
	static byte[] jar(String className, byte[] classBytes, Map<String, byte[]> runtimeClasses, boolean mainClass,
			@Nullable MavenCoordinates coordinates, boolean simd) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			write(zip, MANIFEST, manifest(className, mainClass).getBytes(StandardCharsets.UTF_8));
			if (coordinates != null) {
				String directory = coordinates.metaInfDirectory();
				write(zip, directory + "pom.xml", coordinates.pomXml(simd).getBytes(StandardCharsets.UTF_8));
				write(zip, directory + "pom.properties", coordinates.pomProperties().getBytes(StandardCharsets.UTF_8));
			}
			write(zip, className + ".class", classBytes);
			// Sorted rather than in the map's own order: the entry order is emitted
			// output, and the source is a Map whose iteration order promises nothing.
			for (Map.Entry<String, byte[]> runtimeClass : new TreeMap<>(runtimeClasses).entrySet()) {
				write(zip, runtimeClass.getKey(), runtimeClass.getValue());
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	private static void write(ZipOutputStream zip, String name, byte[] content) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		// setTimeLocal writes the DOS time field directly. setTime(long) would convert
		// through the default time zone and add an extended-timestamp extra field, so the
		// same build would emit different bytes on two machines.
		entry.setTimeLocal(FIXED_TIME);
		zip.putNextEntry(entry);
		zip.write(content);
		zip.closeEntry();
	}

	/**
	 * The manifest. {@code Main-Class} is present exactly when the class HAS a main, so
	 * {@code java -jar out.jar} keeps working for a program and a {@code --no-main}
	 * library jar carries none. {@code Enable-Native-Access} is always present, as in
	 * rontolisp's own exec jar: a program that reaches the foreign function API --
	 * {@code objc:} / {@code appkit:}, {@code --blas}, {@code --gpu} -- then runs under
	 * {@code java -jar} without the JDK's restricted-method warning, and the header is
	 * inert for a program that reaches nothing.
	 */
	private static String manifest(String className, boolean mainClass) {
		StringBuilder manifest = new StringBuilder();
		append(manifest, "Manifest-Version", "1.0");
		append(manifest, "Created-By", "rontolisp " + Version.getVersion());
		append(manifest, "Enable-Native-Access", "ALL-UNNAMED");
		if (mainClass) {
			append(manifest, "Main-Class", className.replace('/', '.'));
		}
		manifest.append("\r\n");
		return manifest.toString();
	}

	/**
	 * Appends one manifest header, folded onto continuation lines at the 72-byte limit
	 * the manifest format imposes -- a deeply packaged {@code Main-Class} reaches it.
	 */
	private static void append(StringBuilder manifest, String name, String value) {
		byte[] bytes = (name + ": " + value).getBytes(StandardCharsets.UTF_8);
		int start = 0;
		while (start < bytes.length) {
			// A continuation line spends one of its 72 bytes on the leading space, and
			// the cut must not fall inside a UTF-8 sequence.
			boolean first = start == 0;
			int end = Math.min(bytes.length, start + (first ? MANIFEST_LINE_WIDTH : MANIFEST_LINE_WIDTH - 1));
			while (end < bytes.length && end > start && (bytes[end] & 0xC0) == 0x80) {
				end--;
			}
			manifest.append(first ? "" : " ")
				.append(new String(bytes, start, end - start, StandardCharsets.UTF_8))
				.append("\r\n");
			start = end;
		}
	}

}
