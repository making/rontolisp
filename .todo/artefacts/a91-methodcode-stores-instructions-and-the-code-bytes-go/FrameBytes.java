import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a class file's StackMapTable and LineNumberTable attributes take: every method body
 * rewritten without each (the pool shared, so the code is the same), the differences
 * printed -- {@code java FrameBytes.java X.class...}.
 */
public class FrameBytes {

	public static void main(String[] args) throws Exception {
		ClassTransform bodies = ClassTransform.transformingMethodBodies(CodeTransform.ACCEPT_ALL);
		for (String file : args) {
			byte[] bytes = Files.readAllBytes(Path.of(file));
			ClassModel model = ClassFile.of().parse(bytes);
			byte[] noFrames = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).transformClass(model, bodies);
			byte[] neither = ClassFile
				.of(ClassFile.StackMapsOption.DROP_STACK_MAPS, ClassFile.LineNumbersOption.DROP_LINE_NUMBERS)
				.transformClass(model, bodies);
			System.out.printf("%s: %,d B; frames %,d B; lines %,d B; the rest %,d B%n", file, bytes.length,
					bytes.length - noFrames.length, noFrames.length - neither.length, neither.length);
		}
	}

}
