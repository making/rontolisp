package am.ik.objc;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the native-image foreign registration of {@code am.ik.objc} against the binding
 * itself, the way {@link NativeImageForeignConfigTest} pins {@code am.ik.gpu}: a shape
 * the binary did not register is one it refuses, and for this package a refusal is a
 * signal in the user's face rather than a decline, so the table has to be complete for
 * everything the shipped widget layer sends.
 *
 * <p>
 * Three layers. The runtime's own downcalls and the callback stubs are pinned on EVERY
 * machine, against a lookup that finds every name (the handles are made, never invoked).
 * The {@code objc_msgSend} shapes are derived from the runtime's type encodings, so the
 * selectors {@code appkit.lisp} and the documented examples send are resolved on a Mac
 * and checked against the file there.
 */
class ObjcNativeImageForeignConfigTest {

	@Test
	void everyRuntimeDowncallShapeIsRegistered() {
		MainThread pump = new MainThread(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING);
		ObjcRuntime runtime = new ObjcRuntime(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING, pump);
		assertThat(NativeImageDowncalls.missing(runtime.signatures(), Set.of()))
			.as("objc runtime downcall shapes with no entry in the native-image metadata -- the binary refuses "
					+ "to bind them, so every objc: verb signals on a Mac that has the runtime")
			.isEmpty();
	}

	@Test
	void everyCallbackShapeIsRegisteredAsAnUpcall() {
		MainThread pump = new MainThread(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING);
		Set<FunctionDescriptor> shapes = new LinkedHashSet<>(pump.upcallSignatures());
		shapes.addAll(ObjcClasses.allCallbackShapes());
		assertThat(shapes).hasSize(7);
		assertThat(NativeImageDowncalls.missingUpcalls(shapes))
			.as("callback shapes with no foreign.upcalls entry -- the binary cannot build the stub, so a class "
					+ "defined at run time has no method body")
			.isEmpty();
	}

	/**
	 * Every selector the widget layer and the documented examples send: a class, a
	 * selector, and whether it is a class method. Kept in step with {@code appkit.lisp},
	 * {@code doc/en/guides/objc-appkit.md} and the {@code cocoa} example library
	 * ({@code examples/macos/cocoa.lisp}) by hand; a new selector there is a row here, so
	 * the binary is known to serve it before it ships.
	 */
	private static final List<String[]> SENT = List.of(cls("NSApplication", "sharedApplication"),
			inst("NSApplication", "setActivationPolicy:"), inst("NSApplication", "finishLaunching"),
			inst("NSApplication", "activateIgnoringOtherApps:"), cls("NSWindow", "alloc"),
			inst("NSWindow", "initWithContentRect:styleMask:backing:defer:"),
			inst("NSWindow", "setReleasedWhenClosed:"), inst("NSWindow", "setTitle:"), inst("NSWindow", "center"),
			inst("NSWindow", "makeKeyAndOrderFront:"), inst("NSWindow", "contentView"), inst("NSWindow", "close"),
			inst("NSWindow", "isVisible"), inst("NSWindow", "frame"), inst("NSWindow", "windowNumber"),
			inst("NSWindow", "setFrame:display:"), cls("NSTextField", "labelWithString:"),
			inst("NSTextField", "setFrame:"), inst("NSTextField", "setStringValue:"),
			inst("NSTextField", "stringValue"), inst("NSView", "addSubview:"), inst("NSButton", "initWithFrame:"),
			inst("NSButton", "setTitle:"), inst("NSButton", "setBezelStyle:"), inst("NSButton", "setTarget:"),
			inst("NSButton", "setAction:"), inst("NSButton", "performClick:"), inst("NSButton", "title"),
			inst("NSObject", "isKindOfClass:"), inst("NSObject", "init"),
			inst("NSObject", "performSelector:withObject:"), inst("NSObject", "respondsToSelector:"),
			inst("NSObject", "performSelectorOnMainThread:withObject:waitUntilDone:"),
			cls("NSString", "stringWithUTF8String:"), inst("NSString", "UTF8String"), inst("NSString", "length"),
			inst("NSString", "rangeOfString:"), inst("NSString", "uppercaseString"),
			cls("NSNumber", "numberWithDouble:"), inst("NSNumber", "doubleValue"),
			cls("NSColor", "colorWithRed:green:blue:alpha:"), inst("NSWindow", "setBackgroundColor:"),
			cls("NSFont", "boldSystemFontOfSize:"), cls("NSFont", "systemFontOfSize:"), cls("NSTextField", "alloc"),
			inst("NSTextField", "initWithFrame:"), inst("NSTextField", "setFont:"), inst("NSTextField", "sizeToFit"),
			inst("NSTextField", "frame"), inst("NSTextField", "setEditable:"), inst("NSTextField", "setSelectable:"),
			inst("NSTextField", "setBezeled:"), inst("NSTextField", "setBordered:"),
			inst("NSTextField", "setDrawsBackground:"), inst("NSTextField", "setAlignment:"),
			inst("NSTextField", "setTextColor:"), cls("NSBox", "alloc"), inst("NSBox", "initWithFrame:"),
			inst("NSBox", "setBoxType:"), inst("NSBox", "setTitlePosition:"), inst("NSBox", "setCornerRadius:"),
			inst("NSBox", "setBorderWidth:"), inst("NSBox", "setFillColor:"), inst("NSBox", "setBorderColor:"),
			inst("NSBox", "setNeedsDisplay:"), cls("NSAppearance", "appearanceNamed:"),
			inst("NSWindow", "setAppearance:"), inst("NSWindow", "setTitlebarAppearsTransparent:"),
			cls("NSTimer", "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:"),
			inst("NSTimer", "invalidate"));

	private static String[] cls(String name, String selector) {
		return new String[] { name, selector, "class" };
	}

	private static String[] inst(String name, String selector) {
		return new String[] { name, selector, "instance" };
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void everySelectorTheWidgetLayerSendsHasARegisteredShape() {
		assumeTrue(ObjcRuntime.available(), ObjcRuntime.description());
		ObjcRuntime runtime = ObjcRuntime.get();
		Set<FunctionDescriptor> shapes = new LinkedHashSet<>();
		List<String> unresolved = new ArrayList<>();
		for (String[] row : SENT) {
			MemorySegment cls = runtime.objcClass(row[0]);
			MemorySegment owner = "class".equals(row[2]) ? runtime.classOf(cls) : cls;
			String raw = runtime.rawEncoding(owner, row[1]);
			if (raw == null) {
				unresolved.add(row[0] + " " + row[1]);
				continue;
			}
			shapes.add(TypeEncoding.parse(raw).descriptor());
		}
		assertThat(unresolved).as("selectors this macOS does not declare").isEmpty();
		assertThat(NativeImageDowncalls.missing(shapes, Set.of()))
			.as("objc_msgSend shapes the widget layer sends with no entry in the native-image metadata -- the "
					+ "binary signals on them, so (appkit:window ...) fails there and works on the JVM")
			.isEmpty();
	}

}
