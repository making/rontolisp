package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ByteCodeWriterTest {

	@TempDir
	Path tempDir;

	// CONSTANT_Utf8 requires the class-file "modified UTF-8": the u2 length counts
	// BYTES (not chars), U+0000 is the two-byte 0xC0 0x80, and a supplementary
	// character is encoded as its CESU-8 surrogate pair (never a 4-byte sequence).
	@Test
	void writeUtf8InfoEncodesModifiedUtf8() {
		assertThat(utf8Bytes("A")).containsExactly(0x00, 0x01, 'A');
		// U+2738 (3-byte BMP char): the length must be 3, not the char count 1.
		assertThat(utf8Bytes("✸")).containsExactly(0x00, 0x03, 0xE2, 0x9C, 0xB8);
		// U+0000 must not be a raw 0x00 byte.
		assertThat(utf8Bytes("\u0000")).containsExactly(0x00, 0x02, 0xC0, 0x80);
		// U+1F4A3 (supplementary): the surrogate pair D83D DCA3 as two 3-byte units.
		assertThat(utf8Bytes("💣")).containsExactly(0x00, 0x06, 0xED, 0xA0, 0xBD, 0xED, 0xB2, 0xA3);
	}

	private static int[] utf8Bytes(String s) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new ByteCodeWriter(out).writeUtf8Info(s);
		byte[] bytes = out.toByteArray();
		int[] unsigned = new int[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			unsigned[i] = bytes[i] & 0xFF;
		}
		return unsigned;
	}

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

	// Pins the exception-table emission AND the class-version-50 contract the Lisp JVM
	// backend relies on: a Code attribute with a non-empty exception table verifies and
	// runs WITHOUT a StackMapTable (the type-inference verifier computes handler frames
	// itself; from version 51 stack maps would be mandatory).
	@Test
	void generateAndRunTypedCatchHandler() throws Exception {
		assertThat(runCatchClass("TypedCatch", true)).isEqualTo("boom");
	}

	// catch_type 0 catches any throwable -- the finally/unwind shape.
	@Test
	void generateAndRunCatchAnyHandler() throws Exception {
		assertThat(runCatchClass("CatchAny", false)).isEqualTo("boom");
	}

	/**
	 * Assembles a version-50 class whose static {@code run()} throws
	 * {@code RuntimeException("boom")} inside a protected range and returns
	 * {@code getMessage()} of the caught exception from the handler, then loads and
	 * invokes it.
	 */
	private String runCatchClass(String classNameStr, boolean typedCatch) throws Exception {
		ConstantPool cp = new ConstantPool();
		ConstantPool.ClassConstant thisClass = cp.addClass(cp.addUtf8(classNameStr));
		ConstantPool.ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ConstantPool.ClassConstant runtimeExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant ctor = cp.addMethodref(runtimeExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		// getMessage is referenced via Throwable so the catch-any handler (whose inferred
		// stack top is Throwable, not RuntimeException) passes the receiver check too.
		ConstantPool.ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ConstantPool.MethodrefConstant getMessage = cp.addMethodref(throwableClass,
				cp.addNameAndType(cp.addUtf8("getMessage"), cp.addUtf8("()Ljava/lang/String;")));
		ConstantPool.StringConstant boom = cp.addString("boom");
		ConstantPool.Utf8Constant runName = cp.addUtf8("run");
		ConstantPool.Utf8Constant runDesc = cp.addUtf8("()Ljava/lang/String;");
		ConstantPool.Utf8Constant codeAttr = cp.addUtf8("Code");

		// 0: NEW, 3: DUP, 4: LDC_W "boom", 7: INVOKESPECIAL <init>, 10: ATHROW,
		// 11 (handler): INVOKEVIRTUAL getMessage, 14: ARETURN
		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut).write(0xCA, 0xFE, 0xBA, 0xBE)
			.writeVersion(0, 50)
			.writeConstantPool(cp)
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass)
			.writeInterfaces(i -> {
			})
			.writeFields(f -> {
			})
			.writeMethods(methods -> methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, runName, runDesc,
					method -> method.writeAttributes(attrs -> attrs.add(codeAttr, attr -> {
						attr.writeU2(3) // max_stack
							.writeU2(0) // max_locals
							.writeCode(Opcode.NEW, runtimeExClass.indexAsU2(), Opcode.DUP, Opcode.LDC_W,
									boom.indexAsU2(), Opcode.INVOKESPECIAL, ctor.indexAsU2(), Opcode.ATHROW,
									Opcode.INVOKEVIRTUAL, getMessage.indexAsU2(), Opcode.ARETURN)
							.writeExceptionTable(List.of(new ByteCodeWriter.ExceptionTableEntry(0, 11, 11,
									typedCatch ? runtimeExClass.index() : 0)))
							.writeU2(0); // attributes_count
					}))))
			.writeAttributes(a -> {
			});

		Path classFile = tempDir.resolve(classNameStr + ".class");
		Files.write(classFile, classOut.toByteArray());
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass(classNameStr);
			Method run = clazz.getMethod("run");
			return (String) run.invoke(null);
		}
	}

}
