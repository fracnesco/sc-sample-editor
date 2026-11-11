// =====================================================================
// SampleHintBar - a strip along the bottom that says what the mouse is over
//
// The same idea as the one in the OneShot / LongSample windows
// (ModuleHintBar) and the sample slicer, written out here so the editor
// keeps depending on nothing of theirs: a window full of small controls
// can be read without a manual.
//
//   hints = SampleHintBar("hover over a control and this bar says what it does");
//   hints.watch(window);                     // once, after the window exists
//   window.layout = VLayout(..., hints.view);
//
//   hints.attach(box, "what this box does");
//   hints.attach([label, slider, box], "the three parts of one control");
//   hints.attach(readout, { "now at " ++ thing.value });   // worked out on hover
//
// `attach` answers what it was given, so a control can be wrapped where it
// is built:
//
//   startBox = hints.attach(NumberBox(), "where the trim starts");
//
// A hint is a String, or a Function answering one. Moving onto nothing in
// particular puts the bar back to its default, and `note` changes that
// default - which is how the last thing that happened stays readable when
// the pointer is somewhere uninteresting.
// =====================================================================

SampleHintBar {
	classvar <>defaultHeight = 22;

	var <view, <text, <>defaultString;
	var toggleBox, font, shown = true;
	var attached;						// [[view, hint] ...], for the window to look the pointer up in
	var lastLookup = 0;

	*new { |defaultString, height| ^super.new.init(defaultString, height) }

	init { |str, height|
		defaultString = str ?? { "hover over a control and this bar says what it does" };
		font = Font(Font.defaultSansFace, 10);
		text = StaticText().font_(font).stringColor_(SampleEditor.colDim).string_(defaultString);
		view = View().background_(SampleEditor.colWaveBg).fixedHeight_(height ? defaultHeight);
		view.layout = HLayout(text).margins_([8, 1, 8, 1]);
		^this
	}

	// A child's mouse-over only arrives if the window is willing to hear
	// about it. A move the window itself hears is looked up among the views
	// with a hint: a button (QPushButton ignores a move with no button down,
	// so Qt hands it on to the window) or a disabled control (which hears
	// nothing) still gets its hint that way. Anywhere else - the gaps between
	// controls - puts the bar back to its default, so a stale hint does not sit
	// there describing something the pointer left a while ago.
	watch { |window|
		var v = window.tryPerform(\view) ? window;
		v !? {
			v.acceptsMouseOver = true;
			v.mouseOverAction = { |top, x, y| this.prHintAt(top, x, y); nil };
		};
		^this
	}

	// `hint` is a String, or a Function answering one. Answers `views`.
	attach { |views, hint|
		views.asArray.do { |v|
			v !? {
				v.acceptsMouseOver = true;
				v.mouseOverAction = { this.string_(hint.value); nil };
				v.mouseEnterAction = { this.string_(hint.value); nil };
				attached = attached.add([v, hint]);
			};
		};
		^views
	}

	// the hint of the smallest view with one under a point of the window's
	// top view, or the default
	prHintAt { |top, x, y|
		var p, best, bestArea, origin;
		// a move fires many times a second; a look a frame is plenty
		if ((Main.elapsedTime - lastLookup) < 0.03) { ^this };
		lastLookup = Main.elapsedTime;
		origin = try { top.absoluteBounds.origin } ? Point(0, 0);
		p = Point(x, y) + origin;
		attached = attached.reject { |pair| pair[0].isClosed };
		attached.do { |pair|
			var r = try { pair[0].absoluteBounds };
			if (r.notNil and: { r.containsPoint(p) } and: { try { pair[0].visible } ? false }) {
				if (bestArea.isNil or: { (r.width * r.height) < bestArea }) { best = pair; bestArea = r.width * r.height };
			};
		};
		if (best.notNil) { this.string_(best[1].value) } { this.clear };
	}

	// what the bar falls back to when the pointer is over nothing: the last
	// thing the window said it did
	note { |str|
		str !? { defaultString = str.asString };
		this.string_(str);
	}

	string_ { |str| text !? { text.string = " " ++ (str ? defaultString) } }

	string { ^text !? (_.string) }

	clear { this.string_(nil) }

	// kept here: a view in a window not yet shown answers false, and a
	// checkbox made then would start unticked
	visible { ^shown }

	visible_ { |bool|
		shown = bool;
		view.visible = bool;
		toggleBox !? { |b| b.value = bool };
	}

	// a checkbox that folds the bar away; made on the first ask, the same
	// one after that
	toggle {
		toggleBox ?? {
			toggleBox = CheckBox().string_("hints").value_(shown)
				.action_({ |v| shown = v.value; view.visible = v.value });
			this.attach(toggleBox, "this bar - untick to fold it away");
		};
		^toggleBox
	}
}
