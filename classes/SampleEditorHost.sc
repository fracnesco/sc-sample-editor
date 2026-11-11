// =====================================================================
// SampleEditorHost - what a SampleEditor knows of the world around it
//
// The editor and its model ask their host where the preview plays, which
// Buffer to play from, what a sample library calls a file, and they tell
// it about every file they write. This one is the editor on its own: the
// preview goes to the server's hardware outputs, a file is read into a
// Buffer of the editor's own, and written files are only written.
//
//   SampleEditor("~/x.wav", SampleEditorHost(s));
//   SampleEditor.defaultHost = SampleEditorHost(s);     // for every editor from now on
//
// SampleEditorDirtHost (below) knows a SuperDirt. Anything else can be a
// host by answering the same messages - SuperDirtGUI's is a
// SampleEditorDirtHost with an owner that also keeps tags:
//
//   server                    the Server the preview plays on
//   numChannels               how many channels the preview writes
//   outputNames               the menu beside play: one String per output
//   outputLabel, outputHint   the menu's label and hint
//   playTarget(i)             (target:, bus:, addAction:) for output i
//   wake(i)                   just before the preview starts on output i
//   bufferFor(path)           a Buffer already holding the file, or nil
//                             (called in a Routine: may sync)
//   sampleAt(path)            [name, index] of the file in a library, or nil
//   pathOf(name, index)       the file a library holds as name:index, or nil
//   bankSize(name)            how many samples a bank holds now
//   fileChanged(path)         a file was written over (save, revert)
//   fileAdded(path, bank)     a file was written into a bank's folder (save as)
//   folderWritten(folder, paths)   new files in a bank folder (to bank...,
//                             export slices...); answers the bank names, or nil
//   store                     where bpm, key and tags are kept (SDGSampleTags'
//                             messages: infoOf, setBpm, setKey, setTags,
//                             addTag, analyse, enqueue), or nil
//   soundExtensions           the kinds of file the library reads
//   samplesFolder             where banks are written by default, or nil
// =====================================================================

SampleEditorHost {
	var <server;

	*new { |server| ^super.newCopyArgs(server ? Server.default) }

	// ----- playing

	numChannels { ^2 }

	// the hardware outputs in pairs: out 0-1, out 2-3 ...
	outputNames {
		var n = (server.options.numOutputBusChannels div: 2).max(1);
		^n.collect { |i| "out %-%".format(i * 2, i * 2 + 1) }
	}

	outputLabel { ^"out" }

	outputHint { ^"where play sends the preview: a pair of the sound card's outputs (out 0-1 is usually the speakers) - it changes nothing in the file" }

	playTarget { |i| ^(target: server.defaultGroup, bus: i * 2, addAction: \addToTail) }

	wake { |i| }

	bufferFor { |path| ^nil }

	// ----- a library

	sampleAt { |path| ^nil }

	pathOf { |name, index| ^nil }

	bankSize { |name| ^0 }

	fileChanged { |path| }

	fileAdded { |path, bank| }

	folderWritten { |folder, paths| ^nil }

	store { ^nil }

	soundExtensions { ^#["wav", "aif", "aiff", "aifc"] }

	samplesFolder { ^SampleCutter.samplesFolder }
}


// =====================================================================
// SampleEditorDirtHost - the editor inside a SuperDirt
//
//   h = SampleEditorDirtHost(~dirt);
//   SampleEditor.defaultHost = h;
//   SampleEditor.sample(\bd, 3);             // bd:3 of the library
//   SampleEditor("~/x.wav");                 // any file; as bd:3 if the library holds it
//
// The preview plays at the head of an orbit's group into its dry bus, so
// its effects apply; the orbit's always-on effects are woken first (they
// pause after 4 s of silence and only a set resumes them). The library's
// own Buffer is played, read first if the library was made with
// doNotReadYet. A save reads the new file back into the same Buffer, so
// every event keeps its bufnum and Tidal plays the new file under the same
// name and number; a file saved into a bank's folder is appended to it; a
// bank folder written is loaded, new files only.
//
// Only SuperDirt's public methods are used, and SuperDirt is never named:
// the class compiles without it.
//
// owner: an object that does some of this itself (SuperDirtGUI). Whatever of
// wakeOrbit, reloadSample, addSample, loadSamples, sampleAtPath and tags it
// answers is asked of it instead.
// =====================================================================

SampleEditorDirtHost : SampleEditorHost {
	var <dirt, <owner;

	*new { |dirt, owner|
		if (dirt.isNil) { Error("SampleEditorDirtHost: give it a SuperDirt, e.g. SampleEditorDirtHost(~dirt)").throw };
		^super.new(dirt.server).initDirt(dirt, owner)
	}

	initDirt { |aDirt, anOwner| dirt = aDirt; owner = anOwner }

	library { ^dirt.soundLibrary }

	prOwnerDoes { |selector| ^owner.notNil and: { owner.respondsTo(selector) } }

	// ----- playing

	numChannels { ^dirt.numChannels }

	orbits { ^dirt.orbits ? [] }

	outputNames { ^this.orbits.collect { |o, i| "orbit " ++ i } }

	outputLabel { ^"orbit" }

	outputHint { ^"the orbit play sends the preview through (orbit 0 is d1), so its effects and fader apply - it changes nothing in the file" }

	playTarget { |i|
		var orbit = this.orbits[i];
		^(target: Group.basicNew(server, orbit.group), bus: orbit.dryBus.index, addAction: \addToHead)
	}

	wake { |i|
		var orbit = this.orbits[i];
		if (orbit.isNil) { ^this };
		if (this.prOwnerDoes(\wakeOrbit)) { ^owner.wakeOrbit(orbit) };
		orbit.globalEffects.do { |fx| if (fx.alwaysRun) { fx.set(orbit.defaultParentEvent) } };
	}

	// the library's Buffer, read now if the library has not read it yet
	bufferFor { |path|
		var at = this.sampleAt(path), event;
		if (at.isNil) { ^nil };
		event = this.library.bufferEvents[at[0]] !? (_[at[1]]);
		if (event.notNil and: { event[\notYetRead] == true }) {
			this.library.readFileIfNecessary(event);
			server.sync;
		};
		^this.library.buffers[at[0]] !? (_[at[1]])
	}

	// ----- the library

	sampleAt { |path|
		if (this.prOwnerDoes(\sampleAtPath)) { ^owner.sampleAtPath(path) };
		path = path.asString.standardizePath;
		this.library.buffers.keysValuesDo { |name, bufs|
			bufs.do { |b, i| if (b.path.notNil and: { SampleEdit.samePath(b.path, path) }) { ^[name, i] } }
		};
		^nil
	}

	pathOf { |name, index|
		^this.library.buffers[name.asSymbol] !? { |bufs| bufs[index] } !? (_.path)
	}

	bankSize { |name| ^(this.library.buffers[name.asSymbol] ? []).size }

	fileChanged { |path|
		var at = this.sampleAt(path);
		if (at.isNil) { ^this };
		if (this.prOwnerDoes(\reloadSample)) { ^owner.reloadSample(at[0], at[1]) };
		this.prReload(at[0], at[1]);
	}

	// the file back into the same Buffer, the header first so the event's
	// frame count is right before the server has even read it
	prReload { |name, index|
		var buf = this.library.buffers[name] !? (_[index]), event, metaData;
		if (buf.isNil or: { File.exists(buf.path).not }) { ^nil };
		SoundFile.use(buf.path, { |file|
			buf.sampleRate = file.sampleRate;
			buf.numFrames = file.numFrames;
			buf.numChannels = file.numChannels;
		});
		buf.allocRead(buf.path);
		event = this.library.bufferEvents[name] !? (_[index]);
		event !? {
			metaData = this.library.tryPerform(\readMetaData, buf.path);
			event.putAll(this.library.makeEventForBuffer(buf, metaData));
			event[\notYetRead] = false;
		};
		^buf
	}

	fileAdded { |path, bank|
		if (this.prOwnerDoes(\addSample)) { ^owner.addSample(path, bank) };
		this.library.loadSoundFile(path.standardizePath, bank.asSymbol, true);
	}

	// the folder's files the library does not hold yet, as the bank named
	// after the folder
	folderWritten { |folder, paths|
		var name, files, lib = this.library;
		if (this.prOwnerDoes(\loadSamples)) { ^owner.loadSamples(folder, false) };
		name = SampleEdit.bankName(folder);
		files = SampleEdit.soundFilesIn(folder).select { |p| this.soundExtensions.includesEqual((p.splitext[1] ? "").toLower) };
		files = files.reject { |p| (lib.buffers[name] ? []).any { |b| SampleEdit.samePath(b.path, p) } };
		if (files.isEmpty) { ^[] };
		lib.loadSoundFilePaths(files, name, true);
		^[name]
	}

	store { ^if (this.prOwnerDoes(\tags)) { owner.tags } }

	soundExtensions { ^this.library.fileExtensions }
}
