package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

/**
 * Stores the build's own classes and resources in the native image as one archive
 * ({@link RunTimeCompilerFiles#RESOURCE}), read off the image's class path while the
 * image is built: a binary has no code source, and a JVM class whose Clojure program
 * lowers as it runs carries the compiler of the build that compiled it
 * ({@code .kb/jvm-run-time-lowering.md}).
 *
 * <p>
 * Compiled only under the {@code native} Maven profile ({@code src/native/java}), which
 * names it in {@code --features}.
 */
public final class RunTimeCompilerFeature implements Feature {

	private static final String OWN_CLASS = RunTimeCompilerFiles.class.getName().replace('.', '/') + ".class";

	@Override
	public void beforeAnalysis(BeforeAnalysisAccess access) {
		for (Path root : access.getApplicationClassPath()) {
			if (holdsBuild(root)) {
				RuntimeResourceAccess.addResource(RunTimeCompilerFiles.class.getModule(), RunTimeCompilerFiles.RESOURCE,
						RunTimeCompilerFiles.archive(root));
				return;
			}
		}
		throw new IllegalStateException("no class path entry of the image holds " + OWN_CLASS);
	}

	private static boolean holdsBuild(Path root) {
		if (Files.isDirectory(root)) {
			return Files.isRegularFile(root.resolve(OWN_CLASS));
		}
		if (!Files.isRegularFile(root)) {
			return false;
		}
		try (ZipFile jar = new ZipFile(root.toFile())) {
			return jar.getEntry(OWN_CLASS) != null;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
