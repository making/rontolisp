package am.ik.objc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.NativeImageDowncalls;
import am.ik.rontolisp.eval.AppKitLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.ObjcLibrary;
import am.ik.rontolisp.eval.SceneLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
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
 * selectors the shipped layers and the documented examples send are resolved on a Mac and
 * checked against the file there; the method shapes the shipped layers define are read
 * off their {@code define-objc-method} forms.
 *
 * <p>
 * Every layer is checked against the {@code objc:} file ALONE
 * ({@link NativeImageDowncalls#OBJC}): a compiled {@code objc:} program carries that file
 * and nothing else of rontolisp's registration, so an image built from its jar serves
 * exactly what it holds.
 */
class ObjcNativeImageForeignConfigTest {

	@Test
	void everyRuntimeDowncallShapeIsRegistered() {
		MainThread pump = new MainThread(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING);
		ObjcRuntime runtime = new ObjcRuntime(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING, pump);
		assertThat(NativeImageDowncalls.missing(NativeImageDowncalls.OBJC, runtime.signatures(), Set.of()))
			.as("objc runtime downcall shapes with no entry in the native-image metadata -- the binary refuses "
					+ "to bind them, so every objc: verb signals on a Mac that has the runtime")
			.isEmpty();
	}

	@Test
	void everyCallbackShapeTheShippedLayersDefineIsRegisteredAsAnUpcall() {
		// The pump's trampoline, the three methods every root class defined in Lisp gets
		// (+allocWithZone:, -copyWithZone:, -dealloc), and every method appkit.lisp and
		// scene.lisp define: the binary refuses a method of any other shape at
		// definition,
		// which would take the widget layer down on the native binary.
		MainThread pump = new MainThread(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING);
		Set<FunctionDescriptor> shapes = new LinkedHashSet<>(pump.upcallSignatures());
		shapes.add(ObjcMethods.shape("@@:^v"));
		shapes.add(ObjcMethods.shape("v@:"));
		List<String> methods = new ArrayList<>();
		methods.addAll(definedMethodEncodings(AppKitLibrary.forms()));
		methods.addAll(definedMethodEncodings(SceneLibrary.forms()));
		assertThat(methods).as("the shipped layers define methods").isNotEmpty();
		for (String types : methods) {
			shapes.add(ObjcMethods.shape(types));
		}
		assertThat(NativeImageDowncalls.missingUpcalls(NativeImageDowncalls.OBJC, shapes))
			.as("callback shapes with no foreign.upcalls entry -- the binary cannot build the stub, so a class "
					+ "defined at run time has no method body")
			.isEmpty();
	}

	/**
	 * The encodings of the {@code (objc:define-objc-method (name result) ((self class)
	 * (arg type) ...) ...)} forms of a library, as {@code objc-class.lisp} spells them. A
	 * type this table does not know fails the test: a new one needs its encoding here
	 * and, when it makes a new shape, an entry in the file.
	 */
	private static List<String> definedMethodEncodings(List<LispVal> forms) {
		List<String> encodings = new ArrayList<>();
		for (LispVal form : forms) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
					|| !"OBJC:DEFINE-OBJC-METHOD".equals(op.name())) {
				continue;
			}
			List<LispVal> parts = cons.toList();
			List<LispVal> nameSpec = ((LispCons) parts.get(1)).toList();
			StringBuilder types = new StringBuilder(encodingOf(nameSpec.get(1))).append("@:");
			List<LispVal> lambdaList = ((LispCons) parts.get(2)).toList();
			for (LispVal argument : lambdaList.subList(1, lambdaList.size())) {
				types.append(encodingOf(((LispCons) argument).toList().get(1)));
			}
			encodings.add(types.toString());
		}
		return encodings;
	}

	private static String encodingOf(LispVal type) {
		String name = type instanceof LispSymbol symbol ? symbol.name() : type.print();
		return switch (name) {
			case ":VOID" -> "v";
			case ":BOOLEAN" -> "B";
			case "OBJC:OBJC-OBJECT-POINTER" -> "@";
			default -> throw new AssertionError("a method type this test has no encoding for: " + name);
		};
	}

	/**
	 * The encodings of the methods the guide's class-definition examples define (the
	 * manual's section 1.4: {@code areaOfWidth:height:}, {@code size}, the {@code pair}
	 * structure answer, an observer's notification method) and of the three methods every
	 * root class defined in Lisp gets. Each is an upcall (the method's IMP) and a
	 * downcall (the send that reaches it, and its super send).
	 */
	private static final List<String> DOCUMENTED_METHODS = List.of("I24@0:8I16I20", "I16@0:8", "{_Pair=ff}16@0:8",
			"v24@0:8@16", "@24@0:8^v16", "v16@0:8");

	/** The notification center's selectors the observer example sends. */
	private static final List<String> DOCUMENTED_SENDS = List.of("v48@0:8@16:24@32@40", "v40@0:8@16@24@32",
			"v32@0:8@16@24");

	@Test
	void everyShapeTheDocumentedClassExamplesUseIsRegistered() {
		Set<FunctionDescriptor> methods = new LinkedHashSet<>();
		for (String types : DOCUMENTED_METHODS) {
			methods.add(ObjcMethods.shape(types));
		}
		Set<FunctionDescriptor> sends = new LinkedHashSet<>(methods);
		for (String types : DOCUMENTED_SENDS) {
			sends.add(TypeEncoding.parse(types).descriptor());
		}
		assertThat(NativeImageDowncalls.missingUpcalls(NativeImageDowncalls.OBJC, methods))
			.as("method shapes the guide defines with no foreign.upcalls entry -- the binary refuses the definition")
			.isEmpty();
		assertThat(NativeImageDowncalls.missing(NativeImageDowncalls.OBJC, sends, Set.of()))
			.as("send shapes the guide's class examples use with no foreign.downcalls entry")
			.isEmpty();
	}

	/**
	 * The blocks the guide makes (doc/en/guides/objc-appkit.md, "Blocks"), as the
	 * encodings they are invoked by -- the block itself first, a plain pointer: the adder
	 * {@code call-objc-block} calls, the comparator, the enumerator, the work item a
	 * serial queue runs and a completion handler. Each is an upcall (the invoke
	 * function); the copy and dispose helpers every block's descriptor carries are two
	 * more.
	 */
	private static final List<String> DOCUMENTED_BLOCKS = List.of("i^vii", "q^v@@", "v^v@Q^B", "v^v", "v^v@@@");

	/**
	 * The blocks the {@code examples/macos} programs make: {@code audio.lisp}'s
	 * {@code AVAudioSourceNode} render block, {@code OSStatus (BOOL *isSilence, const
	 * AudioTimeStamp *, AVAudioFrameCount, AudioBufferList *)}.
	 */
	private static final List<String> EXAMPLE_BLOCKS = List.of("i^v^B^vI^v");

	/**
	 * The C calls the guide's block examples make: the adder called from Lisp
	 * ({@code call-objc-block}), {@code dispatch_queue_create}, {@code dispatch_async}
	 * and {@code dlsym}, which finds them.
	 */
	private static final List<String> DOCUMENTED_CALLS = List.of("i^vii", "@*^v", "v@@?", "^v^v*");

	@Test
	void everyShapeTheDocumentedBlockExamplesUseIsRegistered() {
		Set<FunctionDescriptor> blocks = new LinkedHashSet<>();
		for (String types : DOCUMENTED_BLOCKS) {
			blocks.add(ObjcBlocks.shape(types));
		}
		for (String types : EXAMPLE_BLOCKS) {
			blocks.add(ObjcBlocks.shape(types));
		}
		blocks.add(FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		blocks.add(FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		assertThat(NativeImageDowncalls.missingUpcalls(NativeImageDowncalls.OBJC, blocks))
			.as("block shapes the guide makes with no foreign.upcalls entry -- the binary refuses the block")
			.isEmpty();
		Set<FunctionDescriptor> calls = new LinkedHashSet<>();
		for (String types : DOCUMENTED_CALLS) {
			calls.add(TypeEncoding.parse(types).descriptor());
		}
		assertThat(NativeImageDowncalls.missing(NativeImageDowncalls.OBJC, calls, Set.of()))
			.as("C call shapes the guide's block examples make with no foreign.downcalls entry")
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
			// Core Image and speech, reached through objc:invoke
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
			// examples/macos/audio.lisp: an AVAudioEngine whose source node's render
			// block is a Lisp closure, rendered offline into a PCM buffer and an
			// AVAudioFile, and live through the speakers
			cls("AVAudioEngine", "alloc"), inst("AVAudioEngine", "init"), cls("AVAudioFormat", "alloc"),
			inst("AVAudioFormat", "initStandardFormatWithSampleRate:channels:"), cls("AVAudioSourceNode", "alloc"),
			inst("AVAudioSourceNode", "initWithFormat:renderBlock:"), inst("AVAudioEngine", "attachNode:"),
			inst("AVAudioEngine", "connect:to:format:"), inst("AVAudioEngine", "mainMixerNode"),
			inst("AVAudioEngine", "enableManualRenderingMode:format:maximumFrameCount:error:"),
			inst("AVAudioEngine", "startAndReturnError:"), inst("AVAudioEngine", "manualRenderingFormat"),
			inst("AVAudioEngine", "renderOffline:toBuffer:error:"), inst("AVAudioEngine", "stop"),
			cls("AVAudioPCMBuffer", "alloc"), inst("AVAudioPCMBuffer", "initWithPCMFormat:frameCapacity:"),
			inst("AVAudioPCMBuffer", "frameLength"), inst("AVAudioPCMBuffer", "floatChannelData"),
			inst("AVAudioPCMBuffer", "format"), inst("AVAudioFormat", "settings"), inst("AVAudioFormat", "sampleRate"),
			cls("AVAudioFile", "alloc"), inst("AVAudioFile", "initForWriting:settings:error:"),
			inst("AVAudioFile", "writeFromBuffer:error:"), inst("AVAudioFile", "initForReading:error:"),
			inst("AVAudioFile", "length"), inst("AVAudioFile", "fileFormat"), inst("NSURL", "lastPathComponent"),
			// invoke-with-error on Cocoa's "...AndReturnError:" spelling (the exception
			// corpus)
			inst("NSURL", "checkResourceIsReachableAndReturnError:"),
			// objc.lisp's own bytes and errors: objc:data / objc:bytes and
			// invoke-with-error's ns-error send these, so a program that never spells
			// them still needs them served
			cls("NSMutableData", "dataWithLength:"), inst("NSData", "length"), inst("NSData", "bytes"),
			inst("NSMutableData", "mutableBytes"), inst("NSError", "localizedDescription"), inst("NSError", "domain"),
			inst("NSError", "code"), cls("NSJSONSerialization", "JSONObjectWithData:options:error:"),
			// objc:objc-exception's name and reason, and cocoa:remove-observer
			inst("NSException", "name"), inst("NSException", "reason"),
			// the guide's bytes example: an NSData the string's encoding answers
			inst("NSString", "dataUsingEncoding:"), inst("NSNotificationCenter", "removeObserver:name:object:"),
			// appkit.lisp: a control's current target, which a second on-click reuses
			inst("NSButton", "target"), inst("NSMenuItem", "target"),
			// The shipped metal package (eval/metal.lisp) + metal-triangle.lisp +
			// metal-cube.lisp: a
			// Metal surface on the window's content view. Metal is an Objective-C API,
			// so objc:invoke reaches all of it; the objects are PROTOCOL-typed
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
			proto("MTLBuffer", "length"), proto("MTLRenderCommandEncoder", "setFragmentBytes:length:atIndex:"),
			proto("MTLRenderCommandEncoder", "setDepthStencilState:"),
			// metal:offscreen / metal:pixels: a frame with no window at all --
			// drawn into a shared-storage texture, waited for rather than
			// presented, and read back into an objc:data block. The whole reason
			// the renderer above can be tested (todo-568).
			proto("MTLCommandBuffer", "waitUntilCompleted"),
			proto("MTLTexture", "getBytes:bytesPerRow:fromRegion:mipmapLevel:"),
			// The shipped scene package (eval/scene.lisp): the window's content view is
			// an NSView subclass whose mouse and scroll methods are defined in Lisp, and
			// a resize arrives as an NSViewFrameDidChangeNotification.
			inst("NSWindow", "setContentView:"), cls("NSView", "alloc"), inst("NSView", "initWithFrame:"),
			inst("NSView", "setPostsFrameChangedNotifications:"), inst("NSEvent", "locationInWindow"),
			inst("NSEvent", "modifierFlags"), inst("NSEvent", "scrollingDeltaY"),
			inst("NSEvent", "hasPreciseScrollingDeltas"),
			// objc.lisp: what its own functions send -- retain / release / autorelease /
			// retainCount, the NSArray a vector argument becomes, the NSString a string
			// becomes -- and what the manual's call-side examples and the objc-base
			// corpus send (doc/en/guides/objc-appkit.md).
			inst("NSObject", "retain"), inst("NSObject", "release"), inst("NSObject", "autorelease"),
			inst("NSObject", "retainCount"), cls("NSObject", "new"), inst("NSObject", "self"),
			cls("NSMutableArray", "arrayWithCapacity:"), inst("NSScrollView", "frame"), inst("NSView", "setHidden:"),
			inst("NSView", "isHidden"), cls("NSArray", "arrayWithArray:"),
			cls("NSNumber", "numberWithUnsignedLongLong:"), inst("NSNumber", "unsignedLongLongValue"),
			cls("NSNumber", "numberWithInt:"), cls("NSValue", "valueWithRect:"), inst("NSValue", "rectValue"),
			cls("NSValue", "valueWithPoint:"), inst("NSValue", "pointValue"), cls("NSValue", "valueWithSize:"),
			inst("NSValue", "sizeValue"), cls("NSValue", "valueWithRange:"), inst("NSValue", "rangeValue"),
			cls("NSInvocation", "invocationWithMethodSignature:"),
			cls("NSObject", "instanceMethodSignatureForSelector:"), inst("NSInvocation", "setSelector:"),
			inst("NSInvocation", "selector"), inst("NSObject", "className"),
			// The manual's value returned by reference (1.3.7), on a Foundation method:
			// an int * the corpus allocates with fli:with-dynamic-foreign-objects.
			cls("NSScanner", "scannerWithString:"), inst("NSScanner", "scanInt:"),
			// The guide's blocks: a comparator and an enumerator Foundation calls, and a
			// completion handler NSURLSession calls when a data task finishes.
			inst("NSArray", "sortedArrayUsingComparator:"), inst("NSArray", "enumerateObjectsUsingBlock:"),
			cls("NSURLSession", "sharedSession"), inst("NSURLSession", "dataTaskWithURL:completionHandler:"),
			inst("NSURLSessionTask", "resume"), cls("NSURL", "URLWithString:"),
			inst("NSHTTPURLResponse", "statusCode"));

	/**
	 * The frameworks {@code examples/macos/system-frameworks.lisp} maps in with an
	 * {@code NSBundle} message (and {@code AVFAudio}, which {@code audio.lisp} loads).
	 * None of them is linked into this process either, and their classes do not exist
	 * until one is: the example's first section IS this step, so the test takes it before
	 * it resolves anything below AppKit.
	 */
	private static final List<String> FRAMEWORKS = List.of("Vision", "NaturalLanguage", "CoreImage", "Metal",
			"QuartzCore", "AVFAudio");

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
	 * down; the private class implements it with the same encoding.
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
			Object bundle = runtime.sendRawOnMain(runtime.classOrNullAddress("NSBundle"),
					runtime.selector("bundleWithPath:").address(), "@24@0:8@16", -1,
					new Object[] { "/System/Library/Frameworks/" + framework + ".framework" }, 0);
			assumeTrue(bundle instanceof Long address && address != 0
					&& Long.valueOf(1)
						.equals(runtime.sendRawOnMain(address, runtime.selector("load").address(), "B16@0:8", -1,
								new Object[0], 0)),
					framework + ".framework did not load on this machine");
		}
		Set<FunctionDescriptor> shapes = new LinkedHashSet<>();
		List<String> unresolved = new ArrayList<>();
		for (String[] row : SENT) {
			String raw;
			if ("protocol".equals(row[2])) {
				raw = protocolEncoding(runtime, row[0], row[1]);
			}
			else {
				long cls = runtime.classOrNullAddress(row[0]);
				assertThat(cls).as("the class %s", row[0]).isNotZero();
				long owner = "class".equals(row[2]) ? runtime.classOfAddress(cls) : cls;
				raw = runtime.methodTypes(owner, runtime.selector(row[1]).address());
				long internal = "instance".equals(row[2]) ? runtime.classOrNullAddress(row[0] + "Internal") : 0;
				if (raw == null && internal != 0) {
					// Metal's descriptor classes are abstract in public: alloc answers a
					// private "...Internal" subclass and THAT is where the properties are
					// declared, which is exactly what the runtime resolves against at
					// send
					// time (the receiver's own class). Naming the subclass here keeps the
					// row exact; if Apple ever renames it the row stops resolving and
					// this
					// test says so, which is the failure we want.
					raw = runtime.methodTypes(internal, runtime.selector(row[1]).address());
				}
			}
			if (raw == null) {
				unresolved.add(row[0] + " " + row[1]);
				continue;
			}
			shapes.add(TypeEncoding.parse(raw).descriptor());
		}
		assertThat(unresolved).as("selectors this macOS does not declare").isEmpty();
		assertThat(NativeImageDowncalls.missing(NativeImageDowncalls.OBJC, shapes, Set.of()))
			.as("objc_msgSend shapes the widget layer sends with no entry in the native-image metadata -- the "
					+ "binary signals on them, so (appkit:window ...) fails there and works on the JVM")
			.isEmpty();
	}

	/**
	 * The encoding a protocol declares for an instance method, required or optional, or
	 * {@code null}: {@code protocol_getMethodDescription}, bound here -- the binding
	 * itself never asks a protocol for an encoding, so nothing in it registers the shape.
	 */
	private static @Nullable String protocolEncoding(ObjcRuntime runtime, String protocol, String selector) {
		SymbolLookup objc = SymbolLookup.libraryLookup(ObjcRuntime.LIB_OBJC, Arena.global());
		Linker linker = Linker.nativeLinker();
		MethodHandle getProtocol = linker.downcallHandle(objc.find("objc_getProtocol").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		// struct objc_method_description { SEL name; const char *types; }, by value.
		MethodHandle describe = linker.downcallHandle(objc.find("protocol_getMethodDescription").orElseThrow(),
				FunctionDescriptor.of(MemoryLayout.structLayout(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
						ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_BOOLEAN));
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment proto = (MemorySegment) getProtocol.invokeExact(arena.allocateFrom(protocol));
			assertThat(proto.address()).as("the protocol %s", protocol).isNotZero();
			for (boolean required : new boolean[] { true, false }) {
				MemorySegment description = (MemorySegment) describe.invokeExact((SegmentAllocator) arena, proto,
						runtime.selector(selector), required, true);
				MemorySegment types = description.get(ValueLayout.ADDRESS, 8);
				if (types.address() != 0) {
					return types.reinterpret(Long.MAX_VALUE).getString(0);
				}
			}
			return null;
		}
		catch (Throwable ex) {
			throw new AssertionError("protocol_getMethodDescription failed for " + selector, ex);
		}
	}

	// --- the variadic grid ------------------------------------------------------------

	/**
	 * The three fixed halves every selector of {@code objc::*variadic-selectors*}
	 * ({@code objc.lisp}) is sent through: an object-returning one-argument class method
	 * ({@code arrayWithObjects:}, {@code stringWithFormat:} and most of the rest), the
	 * same shape returning void ({@code appendFormat:}) and the two-argument void one
	 * ({@code +[NSException raise:format:]}). Their variadic split is the fixed argument
	 * count, receiver and selector included.
	 */
	private static final List<FunctionDescriptor> VARIADIC_BASES = List.of(
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
			FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), FunctionDescriptor
				.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

	/** The carriers {@code objc.lisp} picks a variadic argument's type from. */
	private static final List<MemoryLayout> CARRIERS = List.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_DOUBLE);

	/** Variadic arguments per call, the binding's own nil terminator included. */
	private static final int MAX_VARIADIC = 12;

	/**
	 * Up to this many, a variadic argument may be any carrier; past it, only an object.
	 */
	private static final int MIXED_VARIADIC = 4;

	/**
	 * The grid the file registers, in its generation order: each base crossed with every
	 * variadic tail. The LAST variadic argument is always {@code void*}, because
	 * {@code objc.lisp} appends the nil terminator itself -- which is what keeps this
	 * finite enough to be worth writing down.
	 * @return the shape and the index its variadic list starts at
	 */
	private static List<Object[]> variadicGrid() {
		List<Object[]> grid = new ArrayList<>();
		for (FunctionDescriptor base : VARIADIC_BASES) {
			for (List<MemoryLayout> tail : variadicTails()) {
				grid.add(new Object[] { base.appendArgumentLayouts(tail.toArray(MemoryLayout[]::new)),
						base.argumentLayouts().size() });
			}
		}
		return grid;
	}

	private static List<List<MemoryLayout>> variadicTails() {
		List<List<MemoryLayout>> tails = new ArrayList<>();
		for (int n = 1; n <= MAX_VARIADIC; n++) {
			if (n > MIXED_VARIADIC) {
				tails.add(Collections.nCopies(n, ValueLayout.ADDRESS));
				continue;
			}
			for (List<MemoryLayout> head : carrierTuples(n - 1)) {
				List<MemoryLayout> tail = new ArrayList<>(head);
				tail.add(ValueLayout.ADDRESS);
				tails.add(tail);
			}
		}
		return tails;
	}

	private static List<List<MemoryLayout>> carrierTuples(int length) {
		List<List<MemoryLayout>> all = new ArrayList<>();
		all.add(List.of());
		for (int i = 0; i < length; i++) {
			List<List<MemoryLayout>> next = new ArrayList<>();
			for (List<MemoryLayout> prefix : all) {
				for (MemoryLayout carrier : CARRIERS) {
					List<MemoryLayout> one = new ArrayList<>(prefix);
					one.add(carrier);
					next.add(one);
				}
			}
			all = next;
		}
		return all;
	}

	/**
	 * The file's variadic entries and the rule above, pinned against each other in both
	 * directions -- an entry the rule does not generate is as wrong as a shape the file
	 * does not carry, since the rule is what a regeneration would write.
	 */
	@Test
	void theVariadicGridIsExactlyWhatTheFileRegisters() {
		List<String> expected = new ArrayList<>();
		for (Object[] row : variadicGrid()) {
			expected.add(NativeImageDowncalls.signature((FunctionDescriptor) row[0], false) + " variadic@" + row[1]);
		}
		assertThat(expected).hasSize(144).doesNotHaveDuplicates();
		assertThat(NativeImageDowncalls.registeredVariadic(NativeImageDowncalls.OBJC))
			.as("the variadic objc_msgSend entries in the native-image metadata -- a variadic call is its own "
					+ "stub, so a shape outside this grid signals in the binary naming the entry to add")
			.containsExactlyInAnyOrderElementsOf(expected);
	}

	/**
	 * Every selector in the table, resolved against this macOS: each one exists, and its
	 * DECLARED half is one of the three bases the grid is built on -- the encoding is
	 * what {@code objc.lisp} appends the variadic list to, so a selector whose fixed half
	 * is something else would be sent through a shape nothing registered.
	 */
	@Test
	@EnabledOnOs(OS.MAC)
	void everyVariadicSelectorHasOneOfTheGridsBaseShapes() {
		assumeTrue(ObjcRuntime.available(), ObjcRuntime.description());
		ObjcRuntime runtime = ObjcRuntime.get();
		List<String> unresolved = new ArrayList<>();
		List<String> offGrid = new ArrayList<>();
		for (String[] row : VARIADIC_OWNERS) {
			long cls = runtime.classOrNullAddress(row[0]);
			long owner = "class".equals(row[2]) ? runtime.classOfAddress(cls) : cls;
			String raw = runtime.methodTypes(owner, runtime.selector(row[1]).address());
			if (raw == null) {
				unresolved.add(row[0] + " " + row[1]);
				continue;
			}
			if (!VARIADIC_BASES.contains(TypeEncoding.parse(raw).descriptor())) {
				offGrid.add(row[0] + " " + row[1] + ": " + raw);
			}
		}
		assertThat(unresolved).as("variadic selectors this macOS does not declare").isEmpty();
		assertThat(offGrid).as("variadic selectors whose fixed half is outside the registered grid").isEmpty();
		assertThat(VARIADIC_OWNERS.stream().map(row -> row[1]))
			.as("a selector in the table with no owner here is one this test never resolved")
			.containsExactlyInAnyOrderElementsOf(variadicSelectors());
	}

	/** {@code objc::*variadic-selectors*}, read off {@code objc.lisp}. */
	private static List<String> variadicSelectors() {
		for (LispVal form : ObjcLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && "DEFVAR".equals(op.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& "OBJC::*VARIADIC-SELECTORS*".equals(name.name()) && rest.cdr() instanceof LispCons value
					&& value.car() instanceof LispCons quote && quote.cdr() instanceof LispCons quoted
					&& quoted.car() instanceof LispCons list) {
				return list.toList().stream().map(v -> ((LispString) v).value()).toList();
			}
		}
		throw new AssertionError("objc.lisp defines no objc::*variadic-selectors*");
	}

	/**
	 * The class each variadic selector is resolved against. The table itself is names
	 * only -- a selector is global in Objective-C -- so this is the test's own half.
	 */
	private static final List<String[]> VARIADIC_OWNERS = List.of(cls("NSArray", "arrayWithObjects:"),
			inst("NSArray", "initWithObjects:"), cls("NSSet", "setWithObjects:"),
			cls("NSOrderedSet", "orderedSetWithObjects:"), cls("NSDictionary", "dictionaryWithObjectsAndKeys:"),
			inst("NSDictionary", "initWithObjectsAndKeys:"), cls("NSString", "stringWithFormat:"),
			inst("NSString", "initWithFormat:"), cls("NSString", "localizedStringWithFormat:"),
			inst("NSString", "stringByAppendingFormat:"), inst("NSMutableString", "appendFormat:"),
			cls("NSPredicate", "predicateWithFormat:"), cls("NSException", "raise:format:"));

	/**
	 * Every variadic shape a real send actually binds, against the same file: the grid
	 * above is a rule, and this is the binding walking into it.
	 */
	@Test
	@EnabledOnOs(OS.MAC)
	void aVariadicSendBindsAShapeTheBinaryServes() {
		assumeTrue(ObjcRuntime.available(), ObjcRuntime.description());
		ObjcRuntime runtime = ObjcRuntime.get();
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		for (LispVal form : LispReader.readAllFromString("""
				(objc:invoke "NSArray" "arrayWithObjects:" "a" "b" "c")
				(objc:invoke "NSString" "stringWithFormat:" "%@ %ld %.2f" "x" 42 2.5d0)
				""")) {
			evaluator.eval(form);
		}
		for (ObjcRuntime.Signature signature : runtime.variadicSignatures()) {
			assertThat(NativeImageDowncalls.missingVariadic(NativeImageDowncalls.OBJC, Set.of(signature.descriptor()),
					signature.firstVariadicArg()))
				.as("a variadic shape the binding bound with no entry in the native-image metadata")
				.isEmpty();
		}
	}

}
