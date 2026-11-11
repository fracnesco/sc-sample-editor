// =====================================================================
// SampleCutter - a sample cut into slices, written as a bank of its own
//
//   e = SampleEdit("~/breaks.wav"); e.analyseFeatures;
//   x = SampleCutter(e);
//   x.mode = \grid; x.division = 1/16;      // on the grid of e.bpm and e.beatOffset
//   x.mode = \transients; x.sensitivity = 0.7;   // on the attacks the analysis heard
//   x.starts;                               // the frames each slice starts on
//   x.addSliceAt(22050); x.removeSliceNear(44100);   // by hand, on top of either
//   x.keepLevel = -24;                      // keep only slices within 24 dB of the loudest
//   x.keepMinMs = 40; x.keepMaxMs = 400;    // and between 40 and 400 ms long
//   x.toggleKeepAt(22050);                  // that slice in or out, whatever the rules say
//   x.kept; x.keptSlices; x.numKept;        // what export writes
//   x.export;                               // breaks_slices/ beside the file, handed to the host
//   x.export(SampleCutter.samplesFolder +/+ "brk");   // brk/brk_00.wav, brk_01.wav ... into Dirt-Samples
//
// The slices cover the editor's trim: the first starts where the trim does,
// the last ends where it ends. A slice is written with the editor's gain and
// DC as set, and short fades of its own so no cut clicks; the file the
// slices come from is never written to. The folder the slices go to is new
// every time (_2, _3 ... when the name is taken), and is handed to the
// edit's host, which loads it as a bank named after it when it knows a
// SuperDirt (SampleEditorDirtHost). Every file is named after the folder too,
// with the slice's index from 0: the n Tidal plays it as.
//
// Keep: of those slices, export writes only the ones that pass - loud
// enough (the loudest 10 ms of the slice, within keepLevel dB of the
// loudest slice) and long enough and short enough (keepMinMs, keepMaxMs).
// A slice that fails is dropped, not merged: its part of the file is in no
// file, and the kept ones are numbered from 0 without gaps. A slice put in
// or out by hand (toggleKeepAt) stays so, whatever the rules say, until
// clearEdits. Every rule starts open, so keep changes nothing until set.
//
// Notifies \slices when the starts change.
// =====================================================================

SampleCutter {
	classvar <>fadeInMs = 1, <>fadeOutMs = 4;
	classvar <>exportParent;		// where the editor's export last went; nil: Dirt-Samples
	classvar <>levelOff = -60;		// a keepLevel at or under this keeps every level

	var <edit, <mode = \grid, <division = 0.0625, <sensitivity = 0.5, <minLength = 0.03;
	var <added, <removed, <starts;
	var <keepLevel = -60, <keepMinMs = 0, <keepMaxMs = 0;	// 0 ms: no limit
	var <forced;					// [[frame, bool] ...]: slices put in or out by hand, by their start
	var <kept, <levels;				// per slice: kept or not, dB under the loudest slice

	*new { |edit| ^super.newCopyArgs(edit).init }

	init {
		added = [];
		removed = [];
		starts = [];
		forced = [];
		kept = [];
		levels = [];
		this.compute;
	}

	// the divisions a grid offers, as [fraction of a whole note, name]
	*divisions {
		^[[1, "1 bar"], [1/2, "1/2"], [1/4, "1/4"], [1/8, "1/8"], [1/16, "1/16"], [1/32, "1/32"],
			[1/6, "1/4 T"], [1/12, "1/8 T"], [1/24, "1/16 T"]]
	}

	*modes { ^#[\grid, \transients] }

	mode_ { |m| mode = m.asSymbol; this.compute }
	division_ { |d| division = d.asFloat.max(1/128); this.compute }
	sensitivity_ { |v| sensitivity = v.asFloat.clip(0, 1); this.compute }
	// seconds: a cut closer than this to the one before it is not made
	minLength_ { |seconds| minLength = seconds.asFloat.max(0.001); this.compute }

	// dB under the loudest slice a slice may be and still be kept; levelOff or under: any
	keepLevel_ { |db| keepLevel = db.asFloat.clip(levelOff, 0); this.prJudge }
	// ms; 0 for no limit
	keepMinMs_ { |ms| keepMinMs = ms.asFloat.max(0); this.prJudge }
	keepMaxMs_ { |ms| keepMaxMs = ms.asFloat.max(0); this.prJudge }

	// whether a rule is set, so some slice may be dropped
	keepIsOn { ^(keepLevel > levelOff) or: { keepMinMs > 0 } or: { keepMaxMs > 0 } or: { forced.notEmpty } }

	sampleRate { ^edit.sampleRate }

	// ------------------------------------------------------------ the cuts

	compute {
		var f0 = edit.startFrame, f1 = edit.endFrame, sr = edit.sampleRate, gap = (minLength * sr).round.asInteger;
		var tol = (0.003 * sr).round.asInteger, pts, out;
		pts = if (mode == \grid) { this.gridStarts(f0, f1) } { this.transientStarts(f0, f1) };
		pts = pts.reject { |p| removed.any { |r| (p - r).abs <= tol } };
		pts = (pts ++ added).select { |p| p > f0 and: { p < f1 } }.asSet.asArray.sort;
		out = [f0];
		pts.do { |p| if ((p - out.last) >= gap and: { (f1 - p) >= gap }) { out = out.add(p) } };
		starts = out;
		this.prJudge;
		^starts
	}

	// which slices keep keeps; notifies \slices
	prJudge {
		var sr = edit.sampleRate, sl = this.slices, raw, top, tol = (0.003 * sr).round.asInteger;
		raw = sl.collect { |p| edit.levelOf(p[0], p[1]) };
		top = raw.maxItem ? -inf;
		levels = raw.collect { |l| if (top == -inf or: { l == -inf }) { -inf } { l - top } };
		kept = sl.collect { |p, i|
			var ms = (p[1] - p[0]) / sr * 1000, hand = forced.detect { |f| (f[0] - p[0]).abs <= tol };
			if (hand.notNil) { hand[1] } {
				(keepLevel <= levelOff or: { levels[i] >= keepLevel })
				and: { ms >= keepMinMs }
				and: { keepMaxMs <= 0 or: { ms <= keepMaxMs } }
			}
		};
		this.changed(\slices);
	}

	// [[start, end] ...] of the slices export writes
	keptSlices { ^this.slices.select { |p, i| kept[i] ? true } }

	numKept { ^kept.count(_ == true) }

	// the slice a frame is in, or nil outside the trim
	sliceIndexAt { |frame|
		var f1 = edit.endFrame;
		if (starts.isEmpty or: { frame < starts.first } or: { frame >= f1 }) { ^nil };
		^(starts.size - 1 - starts.reverse.detectIndex { |s| s <= frame })
	}

	// the slice a frame is in, in if it was out and out if it was in, kept so
	// through every change of the rules until clearEdits; answers its index
	toggleKeepAt { |frame|
		var i = this.sliceIndexAt(frame), tol = (0.003 * edit.sampleRate).round.asInteger, s, now;
		if (i.isNil) { ^nil };
		s = starts[i];
		now = (kept[i] ? true).not;
		forced = forced.reject { |f| (f[0] - s).abs <= tol }.add([s, now]);
		this.prJudge;
		^i
	}

	// every grid line inside the trim
	gridStarts { |f0, f1|
		var sr = edit.sampleRate;
		if (edit.bpm.isNil) { ^[] };
		^edit.gridLines(division, f0 / sr, f1 / sr).collect { |line| (line[0] * sr).round.asInteger }
	}

	// every attack the analysis heard at the sensitivity, made exact
	transientStarts { |f0, f1|
		var sr = edit.sampleRate;
		^edit.transients(sensitivity).select { |t| (t * sr) > (f0 - (0.02 * sr)) and: { (t * sr) < f1 } }
			.collect { |t| edit.refineOnset(t) }
	}

	// [[start, end] ...], frames
	slices {
		var f1 = edit.endFrame;
		^starts.collect { |a, i| [a, starts[i + 1] ? f1] }
	}

	numSlices { ^starts.size }

	// a cut by hand, kept through every change of mode or sensitivity until clearEdits
	addSliceAt { |frame|
		frame = frame.round.asInteger;
		removed = removed.reject { |r| (r - frame).abs <= (0.003 * edit.sampleRate) };
		added = added.add(frame);
		this.compute;
	}

	// the cut nearest a frame, if one is within `tolerance` frames; not the first
	removeSliceNear { |frame, tolerance|
		var tol = tolerance ? (0.01 * edit.sampleRate), i;
		i = starts.drop(1).minIndex { |s| (s - frame).abs };
		if (i.isNil) { ^false };
		i = i + 1;
		if ((starts[i] - frame).abs > tol) { ^false };
		added = added.reject { |a| (a - starts[i]).abs <= (0.003 * edit.sampleRate) };
		removed = removed.add(starts[i]);
		this.compute;
		^true
	}

	clearEdits { added = []; removed = []; forced = []; this.compute }

	// forget the slices put in or out by hand; the cuts stay
	clearKeep { forced = []; this.prJudge }

	// ------------------------------------------------------------ writing

	// "<file>_slices" beside the file, or with _2, _3 ... after it if that is taken
	*freeFolder { |parent, name|
		var path = parent +/+ name, n = 2;
		while { File.exists(path) } { path = parent +/+ (name ++ "_" ++ n); n = n + 1 };
		^path
	}

	// "<file>_slices", a name SuperDirt takes as a bank's
	defaultName { ^SampleEdit.bankName(edit.path.basename.splitext[0] ++ "_slices").asString }

	defaultFolder { ^this.class.freeFolder(edit.path.dirname, this.defaultName) }

	// the folder SuperDirt's own loadSoundFiles reads, Dirt-Samples beside the
	// SuperDirt quark, or nil when it is not there
	*samplesFolder {
		var cls = \DirtSoundLibrary.asClass, path;
		if (cls.isNil) { ^nil };
		path = cls.filenameSymbol.asString.dirname.dirname.dirname +/+ "Dirt-Samples";
		^if (File.exists(path)) { path }
	}

	// where the editor's export dialog opens: where it went last, else
	// Dirt-Samples, else beside the file
	*exportStart { |edit|
		^[exportParent, this.samplesFolder].detect { |p| p.notNil and: { File.exists(p) } } ?? { edit !? { edit.path.dirname } }
	}

	// the files an export to folder would write: <folder>_00.wav, <folder>_01.wav ...
	fileNames { |folder|
		var base = folder.withoutTrailingSlash.basename, ext = edit.bankFormat[0];
		var digits = (this.numKept - 1).asString.size.max(2);
		^this.numKept.collect { |i| "%_%.%".format(base, i.asString.padLeft(digits, "0"), ext) }
	}

	// Every kept slice to a file of its own in a new folder, each named after the
	// folder with its index from 0 (the n Tidal plays it as); then the folder
	// to the host (loaded as a bank when it knows a library), every slice tagged
	// `slice` (and given the grid's bpm) when the host keeps tags. Answers
	// (folder:, paths:, bank:), or nil.
	export { |folder, load = true|
		var paths, names, store, files, host = edit.host;
		folder = (folder ?? { this.defaultFolder }).standardizePath.withoutTrailingSlash;
		if (File.exists(folder)) { folder = this.class.freeFolder(folder.dirname, folder.basename) };
		if (this.numKept < 1) { "SampleCutter: no slice kept".warn; ^nil };
		File.mkdir(folder);
		if (File.exists(folder).not) { "SampleCutter: could not make %".format(folder).warn; ^nil };
		files = this.fileNames(folder);
		paths = this.keptSlices.collect { |pair, i|
			var p = folder +/+ files[i], fmt = edit.bankFormat;
			edit.writeRange(p, pair[0], pair[1], fadeInMs / 1000, fadeOutMs / 1000, fmt[1], fmt[2])
		};
		if (paths.includes(nil)) { "SampleCutter: some slices could not be written".warn };
		paths = paths.reject(_.isNil);
		if (load) {
			names = host.folderWritten(folder, paths);
			store = host.store;
			store !? {
				paths.do { |p|
					store.addTag(p, \slice);
					if (mode == \grid and: { edit.bpm.notNil }) { store.setBpm(p, edit.bpm, 0) };
				};
				store.enqueue(paths, true);
			};
		};
		this.changed(\exported, folder);
		^(folder: folder, paths: paths, bank: (names !? (_.first)) ?? { SampleEdit.bankName(folder) })
	}
}
