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
	 * Every selector the shipped layers and the documented examples send: a class, a
	 * selector, and whether it is a class method. Kept in step with {@code appkit.lisp},
	 * {@code metal.lisp}, {@code scene.lisp}, {@code doc/en/guides/objc-appkit.md}, the
	 * runtime example ({@code examples/macos/objc-runtime.lisp}) and the listener example
	 * ({@code examples/macos/listener.lisp}) by hand; a new selector there is a row here,
	 * so the binary is known to serve it before it ships.
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
			inst("NSTimer", "invalidate"),
			// the menu bar: a status item, a menu whose entries are Lisp closures, and
			// the way out of a program that has no window to close
			cls("NSStatusBar", "systemStatusBar"), inst("NSStatusBar", "statusItemWithLength:"),
			inst("NSStatusItem", "button"), inst("NSStatusItem", "setMenu:"), cls("NSMenu", "alloc"),
			inst("NSMenu", "addItem:"), inst("NSMenu", "addItemWithTitle:action:keyEquivalent:"),
			cls("NSMenuItem", "separatorItem"), inst("NSMenuItem", "setTarget:"), inst("NSApplication", "terminate:"),
			// examples/macos/menubar.lisp: the clock the timer writes into the title
			cls("NSDateFormatter", "alloc"), inst("NSDateFormatter", "setDateFormat:"), cls("NSDate", "date"),
			// examples/macos/objc-runtime.lisp: the window-free half of the package --
			// introspection, key-value coding, a run-time class Foundation calls back
			// into
			inst("NSObject", "description"), inst("NSObject", "class"), inst("NSObject", "superclass"),
			cls("NSObject", "alloc"), inst("NSObject", "methodSignatureForSelector:"), inst("NSObject", "valueForKey:"),
			inst("NSObject", "setValue:forKey:"), inst("NSString", "stringByAppendingString:"),
			inst("NSString", "hasPrefix:"), inst("NSString", "doubleValue"),
			inst("NSMethodSignature", "numberOfArguments"), inst("NSMethodSignature", "methodReturnType"),
			inst("NSMethodSignature", "getArgumentTypeAtIndex:"), cls("NSArray", "arrayWithObject:"),
			inst("NSArray", "count"), inst("NSArray", "objectAtIndex:"), inst("NSArray", "componentsJoinedByString:"),
			inst("NSArray", "containsObject:"), inst("NSArray", "indexOfObject:"), inst("NSArray", "valueForKeyPath:"),
			inst("NSArray", "sortedArrayUsingDescriptors:"), cls("NSMutableArray", "array"),
			inst("NSMutableArray", "addObject:"), cls("NSMutableDictionary", "dictionary"),
			cls("NSSortDescriptor", "sortDescriptorWithKey:ascending:"), cls("NSNotificationCenter", "defaultCenter"),
			inst("NSNotificationCenter", "addObserver:selector:name:object:"),
			inst("NSNotificationCenter", "postNotificationName:object:"),
			inst("NSNotificationCenter", "removeObserver:"), inst("NSNotification", "name"),
			inst("NSNotification", "object"),
			// examples/macos/listener.lisp: a transcript in a scrolling text view,
			// an editable field whose Return key is a Lisp closure
			cls("NSScrollView", "alloc"), inst("NSScrollView", "initWithFrame:"),
			inst("NSScrollView", "setHasVerticalScroller:"), inst("NSScrollView", "setBorderType:"),
			inst("NSScrollView", "setDocumentView:"), inst("NSScrollView", "setAutoresizingMask:"),
			cls("NSTextView", "alloc"), inst("NSTextView", "initWithFrame:"), inst("NSTextView", "setEditable:"),
			inst("NSTextView", "setFont:"), inst("NSTextView", "setTextContainerInset:"),
			inst("NSTextView", "setVerticallyResizable:"), inst("NSTextView", "setHorizontallyResizable:"),
			inst("NSTextView", "setMinSize:"), inst("NSTextView", "setMaxSize:"),
			inst("NSTextView", "setAutoresizingMask:"), inst("NSTextView", "textContainer"),
			inst("NSTextView", "setString:"), inst("NSTextView", "string"),
			inst("NSTextView", "scrollToEndOfDocument:"), inst("NSTextContainer", "setWidthTracksTextView:"),
			cls("NSFont", "userFixedPitchFontOfSize:"), inst("NSTextField", "setPlaceholderString:"),
			inst("NSTextField", "setTarget:"), inst("NSTextField", "setAction:"),
			inst("NSTextField", "setAutoresizingMask:"), inst("NSButton", "setAutoresizingMask:"),
			inst("NSWindow", "makeFirstResponder:"),
			// examples/macos/system-frameworks.lisp: the frameworks BESIDE AppKit, each
			// mapped into the process at run time -- text recognition, natural language,
			// Core Image and speech, reached through the bare objc: verbs
			cls("NSBundle", "bundleWithPath:"), inst("NSBundle", "load"),
			cls("NLLanguageRecognizer", "dominantLanguageForString:"), cls("NSSpellChecker", "sharedSpellChecker"),
			inst("NSSpellChecker", "checkSpellingOfString:startingAt:"),
			inst("NSSpellChecker", "guessesForWordRange:inString:language:inSpellDocumentWithTag:"),
			cls("NSDataDetector", "dataDetectorWithTypes:error:"),
			inst("NSDataDetector", "matchesInString:options:range:"), inst("NSTextCheckingResult", "range"),
			inst("NSTextCheckingResult", "resultType"), inst("NSString", "substringWithRange:"),
			inst("NSString", "lastPathComponent"), cls("NSDateFormatter", "alloc"),
			inst("NSDateFormatter", "setLocale:"), inst("NSDateFormatter", "setTimeZone:"),
			inst("NSDateFormatter", "setDateStyle:"), inst("NSDateFormatter", "stringFromDate:"),
			cls("NSLocale", "alloc"), inst("NSLocale", "initWithLocaleIdentifier:"),
			cls("NSTimeZone", "timeZoneWithName:"), cls("NSDate", "dateWithTimeIntervalSince1970:"),
			cls("CIFilter", "filterWithName:"), inst("CIFilter", "outputImage"),
			// NSAttributedString leaves initWithString: to the private subclass alloc
			// answers, so the class itself declares nothing to resolve here; the send is
			// the @@:@ shape initWithLocaleIdentifier: above already pins
			cls("NSAttributedString", "alloc"), cls("CIColor", "colorWithRed:green:blue:"),
			inst("CIImage", "imageByCroppingToRect:"), inst("CIImage", "extent"), cls("VNImageRequestHandler", "alloc"),
			inst("VNImageRequestHandler", "initWithCIImage:options:"),
			inst("VNImageRequestHandler", "performRequests:error:"), cls("VNRecognizeTextRequest", "alloc"),
			inst("VNRecognizeTextRequest", "results"), inst("VNRecognizedTextObservation", "topCandidates:"),
			inst("VNRecognizedText", "string"), cls("NSDictionary", "dictionary"),
			inst("NSDictionary", "objectForKey:"), cls("NSSpeechSynthesizer", "alloc"),
			inst("NSSpeechSynthesizer", "startSpeakingString:toURL:"), inst("NSSpeechSynthesizer", "isSpeaking"),
			cls("NSURL", "fileURLWithPath:"), inst("NSURL", "URLByAppendingPathComponent:"), inst("NSURL", "path"),
			cls("NSFileManager", "defaultManager"), inst("NSFileManager", "temporaryDirectory"),
			inst("NSFileManager", "attributesOfItemAtPath:error:"),
			// the binding's own bytes and errors: objc:data / objc:bytes and the
			// :error out slot send these, so a program that never spells them still
			// needs them served
			cls("NSMutableData", "dataWithBytes:length:"), inst("NSData", "length"), inst("NSData", "bytes"),
			inst("NSMutableData", "mutableBytes"), inst("NSError", "localizedDescription"), inst("NSError", "domain"),
			inst("NSError", "code"), cls("NSJSONSerialization", "JSONObjectWithData:options:error:"),
			// The shipped metal package (eval/metal.lisp) + metal-triangle.lisp +
			// metal-cube.lisp: a
			// Metal surface on the window's content view. Metal is an Objective-C API,
			// so objc:send reaches all of it; the objects are PROTOCOL-typed
			// (id<MTLDevice> and friends), which is where the proto rows come in.
			cls("CAMetalLayer", "layer"), inst("CAMetalLayer", "preferredDevice"), inst("CAMetalLayer", "setDevice:"),
			inst("CAMetalLayer", "setPixelFormat:"), inst("CAMetalLayer", "setFramebufferOnly:"),
			inst("CAMetalLayer", "setFrame:"), inst("CAMetalLayer", "setDrawableSize:"),
			inst("CAMetalLayer", "nextDrawable"), inst("NSView", "frame"), inst("NSView", "setLayer:"),
			inst("NSView", "setWantsLayer:"), proto("MTLDevice", "name"), proto("MTLDevice", "newCommandQueue"),
			proto("MTLDevice", "newLibraryWithSource:options:error:"),
			proto("MTLDevice", "newRenderPipelineStateWithDescriptor:error:"),
			proto("MTLDevice", "newBufferWithBytes:length:options:"), proto("MTLLibrary", "newFunctionWithName:"),
			cls("MTLRenderPipelineDescriptor", "alloc"), inst("MTLRenderPipelineDescriptor", "setVertexFunction:"),
			inst("MTLRenderPipelineDescriptor", "setFragmentFunction:"),
			inst("MTLRenderPipelineDescriptor", "colorAttachments"),
			inst("MTLRenderPipelineColorAttachmentDescriptorArray", "objectAtIndexedSubscript:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setPixelFormat:"),
			cls("MTLRenderPassDescriptor", "renderPassDescriptor"), inst("MTLRenderPassDescriptor", "colorAttachments"),
			inst("MTLRenderPassColorAttachmentDescriptorArray", "objectAtIndexedSubscript:"),
			inst("MTLRenderPassColorAttachmentDescriptor", "setTexture:"),
			inst("MTLRenderPassColorAttachmentDescriptor", "setLoadAction:"),
			inst("MTLRenderPassColorAttachmentDescriptor", "setStoreAction:"),
			inst("MTLRenderPassColorAttachmentDescriptor", "setClearColor:"), proto("MTLCommandQueue", "commandBuffer"),
			proto("MTLCommandBuffer", "renderCommandEncoderWithDescriptor:"),
			proto("MTLCommandBuffer", "presentDrawable:"), proto("MTLCommandBuffer", "commit"),
			proto("MTLRenderCommandEncoder", "setRenderPipelineState:"),
			proto("MTLRenderCommandEncoder", "setCullMode:"),
			proto("MTLRenderCommandEncoder", "setFrontFacingWinding:"),
			proto("MTLRenderCommandEncoder", "setVertexBuffer:offset:atIndex:"),
			proto("MTLRenderCommandEncoder", "setVertexBytes:length:atIndex:"),
			proto("MTLRenderCommandEncoder", "drawPrimitives:vertexStart:vertexCount:"),
			proto("MTLRenderCommandEncoder", "endEncoding"), proto("CAMetalDrawable", "texture"),
			// The rest of the shipped metal surface, which was an example until todo-565
			// and is now the layer every metal: program stands on: the depth attachment
			// (metal:attach :depth t, metal:depth-state, metal:pipeline's declaration
			// and metal:frame's pass), additive blending (metal:pipeline :blend t), the
			// rewritable buffer pair (metal:shared-buffer / metal:upload) and the
			// fragment-stage uniform.
			cls("MTLTextureDescriptor", "texture2DDescriptorWithPixelFormat:width:height:mipmapped:"),
			inst("MTLTextureDescriptor", "setStorageMode:"), inst("MTLTextureDescriptor", "setUsage:"),
			proto("MTLDevice", "newTextureWithDescriptor:"), proto("MTLDevice", "newDepthStencilStateWithDescriptor:"),
			proto("MTLDevice", "newBufferWithLength:options:"), cls("MTLDepthStencilDescriptor", "alloc"),
			inst("MTLDepthStencilDescriptor", "setDepthCompareFunction:"),
			inst("MTLDepthStencilDescriptor", "setDepthWriteEnabled:"),
			inst("MTLRenderPipelineDescriptor", "setDepthAttachmentPixelFormat:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setBlendingEnabled:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setRgbBlendOperation:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setAlphaBlendOperation:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setSourceRGBBlendFactor:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setSourceAlphaBlendFactor:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setDestinationRGBBlendFactor:"),
			inst("MTLRenderPipelineColorAttachmentDescriptor", "setDestinationAlphaBlendFactor:"),
			inst("MTLRenderPassDescriptor", "depthAttachment"),
			inst("MTLRenderPassDepthAttachmentDescriptor", "setTexture:"),
			inst("MTLRenderPassDepthAttachmentDescriptor", "setLoadAction:"),
			inst("MTLRenderPassDepthAttachmentDescriptor", "setStoreAction:"),
			inst("MTLRenderPassDepthAttachmentDescriptor", "setClearDepth:"), proto("MTLBuffer", "contents"),
			inst("NSData", "getBytes:length:"), proto("MTLRenderCommandEncoder", "setFragmentBytes:length:atIndex:"),
			proto("MTLRenderCommandEncoder", "setDepthStencilState:"),
			// metal:offscreen / metal:pixels: a frame with no window at all --
			// drawn into a shared-storage texture, waited for rather than
			// presented, and read back into an objc:data block. The whole reason
			// the renderer above can be tested (todo-568).
			proto("MTLCommandBuffer", "waitUntilCompleted"),
			proto("MTLTexture", "getBytes:bytesPerRow:fromRegion:mipmapLevel:"),
			// The shipped scene package (eval/scene.lisp): the window's content view is
			// an NSView subclass whose mouse and scroll selectors are Lisp closures, and
			// a resize arrives as an NSViewFrameDidChangeNotification.
			inst("NSWindow", "setContentView:"), cls("NSView", "alloc"), inst("NSView", "initWithFrame:"),
			inst("NSView", "setPostsFrameChangedNotifications:"), inst("NSEvent", "locationInWindow"),
			inst("NSEvent", "modifierFlags"), inst("NSEvent", "scrollingDeltaY"),
			inst("NSEvent", "hasPreciseScrollingDeltas"));

	/**
	 * The frameworks {@code examples/macos/system-frameworks.lisp} maps in with an
	 * {@code NSBundle} message. None of them is linked into this process either, and
	 * their classes do not exist until one is: the example's first section IS this step,
	 * so the test takes it before it resolves anything below AppKit.
	 */
	private static final List<String> FRAMEWORKS = List.of("Vision", "NaturalLanguage", "CoreImage", "Metal",
			"QuartzCore");

	private static String[] cls(String name, String selector) {
		return new String[] { name, selector, "class" };
	}

	private static String[] inst(String name, String selector) {
		return new String[] { name, selector, "instance" };
	}

	/**
	 * A selector declared by a PROTOCOL, not a class: every Metal object a program holds
	 * is an {@code id<MTLDevice>} / {@code id<MTLCommandBuffer>} whose concrete class is
	 * private and machine-specific, and the protocol is where its encoding is written
	 * down -- which is also where {@link ObjcRuntime#send} finds it at run time when the
	 * concrete class declares nothing.
	 */
	private static String[] proto(String name, String selector) {
		return new String[] { name, selector, "protocol" };
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void everySelectorTheWidgetLayerSendsHasARegisteredShape() {
		assumeTrue(ObjcRuntime.available(), ObjcRuntime.description());
		ObjcRuntime runtime = ObjcRuntime.get();
		for (String framework : FRAMEWORKS) {
			MemorySegment bundle = (MemorySegment) runtime
				.send(runtime.objcClass("NSBundle"), "bundleWithPath:",
						"/System/Library/Frameworks/" + framework + ".framework")
				.value();
			assumeTrue(bundle != null && Boolean.TRUE.equals(runtime.send(bundle, "load").value()),
					framework + ".framework did not load on this machine");
		}
		Set<FunctionDescriptor> shapes = new LinkedHashSet<>();
		List<String> unresolved = new ArrayList<>();
		for (String[] row : SENT) {
			String raw;
			if ("protocol".equals(row[2])) {
				raw = runtime.protocolEncoding(row[0], row[1]);
			}
			else {
				MemorySegment cls = runtime.objcClass(row[0]);
				MemorySegment owner = "class".equals(row[2]) ? runtime.classOf(cls) : cls;
				raw = runtime.rawEncoding(owner, row[1]);
				MemorySegment internal = "instance".equals(row[2]) ? runtime.classOrNull(row[0] + "Internal") : null;
				if (raw == null && internal != null) {
					// Metal's descriptor classes are abstract in public: alloc answers a
					// private "...Internal" subclass and THAT is where the properties are
					// declared, which is exactly what the runtime resolves against at
					// send
					// time (the receiver's own class). Naming the subclass here keeps the
					// row exact; if Apple ever renames it the row stops resolving and
					// this
					// test says so, which is the failure we want.
					raw = runtime.rawEncoding(internal, row[1]);
				}
			}
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
