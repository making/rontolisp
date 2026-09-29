import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compiles one program with a jar in process and prints the full stack trace of a failure. */
public class Trace {

	public static void main(String[] args) throws Exception {
		URLClassLoader loader = new URLClassLoader(new URL[] { Path.of(args[0]).toUri().toURL() },
				ClassLoader.getPlatformClassLoader());
		Class<?> c = loader.loadClass("am.ik.rontolisp.cli.JvmSourceCompiler");
		Object compiler = c.getConstructor(String.class).newInstance("P");
		try {
			c.getMethod("compile", String.class, String.class).invoke(compiler, Files.readString(Path.of(args[1])), null);
			System.out.println("compiled");
		}
		catch (InvocationTargetException e) {
			e.getCause().printStackTrace(System.out);
		}
	}

}
