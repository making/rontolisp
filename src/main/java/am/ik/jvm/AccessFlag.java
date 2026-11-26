package am.ik.jvm;

/**
 * JVM access flags as defined in the JVM specification.
 */
public interface AccessFlag {

	/** Access flag {@code ACC_PUBLIC} (0x0001) for class, field, method. */
	int ACC_PUBLIC = 0x0001; // class, field, method

	/** Access flag {@code ACC_PRIVATE} (0x0002) for class, field, method. */
	int ACC_PRIVATE = 0x0002; // class, field, method

	/** Access flag {@code ACC_PROTECTED} (0x0004) for class, field, method. */
	int ACC_PROTECTED = 0x0004; // class, field, method

	/** Access flag {@code ACC_STATIC} (0x0008) for field, method. */
	int ACC_STATIC = 0x0008; // field, method

	/** Access flag {@code ACC_FINAL} (0x0010) for class, field, method, parameter. */
	int ACC_FINAL = 0x0010; // class, field, method, parameter

	/** Access flag {@code ACC_SUPER} (0x0020) for class. */
	int ACC_SUPER = 0x0020; // class

	/** Access flag {@code ACC_SYNCHRONIZED} (0x0020) for method. */
	int ACC_SYNCHRONIZED = 0x0020; // method

	/** Access flag {@code ACC_OPEN} (0x0020) for module. */
	int ACC_OPEN = 0x0020; // module

	/** Access flag {@code ACC_TRANSITIVE} (0x0020) for module requires. */
	int ACC_TRANSITIVE = 0x0020; // module requires

	/** Access flag {@code ACC_VOLATILE} (0x0040) for field. */
	int ACC_VOLATILE = 0x0040; // field

	/** Access flag {@code ACC_BRIDGE} (0x0040) for method. */
	int ACC_BRIDGE = 0x0040; // method

	/** Access flag {@code ACC_STATIC_PHASE} (0x0040) for module requires. */
	int ACC_STATIC_PHASE = 0x0040; // module requires

	/** Access flag {@code ACC_VARARGS} (0x0080) for method. */
	int ACC_VARARGS = 0x0080; // method

	/** Access flag {@code ACC_TRANSIENT} (0x0080) for field. */
	int ACC_TRANSIENT = 0x0080; // field

	/** Access flag {@code ACC_NATIVE} (0x0100) for method. */
	int ACC_NATIVE = 0x0100; // method

	/** Access flag {@code ACC_INTERFACE} (0x0200) for class. */
	int ACC_INTERFACE = 0x0200; // class

	/** Access flag {@code ACC_ABSTRACT} (0x0400) for class, method. */
	int ACC_ABSTRACT = 0x0400; // class, method

	/** Access flag {@code ACC_STRICT} (0x0800) for method. */
	int ACC_STRICT = 0x0800; // method

	/**
	 * Access flag {@code ACC_SYNTHETIC} (0x1000) for class, field, method, parameter,
	 * module.
	 */
	int ACC_SYNTHETIC = 0x1000; // class, field, method, parameter, module *

	/** Access flag {@code ACC_ANNOTATION} (0x2000) for class. */
	int ACC_ANNOTATION = 0x2000; // class

	/** Access flag {@code ACC_ENUM} (0x4000) for class, field, inner. */
	int ACC_ENUM = 0x4000; // class(?) field inner

	/** Access flag {@code ACC_MANDATED} (0x8000) for field, method, parameter, module. */
	int ACC_MANDATED = 0x8000; // field, method, parameter, module, module *

	/** Access flag {@code ACC_MODULE} (0x8000) for class. */
	int ACC_MODULE = 0x8000; // class

	/** Access flag {@code ACC_RECORD} (0x10000) for class. */
	int ACC_RECORD = 0x10000; // class

	/** Access flag {@code ACC_DEPRECATED} (0x20000) for class, field, method. */
	int ACC_DEPRECATED = 0x20000; // class, field, method

}
