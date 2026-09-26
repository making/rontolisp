package am.ik.jvm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The DECLARED shape of a class file: its names, flags and member signatures, never its
 * code. It is what a compile-time resolver needs to choose a method without loading the
 * class -- the only way to do so in a process that has no reflection over the class (a
 * native image), and the way to resolve against a Java release other than the running one
 * (the {@code ct.sym} signature files are class files without code).
 *
 * @param access the class access flags ({@link AccessFlag})
 * @param name the internal name ({@code java/lang/String})
 * @param superName the internal name of the superclass, or {@code null} for
 * {@code java/lang/Object} and for a module descriptor
 * @param interfaces the internal names of the direct superinterfaces
 * @param fields the declared fields
 * @param methods the declared methods and constructors ({@code <init>})
 * @param exports for a module descriptor ({@code module-info}), the packages it exports
 * to every module, as internal names ({@code java/lang}); empty for any other class
 */
public record ClassFileInfo(int access, String name, @Nullable String superName, List<String> interfaces,
		List<Member> fields, List<Member> methods, List<String> exports) {

	private static final int MAGIC = 0xCAFEBABE;

	/**
	 * A declared field or method.
	 *
	 * @param access the member access flags ({@link AccessFlag})
	 * @param name the member name ({@code <init>} for a constructor)
	 * @param descriptor the JVM descriptor ({@code (IJ)Ljava/lang/String;})
	 */
	public record Member(int access, String name, String descriptor) {

		/**
		 * @return whether the member is {@code public}
		 */
		public boolean isPublic() {
			return (this.access & AccessFlag.ACC_PUBLIC) != 0;
		}

		/**
		 * @return whether the member is {@code static}
		 */
		public boolean isStatic() {
			return (this.access & AccessFlag.ACC_STATIC) != 0;
		}

	}

	/**
	 * @return whether this is an interface (annotation types included)
	 */
	public boolean isInterface() {
		return (this.access & AccessFlag.ACC_INTERFACE) != 0;
	}

	/**
	 * @return whether the class is {@code public}
	 */
	public boolean isPublic() {
		return (this.access & AccessFlag.ACC_PUBLIC) != 0;
	}

	/**
	 * @return whether the class is {@code final}
	 */
	public boolean isFinal() {
		return (this.access & AccessFlag.ACC_FINAL) != 0;
	}

	/**
	 * Parses a class file (or a {@code ct.sym} signature file, which has the same
	 * format). Attributes are skipped except a module descriptor's {@code Module}
	 * attribute, whose unqualified exports are read.
	 * @param classFile the class file bytes
	 * @return the declared shape
	 * @throws IllegalArgumentException when the bytes are not a class file
	 */
	public static ClassFileInfo parse(byte[] classFile) {
		try {
			return new Parser(classFile).parse();
		}
		catch (IndexOutOfBoundsException ex) {
			throw new IllegalArgumentException("truncated class file", ex);
		}
	}

	private static final class Parser {

		private final byte[] b;

		private int p;

		private @Nullable Object[] pool = new Object[0];

		private int[] tags = new int[0];

		Parser(byte[] bytes) {
			this.b = bytes;
		}

		ClassFileInfo parse() {
			if (u4() != MAGIC) {
				throw new IllegalArgumentException("not a class file");
			}
			this.p += 4; // minor, major
			readPool();
			int access = u2();
			String name = className(u2());
			int superIndex = u2();
			String superName = superIndex == 0 ? null : className(superIndex);
			int interfaceCount = u2();
			List<String> interfaces = new ArrayList<>(interfaceCount);
			for (int i = 0; i < interfaceCount; i++) {
				interfaces.add(className(u2()));
			}
			List<Member> fields = members();
			List<Member> methods = members();
			List<String> exports = new ArrayList<>();
			int attributeCount = u2();
			for (int i = 0; i < attributeCount; i++) {
				String attribute = utf8(u2());
				int length = u4();
				int end = this.p + length;
				if ((access & AccessFlag.ACC_MODULE) != 0 && "Module".equals(attribute)) {
					readExports(exports);
				}
				this.p = end;
			}
			return new ClassFileInfo(access, name, superName, List.copyOf(interfaces), fields, methods,
					List.copyOf(exports));
		}

		private void readPool() {
			int count = u2();
			this.pool = new Object[count];
			this.tags = new int[count];
			for (int i = 1; i < count; i++) {
				int tag = u1();
				this.tags[i] = tag;
				switch (tag) {
					case 1 -> {
						int length = u2();
						this.pool[i] = modifiedUtf8(this.p, length);
						this.p += length;
					}
					// Class, String, MethodType, Module, Package: one index
					case 7, 8, 16, 19, 20 -> this.pool[i] = u2();
					case 15 -> this.p += 3; // MethodHandle
					case 3, 4, 9, 10, 11, 12, 17, 18 -> this.p += 4;
					case 5, 6 -> { // long / double take two slots
						this.p += 8;
						i++;
					}
					default -> throw new IllegalArgumentException("unknown constant pool tag " + tag);
				}
			}
		}

		private List<Member> members() {
			int count = u2();
			List<Member> members = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				int access = u2();
				String name = utf8(u2());
				String descriptor = utf8(u2());
				int attributeCount = u2();
				for (int a = 0; a < attributeCount; a++) {
					this.p += 2;
					int length = u4();
					this.p += length;
				}
				members.add(new Member(access, name, descriptor));
			}
			return List.copyOf(members);
		}

		// Module attribute: name, flags, version, requires[], exports[], ... -- only the
		// exports without a "to" list are read.
		private void readExports(List<String> exports) {
			this.p += 6;
			int requiresCount = u2();
			this.p += requiresCount * 6;
			int exportsCount = u2();
			for (int i = 0; i < exportsCount; i++) {
				int packageIndex = u2();
				this.p += 2; // flags
				int toCount = u2();
				this.p += toCount * 2;
				if (toCount == 0 && this.tags[packageIndex] == 20) {
					exports.add(utf8((Integer) poolEntry(packageIndex)));
				}
			}
		}

		private String className(int index) {
			if (this.tags[index] != 7) {
				throw new IllegalArgumentException("constant " + index + " is not a class");
			}
			return utf8((Integer) poolEntry(index));
		}

		private String utf8(int index) {
			if (this.tags[index] != 1) {
				throw new IllegalArgumentException("constant " + index + " is not a Utf8");
			}
			return (String) poolEntry(index);
		}

		private Object poolEntry(int index) {
			Object entry = this.pool[index];
			if (entry == null) {
				throw new IllegalArgumentException("constant " + index + " is empty");
			}
			return entry;
		}

		// The class file's "modified UTF-8": 1-, 2- and 3-byte forms only, a
		// supplementary character already split into two 3-byte surrogates.
		private String modifiedUtf8(int offset, int length) {
			StringBuilder sb = new StringBuilder(length);
			int i = offset;
			int end = offset + length;
			while (i < end) {
				int c = this.b[i] & 0xFF;
				if (c < 0x80) {
					sb.append((char) c);
					i++;
				}
				else if ((c & 0xE0) == 0xC0) {
					sb.append((char) (((c & 0x1F) << 6) | (this.b[i + 1] & 0x3F)));
					i += 2;
				}
				else {
					sb.append((char) (((c & 0x0F) << 12) | ((this.b[i + 1] & 0x3F) << 6) | (this.b[i + 2] & 0x3F)));
					i += 3;
				}
			}
			return sb.toString();
		}

		private int u1() {
			return this.b[this.p++] & 0xFF;
		}

		private int u2() {
			int v = ((this.b[this.p] & 0xFF) << 8) | (this.b[this.p + 1] & 0xFF);
			this.p += 2;
			return v;
		}

		private int u4() {
			int v = ((this.b[this.p] & 0xFF) << 24) | ((this.b[this.p + 1] & 0xFF) << 16)
					| ((this.b[this.p + 2] & 0xFF) << 8) | (this.b[this.p + 3] & 0xFF);
			this.p += 4;
			return v;
		}

	}

}
