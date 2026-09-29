import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts a class file's methods by family -- a name with its trailing {@code $k} or
 * {@code _k} number folded: {@code java Families.java A.class B.class}, the counts side by
 * side where they differ.
 */
public class Families {

	public static void main(String[] args) throws Exception {
		Map<String, int[]> counts = new TreeMap<>();
		for (int side = 0; side < 2; side++) {
			for (MethodModel method : ClassFile.of().parse(Files.readAllBytes(Path.of(args[side]))).methods()) {
				String name = method.methodName().stringValue();
				String family = name.replaceAll("\\$\\d+$", "\\$k").replaceAll("^_lambda_\\d+$", "_lambda_k");
				counts.computeIfAbsent(family, k -> new int[2])[side]++;
			}
		}
		counts.forEach((family, c) -> {
			if (c[0] != c[1]) {
				System.out.printf("%-40s %6d -> %6d%n", family, c[0], c[1]);
			}
		});
	}

}
