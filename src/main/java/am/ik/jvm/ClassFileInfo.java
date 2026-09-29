package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
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

	private static final int LATEST_MAJOR = ClassFile.latestMajorVersion();

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
	 * Parses a class file (or a {@code ct.sym} signature file, which has the same format)
	 * through {@code java.lang.classfile}. No attribute is read except a module
	 * descriptor's {@code Module} attribute, whose unqualified exports are. A class file
	 * newer than the running JDK reads too: the parser refuses a version it does not
	 * know, so the version is lowered in a copy -- the declared shape is laid out the
	 * same in every version.
	 * @param classFile the class file bytes
	 * @return the declared shape
	 * @throws IllegalArgumentException when the bytes are not a class file
	 */
	public static ClassFileInfo parse(byte[] classFile) {
		try {
			return of(ClassFile.of().parse(knownVersion(classFile)));
		}
		catch (IndexOutOfBoundsException ex) {
			throw new IllegalArgumentException("truncated class file", ex);
		}
	}

	private static ClassFileInfo of(ClassModel model) {
		int access = model.flags().flagsMask();
		List<String> exports = new ArrayList<>();
		if ((access & AccessFlag.ACC_MODULE) != 0) {
			model.findAttribute(Attributes.module())
				.ifPresent(module -> module.exports()
					.stream()
					.filter(export -> export.exportsTo().isEmpty())
					.forEach(export -> exports.add(export.exportedPackage().name().stringValue())));
		}
		return new ClassFileInfo(access, model.thisClass().asInternalName(),
				model.superclass().map(ClassEntry::asInternalName).orElse(null),
				model.interfaces().stream().map(ClassEntry::asInternalName).toList(),
				model.fields()
					.stream()
					.map(f -> new Member(f.flags().flagsMask(), f.fieldName().stringValue(),
							f.fieldType().stringValue()))
					.toList(),
				model.methods()
					.stream()
					.map(m -> new Member(m.flags().flagsMask(), m.methodName().stringValue(),
							m.methodType().stringValue()))
					.toList(),
				List.copyOf(exports));
	}

	// The major version is bytes 6..7, after the magic and the minor version.
	private static byte[] knownVersion(byte[] classFile) {
		if (classFile.length < 8 || (((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF)) <= LATEST_MAJOR) {
			return classFile;
		}
		byte[] copy = classFile.clone();
		copy[4] = 0;
		copy[5] = 0;
		copy[6] = (byte) (LATEST_MAJOR >>> 8);
		copy[7] = (byte) LATEST_MAJOR;
		return copy;
	}

}
