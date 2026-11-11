// =====================================================================
// SampleEdit - one sound file, opened for editing
//
// The file is read into the language, one Signal per channel, and every
// edit is a setting on top of it - the trim, a gain, the DC taken out,
// fades on the edges - until `save` writes the result over the file (a
// .bak of the original beside it, kept for `revert`) or `saveAs` writes
// it somewhere else. The host (SampleEditorHost, or one that knows a
// SuperDirt) hears of every file written, so a sample library can read a
// saved file back into the same Buffer.
//
//   e = SampleEdit("~/kick.wav");             // the default host: SampleEditor.defaultHost
//   e = SampleEdit(path, SampleEditorDirtHost(~dirt));   // as bd:3 when ~dirt holds the file
//   e.setRegion(0.1, 0.9); e.gainDb = -3; e.removeDC = true;
//   e.metrics;                               // (peak:, rms:, lufs: ...) of the result
//   e.metrics(false);                        // of the file as it is
//   e.save;                                  // over the file, .bak kept
//   e.revert;                                // the .bak back
//   e.addToBank("~/samples/kicks");          // the trim as kicks/kicks_00.wav, loaded by the host
//
// Nothing here needs the server: the reading, the numbers and the writing
// are all language side, and exact. The editor window (SampleEditor)
// is what plays it.
// =====================================================================

SampleEdit {
	classvar <>maxSamples = 24000000;		// frames x channels the editor will read
	// whatever maxSamples says: the file is read into one Signal, and sclang
	// crashes (no error) on any object of 2 GiB or more - 536,870,912 floats
	classvar <hardLimit = 536000000;
	classvar <>backupSuffix = ".bak";		// beside the file; not a sound file extension, so the library skips it
	classvar <>envelopeColumns = 4096;		// the peak envelope kept for drawing
	classvar <>directFrames = 262144;		// a view spanning fewer frames than this is drawn from the samples
	classvar <>ceilingDb = -1;				// what normalize brings the peak to

	var <host, <name, <index, <path;
	var <sampleRate, <numChannels, <numFrames, <headerFormat, <sampleFormat;
	var <channels;							// one Signal per channel, the file as read
	var <start = 0.0, <end = 1.0;			// the trim, as fractions of the file
	var <gainDb = 0.0, <removeDC = false;
	var <fadeIn = 0.0, <fadeOut = 0.0;		// seconds, on the edges of the trim
	var <stats, <hops, <envelopes;			// the whole file, analysed once
	var <loaded = false, <label;
	var <features;							// SampleFeatures of the whole file, once analyseFeatures has run
	var <bpm, <beatOffset = 0.0, <beatsPerBar = 4;	// the grid: nil bpm, no grid
	var blocks;								// energyBlocks, worked out once per file read

	// a file, as the host's library knows it (name and index) when it does
	*new { |path, host|
		var at;
		host = host ?? { SampleEditor.defaultHost };
		path = path.asString.standardizePath;
		at = host.sampleAt(path);
		^super.newCopyArgs(host, at !? { at[0].asSymbol }, at !? { at[1] }, path).init
	}

	init {
		label = if (name.notNil) { "%:%".format(name, index) } { path.basename };
		this.load;
	}

	// ------------------------------------------------------------- the file

	inLibrary { ^name.notNil }

	fileName { ^path.basename }


	load {
		var sf = SoundFile.openRead(path), data;
		if (sf.isNil) { Error("SampleEdit: cannot read " ++ path).throw };
		sampleRate = sf.sampleRate;
		numChannels = sf.numChannels;
		numFrames = sf.numFrames;
		headerFormat = sf.headerFormat;
		sampleFormat = sf.sampleFormat;
		// in Floats: an sclang Integer is 32 bits, the product of a long file wraps
		if ((numFrames.asFloat * numChannels) > min(maxSamples, hardLimit)) {
			sf.close;
			Error("SampleEdit: % is too long for the editor (% samples, the limit is %%)"
				.format(path.basename, (numFrames.asFloat * numChannels).asStringPrec(12),
					min(maxSamples, hardLimit).asFloat.asStringPrec(12),
					if (maxSamples > hardLimit) { " - sclang cannot hold more in one piece" } { "" })).throw
		};
		data = Signal.newClear(numFrames * numChannels);
		sf.readData(data);
		sf.close;
		channels = numChannels.collect { |c|
			var ch = Signal.newClear(numFrames);
			numFrames.do { |i| ch[i] = data[(i * numChannels) + c] };
			ch
		};
		this.prAnalyse;
		features = nil;		// the onsets and the tempo are worked out again: analyseFeatures
		blocks = nil;
		loaded = true;
		this.changed(\loaded);
	}

	prAnalyse {
		stats = SampleAnalysis.stats(channels, 0, numFrames);
		hops = SampleAnalysis.kHops(channels, sampleRate);
		envelopes = channels.collect { |ch|
			SampleEdit.peaksOf(ch, envelopeColumns, 0, numFrames)
		};
	}

	duration { ^numFrames / sampleRate }

	// ------------------------------------------------------------ levels

	// frames in one energy block: 2 ms
	blockFrames { ^(sampleRate * 0.002).round.asInteger.max(1) }

	// The mean square of the file in every 2 ms block, the channels summed,
	// as read (no gain, the DC left in), worked out the first time it is asked
	// for. What the slicer's keep judges a slice's level from.
	energyBlocks {
		var bf, nb;
		if (blocks.notNil) { ^blocks };
		bf = this.blockFrames;
		nb = numFrames div: bf;
		blocks = FloatArray.newClear(nb);
		channels.do { |ch|
			nb.do { |b|
				var acc = 0.0, base = b * bf;
				bf.do { |k| var x = ch[base + k]; acc = acc + (x * x) };
				blocks[b] = blocks[b] + (acc / bf);
			};
		};
		^blocks
	}

	// The level of [f0, f1), dB: the loudest 10 ms in it (5 blocks), from the
	// blocks wholly inside it; a stretch shorter than that is read as one
	// window. -inf for silence.
	levelOf { |f0, f1|
		var e = this.energyBlocks, bf = this.blockFrames, b0, b1, win = 5, best = 0.0, sum = 0.0;
		b0 = (f0 / bf).ceil.asInteger.clip(0, e.size);
		b1 = (f1 / bf).floor.asInteger.clip(b0, e.size);
		if ((b1 - b0) < 1) {
			b0 = (f0 / bf).floor.asInteger.clip(0, (e.size - 1).max(0));
			b1 = (b0 + 1).min(e.size);
		};
		if (b1 <= b0) { ^-inf };
		if ((b1 - b0) <= win) {
			(b0..(b1 - 1)).do { |b| sum = sum + e[b] };
			^(sum / (b1 - b0)).ampdb * 0.5
		};
		win.do { |k| sum = sum + e[b0 + k] };
		best = sum;
		(b0 + win).forBy(b1 - 1, 1) { |b|
			sum = sum + e[b] - e[b - win];
			if (sum > best) { best = sum };
		};
		^(best / win).ampdb * 0.5
	}

	startFrame { ^(start * numFrames).round.asInteger.clip(0, numFrames) }
	endFrame { ^(end * numFrames).round.asInteger.clip(0, numFrames) }
	regionFrames { ^this.endFrame - this.startFrame }
	regionDuration { ^this.regionFrames / sampleRate }

	// ------------------------------------------------------------ settings

	start_ { |frac| this.setRegion(frac, end) }

	end_ { |frac| this.setRegion(start, frac) }

	setRegion { |s, e|
		var minSpan = if (numFrames > 1) { 1 / numFrames } { 1 };
		s = (s ? start).clip(0, 1);
		e = (e ? end).clip(0, 1);
		if (e < s) { #s, e = [e, s] };
		if ((e - s) < minSpan) { e = (s + minSpan).min(1); s = e - minSpan };
		start = s;
		end = e;
		this.prClampFades;
		this.prChanged;
	}

	gainDb_ { |db|
		gainDb = db.clip(-60, 24);
		this.prChanged;
	}

	removeDC_ { |bool|
		removeDC = bool.asBoolean;
		this.prChanged;
	}

	fadeIn_ { |seconds|
		fadeIn = seconds.max(0);
		this.prClampFades;
		this.prChanged;
	}

	fadeOut_ { |seconds|
		fadeOut = seconds.max(0);
		this.prClampFades;
		this.prChanged;
	}

	// together the fades never cover more than the trim
	prClampFades {
		var half = this.regionDuration * 0.5;
		fadeIn = fadeIn.clip(0, half);
		fadeOut = fadeOut.clip(0, half);
	}

	reset {
		start = 0.0;
		end = 1.0;
		gainDb = 0.0;
		removeDC = false;
		fadeIn = 0.0;
		fadeOut = 0.0;
		this.prChanged;
	}

	isPristine {
		^(start == 0) and: { end == 1 } and: { gainDb == 0 } and: { removeDC.not }
			and: { fadeIn == 0 } and: { fadeOut == 0 }
	}

	// the gain that brings the peak of the result to the ceiling
	normalize { |ceiling|
		var m = SampleAnalysis.metrics(this.regionStats, 0, removeDC);
		this.gainDb = SampleAnalysis.gainToCeiling(m[\peakAmp], ceiling ? ceilingDb);
	}

	prChanged { this.changed(\params) }

	// ------------------------------------------------------------- numbers

	// the offset of each channel, over the whole file
	dcOffsets {
		^stats.collect { |st| if (st[\n] > 0) { st[\sum] / st[\n] } { 0.0 } }
	}

	regionStats {
		if (start == 0 and: { end == 1 }) { ^stats };
		^SampleAnalysis.stats(channels, this.startFrame, this.endFrame)
	}

	// The readings, of the result (the trim with the gain and the DC as set)
	// or of the file as it is. See SampleAnalysis.metrics for the keys;
	// lufs and momentary are added here, and wholeBlock says the stretch was
	// shorter than a loudness block and measured as one.
	metrics { |result = true|
		var st = if (result) { this.regionStats } { stats };
		var m = SampleAnalysis.metrics(st, if (result) { gainDb } { 0 }, result and: { removeDC });
		var l = if (result) {
			SampleAnalysis.loudness(hops, sampleRate, this.startFrame, this.endFrame, gainDb)
		} {
			SampleAnalysis.loudness(hops, sampleRate, 0, numFrames, 0)
		};
		m[\lufs] = l !? (_[\integrated]);
		m[\momentary] = l !? (_[\momentaryMax]);
		m[\wholeBlock] = (l !? (_[\whole])) ? false;
		m[\frames] = if (result) { this.regionFrames } { numFrames };
		m[\duration] = m[\frames] / sampleRate;
		^m
	}

	// ------------------------------------------------------------- drawing

	// [[min, max] ...] per column of channel c over [lo, hi], fractions of
	// the file. The envelope holds the detail while there are many frames
	// behind each of its columns; a window short enough is read from the
	// samples, which is what a deep zoom needs.
	peaks { |c = 0, numCols = 512, lo = 0, hi = 1|
		var f0 = (lo * numFrames).floor.asInteger.clip(0, numFrames);
		var f1 = (hi * numFrames).ceil.asInteger.clip(f0, numFrames);
		var span = f1 - f0, env = envelopes !? (_[c]);
		if (span < 1 or: { channels.isNil }) { ^nil };
		if (span > directFrames and: { env.notNil }
			and: { (env.size * (hi - lo)) >= (numCols * 1.5) }) {
			^SampleEdit.reduce(env, numCols, lo, hi)
		};
		^SampleEdit.peaksOf(channels[c], numCols, f0, f1)
	}

	// the samples of channel c in [f0, f1), for a zoom deep enough to draw
	// them joined
	samples { |c = 0, f0 = 0, f1|
		var a = f0.clip(0, numFrames - 1), b = ((f1 ? numFrames) - 1).clip(a, numFrames - 1);
		^channels[c].copyRange(a, b)
	}

	// min/max per column of a Signal over frames [f0, f1)
	*peaksOf { |sig, numCols, f0 = 0, f1|
		var span, n, perCol;
		f1 = f1 ? sig.size;
		span = (f1 - f0).max(0);
		if (span < 1) { ^nil };
		n = numCols.asInteger.clip(1, span);
		perCol = span / n;
		^Array.fill(n, { |i|
			var a = f0 + (i * perCol).floor.asInteger;
			var b = (f0 + ((i + 1) * perCol).ceil.asInteger).min(f1);
			var mn = 0.0, mx = 0.0;
			(b - a).do { |k|
				var x = sig[a + k];
				if (x < mn) { mn = x };
				if (x > mx) { mx = x };
			};
			[mn, mx]
		})
	}

	// a peak envelope reduced to n columns over [lo, hi] of what it covers
	*reduce { |peaks, n, lo = 0, hi = 1|
		var size = peaks.size, span = hi - lo;
		if (size < 1) { ^nil };
		^Array.fill(n, { |x|
			var i0 = ((lo + (span * x / n)) * size).floor.asInteger.clip(0, size - 1);
			var i1 = ((lo + (span * (x + 1) / n)) * size).ceil.asInteger.clip(i0 + 1, size);
			var mn = 0, mx = 0;
			(i1 - i0).do { |k|
				var p = peaks[i0 + k];
				mn = min(mn, p[0]);
				mx = max(mx, p[1]);
			};
			[mn, mx]
		})
	}

	// ----------------------------------------------------- rendering, files

	// the result: the trim, the DC out, the gain, the fades - interleaved,
	// and clipped to full scale for an integer format
	render { |clipTo|
		^this.renderRange(this.startFrame, this.endFrame, fadeIn, fadeOut, clipTo)
	}

	// any stretch of the file [f0, f1) with the gain and the DC as set, and
	// sine fades of its own, seconds - what a slice is written from
	renderRange { |f0, f1, fadeInSecs = 0, fadeOutSecs = 0, clipTo|
		var nc = numChannels, g = gainDb.dbamp, n, out, fi, fo;
		var dc = if (removeDC) { this.dcOffsets } { 0.0 ! nc };
		f0 = f0.asInteger.clip(0, numFrames);
		f1 = f1.asInteger.clip(f0, numFrames);
		n = f1 - f0;
		out = Signal.newClear(n * nc);
		fi = (fadeInSecs * sampleRate).round.asInteger.clip(0, n);
		fo = (fadeOutSecs * sampleRate).round.asInteger.clip(0, n - fi);
		nc.do { |c|
			var ch = channels[c], off = dc[c];
			n.do { |i| out[(i * nc) + c] = (ch[f0 + i] - off) * g };
		};
		// sine fades on the edges, so a trim cannot click
		fi.do { |i|
			var a = sin(i / fi * 0.5pi);
			nc.do { |c| var k = (i * nc) + c; out[k] = out[k] * a };
		};
		fo.do { |i|
			// the last sample is silence, the way the first one of a fade in is
			var a = sin((fo - 1 - i) / fo * 0.5pi);
			nc.do { |c| var k = ((n - fo + i) * nc) + c; out[k] = out[k] * a };
		};
		clipTo !? { out.size.do { |k| out[k] = out[k].clip(clipTo.neg, clipTo) } };
		^out
	}

	*isFloatFormat { |format| ^#["float", "double"].any { |f| f == format.asString } }

	// Write the result to a path, in the file's own format unless told
	// otherwise. An integer format is clipped just under full scale first,
	// so a gain past 0 dBFS clips rather than wraps. Answers the path, or nil.
	write { |outPath, header, format|
		^this.writeRange(outPath, this.startFrame, this.endFrame, fadeIn, fadeOut, header, format)
	}

	// [f0, f1) with its own fades to a file, in the file's own format unless told
	// otherwise. Answers the path, or nil.
	writeRange { |outPath, f0, f1, fadeInSecs = 0, fadeOutSecs = 0, header, format|
		var hdr = header ? headerFormat, fmt = format ? sampleFormat, sf, data;
		data = this.renderRange(f0, f1, fadeInSecs, fadeOutSecs, if (SampleEdit.isFloatFormat(fmt)) { nil } { 0.9999 });
		sf = SoundFile.openWrite(outPath, hdr, fmt, numChannels, sampleRate.asInteger);
		if (sf.isNil and: { hdr != "WAV" or: { fmt != "int24" } }) {
			"SampleEdit: cannot write % as % %, writing WAV int24 instead".format(outPath.basename, hdr, fmt).warn;
			data = this.renderRange(f0, f1, fadeInSecs, fadeOutSecs, 0.9999);
			sf = SoundFile.openWrite(outPath, "WAV", "int24", numChannels, sampleRate.asInteger);
		};
		if (sf.isNil) { "SampleEdit: could not open % for writing".format(outPath).warn; ^nil };
		sf.writeData(data);
		sf.close;
		^outPath
	}

	backupPath { ^path ++ backupSuffix }

	hasBackup { ^File.exists(this.backupPath) }

	// Over the file. The first save keeps the original beside it as a .bak;
	// later saves leave that alone, so revert always means the file as it
	// was before the editor touched it. The library's Buffer is re-read,
	// and this edit starts again from the saved file.
	save {
		if (this.hasBackup.not) {
			File.copy(path, this.backupPath);
			if (this.hasBackup.not) {
				"SampleEdit: could not back up % - not saving".format(path).warn;
				^nil
			};
		};
		if (this.write(path).isNil) { ^nil };
		this.prAfterFileChanged;
		this.changed(\saved, path);
		^path
	}

	// To another file, in the file's own format. When the file lands in the
	// folder of the bank this sample is in, it is appended to that bank so
	// Tidal can play it right away - as the last index, whatever its name.
	saveAs { |outPath, addToBank = true|
		var written;
		outPath = outPath.asString.standardizePath;
		if (outPath == path) { ^this.save };
		written = this.write(outPath);
		if (written.isNil) { ^nil };
		if (addToBank and: { this.inLibrary } and: { this.class.samePath(outPath.dirname, path.dirname) }) {
			host.fileAdded(outPath, name);
		};
		this.changed(\savedAs, outPath);
		^outPath
	}

	// The trim as a new file in a bank's folder, then into the library, so Tidal
	// plays it at once: a folder that exists gets one more file, a new one is
	// made. The file is named after the folder with the next free number
	// (<bank>_03.wav), so after a restart, when SuperDirt reads the folder in
	// name order, it keeps its place among the ones written this way. A cut
	// inside the file gets the slicer's short fades, so it does not click.
	// Answers (path:, bank:, index:), or nil.
	addToBank { |folder|
		var fmt = this.bankFormat, out, fi, fo, bank, index, at;
		folder = folder.asString.standardizePath.withoutTrailingSlash;
		if (this.regionFrames < 1) { "SampleEdit: the trim is empty".warn; ^nil };
		if (File.exists(folder).not) { File.mkdir(folder) };
		if (File.exists(folder).not) { "SampleEdit: could not make %".format(folder).warn; ^nil };
		out = this.class.nextBankFile(folder, fmt[0]);
		fi = if (this.startFrame > 0) { fadeIn.max(SampleCutter.fadeInMs / 1000) } { fadeIn };
		fo = if (this.endFrame < numFrames) { fadeOut.max(SampleCutter.fadeOutMs / 1000) } { fadeOut };
		if (this.writeRange(out, this.startFrame, this.endFrame, fi, fo, fmt[1], fmt[2]).isNil) { ^nil };
		bank = this.class.bankName(folder);
		host.folderWritten(folder, [out]);
		at = host.sampleAt(out);
		if (at.notNil) { bank = at[0].asSymbol; index = at[1] };
		host.store !? { |st|
			if (bpm.notNil) { st.setBpm(out, bpm, 0) };
			st.enqueue([out], true)
		};
		this.changed(\addedToBank, out, bank, index);
		^(path: out, bank: bank, index: index)
	}

	// [extension, header, sample format] of a file written for a bank: the
	// file's own, unless the host's library would not read it (SuperDirt loads
	// wav and aiff only: a dropped flac or mp3 is written as WAV)
	bankFormat {
		var ext = (path.splitext[1] ? "wav").toLower;
		var lib = host.soundExtensions;
		if (lib.includesEqual(ext)) { ^[path.splitext[1], headerFormat, sampleFormat] };
		^["wav", "WAV", if (#["int16", "int24", "int32", "float"].includesEqual(sampleFormat.asString)) { sampleFormat } { "int24" }]
	}

	// the file addToBank would write into folder: <folder>_<nn>.<ext>, nn the
	// number of sound files already there, or the first free one after it
	*nextBankFile { |folder, ext = "wav"|
		var base = folder.withoutTrailingSlash.basename, n, p;
		n = this.soundFilesIn(folder).size;
		while {
			p = folder +/+ "%_%.%".format(base, n.asString.padLeft(2, "0"), ext);
			File.exists(p)
		} { n = n + 1 };
		^p
	}

	// the sound files right inside a folder (not its subfolders), sorted
	*soundFilesIn { |folder|
		var exts = #["wav", "aif", "aiff", "aifc", "flac", "ogg", "mp3", "caf", "w64", "rf64", "wave"];
		if (File.exists(folder).not) { ^[] };
		^this.folderEntries(folder.withoutTrailingSlash).select { |p|
			p.last.isPathSeparator.not and: { exts.includesEqual((p.splitext[1] ? "").toLower) }
		}
	}

	// ----------------------------------------------------------- paths

	// two spellings of one path: separators, a trailing slash, and case on Windows
	*samePath { |a, b|
		^a.notNil and: { b.notNil } and: { this.prNormalPath(a) == this.prNormalPath(b) }
	}

	*prNormalPath { |p|
		p = p.asString.standardizePath.tr($\\, $/);
		while { p.size > 1 and: { p.last == $/ } } { p = p.drop(-1) };
		^if (thisProcess.platform.name == \windows) { p.toLower } { p }
	}

	// the bank a folder makes in SuperDirt: its name, with what Tidal's mini
	// notation would read as something else (spaces above all) as _
	*bankName { |folderPath|
		^folderPath.asString.withoutTrailingSlash.basename.collect { |c|
			if (c.ascii >= 0 and: { c.isAlphaNum.not } and: { "_-.".includes(c).not }) { $_ } { c }
		}.asSymbol
	}

	// what is in a folder, sorted. pathMatch is glob: a folder called Kit [Vol 1]
	// would match nothing unless its brackets are escaped (not on Windows,
	// where a backslash is the separator)
	*folderEntries { |folder|
		if (thisProcess.platform.name != \windows) {
			"\\[]*?".do { |c| folder = folder.escapeChar(c) };
		};
		^(folder +/+ "*").pathMatch.sort
	}

	// the .bak back over the file, kept for next time
	revert {
		if (this.hasBackup.not) { "SampleEdit: no backup of % to revert to".format(path.basename).warn; ^nil };
		File.delete(path);
		File.copy(this.backupPath, path);
		if (File.exists(path).not) {
			"SampleEdit: could not put % back".format(path).warn;
			^nil
		};
		this.prAfterFileChanged;
		this.changed(\reverted, path);
		^path
	}

	prAfterFileChanged {
		host.fileChanged(path);
		start = 0.0;
		end = 1.0;
		gainDb = 0.0;
		removeDC = false;
		fadeIn = 0.0;
		fadeOut = 0.0;
		this.load;
	}

	// ------------------------------------------------------------ the grid

	// The features of the file as it is read (onsets, tempo, key), the whole
	// of it: from a Routine with SampleFeatures.breathing set, it gives the
	// rest of sclang its turns. The grid takes the analysis's bpm when it has none.
	analyseFeatures {
		features = SampleFeatures.analyseChannels(channels, sampleRate);
		if (bpm.isNil and: { features[\bpm].notNil }) {
			bpm = features[\bpm];
			beatOffset = features[\beatOffset] ? 0;
		};
		this.changed(\features);
		^features
	}

	bpm_ { |value|
		bpm = value !? { value.asFloat.clip(20, 999) };
		this.changed(\grid);
	}

	// where the first beat is, seconds; the grid runs both ways from it
	beatOffset_ { |seconds|
		beatOffset = (seconds ? 0).asFloat.clip(0, this.duration.max(0));
		this.changed(\grid);
	}

	beatsPerBar_ { |n| beatsPerBar = n.asInteger.max(1); this.changed(\grid) }

	beatSeconds { ^bpm !? { 60 / bpm } }

	// The grid's lines between two times, seconds: [[time, beat number] ...],
	// one per `division` of a whole note (1/4 a beat, 1/16 a sixteenth); the
	// beat number is fractional between beats, and a bar starts on a multiple
	// of beatsPerBar.
	gridLines { |division = 0.25, from = 0, to|
		var step, k0, k1;
		if (bpm.isNil) { ^[] };
		to = to ? this.duration;
		step = this.beatSeconds * 4 * division;
		if (step <= 0) { ^[] };
		k0 = ((from - beatOffset) / step).ceil.asInteger;
		k1 = ((to - beatOffset) / step).floor.asInteger;
		if (k1 < k0 or: { (k1 - k0) > 20000 }) { ^[] };
		^(k0..k1).collect { |k| [beatOffset + (k * step), k * 4 * division] }
	}

	// the onsets the analysis found at a sensitivity, seconds
	transients { |sensitivity = 0.5|
		if (features.isNil) { ^[] };
		^SampleFeatures.pickOnsets(features[\peaks], sensitivity)
	}

	// where the first hit is, seconds: the first attack at a high sensitivity,
	// or where the sound starts if that is earlier
	firstTransient {
		var t = this.transients(0.8).first, start = features !? { features[\start] };
		^if (t.isNil) { start } { if (start.notNil and: { start < t }) { start } { t } }
	}

	// An onset time, from the analysis's coarse frames, made exact: the first
	// sample within 15 ms either side that reaches 30% of the loudest there,
	// a millisecond earlier, back to where channel 0 crosses zero. A frame.
	refineOnset { |seconds|
		var c = (seconds * sampleRate).round.asInteger, w = (0.015 * sampleRate).asInteger;
		var a = (c - w).clip(0, numFrames - 1), b = (c + w).clip(a, numFrames - 1), m = 0.0, i, ch0 = channels[0], lim;
		(a..b).do { |k| channels.do { |ch| m = max(m, ch[k].abs) } };
		if (m <= 0) { ^c.clip(0, numFrames) };
		i = (a..b).detect { |k| channels.any { |ch| ch[k].abs >= (m * 0.3) } } ? c;
		i = (i - (0.001 * sampleRate).round.asInteger).max(0);
		lim = (i - (0.002 * sampleRate).round.asInteger).max(0);
		while { i > lim and: { (ch0[i] * ch0[(i - 1).max(0)]) > 0 } } { i = i - 1 };
		^i
	}

	printOn { |stream| stream << "SampleEdit(" << label << ")" }
}
