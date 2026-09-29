import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every X.A.class / X.B.class pair under a directory: which methods' instructions differ,
 * which exist on one side only; then a histogram of the method names (a trailing $k segment
 * number folded) over all pairs: {@code java MethodDiffAll.java diffs-dir}.
 */
public class MethodDiffAll {

	public static void main(String[] args) throws Exception {
		Map<String, Integer> histogram = new TreeMap<>();
		List<Path> as;
		try (Stream<Path> files = Files.list(Path.of(args[0]))) {
			as = files.filter(p -> p.toString().endsWith(".A.class")).sorted().toList();
		}
		for (Path a : as) {
			Path b = Path.of(a.toString().replace(".A.class", ".B.class"));
			Map<String, List<String>> x = methods(a);
			Map<String, List<String>> y = methods(b);
			List<String> found = new ArrayList<>();
			for (Map.Entry<String, List<String>> m : x.entrySet()) {
				List<String> other = y.get(m.getKey());
				if (other == null) {
					found.add("A-only " + m.getKey());
				}
				else if (!other.equals(m.getValue())) {
					found.add("diff " + m.getKey());
				}
			}
			for (String k : y.keySet()) {
				if (!x.containsKey(k)) {
					found.add("B-only " + k);
				}
			}
			if (found.isEmpty()) {
				found.add("same-instructions");
			}
			System.out.println(a.getFileName() + ": " + found);
			for (String f : found) {
				String key = f.replaceAll("\\$\\d+\\(", "\\$k(").replaceAll("\\(.*", "");
				histogram.merge(key, 1, Integer::sum);
			}
		}
		System.out.println("== histogram");
		histogram.forEach((k, v) -> System.out.println(v + " " + k));
	}

	static Map<String, List<String>> methods(Path file) throws Exception {
		ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (MethodModel method : model.methods()) {
			List<String> instructions = new ArrayList<>();
			CodeAttribute code = method.findAttribute(Attributes.code()).orElse(null);
			if (code != null) {
				for (CodeElement element : code.elementList()) {
					if (element instanceof Instruction instruction) {
						instructions.add(instruction.toString());
					}
				}
			}
			out.put(method.methodName().stringValue() + method.methodType().stringValue(), instructions);
		}
		return out;
	}

}
