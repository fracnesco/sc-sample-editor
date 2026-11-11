// =====================================================================
// SampleEditor - a window on one sound file: trim it, gain it, take the DC
// out, fade the edges, read its numbers, slice it, save it
//
//   SampleEditor("~/x.wav");                          // plays to the hardware outputs
//   SampleEditor("~/x.wav", SampleEditorDirtHost(~dirt));   // through SuperDirt's orbits
//   SampleEditor.defaultHost = SampleEditorDirtHost(~dirt); // from now on, for every editor
//
// What the editor knows of the world is its host (SampleEditorHost): where
// the preview plays, what to tell a sample library after a file is written,
// and where a file's bpm, key and tags are kept. The plain host plays to
// the server's hardware outputs and only writes files; SampleEditorDirtHost
// plays through a SuperDirt's orbits, knows its library (a file it holds
// opens as bd:3, a save is read back into the same Buffer, a bank written
// is loaded); SuperDirtGUI gives one that also keeps tags.
//
// A sound file dropped from the Finder on the window opens in the editor -
// in this window's place when nothing here was changed yet - and so does
// open...: from there it is trimmed, sliced and saved like any other. to
// bank... writes the trim (S to E, with the gain, the DC and the fades as
// set) as a new file into a bank's folder, Dirt-Samples by default, and
// hands it to the host: select a part, to bank..., return, the next part.
//
// The preview is the host's Buffer for the file (or one read for it)
// through a small synth, into the output picked in the window - an orbit's
// dry bus with a SuperDirt host, so its effects and fader apply. The trim,
// the gain, the fades and the DC are all applied live in the synth; the
// samples are only rewritten on save. The numbers on the right are
// SampleAnalysis on the file as it is and on what the save would write.
//
// Under the header, what the analysis says (SampleFeatures, and the host's
// tag store when it has one): the bpm, sure or not, with the beat grid it
// draws over the wave - halve it, double it, slide it with alt + drag, or put
// its first beat on the first attack; the key; the tags. With a store,
// whatever is changed there is kept for the file.
//
// The slicer (SampleCutter) cuts the trim on the grid at a division, or on the
// attacks at a sensitivity, and shows the cuts on the wave as they move;
// shift + click adds or takes away a cut. export slices... asks where (the
// Finder's folder dialog, at Dirt-Samples the first time) and under what
// name, writes every slice to a new folder of that name, each file named
// after it with its index, and hands it to the host; the file stays as it is.
// keep, under the slicer, exports only the slices loud and long enough.
//
// Keys, when the window has focus: space plays and stops, 0 shows the
// whole file, - and = zoom, f zooms to the trim, left and right slide,
// g shows and hides the grid, cmd-s saves (ctrl-s on Windows and Linux).
// =====================================================================

SampleEditor {
	classvar <>windowBounds;
	classvar <all;						// the open editors: opening a sample twice fronts the first
	classvar <>folderChooser;			// { |start, ok| } in place of the Finder's folder dialog: checks set it
	classvar <>fileChooser;				// { |ok| } in place of the Finder's open dialog: checks set it
	classvar <>bankParent, <>lastBank;	// where to bank... went last, and under what name
	classvar defaultHost;				// the host an editor gets when given none
	classvar <colBg, <colPanel, <colText, <colDim, <colAccent, <colOn, <colWarn;
	classvar <colWave, <colWaveDim, <colWaveBg, <colHandle, <colPlay;

	var <host, <edit, <window, <waveView, <hints, <outputIndex;
	var titleText, infoText, outputMenu, playBtn, stopBtn;
	var startBox, endBox, fadeInBox, fadeOutBox;
	var gainKnob, gainBox, normBtn, ceilBox, dcBox, dcInfo;
	var resetBtn, revertBtn, saveBtn, saveAsBtn, openBtn, bankBtn;
	var statTexts;						// key -> [original, result] StaticTexts
	var previewSynth, posFunc, tempBuffer, defReady = false, statsPending = false;
	var font, smallFont, boldFont, monoFont, gainSpec;
	var <slicer, analysisRoutine, bpmBox, offsetBox, bpmFlag, keyField, keyFlag, tagsField, gridCheck, analysisText;
	var sliceCheck, sliceModeMenu, sliceDivMenu, sensKnob, sensBox, minBox, sliceCount, exportBtn, sliceViews, <exportDialog;
	var keepLevelKnob, keepLevelBox, keepMinBox, keepMaxBox, keepCount, keepSpec;
	var persistPending = false, <bankDialog;

	*initClass {
		all = List.new;
		colBg = Color.grey(0.13);
		colPanel = Color.grey(0.19);
		colText = Color.grey(0.85);
		colDim = Color.grey(0.55);
		colAccent = Color(0.95, 0.65, 0.25);
		colOn = Color(0.45, 0.8, 0.45);
		colWarn = Color(0.9, 0.4, 0.35);
		colWave = Color(0.42, 0.76, 0.95);
		colWaveDim = Color(0.30, 0.33, 0.40);
		colWaveBg = Color(0.09, 0.10, 0.12);
		colHandle = Color(0.95, 0.95, 0.97);
		colPlay = Color(0.42, 0.94, 0.55);
	}

	// the host an editor gets when given none: the one set, else a plain
	// SampleEditorHost on Server.default. SuperDirtGUI sets its own here.
	*defaultHost { ^defaultHost ?? { SampleEditorHost(Server.default) } }
	*defaultHost_ { |aHost| defaultHost = aHost }

	// a knob dragged up and down; a double click puts it back to default (a
	// unit value, or a function answering one) through its action
	*makeKnob { |size = 36, centered = false, default|
		var knob = Knob().fixedWidth_(size).fixedHeight_(size)
			.mode_(\vert).step_(0.005).centered_(centered)
			.color_([Color.grey(0.32), colAccent, Color.grey(0.16), colText]);
		// true keeps the double click from the knob; nil passes any other click on
		knob.mouseDownAction = { |v, x, y, mods, button, clickCount|
			var unit;
			if (clickCount == 2 and: { (button ? 0) == 0 }) {
				unit = default.value;
				if (unit.isNumber) { v.valueAction = unit.clip(0, 1); true }
			}
		};
		^knob
	}

	// the modifier a shortcut takes here: cmd on macOS, ctrl elsewhere, which
	// is how QtCollider reports the key (QKeyModifiers.toCocoa)
	*isShortcut { |mod|
		^mod.notNil and: { Platform.case(\osx, { mod.isCmd }, { mod.isCtrl }) }
	}

	// what the hints call the save shortcut
	*saveKeyName { ^Platform.case(\osx, { "cmd-s" }, { "ctrl-s" }) }

	// An editor on a file, open; the one already open on it is fronted.
	// host: nil for defaultHost. output: the host's output the preview plays
	// into (an orbit with a SuperDirt host). bounds: where the window goes.
	*new { |path, host, output = 0, bounds|
		var keep = windowBounds;
		path = path.asString.standardizePath;
		all.do { |e| if (e.isOpen and: { SampleEdit.samePath(e.edit.path, path) }) { e.front; ^e } };
		host = host ?? { this.defaultHost };
		bounds !? { windowBounds = bounds };
		^protect {
			super.new.init(SampleEdit(path, host), host, output)
		} { if (bounds.notNil) { windowBounds = keep } }
	}

	// the same, for a file a host's library holds as name:index
	*sample { |name, index = 0, host, output = 0|
		var path;
		host = host ?? { this.defaultHost };
		path = host.pathOf(name, index);
		if (path.isNil) { Error("SampleEditor: no sample at %:%".format(name, index)).throw };
		^this.new(path, host, output)
	}

	// the files and folders a drop brings: the Finder hands over one path
	// as a String, several as an Array of them
	*droppedPaths { |data|
		// (not [data].flat: that would take a String apart into its letters)
		data = if (data.isString) { [data] } { data.asArray };
		^data.select { |d| d.isString }.collect { |p|
			p = p.stripWhiteSpace;
			if (p.beginsWith("file://")) { p = p.drop(7) };
			p.withoutTrailingSlash
		}.select { |p| p.notEmpty and: { File.exists(p) } }
	}

	*isSoundFile { |path|
		^File.exists(path) and: { File.type(path) != \directory } and: {
			#["wav", "aif", "aiff", "aifc", "flac", "ogg", "mp3", "caf", "w64", "rf64", "wave"]
				.includesEqual((path.splitext[1] ? "").toLower)
		}
	}

	// view takes drops: test answers whether the paths will do, action gets them
	*acceptDrops { |view, action, test|
		test = test ? { |paths| paths.any { |p| this.isSoundFile(p) } };
		view.canReceiveDragHandler = { test.value(this.droppedPaths(View.currentDrag)) == true };
		view.receiveDragHandler = { action.value(this.droppedPaths(View.currentDrag)) };
	}

	init { |anEdit, aHost, output|
		edit = anEdit;
		host = aHost;
		outputIndex = (output ? 0).clip(0, (host.outputNames.size - 1).max(0));
		font = Font(Font.defaultSansFace, 11);
		smallFont = Font(Font.defaultSansFace, 9);
		boldFont = Font(Font.defaultSansFace, 12, true);
		monoFont = Font(Font.defaultMonoFace, 10);
		gainSpec = ControlSpec(-24, 24, \lin, 0.1, 0);
		this.prBuild;
		edit.addDependant(this);
		this.store !? (_.addDependant(this));
		all.add(this);
		CmdPeriod.add(this);
		this.prListenForPosition;
		this.prEnsureDef;
		this.prGridFromStore;
		this.refresh;
		window.front;
		this.analyse;
	}

	// where the file's bpm, key and tags are kept, when the host keeps them
	store { ^host.store }

	info { ^this.store !? { |st| st.infoOf(edit.path) } }

	server { ^host.server }

	isOpen { ^window.notNil and: { window.isClosed.not } }

	front { if (this.isOpen) { window.front } }

	close { if (this.isOpen) { window.close } }

	isPlaying { ^previewSynth.notNil }

	// --------------------------------------------------------------- build

	prBuild {
		var bounds = windowBounds ?? { Rect(120, 120, 980, 700) };
		window = Window("sample - " ++ edit.label, bounds);
		if (GUI.id == \qt) { window.view.palette = QPalette.dark };
		window.view.background = SampleEditor.colBg;

		hints = SampleHintBar("trim with the S and E markers, set a gain, take the DC out, then save"
			" - the library plays the new file at once");
		waveView = SampleWaveView(nil);
		waveView.hints = hints;
		waveView.edit = edit;
		waveView.action = { this.prRefreshBoxes; this.prScheduleStats };

		waveView.gridAction = { |wv, done| this.prGridMoved(done) };
		waveView.sliceAction = { this.prRefreshSlicer };

		window.layout = VLayout(
			this.prHeaderRow,
			this.prAnalysisRow,
			[waveView.view, stretch: 1],
			this.prTrimRow,
			this.prGainRow,
			this.prSlicerRow,
			this.prStatsPanel,
			hints.view
		).margins_(8).spacing_(6);

		hints.watch(window);
		this.prAttachHints;
		window.view.keyDownAction = { |v, char, mod, uni, keycode, key| this.prKeyDown(char, mod, key) };
		[window.view, waveView.view, titleText, infoText].do { |v|
			this.class.acceptDrops(v, { |paths| this.openFiles(paths) })
		};
		window.onClose = { this.prClosed };
	}

	prHeaderRow {
		titleText = StaticText().string_(edit.label).font_(boldFont).stringColor_(SampleEditor.colAccent);
		infoText = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont);
		playBtn = this.prButton("play", 46, { this.play },
			"play from S to E as it would be saved - gain, fades, DC - through the output in the menu; nothing is written (space)");
		stopBtn = this.prButton("stop", 46, { this.stop }, "stop the preview (space)");
		outputMenu = PopUpMenu().fixedWidth_(84)
			.items_(host.outputNames)
			.value_(outputIndex)
			.action_({ |m| outputIndex = m.value });
		resetBtn = this.prButton("reset", 50, { edit.reset },
			"forget every setting made here: S and E back to the ends, no gain, no fades, DC left in - the file itself is not touched");
		revertBtn = this.prButton("revert", 54, { this.prRevert }, {
			if (edit.hasBackup) {
				"put the original back over the file: the first save kept it beside it as % - with SuperDirt, Tidal plays the original again".format(edit.backupPath.basename)
			} {
				"put the original back after a save - nothing to put back yet: the file has not been saved over"
			}
		});
		saveBtn = this.prButton("save", 50, { this.prSave },
			"write S to E, with gain, fades and DC, over this file (%) - the first save keeps the original beside it as .bak, for revert".format(this.class.saveKeyName));
		saveAsBtn = this.prButton("save as...", 74, { this.prSaveAs },
			"write the result to a new file and leave this one as it is - saved in this sample's folder, the new file joins its bank");
		openBtn = this.prButton("open...", 56, { this.openDialog },
			"open another sound file (or drop one from the Finder on this window) - it takes this window if nothing here was changed");
		bankBtn = this.prButton("to bank...", 70, { this.addToBankDialog },
			"add S to E as a new numbered file to a SuperDirt bank (a new name makes a new bank) and load it - this file is not touched");
		^HLayout(
			titleText, infoText, nil,
			playBtn, stopBtn, hints.attach(this.prLabel(host.outputLabel, 30), host.outputHint), outputMenu,
			nil,
			openBtn, resetBtn, revertBtn, saveBtn, saveAsBtn, bankBtn
		).spacing_(4)
	}

	prTrimRow {
		startBox = this.prNumBox(1, { |v| edit.start_(v / 1000 / edit.duration.max(1e-9)) });
		endBox = this.prNumBox(1, { |v| edit.end_(v / 1000 / edit.duration.max(1e-9)) });
		fadeInBox = this.prNumBox(1, { |v| edit.fadeIn_(v / 1000) });
		fadeOutBox = this.prNumBox(1, { |v| edit.fadeOut_(v / 1000) });
		^HLayout(
			this.prLabel("start ms", 50), startBox,
			this.prLabel("end ms", 44), endBox,
			this.prLabel("fade in ms", 62), fadeInBox,
			this.prLabel("fade out ms", 66), fadeOutBox,
			this.prButton("-", 26, { waveView.zoom(0.5) }, "zoom out (-)"),
			this.prButton("+", 26, { waveView.zoom(2) }, "zoom in (=)"),
			this.prButton("fit", 34, { waveView.zoomAll }, "the whole file (0)"),
			this.prButton("trim", 40, { waveView.zoomToRegion }, "zoom to the trim (f)"),
			nil,
			StaticText().string_("drag  top: S/E   bottom: fades   middle: move the trim")
				.stringColor_(SampleEditor.colDim).font_(smallFont).align_(\right).minWidth_(280)
		).spacing_(4)
	}

	prGainRow {
		gainKnob = SampleEditor.makeKnob(28, true, { gainSpec.unmap(0) })
			.action_({ |v| edit.gainDb_(gainSpec.map(v.value)) });
		gainBox = NumberBox().fixedWidth_(56).decimals_(1).align_(\center)
			.action_({ |v| edit.gainDb_(v.value) });
		ceilBox = NumberBox().fixedWidth_(46).decimals_(1).clipLo_(-24).clipHi_(0)
			.value_(SampleEdit.ceilingDb);
		normBtn = this.prButton("normalize to", 84, { edit.normalize(ceilBox.value) },
			"set the gain so the loudest sample of the trim sits at the dBFS in the box");
		dcBox = CheckBox().string_("remove DC")
			.action_({ |v| edit.removeDC_(v.value) });
		dcInfo = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont);
		^HLayout(
			this.prLabel("gain dB", 46), gainKnob, gainBox,
			normBtn, ceilBox, this.prLabel("dBFS", 30),
			dcBox, dcInfo,
			nil
		).spacing_(4)
	}

	// the bpm and its grid, the key, the tags
	prAnalysisRow {
		var panel = View().background_(SampleEditor.colPanel);
		analysisText = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont).string_("analysing...");
		bpmBox = NumberBox().fixedWidth_(58).decimals_(2).clipLo_(0).clipHi_(999).step_(0.01)
			.action_({ |v| this.setBpm(if (v.value > 0) { v.value } { nil }) });
		bpmFlag = StaticText().font_(smallFont).fixedWidth_(44);
		offsetBox = NumberBox().fixedWidth_(58).decimals_(1).clipLo_(0)
			.action_({ |v| edit.beatOffset_(v.value / 1000); this.prGridMoved(true) });
		gridCheck = CheckBox().string_("grid").value_(true)
			.action_({ |v| waveView.showGrid = v.value; waveView.refresh });
		keyField = TextField().fixedWidth_(46)
			.action_({ |v| this.setKey(v.string) });
		keyFlag = StaticText().font_(smallFont).fixedWidth_(44);
		tagsField = TextField().minWidth_(160)
			.action_({ |v| this.setTags(v.string.split($ ).reject(_.isEmpty)) });
		panel.layout = VLayout(
			HLayout(
				this.prLabel("bpm", 24), bpmBox, bpmFlag,
				this.prButton("÷2", 26, { edit.bpm !? { |b| this.setBpm(b / 2) } }, "half the bpm: a beat twice as long"),
				this.prButton("×2", 26, { edit.bpm !? { |b| this.setBpm(b * 2) } }, "double the bpm"),
				this.prLabel("beat 1 ms", 56), offsetBox,
				this.prButton("to 1st hit", 66, { this.snapGridToFirstHit }, "put the grid's first beat on the first attack of the file"),
				gridCheck,
				this.prLabel("key", 24), keyField, keyFlag,
				this.prLabel("tags", 28), [tagsField, stretch: 1],
				this.prButton("analyse", 56, { this.analyse(true) }, "analyse the file again: onsets, bpm, key, tags")
			).spacing_(4),
			analysisText
		).margins_(5).spacing_(3);
		^panel
	}

	prSlicerRow {
		var panel = View().background_(SampleEditor.colPanel);
		sliceCheck = CheckBox().string_("slicer").action_({ |v| this.slicing = v.value });
		sliceModeMenu = PopUpMenu().fixedWidth_(96).items_(["on the grid", "on attacks"])
			.action_({ |m| slicer !? { slicer.mode = SampleCutter.modes[m.value] }; this.prRefreshSlicer });
		sliceDivMenu = PopUpMenu().fixedWidth_(72).items_(SampleCutter.divisions.collect(_[1])).value_(4)
			.action_({ |m| this.prDivisionChanged });
		sensKnob = SampleEditor.makeKnob(26, false, 0.5).value_(0.5)
			.action_({ |k| sensBox.value = k.value.round(0.01); slicer !? { slicer.sensitivity = k.value } });
		sensBox = NumberBox().fixedWidth_(44).decimals_(2).clipLo_(0).clipHi_(1).step_(0.01).value_(0.5)
			.action_({ |b| sensKnob.value = b.value; slicer !? { slicer.sensitivity = b.value } });
		minBox = NumberBox().fixedWidth_(44).decimals_(0).clipLo_(1).value_(30)
			.action_({ |b| slicer !? { slicer.minLength = b.value / 1000 } });
		sliceCount = StaticText().stringColor_(SampleEditor.colText).font_(smallFont).fixedWidth_(70);
		exportBtn = this.prButton("export slices...", 100, { this.exportSlicesDialog },
			"pick a folder and a name: every slice goes to a new folder of that name, files numbered as Tidal's n, and is loaded as a bank - the file itself is not touched");
		// keep: which slices export writes
		keepSpec = ControlSpec(SampleCutter.levelOff, 0, \lin, 0.5, SampleCutter.levelOff);
		keepLevelKnob = SampleEditor.makeKnob(26, false, 0).value_(0)
			.action_({ |k| this.setKeepLevel(keepSpec.map(k.value)) });
		keepLevelBox = NumberBox().fixedWidth_(50).decimals_(1).clipLo_(SampleCutter.levelOff).clipHi_(0).step_(0.5)
			.value_(SampleCutter.levelOff)
			.action_({ |b| this.setKeepLevel(b.value) });
		keepMinBox = NumberBox().fixedWidth_(50).decimals_(0).clipLo_(0).value_(0)
			.action_({ |b| slicer !? { slicer.keepMinMs = b.value } });
		keepMaxBox = NumberBox().fixedWidth_(50).decimals_(0).clipLo_(0).value_(0)
			.action_({ |b| slicer !? { slicer.keepMaxMs = b.value } });
		keepCount = StaticText().stringColor_(SampleEditor.colText).font_(smallFont).minWidth_(110);
		sliceViews = [sliceModeMenu, sliceDivMenu, sensKnob, sensBox, minBox, exportBtn,
			keepLevelKnob, keepLevelBox, keepMinBox, keepMaxBox];
		panel.layout = VLayout(
			HLayout(
				sliceCheck, sliceModeMenu, sliceDivMenu,
				this.prLabel("sensitivity", 58), sensKnob, sensBox,
				this.prLabel("min ms", 40), minBox,
				sliceCount, nil, exportBtn,
				this.prButton("clear cuts", 64, { slicer !? (_.clearEdits) }, "take away every cut made by hand with shift + click, and every slice put in or out with %-click".format(this.class.clickModName))
			).spacing_(4),
			HLayout(
				this.prLabel("keep", 30),
				this.prLabel("level over dB", 74), keepLevelKnob, keepLevelBox,
				this.prLabel("length ms", 56), keepMinBox, this.prLabel("to", 14), keepMaxBox,
				keepCount, nil,
				this.prButton("keep all", 60, { this.keepAll }, "open every rule again and forget the slices put in or out by hand")
			).spacing_(4)
		).margins_(5).spacing_(3);
		sliceViews.do(_.enabled_(false));
		^panel
	}

	// original | result, one row per reading
	prStatsPanel {
		var rows, keys, panel;
		statTexts = IdentityDictionary.new;
		keys = [
			[\duration, "duration"], [\peak, "peak"], [\rms, "rms"], [\crest, "crest"],
			[\lufs, "loudness"], [\momentary, "loudest 400 ms"], [\dc, "dc offset"], [\clipped, "clipped"]
		];
		rows = [[
			StaticText().string_("").fixedWidth_(90),
			StaticText().string_("original").stringColor_(SampleEditor.colDim).font_(smallFont).align_(\right).fixedWidth_(150),
			StaticText().string_("result").stringColor_(SampleEditor.colAccent).font_(smallFont).align_(\right).fixedWidth_(150)
		]] ++ keys.collect { |pair|
			var orig = StaticText().stringColor_(SampleEditor.colText).font_(monoFont).align_(\right).fixedWidth_(150);
			var res = StaticText().stringColor_(SampleEditor.colText).font_(monoFont).align_(\right).fixedWidth_(150);
			statTexts[pair[0]] = [orig, res];
			[this.prLabel(pair[1], 90), orig, res]
		};
		panel = View().background_(SampleEditor.colPanel);
		panel.layout = HLayout(
			GridLayout.rows(*rows).spacing_(2).margins_(0),
			nil
		).margins_(6).spacing_(4);
		^panel
	}

	// --------------------------------------------------------------- hints

	prAttachHints {
		var h = hints;
		h.attach(titleText, "the bank and the index this sample is played as, from Tidal or the explorer");
		h.attach(infoText, "the file behind it - drop another one from the Finder to open it");
		h.attach(outputMenu, host.outputHint);
		h.attach(waveView.view, "one lane per channel - drag the top strip for the trim, the bottom for the"
			" fades, the middle to move the trim   -   cmd + two fingers up and down zooms, two fingers"
			" on their own slide, double click shows the whole file   -   drop a sound file here to open it");
		h.attach(startBox, "where the file will start, in milliseconds");
		h.attach(endBox, "where it will end");
		h.attach(fadeInBox, "a sine fade in over the first milliseconds of the trim");
		h.attach(fadeOutBox, "a sine fade out over the last");
		h.attach([gainKnob, gainBox], "dB on the samples - drawn into the wave, heard in the preview, written on save");
		h.attach(ceilBox, "the dBFS normalize aims the peak at");
		h.attach(dcBox, "take the mean of each channel out - the dashed line in the wave");
		h.attach(dcInfo, "the offset of each channel as it is in the file");
		h.attach(bpmBox, "the bpm of the grid - what the analysis heard until you change it; 0 takes the grid away");
		h.attach(bpmFlag, "sure: the analysis trusts its bpm; unsure: check it against the grid; by hand: you set it");
		h.attach(offsetBox, "where a beat of the grid falls, ms from the start - alt + drag on the wave slides it");
		h.attach(gridCheck, "show the beat grid over the wave: bars, beats, and the slicer's division (g)");
		h.attach(keyField, "the key (Am, F#, Eb) or the note of a one-shot (C4) - type to set it, empty for the analysis's");
		h.attach(keyFlag, "sure, unsure or by hand, as for the bpm");
		h.attach(tagsField, "the tags of the file, separated by spaces - enter keeps them; Tidal can pick samples by them");
		h.attach(analysisText, "what the analysis measured");
		h.attach(sliceCheck, "cut the trim into slices - the cuts show on the wave as you change what makes them");
		h.attach(sliceModeMenu, "on the grid: every division of the bpm grid; on attacks: wherever the analysis hears one");
		h.attach(sliceDivMenu, "how long a slice on the grid is: a bar, a quarter note, a sixteenth... T for triplets");
		h.attach([sensKnob, sensBox], "on attacks: how weak an attack still makes a cut - more is more slices; the dashed line on the curve");
		h.attach(minBox, "no slice shorter than this, ms");
		h.attach(sliceCount, "how many slices the cuts make - keep may drop some of them from the export");
		h.attach([keepLevelKnob, keepLevelBox], "keep only slices this loud: dB under the loudest slice (its loudest 10 ms) - -60 keeps every level, -12 only the hits near the top");
		h.attach(keepMinBox, "keep only slices at least this long, ms - 0 for no limit");
		h.attach(keepMaxBox, "keep only slices at most this long, ms - 0 for no limit");
		h.attach(keepCount, "how many slices export writes: dropped ones are greyed and crossed on the wave, %-click one to put it in or out".format(this.class.clickModName));
		statTexts.keysValuesDo { |key, texts|
			h.attach(texts, switch (key,
				\duration, { "seconds, and frames, of the file and of the trim" },
				\peak, { "the loudest sample, dBFS - over 0 and an integer file clips" },
				\rms, { "the mean level over every channel, dBFS" },
				\crest, { "peak over rms: how spiky it is" },
				\lufs, { "integrated loudness, K-weighted and gated as BS.1770 says - a stretch shorter than 400 ms is read as one block" },
				\momentary, { "the loudest 400 ms window, LUFS" },
				\dc, { "the mean of each channel: the offset a remove DC takes out" },
				\clipped, { "samples already at full scale in the file, and whether the result would pass it" },
				{ "" }
			));
		};
	}

	// ------------------------------------------------------------- widgets

	prLabel { |str, width|
		var t = StaticText().string_(str).stringColor_(SampleEditor.colDim).font_(smallFont).align_(\right);
		width !? { t.fixedWidth_(width) };
		^t
	}

	prButton { |str, width, func, hint|
		var b = Button().fixedWidth_(width).states_([[str, SampleEditor.colText, SampleEditor.colPanel]])
			.action_({ func.value });
		hint !? { hints.attach(b, hint) };
		^b
	}

	prNumBox { |decimals, func|
		var nb = NumberBox().fixedWidth_(66).decimals_(decimals).clipLo_(0);
		nb.action = { |v| func.value(v.value) };
		^nb
	}

	// ------------------------------------------------------------- refresh

	refresh {
		this.prRefreshHeader;
		this.prRefreshBoxes;
		this.prRefreshStats;
		this.prRefreshAnalysis;
		this.prRefreshSlicer;
		waveView.invalidate;
	}

	// ------------------------------------------------------------ analysis

	// the bpm, the beat and the key the explorer knows for the file, onto the edit
	prGridFromStore {
		var info = this.info;
		if (info.notNil and: { info[\bpm].notNil }) {
			edit.bpm = info[\bpm];
			edit.beatOffset = info[\beatOffset] ? 0;
		};
	}

	// the features of the whole file, in the background; again, and the
	// library's analysis too, when forced
	analyse { |force = false|
		analysisRoutine !? (_.stop);
		analysisText.string = "analysing...";
		analysisRoutine = Routine {
			var info;
			SampleFeatures.breathing = true;
			edit.analyseFeatures;
			SampleFeatures.breathing = false;
			if (force) { this.store !? { |st| st.analyse(edit.path, true) } };
			info = this.info;
			// what the file is known as wins over this pass, unless there is nothing
			if (info.notNil and: { info[\bpm].notNil }) {
				edit.bpm = info[\bpm];
				edit.beatOffset = info[\beatOffset] ? 0;
			};
			analysisRoutine = nil;
			if (this.isOpen) { this.prRefreshAnalysis; this.prRefreshSlicer; waveView.refresh };
		}.play(AppClock);
	}

	setBpm { |bpm|
		edit.bpm = bpm;
		if (bpm.isNil) { edit.beatOffset = 0 };
		this.prPersistGrid;
	}

	setKey { |str|
		var key = str.asString.stripWhiteSpace;
		this.store !? { |st| st.setKey(edit.path, if (key.isEmpty) { nil } { key }) };
		this.prRefreshAnalysis;
	}

	setTags { |words|
		this.store !? { |st| st.setTags(edit.path, words) };
		this.prRefreshAnalysis;
	}

	// the grid's first beat on the first attack, made exact to the sample
	snapGridToFirstHit {
		var t = edit.firstTransient;
		if (edit.bpm.isNil) { ^hints.note("no grid - give it a bpm first") };
		if (t.isNil) { ^hints.note("no attack heard in the file") };
		edit.beatOffset = edit.refineOnset(t) / edit.sampleRate;
		this.prPersistGrid;
	}

	prGridMoved { |done|
		offsetBox.value = (edit.beatOffset * 1000).round(0.1);
		if (done) { this.prPersistGrid };
	}

	// a grid set here is the file's bpm from now on, by hand
	prPersistGrid {
		this.store !? { |st| st.setBpm(edit.path, edit.bpm, edit.beatOffset) };
		this.prRefreshAnalysis;
		slicer !? (_.compute);
	}

	prFlag { |view, sure, byHand, present|
		view.string = case { present.not } { "" } { byHand } { "by hand" } { sure } { "sure" } { "unsure" };
		view.stringColor = case { byHand } { SampleEditor.colAccent } { sure } { SampleEditor.colOn } { SampleEditor.colWarn };
	}

	prRefreshAnalysis {
		var info = this.info, f = edit.features, parts;
		if (bpmBox.isNil) { ^this };
		bpmBox.value = edit.bpm ? 0;
		offsetBox.value = (edit.beatOffset * 1000).round(0.1);
		if (info.notNil and: { info[\analysed] or: { info[\bpmByHand] } or: { info[\keyByHand] } }) {
			this.prFlag(bpmFlag, info[\bpmSure], info[\bpmByHand], info[\bpm].notNil);
			if (keyField.hasFocus.not) { keyField.string = info[\key] ? "" };
			this.prFlag(keyFlag, info[\keySure], info[\keyByHand], info[\key].notNil);
			if (tagsField.hasFocus.not) { tagsField.string = info[\tags].join(" ") };
		} {
			this.prFlag(bpmFlag, f !? { f[\bpmSure] } ? false, false, edit.bpm.notNil);
			if (keyField.hasFocus.not) { keyField.string = f !? { f[\key] } ? "" };
			this.prFlag(keyFlag, f !? { f[\keySure] } ? false, false, f.notNil and: { f[\key].notNil });
			if (tagsField.hasFocus.not) { tagsField.string = info !? { info[\tags].join(" ") } ? "" };
		};
		if (f.isNil) {
			analysisText.string = if (analysisRoutine.notNil) { "analysing..." } { "" };
		} {
			parts = [
				"% attack%".format(f[\onsets].size, if (f[\onsets].size == 1) { "" } { "s" }),
				"attack % ms".format((f[\attack] * 1000).round(0.1)),
				"sounds % s".format(f[\length].round(0.01)),
				"centroid % Hz".format(f[\centroid].round),
				"tonal %".format(f[\tonal].round(0.01)),
				f[\bpm] !? { |b| "analysis: % bpm (%)%".format(b, f[\bpmConfidence].round(0.01), if (f[\lengthFit] == true) { ", fits the length" } { "" }) } ? "analysis: no tempo",
				f[\key] !? { |k| "% (%)".format(k, f[\keyConfidence].round(0.01)) } ? "no key"
			];
			analysisText.string = parts.join("  ·  ");
		};
	}

	// ------------------------------------------------------------- slicer

	slicing { ^slicer.notNil }

	slicing_ { |bool|
		if (bool and: { slicer.isNil }) {
			slicer = SampleCutter(edit);
			slicer.mode = SampleCutter.modes[sliceModeMenu.value];
			slicer.division = SampleCutter.divisions[sliceDivMenu.value][0];
			slicer.sensitivity = sensBox.value;
			slicer.minLength = minBox.value / 1000;
			slicer.keepLevel = keepLevelBox.value;
			slicer.keepMinMs = keepMinBox.value;
			slicer.keepMaxMs = keepMaxBox.value;
			slicer.addDependant(this);
		};
		if (bool.not and: { slicer.notNil }) {
			slicer.removeDependant(this);
			slicer = nil;
		};
		sliceCheck.value = bool;
		sliceViews.do(_.enabled_(bool));
		waveView.slicer = slicer;
		this.prRefreshSlicer;
		waveView.refresh;
	}

	// the level rule, dB under the loudest slice; SampleCutter.levelOff keeps every level
	setKeepLevel { |db|
		db = db.clip(SampleCutter.levelOff, 0);
		keepLevelBox.value = db;
		keepLevelKnob.value = keepSpec.unmap(db);
		slicer !? { slicer.keepLevel = db };
		this.prRefreshSlicer;
	}

	// every rule open, nothing in or out by hand; the cuts stay
	keepAll {
		keepMinBox.value = 0;
		keepMaxBox.value = 0;
		this.setKeepLevel(SampleCutter.levelOff);
		slicer !? {
			slicer.keepMinMs = 0;
			slicer.keepMaxMs = 0;
			slicer.clearKeep;
		};
	}

	// what the hints call the modifier that puts a slice in or out
	*clickModName { ^Platform.case(\osx, { "cmd" }, { "ctrl" }) }

	prDivisionChanged {
		var d = SampleCutter.divisions[sliceDivMenu.value][0];
		waveView.gridDivision = d;
		slicer !? { slicer.division = d };
		waveView.refresh;
	}

	prRefreshSlicer {
		if (sliceCount.isNil) { ^this };
		sliceCount.string = if (slicer.isNil) { "" } {
			if (slicer.mode == \grid and: { edit.bpm.isNil }) { "no grid" } { "% slice%".format(slicer.numSlices, if (slicer.numSlices == 1) { "" } { "s" }) }
		};
		keepCount.string = if (slicer.isNil or: { slicer.numSlices < 1 }) { "" } {
			if (slicer.keepIsOn) { "% of % kept".format(slicer.numKept, slicer.numSlices) } { "all kept" }
		};
		keepCount.stringColor = if (slicer.notNil and: { slicer.keepIsOn } and: { slicer.numKept < 1 }) { SampleEditor.colWarn } { SampleEditor.colText };
		slicer !? {
			waveView.gridDivision = slicer.division;
			sliceModeMenu.value = SampleCutter.modes.indexOf(slicer.mode) ? 0;
			sliceDivMenu.value = SampleCutter.divisions.detectIndex { |d| (d[0] - slicer.division).abs < 1e-6 } ? sliceDivMenu.value;
		};
		sliceDivMenu.enabled = slicer.notNil and: { slicer.mode == \grid };
		[sensKnob, sensBox].do(_.enabled_(slicer.notNil and: { slicer.mode == \transients }));
		waveView.refresh;
	}

	// every slice to folder (nil: "<file>_slices" beside the file), then a bank
	exportSlices { |folder|
		var result, home = host.samplesFolder;
		if (slicer.isNil) { ^hints.note("switch the slicer on first") };
		result = slicer.export(folder);
		if (result.isNil) { ^hints.note("nothing written - see the post window") };
		hints.note("% slices in % - play them as s \"%\" # n 0 .. %%".format(
			result[\paths].size, result[\folder].basename, result[\bank] ? result[\folder].basename, result[\paths].size - 1,
			if (home.notNil and: { SampleEdit.samePath(result[\folder].dirname, home) }) { " - in the samples folder, so it is read at every start" } { "" }));
		^result
	}

	// The Finder's folder dialog for where the slices go (where they went last,
	// Dirt-Samples the first time), then a name for the folder: the bank's
	// name, and every file's with the slice's index after it.
	exportSlicesDialog {
		if (slicer.isNil) { ^hints.note("switch the slicer on first") };
		if (slicer.numSlices < 1) { ^hints.note("nothing to slice") };
		if (slicer.numKept < 1) { ^hints.note("keep drops every slice - lower the level or widen the length") };
		this.class.chooseFolder(SampleCutter.exportStart(edit), { |dir| if (this.isOpen) { this.prNameDialog(dir) } });
	}

	*chooseFolder { |start, ok|
		var answer = { |path| path !? { { ok.value(path.asString.withoutTrailingSlash) }.defer } };
		if (folderChooser.notNil) { ^folderChooser.value(start, answer) };
		FileDialog(answer, nil, 2, 0, true, start);
	}

	// the name, with what it will write; return exports, escape cancels
	prNameDialog { |dir|
		var win, field, whereText, preview, go, refresh, name, target;
		var font = Font(Font.defaultSansFace, 11);
		var btn = { |str, func, back| Button().states_([[str, if (back.notNil) { Color.black } { SampleEditor.colText }, back ? SampleEditor.colPanel]]).action_({ func.value }) };
		exportDialog !? { |d| if (d[\window].isClosed.not) { d[\window].close } };
		SampleCutter.exportParent = dir;
		win = Window("export slices - " ++ edit.label,
			Rect(window.bounds.left + 160, window.bounds.top + 160, 520, 132)).background_(SampleEditor.colBg);
		if (GUI.id == \qt) { win.view.palette = QPalette.dark };
		field = TextField().string_(slicer.defaultName).font_(font);
		whereText = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont);
		preview = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont);
		name = { SampleEdit.bankName(field.string.stripWhiteSpace).asString };
		target = { SampleCutter.freeFolder(dir, name.value) };
		refresh = {
			var n = name.value, t, files;
			whereText.string = "in " ++ dir;
			if (n.isEmpty) { preview.string = "give the folder a name" } {
				t = target.value; files = slicer.fileNames(t);
				preview.string = "%%/%%  -  s \"%\" # n 0 .. %".format(
					if (t.basename != n) { "% is taken there, so: ".format(n) } { "" },
					t.basename, files.first, if (files.size > 1) { " ... " ++ files.last } { "" },
					t.basename, files.size - 1);
			};
		};
		go = { |folderName|
			var n;
			folderName !? { field.string = folderName };
			n = name.value;
			if (n.notEmpty) { win.close; this.exportSlices(dir +/+ n) }
		};
		field.action = { go.value };
		field.keyUpAction = { refresh.value; nil };
		field.keyDownAction = { |view, char, mod, uni, keycode, key| if (key == 16r1000000) { win.close; true } };
		win.layout = VLayout(
			HLayout(StaticText().string_("folder name").stringColor_(SampleEditor.colDim).font_(font), field).spacing_(6),
			HLayout([whereText, stretch: 1], btn.("change...", {
				this.class.chooseFolder(dir, { |d| if (win.isClosed.not) { dir = d; SampleCutter.exportParent = d; refresh.value; win.front } })
			}).fixedWidth_(70)).spacing_(6),
			preview,
			HLayout(nil, btn.("cancel", { win.close }), btn.("export", { go.value }, SampleEditor.colAccent)).spacing_(4)
		).margins_(8).spacing_(5);
		refresh.value;
		exportDialog = (window: win, folder: { dir }, export: go, preview: { preview.string });
		win.front;
		field.focus(true);
		^win
	}

	prRefreshHeader {
		titleText.string = edit.label;
		infoText.string = "%  ·  % s  ·  % ch  ·  % Hz  ·  % %".format(
			edit.fileName, edit.duration.round(0.001), edit.numChannels,
			edit.sampleRate.asInteger, edit.headerFormat, edit.sampleFormat);
		revertBtn.enabled = edit.hasBackup;
		window.name = "sample - " ++ edit.label;
	}

	prRefreshBoxes {
		var dur = edit.duration, dc = edit.dcOffsets;
		startBox.value = (edit.start * dur * 1000).round(0.1);
		endBox.value = (edit.end * dur * 1000).round(0.1);
		fadeInBox.value = (edit.fadeIn * 1000).round(0.1);
		fadeOutBox.value = (edit.fadeOut * 1000).round(0.1);
		gainKnob.value = gainSpec.unmap(edit.gainDb);
		gainBox.value = edit.gainDb.round(0.1);
		dcBox.value = edit.removeDC;
		dcInfo.string = "dc " ++ dc.collect { |v| this.prSigned(v, 4) }.join("  ");
	}

	// a drag fires a dozen changes a second; the numbers are worked out once
	// it has been still for a moment
	prScheduleStats {
		if (statsPending) { ^this };
		statsPending = true;
		AppClock.sched(0.15, {
			statsPending = false;
			if (this.isOpen) { this.prRefreshStats };
			nil
		});
	}

	prRefreshStats {
		var o = edit.metrics(false), r = edit.metrics(true);
		[[o, 0], [r, 1]].do { |pair|
			var m = pair[0], col = pair[1], db = { |x| SampleAnalysis.dbString(x, "") };
			statTexts[\duration][col].string = "% s  (%)".format(m[\duration].round(0.001), m[\frames]);
			statTexts[\peak][col].string = db.(m[\peak]) ++ " dBFS";
			statTexts[\rms][col].string = db.(m[\rms]) ++ " dBFS";
			statTexts[\crest][col].string = db.(m[\crest]) ++ " dB";
			statTexts[\lufs][col].string = db.(m[\lufs]) ++ " LUFS" ++ if (m[\wholeBlock]) { " (short)" } { "" };
			statTexts[\momentary][col].string = db.(m[\momentary]) ++ " LUFS";
			statTexts[\dc][col].string = m[\dcAfter].collect { |v| this.prSigned(v, 4) }.join(" ");
			statTexts[\clipped][col].string = if (col == 0) {
				if (m[\clipped] > 0) { "% samples".format(m[\clipped]) } { "none" }
			} {
				if (m[\overs]) { "over 0 dBFS" } { "none" }
			};
			statTexts[\clipped][col].stringColor = if (col == 1 and: { m[\overs] }) { SampleEditor.colWarn } { SampleEditor.colText };
			statTexts[\peak][col].stringColor = if (col == 1 and: { m[\overs] }) { SampleEditor.colWarn } { SampleEditor.colText };
		};
	}

	prSigned { |v, decimals = 4|
		var r = v.round(10 ** decimals.neg);
		^if (r > 0) { "+" ++ r } { r.asString }
	}

	// the model speaks; everything on screen follows
	// (no caret inside the deferred function: by the time it runs this method
	// has returned, and a return from a dead frame is an error, not a guard)
	update { |obj, what ... args|
		if (slicer.notNil and: { obj === slicer }) {
			if (what == \slices) { { if (this.isOpen) { this.prRefreshSlicer } }.defer };
			^this
		};
		if (obj === this.store) {
			if (what == \tags and: { args[0].isNil or: { args[0] == edit.path } }) {
				{ if (this.isOpen) { this.prRefreshAnalysis } }.defer
			};
			^this
		};
		if (obj !== edit) { ^this };
		if (what == \grid or: { what == \features }) {
			{ if (this.isOpen) { slicer !? (_.compute); waveView.refresh; this.prRefreshAnalysis } }.defer;
			^this
		};
		{
			if (this.isOpen) { switch (what,
				\params, {
					slicer !? (_.compute);
					this.prRefreshBoxes;
					waveView.refresh;
					this.prScheduleStats;
					previewSynth !? { |sy|
						sy.set(\amp, edit.gainDb.dbamp, \dc, this.prDcArgs);
					};
				},
				\loaded, { this.refresh },
				\saved, {
					this.refresh;
					this.analyse;
					this.store !? { |st| st.enqueue([edit.path], true) };
					waveView.zoomAll;
					hints.note("saved % - the library plays the new file now; revert brings the original back".format(edit.fileName));
				},
				\reverted, {
					this.refresh;
					this.analyse;
					this.store !? { |st| st.enqueue([edit.path], true) };
					waveView.zoomAll;
					hints.note("% is the original again".format(edit.fileName));
				},
				\addedToBank, {
					hints.note("wrote % - play it as s \"%:%\"".format(
						args[0].asString.basename, args[1], args[2] ? "?"));
				},
				\savedAs, {
					hints.note("wrote " ++ args[0].asString.basename
						++ if (SampleEdit.samePath(args[0].asString.dirname, edit.path.dirname) and: { edit.inLibrary }) {
							" - added to the bank as its last index" } { "" });
				}
			) };
		}.defer;
	}

	// ---------------------------------------------------------------- files

	// Files dropped or picked: each opens in an editor of its own. The first
	// takes this window's place when nothing here was changed yet (no trim,
	// gain, fades, cuts by hand) and it is not playing. Folders are skipped.
	openFiles { |paths|
		var files = paths.asArray.select { |p| this.class.isSoundFile(p) }, replace, bounds, opened;
		if (files.isEmpty) { ^hints.note("no sound file in that - wav, aiff, flac...") };
		files = files.reject { |p| SampleEdit.samePath(p, edit.path) };
		if (files.isEmpty) { ^this.front };
		replace = edit.isPristine and: { this.isPlaying.not }
			and: { slicer.isNil or: { slicer.added.isEmpty and: { slicer.removed.isEmpty } } };
		bounds = if (replace) { window.bounds } { window.bounds.moveBy(30, 30) };
		opened = files.collect { |p, i|
			try {
				this.class.new(p, host, outputIndex, if (i == 0) { bounds } { bounds.moveBy(30 * i, 30 * i) })
			} { |err|
				hints.note("could not open % - %".format(p.basename, err.errorString));
				err.reportError;
				nil
			}
		}.reject(_.isNil);
		if (replace and: { opened.notEmpty } and: { opened.includes(this).not }) { this.close };
		^opened
	}

	openDialog {
		var start = edit.path.dirname;
		var ok = { |paths| { if (this.isOpen) { this.openFiles(paths.asArray.collect(_.asString)) } }.defer };
		if (fileChooser.notNil) { ^fileChooser.value(ok) };
		FileDialog({ |paths| ok.value(paths) }, nil, 3, 0, false, start);
	}

	// the trim into a bank's folder (parent +/+ bank), loaded. Answers
	// (path:, bank:, index:), or nil
	addToBank { |folder|
		var result;
		result = edit.addToBank(folder);
		if (result.isNil) { hints.note("nothing written - see the post window"); ^nil };
		bankParent = result[\path].dirname.dirname;
		lastBank = result[\bank].asString;
		^result
	}

	// a small window: the bank's name, where its folder is, what it will
	// write; return writes it, escape cancels. A bank that exists gets one more
	// file; a name that is new makes the folder.
	addToBankDialog {
		var start = [bankParent, host.samplesFolder].detect { |p| p.notNil and: { File.exists(p) } } ?? { edit.path.dirname };
		if (edit.regionFrames < 1) { ^hints.note("the trim is empty") };
		^this.prBankDialog(start)
	}

	prBankDialog { |dir|
		var win, field, whereText, preview, go, refresh, name, folder, pick;
		var font = Font(Font.defaultSansFace, 11);
		var btn = { |str, func, back| Button().states_([[str, if (back.notNil) { Color.black } { SampleEditor.colText }, back ? SampleEditor.colPanel]]).action_({ func.value }) };
		bankDialog !? { |d| if (d[\window].isClosed.not) { d[\window].close } };
		win = Window("to bank - " ++ edit.label,
			Rect(window.bounds.left + 160, window.bounds.top + 160, 540, 132)).background_(SampleEditor.colBg);
		if (GUI.id == \qt) { win.view.palette = QPalette.dark };
		field = TextField().string_(lastBank ?? { SampleEdit.bankName(edit.path.basename.splitext[0]).asString }).font_(font);
		whereText = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont);
		preview = StaticText().stringColor_(SampleEditor.colDim).font_(smallFont);
		name = { SampleEdit.bankName(field.string.stripWhiteSpace).asString };
		folder = { dir +/+ name.value };
		// a folder picked that is a bank itself (sound files right in it): that
		// bank, in its parent
		pick = { |d|
			if (SampleEdit.soundFilesIn(d).notEmpty) { dir = d.dirname; field.string = d.basename } { dir = d };
		};
		if (SampleEdit.soundFilesIn(dir).notEmpty and: { dir != SampleCutter.samplesFolder }) { pick.(dir) };
		refresh = {
			var n = name.value, f, have, file, idx, loaded;
			whereText.string = "in " ++ dir;
			if (n.isEmpty) { preview.string = "give the bank a name" } {
				f = folder.value;
				have = SampleEdit.soundFilesIn(f).size;
				file = SampleEdit.nextBankFile(f, edit.bankFormat[0]).basename;
				loaded = host.bankSize(n.asSymbol);
				idx = if (loaded > 0) { loaded } { have };	// appended, or the folder read whole
				preview.string = if (have > 0) {
					"adds % to %, which has % - s \"%:%\"".format(file, n, have, n, idx)
				} {
					"a new bank: %/% - s \"%:%\"".format(n, file, n, idx)
				};
			};
		};
		go = { |bankName|
			var n;
			bankName !? { field.string = bankName };
			n = name.value;
			if (n.notEmpty) { win.close; this.addToBank(folder.value) }
		};
		field.action = { go.value };
		field.keyUpAction = { refresh.value; nil };
		field.keyDownAction = { |view, char, mod, uni, keycode, key| if (key == 16r1000000) { win.close; true } };
		win.layout = VLayout(
			HLayout(StaticText().string_("bank").stringColor_(SampleEditor.colDim).font_(font), field).spacing_(6),
			HLayout([whereText, stretch: 1], btn.("change...", {
				this.class.chooseFolder(dir, { |d| if (win.isClosed.not) { pick.(d); refresh.value; win.front } })
			}).fixedWidth_(70)).spacing_(6),
			preview,
			HLayout(nil, btn.("cancel", { win.close }), btn.("add", { go.value }, SampleEditor.colAccent)).spacing_(4)
		).margins_(8).spacing_(5);
		refresh.value;
		bankDialog = (window: win, folder: { dir }, add: go, preview: { preview.string }, pick: pick);
		win.front;
		field.focus(true);
		^win
	}

	prSave {
		if (edit.isPristine) { ^hints.note("nothing to save - the file is as it was") };
		if (edit.save.isNil) { hints.note("could not save - see the post window") };
	}

	// the dialog opens in the sample's own folder, with a name beside it
	prSaveAs {
		var ext = edit.path.splitext[1] ? "wav";
		var suggested = edit.path.dirname +/+ (edit.path.basename.splitext[0] ++ "-edit." ++ ext);
		Dialog.savePanel({ |p|
			if (p.splitext[1].isNil) { p = p ++ "." ++ ext };
			if (edit.saveAs(p).isNil) { hints.note("could not write " ++ p) };
		}, nil, suggested);
	}

	prRevert {
		this.stop;
		if (edit.revert.isNil) { hints.note("nothing to revert to") };
	}

	// -------------------------------------------------------------- preview

	previewDefName { ^SampleEditor.previewDefName(edit.numChannels, host.numChannels) }

	*previewDefName { |src, out| ^"sampleeditor_preview_%_%".format(src, out).asSymbol }

	// The library's Buffer, read from startFrame to endFrame once, with the
	// gain, the DC and the fades applied on the way, into an orbit's dry bus.
	// The read position comes back 25 times a second for the playhead.
	*previewDef { |src = 1, out = 2|
		^SynthDef(this.previewDefName(src, out), {
			|bufnum, outBus, startFrame = 0, endFrame = 1, amp = 1, fadeIn = 0, fadeOut = 0, gate = 1|
			var dc = NamedControl.kr(\dc, 0 ! src);
			var frames = (endFrame - startFrame).max(1);
			var dur = frames / BufSampleRate.ir(bufnum);
			var phase = Line.ar(startFrame, endFrame, dur, doneAction: 2);
			var sig = BufRd.ar(src, bufnum, phase, 0, 4).asArray - dc.asArray;
			var env = EnvGen.ar(Env([0, 1, 1, 0], [fadeIn, (dur - fadeIn - fadeOut).max(0.001), fadeOut], \sine));
			var stopEnv = EnvGen.kr(Env.cutoff(0.02), gate, doneAction: 2);
			SendReply.kr(Impulse.kr(25), '/sampleeditor_pos', A2K.kr(phase) / BufFrames.ir(bufnum));
			sig = sig * env * stopEnv * amp;
			sig = if (src > out) { sig.keep(out) } { sig.wrapExtend(out) };
			Out.ar(outBus, sig);
		})
	}

	prEnsureDef {
		var s = this.server;
		if (s.isNil or: { s.serverRunning.not }) { ^this };
		fork {
			SampleEditor.previewDef(edit.numChannels, host.numChannels).add;
			s.sync;
			defReady = true;
		};
	}

	prListenForPosition {
		posFunc = OSCFunc({ |msg|
			if (previewSynth.notNil and: { msg[1] == previewSynth.nodeID }) {
				{ if (this.isOpen) { waveView.playhead = msg[3] } }.defer;
			};
		}, '/sampleeditor_pos', this.server.addr).fix; // a cmd-period frees the others; prClosed frees this
	}

	prDcArgs {
		^if (edit.removeDC) { edit.dcOffsets } { 0.0 ! edit.numChannels }
	}

	// the Buffer to play from: the host's (a library's), or one read for the
	// file and freed with the window. Inside the fork play started, so the
	// host may sync.
	prBuffer { |action|
		var buf = host.bufferFor(edit.path);
		if (buf.notNil) { ^action.value(buf) };
		if (tempBuffer.notNil and: { tempBuffer.path == edit.path }) { ^action.value(tempBuffer) };
		tempBuffer !? (_.free);
		tempBuffer = Buffer.read(this.server, edit.path, action: { |b| action.value(b) });
	}

	play {
		var s = this.server;
		if (s.isNil or: { s.serverRunning.not }) { ^hints.note("the server is not running") };
		if (host.outputNames.isEmpty) { ^hints.note("nowhere to play to") };
		this.stop;
		fork {
			if (defReady.not) {
				SampleEditor.previewDef(edit.numChannels, host.numChannels).add;
				s.sync;
				defReady = true;
			};
			this.prBuffer({ |buf| this.prStart(buf) });
		};
	}

	// The host wakes the output first (SuperDirt's orbit effects sleep after
	// 4 s of silence, and a preview into a quiet orbit would go unheard); its
	// resume goes out at the server's latency, and the synth with it, so the
	// first fraction of a second is not lost.
	prStart { |buf|
		var server = this.server;
		var i = outputIndex.clip(0, (host.outputNames.size - 1).max(0));
		var where = host.playTarget(i);
		var synth;
		host.wake(i);
		server.makeBundle(server.latency, {
			synth = Synth(this.previewDefName, [
				\bufnum, buf.bufnum, \outBus, where[\bus],
				\startFrame, edit.startFrame, \endFrame, edit.endFrame,
				\amp, edit.gainDb.dbamp, \fadeIn, edit.fadeIn, \fadeOut, edit.fadeOut,
				\dc, this.prDcArgs
			], where[\target], where[\addAction] ? \addToHead);
		});
		previewSynth = synth;
		synth.onFree {
			if (previewSynth === synth) { previewSynth = nil };
			{ if (this.isOpen) { waveView.playhead = nil } }.defer;
		};
	}

	stop {
		previewSynth !? { |sy|
			previewSynth = nil;
			sy.set(\gate, 0);
		};
		waveView !? { |wv| { if (this.isOpen) { wv.playhead = nil } }.defer };
	}

	// ------------------------------------------------------------ keyboard

	// answers true for a key it took and nil for the rest - to QtCollider a hook
	// answering false keeps the key from the widget as surely as true does
	prKeyDown { |char, mod, key|
		var isChar = char.notNil and: { char.isKindOf(Char) };
		switch (key,
			16r1000012, { waveView.panBy(-0.1); ^true },		// left
			16r1000014, { waveView.panBy(0.1); ^true }			// right
		);
		if (isChar.not) { ^nil };
		// the key code too: with ctrl held, Qt may hand over a control character
		// (19 for s) instead of the letter
		if (this.class.isShortcut(mod)) {
			if (char.toLower == $s or: { key == 16r53 }) { this.prSave; ^true };
			^nil
		};
		switch (char,
			$ , { if (this.isPlaying) { this.stop } { this.play }; ^true },
			$0, { waveView.zoomAll; ^true },
			$-, { waveView.zoom(0.5); ^true },
			$=, { waveView.zoom(2); ^true },
			$+, { waveView.zoom(2); ^true },
			$f, { waveView.zoomToRegion; ^true },
			$g, { gridCheck.valueAction = gridCheck.value.not; ^true }
		);
		^nil
	}

	// ------------------------------------------------------------ lifecycle

	// a cmd-period has freed the preview synth and cleared the clock the
	// analysis ran on: the playhead goes, and the analysis starts over
	cmdPeriod {
		previewSynth = nil;
		{ if (this.isOpen) { waveView.playhead = nil } }.defer;
		if (analysisRoutine.notNil) { analysisRoutine = nil; { if (this.isOpen) { this.analyse } }.defer };
	}

	prClosed {
		CmdPeriod.remove(this);
		this.stop;
		analysisRoutine !? (_.stop);
		analysisRoutine = nil;
		SampleFeatures.breathing = false;
		slicer !? (_.removeDependant(this));
		this.store !? (_.removeDependant(this));
		posFunc !? (_.free);
		posFunc = nil;
		tempBuffer !? (_.free);
		tempBuffer = nil;
		edit.removeDependant(this);
		all.remove(this);
		try { windowBounds = window.bounds };
	}
}
