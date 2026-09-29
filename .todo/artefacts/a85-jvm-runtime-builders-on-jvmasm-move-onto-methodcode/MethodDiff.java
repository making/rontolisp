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

/**
 * The methods whose instructions differ between two class files, with the first differing
 * instruction of the first three: {@code java MethodDiff.java A.class B.class}.
 */
public class MethodDiff {

	public static void main(String[] args) throws Exception {
		Map<String, List<String>> a = methods(args[0]);
		Map<String, List<String>> b = methods(args[1]);
		int shown = 0;
		for (Map.Entry<String, List<String>> method : a.entrySet()) {
			List<String> x = method.getValue();
			List<String> y = b.get(method.getKey());
			if (y == null) {
				System.out.println("only in A: " + method.getKey());
			}
			else if (!x.equals(y)) {
				System.out.println("DIFF " + method.getKey() + " (" + x.size() + " vs " + y.size() + " instructions)");
				if (shown++ < 3) {
					for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
						if (!x.get(i).equals(y.get(i))) {
							System.out.println("   @" + i + "  A: " + x.get(i) + "\n        B: " + y.get(i));
							break;
						}
					}
				}
			}
		}
		b.keySet().stream().filter(key -> !a.containsKey(key)).forEach(key -> System.out.println("only in B: " + key));
	}

	static Map<String, List<String>> methods(String file) throws Exception {
		ClassModel model = ClassFile.of().parse(Files.readAllBytes(Path.of(file)));
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
