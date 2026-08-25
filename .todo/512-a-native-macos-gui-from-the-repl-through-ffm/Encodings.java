import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/** Is the ObjC runtime self-describing enough to derive a FunctionDescriptor? */
public class Encodings {
	static final Linker K = Linker.nativeLinker();
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final Arena A = Arena.global();

	public static void main(String[] a) throws Throwable {
		SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", A);
		SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", A);
		MethodHandle getClass_ = K.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
		MethodHandle selReg = K.downcallHandle(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(P, P));
		MethodHandle instMeth = K.downcallHandle(objc.find("class_getInstanceMethod").orElseThrow(), FunctionDescriptor.of(P, P, P));
		MethodHandle clsMeth = K.downcallHandle(objc.find("class_getClassMethod").orElseThrow(), FunctionDescriptor.of(P, P, P));
		MethodHandle enc = K.downcallHandle(objc.find("method_getTypeEncoding").orElseThrow(), FunctionDescriptor.of(P, P));

		String[][] probes = {
			{"NSApplication", "sharedApplication", "class"},
			{"NSApplication", "setActivationPolicy:", "inst"},
			{"NSWindow", "initWithContentRect:styleMask:backing:defer:", "inst"},
			{"NSWindow", "setTitle:", "inst"},
			{"NSWindow", "windowNumber", "inst"},
			{"NSWindow", "frame", "inst"},
			{"NSView", "setFrame:", "inst"},
			{"NSButton", "setAction:", "inst"},
			{"NSTextField", "labelWithString:", "class"},
			{"NSString", "stringWithUTF8String:", "class"},
			{"NSObject", "isKindOfClass:", "inst"},
			{"NSSlider", "doubleValue", "inst"},
			{"NSColor", "colorWithRed:green:blue:alpha:", "class"},
		};
		for (String[] p : probes) {
			MemorySegment c = (MemorySegment) getClass_.invokeExact(A.allocateFrom(p[0]));
			MemorySegment s = (MemorySegment) selReg.invokeExact(A.allocateFrom(p[1]));
			MemorySegment m = p[2].equals("class") ? (MemorySegment) clsMeth.invokeExact(c, s)
					: (MemorySegment) instMeth.invokeExact(c, s);
			if (m.address() == 0) { System.out.printf("%-14s %-46s NOT FOUND%n", p[0], p[1]); continue; }
			MemorySegment e = (MemorySegment) enc.invokeExact(m);
			System.out.printf("%-14s %-46s %s%n", p[0], p[1], e.reinterpret(Long.MAX_VALUE).getString(0));
		}
	}
}
