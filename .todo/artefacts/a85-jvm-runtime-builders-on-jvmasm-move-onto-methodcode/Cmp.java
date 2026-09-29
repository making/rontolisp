import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Compiles every program under a directory with two rontolisp jars in-process and compares
 * every class file each produces: {@code java Cmp.java A.jar B.jar programs-dir}.
 */
public class Cmp {

	public static void main(String[] args) throws Exception {
		Compiler a = new Compiler(Path.of(args[0]));
		Compiler b = new Compiler(Path.of(args[1]));
		List<Path> programs;
		try (Stream<Path> files = Files.walk(Path.of(args[2]))) {
			programs = files.filter(p -> p.toString().endsWith(".lisp")).sorted().toList();
		}
		int same = 0, differ = 0, failedBoth = 0, failedOne = 0, classes = 0;
		for (Path program : programs) {
			String source = Files.readString(program);
			Map<String, byte[]> x;
			Map<String, byte[]> y;
			String ex = null, ey = null;
			try {
				x = a.compile(source);
			}
			catch (Throwable t) {
				x = null;
				ex = root(t);
			}
			try {
				y = b.compile(source);
			}
			catch (Throwable t) {
				y = null;
				ey = root(t);
			}
			if (x == null || y == null) {
				if (x == null && y == null && ex.equals(ey)) {
					failedBoth++;
				}
				else {
					failedOne++;
					System.out.println("FAIL-MISMATCH " + program + "\n   A: " + ex + "\n   B: " + ey);
				}
				continue;
			}
			boolean ok = x.keySet().equals(y.keySet());
			if (!ok) {
				System.out.println("CLASS-SET " + program + " " + x.keySet() + " vs " + y.keySet());
			}
			for (Map.Entry<String, byte[]> e : x.entrySet()) {
				classes++;
				byte[] other = y.get(e.getKey());
				if (other != null && !Arrays.equals(e.getValue(), other)) {
					ok = false;
					System.out.println("DIFF " + program + " " + e.getKey());
					Path dump = Path.of("diffs");
					Files.createDirectories(dump);
					String base = program.getFileName().toString() + "-" + e.getKey().replace('/', '_');
					Files.write(dump.resolve(base + ".A.class"), e.getValue());
					Files.write(dump.resolve(base + ".B.class"), other);
				}
			}
			if (ok) {
				same++;
			}
			else {
				differ++;
			}
		}
		System.out.println("programs " + programs.size() + ": identical " + same + ", differ " + differ
				+ ", failed on both alike " + failedBoth + ", failed differently " + failedOne + "; classes compared "
				+ classes);
	}

	static String root(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getClass().getName() + ": " + t.getMessage();
	}

	static final class Compiler {

		final Constructor<?> ctor;

		final Method compile;

		final Method classBytes;

		final Method runtimeClasses;

		final Method internalName;

		Compiler(Path jar) throws Exception {
			URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() },
					ClassLoader.getPlatformClassLoader());
			Class<?> c = loader.loadClass("am.ik.rontolisp.cli.JvmSourceCompiler");
			this.ctor = c.getConstructor(String.class);
			this.compile = c.getMethod("compile", String.class, String.class);
			Class<?> r = loader.loadClass("am.ik.rontolisp.cli.JvmSourceCompiler$Result");
			this.classBytes = r.getMethod("classBytes");
			this.runtimeClasses = r.getMethod("runtimeClasses");
			this.internalName = r.getMethod("internalClassName");
		}

		@SuppressWarnings("unchecked")
		Map<String, byte[]> compile(String source) throws Throwable {
			Object compiler = this.ctor.newInstance("P");
			Object result;
			try {
				result = this.compile.invoke(compiler, source, null);
			}
			catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause();
			}
			Map<String, byte[]> out = new TreeMap<>();
			out.put((String) this.internalName.invoke(result), (byte[]) this.classBytes.invoke(result));
			out.putAll((Map<String, byte[]>) this.runtimeClasses.invoke(result));
			return out;
		}

	}

}
