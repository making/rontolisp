import java.lang.classfile.Attribute;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LineNumberTableAttribute;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sums a class file's parts: {@code java ClassStats.java X.class...} -- bytes, methods, code
 * bytes, StackMapTable frames and bytes, LineNumberTable entries, pool entries.
 */
public class ClassStats {

	public static void main(String[] args) throws Exception {
		for (String file : args) {
			byte[] bytes = Files.readAllBytes(Path.of(file));
			ClassModel model = ClassFile.of().parse(bytes);
			long code = 0;
			long frames = 0;
			long frameBytes = 0;
			long lines = 0;
			int methods = 0;
			int over8000 = 0;
			for (MethodModel method : model.methods()) {
				methods++;
				CodeAttribute attribute = method.findAttribute(Attributes.code()).orElse(null);
				if (attribute == null) {
					continue;
				}
				code += attribute.codeLength();
				if (attribute.codeLength() > 8000) {
					over8000++;
				}
				for (Attribute<?> a : attribute.attributes()) {
					if (a instanceof StackMapTableAttribute smt) {
						frames += smt.entries().size();
					}
					if (a instanceof LineNumberTableAttribute lnt) {
						lines += lnt.lineNumbers().size();
					}
				}
			}
			System.out.printf("%s: %,d B, %,d methods (%d over 8000), code %,d B, frames %,d, lines %,d, pool %,d%n",
					file, bytes.length, methods, over8000, code, frames, lines, model.constantPool().size());
		}
	}

}
