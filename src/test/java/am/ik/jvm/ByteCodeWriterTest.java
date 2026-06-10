package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ByteCodeWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void generateAndRunHelloWorld() throws Exception {
		ConstantPool cp = new ConstantPool();

		// this class and java/lang/Object superclass
		ConstantPool.ClassConstant thisClass = cp.addClass(cp.addUtf8("HelloWorld"));
		ConstantPool.ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));

		// System.out field reference
		ConstantPool.ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		ConstantPool.FieldrefConstant systemOut = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("out"), cp.addUtf8("Ljava/io/PrintStream;")));

		// "Hello, World!" string constant
		ConstantPool.StringConstant helloWorld = cp.addString("Hello, World!");

		// PrintStream.println(String) method reference
		ConstantPool.ClassConstant printStreamClass = cp.addClass(cp.addUtf8("java/io/PrintStream"));
		ConstantPool.MethodrefConstant println = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("(Ljava/lang/String;)V")));

		// Method and attribute name constants
		ConstantPool.Utf8Constant mainName = cp.addUtf8("main");
		ConstantPool.Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		ConstantPool.Utf8Constant codeAttr = cp.addUtf8("Code");

		// Assemble the class file
		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut).write(0xCA, 0xFE, 0xBA, 0xBE) // magic
			.writeVersion(0, 52) // class file version 52 (Java 8)
			.writeConstantPool(cp)
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass)
			.writeInterfaces(i -> {
			})
			.writeFields(f -> {
			})
			.writeMethods(methods -> methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainName, mainDesc,
					method -> method.writeAttributes(attrs -> attrs.add(codeAttr, attr -> {
						attr.writeU2(2) // max_stack
							.writeU2(1) // max_locals (String[] args)
							.writeCode(Opcode.GETSTATIC, systemOut.indexAsU2(), Opcode.LDC_W, helloWorld.indexAsU2(),
									Opcode.INVOKEVIRTUAL, println.indexAsU2(), Opcode.RETURN)
							.writeU2(0) // exception_table_length
							.writeU2(0); // attributes_count
					}))))
			.writeAttributes(a -> {
			});

		// Write to a temp file so URLClassLoader can load it
		byte[] classBytes = classOut.toByteArray();
		Path classFile = tempDir.resolve("HelloWorld.class");
		Files.write(classFile, classBytes);

		// Load and invoke the generated main method, capturing stdout
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("HelloWorld");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("Hello, World!");
		}
	}

}
