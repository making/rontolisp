package am.ik.rontolisp.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;

// usage: Probe File.java [-v] : the census of the resumable lowering; -v prints the forms too
public class Probe {
	public static void main(String[] args) throws Exception {
		Path path = Path.of(args[0]);
		String source = Files.readString(path);
		String name = path.getFileName().toString().replace(".java", "");
		JavaFiles files = new JavaFiles() {
			public JavaFiles.Source find(String from, String p) {
				Path found = Path.of(from).toAbsolutePath().getParent().resolve(p).normalize();
				try {
					return Files.exists(found) ? new JavaFiles.Source(found.toString(), Files.readString(found)) : null;
				}
				catch (Exception e) {
					return null;
				}
			}
			public String absolute(String p) {
				return Path.of(p).toAbsolutePath().toString();
			}
		};
		JavaResumable.Census[] census = new JavaResumable.Census[1];
		List<LispVal> forms = JavaLowering.lower(JavaParser.parse(source, path.toString(), name), source,
				path.toString(), files, null, false, null, true, c -> census[0] = c);
		JavaResumable.Census c = census[0];
		if (c == null) {
			System.out.println(name + ": starts no thread");
			return;
		}
		System.out.println(name + ": functions " + c.functions() + ", resumable " + c.resumable().size() + ", lambdas "
				+ c.lambdas() + ", resumable lambdas " + c.resumableLambdas() + ", regions " + c.regions() + ", held "
				+ c.held().size() + ", blocking entries " + c.blocking());
		if (args.length > 1) {
			c.resumable().forEach(r -> System.out.println("  R " + r));
			c.held().forEach(r -> System.out.println("  H " + r));
		}
		if (args.length > 2) {
			for (LispVal form : forms) {
				System.out.println(form.print());
			}
		}
	}
}
