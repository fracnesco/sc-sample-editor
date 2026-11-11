// =====================================================================
// SampleFeatures - what a sample sounds like, as numbers, in the language
//
// Read a file (or take the Signals the editor holds), mix it to mono, bring
// it down to about 22 kHz, and take two short-time spectra of it:
//
//   1024 / 256   the onset curve (spectral flux of the log magnitude), and per
//                frame the energy, the centroid, the flatness and how much is
//                under 150 Hz and over 5 kHz
//   4096 / 2048  the spectral peaks, folded into 12 pitch classes after the
//                tuning of the whole file is taken out: the chroma
//
// and from those, and a fine envelope of the mono signal:
//
//   envelope   attack, decay to -20 dB, how much of it holds, how long it is
//              audible
//   onsets     every peak of the onset curve with how far it stands out, so
//              a sensitivity picks from them without the spectra again
//   tempo      the onset curve's autocorrelation, as a comb over 60..200 bpm,
//              with a bonus for a bpm that fits the file's length in whole
//              beats; a confidence, and the grid's offset
//   key        the chroma against the Krumhansl-Kessler profiles; a one-shot
//              holding one pitch class is a note instead (\note, with an
//              octave), and a sound without pitch has no key at all
//
//   f = SampleFeatures.analyse("~/x.wav");    // an Event, see analyseMono
//   f[\bpm]; f[\bpmSure]; f[\key]; f[\keySure]; f[\attack]; f[\onsets];
//   SampleFeatures.pickOnsets(f[\peaks], 0.7);   // onset times at a sensitivity
//
// Nothing needs the server. Called from a Routine with `breathing` set, every
// loop gives the rest of sclang a turn every `sliceSeconds`, so a background
// analysis never holds up an event for longer than that.
// =====================================================================

SampleFeatures {
	classvar <>version = 1;					// a cached analysis of another version is redone
	classvar <>maxSeconds = 30;				// what analyse reads of a long file
	classvar <>targetRate = 22050;
	classvar <>fftSize = 1024, <>hopSize = 256;
	classvar <>chromaSize = 4096, <>chromaHop = 2048;
	classvar <>minBpm = 60, <>maxBpm = 200;
	classvar <>fluxLag = 2;					// the onset curve compares a frame with the one this far back
	classvar <>salienceFloor = 150;			// an onset curve whose top is lower than this is held to it: a pad's drift is not an attack
	classvar <>breathing = false;			// true inside a background Routine
	classvar <>sliceSeconds = 0.012;
	classvar lastBreath = 0;
	classvar <majorProfile, <minorProfile, <noteNames;

	*initClass {
		majorProfile = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
		minorProfile = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];
		noteNames = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
	}

	// a turn for everything else, from inside a long loop
	*breathe {
		var now;
		if (breathing.not) { ^this };
		now = Main.elapsedTime;
		if ((now - lastBreath) > sliceSeconds and: { thisThread.isKindOf(Routine) }) {
			0.001.wait;
			lastBreath = Main.elapsedTime;
		};
	}

	// ------------------------------------------------------------- reading

	// The file's header and its first `seconds`, mixed and brought down to
	// about targetRate in the same pass (a box filter: the features here do
	// not mind a little aliasing). nil if it cannot be read.
	*read { |path, seconds|
		var sf = SoundFile.openRead(path.standardizePath), sr, nc, total, n, data, factor, out, rate;
		if (sf.isNil) { ^nil };
		sr = sf.sampleRate;
		nc = sf.numChannels.max(1);
		total = sf.numFrames;
		n = total.min(((seconds ? maxSeconds) * sr).asInteger);
		data = Signal.newClear(n * nc);
		if (n > 0) { sf.readData(data) };
		sf.close;
		factor = (sr / targetRate).round.asInteger.max(1);
		rate = sr / factor;
		out = this.prDownmix(data, nc, n, factor);
		^(mono: out, rate: rate, sampleRate: sr, numChannels: nc, numFrames: total,
			duration: if (sr > 0) { total / sr } { 0 }, readFrames: n)
	}

	// interleaved frames -> one Signal at 1/factor of the rate
	*prDownmix { |data, nc, n, factor|
		var m = n div: factor, out = Signal.newClear(m), scale = 1 / (nc * factor);
		m.do { |j|
			var acc = 0.0, base = j * factor * nc;
			(factor * nc).do { |k| acc = acc + data[base + k] };
			out[j] = acc * scale;
			if ((j & 16383) == 0) { this.breathe };
		};
		^out
	}

	// the same from the Signals the editor holds, one per channel
	*fromChannels { |channels, sampleRate, seconds|
		var nc = channels.size.max(1), total = channels.first.size;
		var n = if (seconds.isNil) { total } { total.min((seconds * sampleRate).asInteger) };
		var factor = (sampleRate / targetRate).round.asInteger.max(1);
		var m = n div: factor, out = Signal.newClear(m), scale = 1 / (nc * factor);
		m.do { |j|
			var acc = 0.0, base = j * factor;
			channels.do { |ch| factor.do { |k| acc = acc + ch[base + k] } };
			out[j] = acc * scale;
			if ((j & 16383) == 0) { this.breathe };
		};
		^(mono: out, rate: sampleRate / factor, sampleRate: sampleRate, numChannels: nc,
			numFrames: total, duration: total / sampleRate, readFrames: n)
	}

	// ------------------------------------------------------------- analysis

	*analyse { |path, seconds|
		var r = this.read(path, seconds);
		if (r.isNil) { ^nil };
		^this.analyseRead(r)
	}

	*analyseChannels { |channels, sampleRate, seconds|
		^this.analyseRead(this.fromChannels(channels, sampleRate, seconds))
	}

	*analyseRead { |r|
		var f = this.analyseMono(r[\mono], r[\rate], r[\duration]);
		f.putAll((
			sampleRate: r[\sampleRate], numChannels: r[\numChannels], numFrames: r[\numFrames],
			duration: r[\duration], partial: r[\readFrames] < r[\numFrames], version: version
		));
		^f
	}

	// Everything, from a mono Signal at `rate`; `duration` is the whole
	// file's, which the tempo's length fit wants even when only the start
	// was read. Answers an Event:
	//   peak, rms, attack, decay, sustain, audible, start, crest
	//   centroid, flatness, low, high          energy-weighted over the sound
	//   odf, hopSeconds                         the onset curve
	//   peaks                                   [[time, salience] ...] of its peaks
	//   onsets                                  their times at sensitivity 0.5
	//   bpm, bpmConfidence, bpmSure, beatOffset, lengthFit   (bpm nil: no tempo)
	//   chroma, tonal, key, tonic, mode, octave, keyConfidence, keySure (key nil: no pitch)
	*analyseMono { |mono, rate, duration|
		var env = this.envelope(mono, rate);
		var spec = this.spectra(mono, rate);
		var peaks = this.onsetPeaks(spec[\odf], spec[\hopSeconds]);
		var onsets = this.pickOnsets(peaks, 0.5);
		var chroma = this.chroma(mono, rate);
		var f = ();
		f.putAll(env);
		f.putAll(spec);
		f[\peaks] = peaks;
		f[\onsets] = onsets;
		f.putAll(this.tempo(spec[\beatCurve], spec[\hopSeconds], duration ? (mono.size / rate), onsets, env, spec[\odf]));
		f.putAll(this.key(chroma, f, onsets));
		^f
	}

	// --------------------------------------------------------------- envelope

	// The peak of |x| over 64-sample steps, and what it says about the shape:
	//   attack    from 10% of the peak to 90% of it, seconds
	//   decay     from the peak to the first point 20 dB under it
	//   sustain   the share of the audible stretch within 15 dB of the peak
	//   audible   from the first to the last point within 40 dB of the peak
	//   crest     peak over the mean of the envelope over that stretch
	*envelope { |mono, rate|
		var step = 64, n = mono.size div: step, env = Signal.newClear(n.max(1));
		var peak = 0.0, peakAt = 0, sq = 0.0, a10, a90, s40, e40, e20, d20, held = 0, sum = 0.0, raw;
		var t = { |i| i * step / rate };
		n.do { |i|
			var m = 0.0, base = i * step;
			step.do { |k| var x = mono[base + k].abs; if (x > m) { m = x }; };
			env[i] = m;
			if ((i & 4095) == 0) { this.breathe };
		};
		// the highest point of the 9 steps around each, so a low note's cycles
		// (a 50 Hz period is seven steps) do not read as dips
		raw = env;
		env = Signal.newClear(n.max(1));
		n.do { |i|
			var m = 0.0;
			((i - 4).max(0)..(i + 4).min(n - 1)).do { |j| if (raw[j] > m) { m = raw[j] } };
			env[i] = m;
			if (m > peak) { peak = m; peakAt = i };
		};
		mono.do { |x| sq = sq + (x * x) };
		if (peak <= 1e-6) {
			^(peak: 0, rms: 0, attack: 0, decay: 0, sustain: 0, audible: 0, length: 0, start: 0, crest: 1, env: env, envStep: step / rate)
		};
		a10 = (0..peakAt).detect { |i| env[i] >= (peak * 0.1) } ? 0;
		a90 = (a10..peakAt).detect { |i| env[i] >= (peak * 0.9) } ? peakAt;
		s40 = (0..peakAt).detect { |i| env[i] >= (peak * 0.01) } ? 0;
		e40 = n - 1;
		while { e40 > peakAt and: { env[e40] < (peak * 0.01) } } { e40 = e40 - 1 };
		d20 = (peakAt..(n - 1)).detect { |i| env[i] < (peak * 0.1) } ? n;
		e20 = e40;
		while { e20 > peakAt and: { env[e20] < (peak * 0.1) } } { e20 = e20 - 1 };
		(s40..e40).do { |i| sum = sum + env[i]; if (env[i] >= (peak * 0.178)) { held = held + 1 } };
		^(
			peak: peak,
			rms: (sq / mono.size.max(1)).sqrt,
			attack: t.(a90 - a10).max(0),
			decay: t.(d20 - peakAt),
			sustain: held / (e40 - s40 + 1),
			audible: t.(e40 - s40 + 1),
			length: t.(e20 - s40 + 1),
			start: t.(s40),
			crest: peak / (sum / (e40 - s40 + 1)).max(1e-9),
			env: env,
			envStep: step / rate
		)
	}

	// ---------------------------------------------------------------- spectra

	// The 1024-point frames, centred on every hop: the onset curve and the
	// spectral shape, energy-weighted over the frames within 40 dB of the
	// loudest so a tail of silence does not move them.
	*spectra { |mono, rate|
		var size = fftSize, hop = hopSize, half = size div: 2, pad = Signal.newClear(half);
		var padded = pad ++ mono ++ Signal.newClear(size);
		var numFrames = (mono.size div: hop) + 1;
		var cosTable = Signal.fftCosTable(size), win = Signal.hanningWindow(size), im = Signal.newClear(size);
		var freqs = Signal.fill(half, { |k| k * rate / size });
		var lowMask = Signal.fill(half, { |k| if ((k * rate / size) < 150) { 1.0 } { 0.0 } });
		var highMask = Signal.fill(half, { |k| if ((k * rate / size) > 5000) { 1.0 } { 0.0 } });
		var odf = Signal.newClear(numFrames), beat = Signal.newClear(numFrames), energy = Signal.newClear(numFrames);
		var cent = Signal.newClear(numFrames), flat = Signal.newClear(numFrames);
		var low = Signal.newClear(numFrames), high = Signal.newClear(numFrames);
		var history = { Signal.newClear(half) } ! fluxLag.max(1), norm = 1 / (size / 4), eMax, gate, wSum = 0.0;
		var kl0 = (30 * size / rate).floor.asInteger.max(1), kl1 = (400 * size / rate).ceil.asInteger;
		var lowPeak = Signal.newClear(numFrames), loudest, swept, flats;
		var out = (centroid: 0.0, flatness: 0.0, low: 0.0, high: 0.0);
		numFrames.do { |k|
			var frame = padded.copyRange(k * hop, (k * hop) + size - 1) * win;
			var c = frame.fft(im, cosTable);
			var re = c.real.copyRange(0, half - 1), ii = c.imag.copyRange(0, half - 1);
			var pow = (re.squared + ii.squared) * (norm * norm);
			var lm = (pow.sqrt * 1000 + 1).log;
			var e = pow.sum;
			odf[k] = (lm - history[0]).max(0).sum;
			beat[k] = (lm - history.last).max(0).sum;
			history = history.drop(1).add(lm);
			energy[k] = e;
			if (e > 1e-12) {
				cent[k] = (pow * freqs).sum / e;
				flat[k] = exp(((pow + 1e-12).log).sum / half) / (e / half);
				low[k] = (pow * lowMask).sum / e;
				high[k] = (pow * highMask).sum / e;
				// the strongest bin under 400 Hz, interpolated: a kick's falling pitch
				lowPeak[k] = this.prPeakFreq(pow, kl0, kl1, rate, size);
			};
			this.breathe;
		};
		// for the tempo, the first frame rising from nothing is not a beat: a file
		// that starts on a sound would otherwise pull the autocorrelation round it
		beat[0] = 0;
		eMax = energy.maxItem ? 0;
		gate = eMax * 1e-4;
		numFrames.do { |k|
			var e = energy[k];
			if (e > gate) {
				wSum = wSum + e;
				out[\centroid] = out[\centroid] + (cent[k] * e);
				out[\flatness] = out[\flatness] + (flat[k] * e);
				out[\low] = out[\low] + (low[k] * e);
				out[\high] = out[\high] + (high[k] * e);
			};
		};
		if (wSum > 0) { out.keysDo { |key| out[key] = out[key] / wSum } };
		// the median flatness of the frames within 30 dB: a snare's noise tail
		// counts as much as its tuned body
		flats = (0..(numFrames - 1)).select { |k| energy[k] > (eMax * 1e-3) }.collect { |k| flat[k] }.sort;
		out[\flatnessMedian] = if (flats.isEmpty) { 0 } { flats[flats.size div: 2] };
		// how far the low peak falls over the 250 ms from the loudest frame, of
		// the frames within 20 dB that are mostly low: 1 for a held note
		loudest = energy.maxIndex ? 0;
		swept = ((loudest..(loudest + (0.25 * rate / hop).round.asInteger).min(numFrames - 1))).select { |k|
			energy[k] > (eMax * 0.01) and: { low[k] > 0.3 } and: { lowPeak[k] > 0 }
		}.collect { |k| lowPeak[k] };
		out[\sweep] = if (swept.size < 2) { 1 } { swept.maxItem / swept.minItem.max(1) };
		out[\odfTop] = odf.maxItem ? 0;
		out[\odf] = odf;
		out[\beatCurve] = beat;
		out[\energy] = energy;
		out[\hopSeconds] = hop / rate;
		^out
	}

	*prPeakFreq { |pow, k0, k1, rate, size|
		var best = k0, bv = 0, a, b, c, d;
		(k0..k1.min(pow.size - 2)).do { |k| if (pow[k] > bv) { bv = pow[k]; best = k } };
		if (bv <= 0 or: { best < 1 }) { ^0 };
		a = pow[best - 1].sqrt; b = bv.sqrt; c = pow[best + 1].sqrt;
		d = a - (2 * b) + c;
		d = if (d.abs > 1e-12) { (0.5 * (a - c) / d).clip(-0.5, 0.5) } { 0 };
		^(best + d) * rate / size
	}

	// ----------------------------------------------------------------- onsets

	// Every local maximum of the onset curve (over 3 frames either side), with
	// its salience: how far it stands over the median of the half second
	// around it, as a share of the curve's highest point. [[seconds, salience]]
	// sorted by time. The time is half a hop before the frame, where the
	// rise began.
	*onsetPeaks { |odf, hopSeconds|
		var n = odf.size, top = (odf.maxItem ? 0).max(salienceFloor), w = (0.25 / hopSeconds).round.asInteger.max(3);
		var out = [];
		if (top <= 0) { ^out };
		// (0..(n - 2)) would count down for one frame: an Integer's do never does
		(n - 1).do { |k|
			var v = odf[k], isMax = true, lo, hi, med;
			(1..3).do { |d|
				if (k - d >= 0 and: { odf[k - d] > v }) { isMax = false };
				if (k + d < n and: { odf[k + d] >= v }) { isMax = false };
			};
			if (isMax and: { v > (top * 0.02) }) {
				lo = (k - w).max(0);
				hi = (k + w).min(n - 1);
				med = odf.copyRange(lo, hi).asArray.sort[(hi - lo) div: 2];
				out = out.add([((k - 0.5) * hopSeconds).max(0), ((v - med) / top).max(0)]);
			};
			if ((k & 1023) == 0) { this.breathe };
		};
		^out
	}

	// The onset times whose salience clears what a sensitivity asks: 0 keeps
	// only the strongest attacks, 1 nearly every bump. Two onsets closer than
	// minGap keep the stronger one.
	*pickOnsets { |peaks, sensitivity = 0.5, minGap = 0.04|
		var thr = this.thresholdFor(sensitivity), kept = [];
		(peaks ? []).do { |p|
			if (p[1] >= thr) {
				if (kept.notEmpty and: { (p[0] - kept.last[0]) < minGap }) {
					if (p[1] > kept.last[1]) { kept[kept.size - 1] = p };
				} {
					kept = kept.add(p);
				};
			};
		};
		^kept.collect(_[0])
	}

	// sensitivity 0..1 -> the salience an onset needs, 0.6 .. 0.01, on a log scale
	*thresholdFor { |sensitivity| ^sensitivity.clip(0, 1).linexp(0, 1, 0.6, 0.01) }

	// ------------------------------------------------------------------ tempo

	// Only for something with a few onsets and a second and a half of sound:
	// a one-shot has no tempo, which is not the same as an unsure one.
	*tempo { |odf, hopSeconds, duration, onsets, env, phaseCurve|
		var none = (bpm: nil, bpmConfidence: 0, bpmSure: false, beatOffset: 0, lengthFit: false);
		var n = odf.size, x, mean, maxLag, ac, score, cands, best, bestScore, fit, med;
		var lagOf = { |bpm| 60 / bpm / hopSeconds };
		var acAt, combAt, prior, competitor, conf, phase, sure, rivals;
		if (onsets.size < 4 or: { (env[\audible] ? 0) < 1.0 }) { ^none };
		// the curve less its local mean, half-wave rectified: the beats, not the level
		x = Signal.newClear(n);
		n.do { |k|
			var lo = (k - 16).max(0), hi = (k + 16).min(n - 1), s = 0.0;
			(lo..hi).do { |j| s = s + odf[j] };
			x[k] = (odf[k] - (s / (hi - lo + 1))).max(0);
			if ((k & 1023) == 0) { this.breathe };
		};
		maxLag = (lagOf.(minBpm) * 4.2).ceil.asInteger.min(n - 2);
		if (maxLag < (lagOf.(maxBpm) * 2)) { ^none };
		ac = Signal.newClear(maxLag + 2);
		(1..maxLag).do { |l|
			ac[l] = (x.copyRange(0, n - 1 - l) * x.copyRange(l, n - 1)).sum / (n - l);
			if ((l & 31) == 0) { this.breathe };
		};
		mean = ac.copyRange(1, maxLag).sum / maxLag;
		acAt = { |lag|
			var i = lag.floor.asInteger, fr = lag - i;
			if (i < 1 or: { i >= maxLag }) { 0 } { (ac[i] * (1 - fr)) + (ac[i + 1] * fr) }
		};
		// a beat, two, three, four: a comb, so the tempo and not a subdivision wins
		combAt = { |bpm|
			var l = lagOf.(bpm), s = 0.0;
			[1, 2, 3, 4].do { |k| if ((l * k) < maxLag) { s = s + (acAt.(l * k) / k.sqrt) } };
			s
		};
		prior = { |bpm| exp(-0.5 * (log2(bpm / 120) / 0.9).squared) };
		cands = (minBpm, minBpm + 0.25 .. maxBpm);
		score = cands.collect { |b| combAt.(b) * prior.(b) };
		this.breathe;
		best = score.maxIndex;
		bestScore = score[best];
		if (bestScore <= 0) { ^none };
		best = cands[best];
		// a whole number of beats in the file - what a loop cut on the beat has -
		// wins when it scores nearly as well as the free choice
		fit = false;
		if (duration > 1.0) {
			var fitBest, fitScore = 0;
			(1..256).do { |beats|
				var b = beats * 60 / duration, s;
				if (b >= minBpm and: { b <= maxBpm }) {
					s = combAt.(b) * prior.(b) * if ((beats % 4) == 0) { 1.12 } { 1.0 };
					if (s > fitScore) { fitScore = s; fitBest = b };
				};
			};
			if (fitBest.notNil and: { fitScore >= (bestScore * 0.9) }) { best = fitBest; fit = true };
		};
		// how far the winner stands out, and whether something unrelated comes close
		med = score.copy.sort[score.size div: 2];
		conf = ((combAt.(best) * prior.(best)) - med) / (combAt.(best) * prior.(best)).max(1e-12);
		rivals = cands.select { |b|
			var r = log2(b / best), d = [0, 1, -1, log2(1.5), log2(2/3), log2(3), log2(1/3)].collect { |o| (r - o).abs }.minItem;
			d > 0.06
		};
		competitor = rivals.collect { |b| combAt.(b) * prior.(b) }.maxItem ? 0;
		competitor = competitor / (combAt.(best) * prior.(best)).max(1e-12);
		sure = (conf > 0.45 and: { competitor < 0.75 }) or: { fit and: { conf > 0.3 } and: { competitor < 0.9 } };
		// the phase from the onset curve, which keeps the attack a file starts on
		phase = this.beatPhase(phaseCurve ? x, lagOf.(best), ((env[\start] ? 0) / hopSeconds).round.asInteger);
		^(
			bpm: best.round(0.01),
			bpmConfidence: (conf * (1 - (competitor * 0.5))).clip(0, 1),
			bpmSure: sure,
			beatOffset: (phase - 0.5).max(0) * hopSeconds,
			lengthFit: fit
		)
	}

	// The frame, within one beat, where a grid of `lag` frames lands on the
	// most onset - unless the grid through where the sound starts lands on
	// nearly as much: a loop is cut on its downbeat far more often than on an
	// offbeat, though a hat may be louder there than the kick.
	*beatPhase { |x, lag, startFrame = 0|
		var n = x.size, steps = lag.ceil.asInteger.max(1), scores, best, s0, p0;
		scores = steps.collect { |p|
			var s = 0.0, t = p.asFloat;
			while { t < n } { s = s + x[t.round.asInteger.min(n - 1)]; t = t + lag };
			s
		};
		best = scores.maxIndex ? 0;
		// the start's phase, give or take two frames
		p0 = (startFrame % lag).round.asInteger;
		s0 = (-2..2).collect { |d| scores.clipAt(p0 + d) }.maxItem;
		^if (s0 >= (scores[best] * 0.1)) { (-2..2).collect { |d| p0 + d }.clip(0, steps - 1).maxItem { |p| scores[p] } } { best }
	}

	// -------------------------------------------------------------------- key

	// The spectral peaks of 4096-point frames, 55 Hz .. 4 kHz, each at its
	// interpolated frequency; the tuning of the whole file (how far its peaks
	// sit, on average, between two semitones) taken out; folded into pitch
	// classes. Answers (chroma: 12 weights summing to 1, lowest: [pc, Hz] of
	// the lowest strong peak per pitch class, strength: how much went in).
	*chroma { |mono, rate|
		var size = chromaSize, hop = chromaHop, half = size div: 2;
		var cosTable = Signal.fftCosTable(size), win = Signal.hanningWindow(size), im = Signal.newClear(size);
		var padded = mono ++ Signal.newClear(size);
		var k0 = (55 * size / rate).floor.asInteger.max(2), k1 = (4000 * size / rate).ceil.asInteger.min(half - 2);
		var peaks = [], frames = ((mono.size - size).max(0) div: hop) + 1, energies, eMax;
		var devX = 0.0, devY = 0.0, dev, chroma = 0.0 ! 12, lowest = nil ! 12, total;
		energies = frames.collect { |f| padded.copyRange(f * hop, (f * hop) + size - 1).squared.sum };
		eMax = energies.maxItem ? 0;
		frames.do { |f|
			var frame, c, mag, top;
			if (energies[f] > (eMax * 0.001)) {
				frame = padded.copyRange(f * hop, (f * hop) + size - 1) * win;
				c = frame.fft(im, cosTable);
				mag = (c.real.copyRange(0, half - 1).squared + c.imag.copyRange(0, half - 1).squared).sqrt;
				top = mag.copyRange(k0, k1).maxItem;
				(k0..k1).do { |k|
					var a = mag[k - 1], b = mag[k], d = mag[k + 1], la, lb, ld, delta, freq, p;
					if (b > a and: { b >= d } and: { b > (top * 0.05) }) {
						la = log(a + 1e-9); lb = log(b + 1e-9); ld = log(d + 1e-9);
						delta = 0.5 * (la - ld) / (la - (2 * lb) + ld).min(-1e-9);
						freq = (k + delta.clip(-0.5, 0.5)) * rate / size;
						p = (12 * log2(freq / 440)) + 69;
						peaks = peaks.add([p, b / top, freq]);
					};
				};
			};
			this.breathe;
		};
		if (peaks.isEmpty) { ^(chroma: chroma, lowest: lowest, strength: 0, tuning: 0) };
		peaks.do { |pk| var a = (pk[0] - pk[0].round) * 2pi; devX = devX + (cos(a) * pk[1]); devY = devY + (sin(a) * pk[1]) };
		dev = atan2(devY, devX) / 2pi;
		peaks.do { |pk|
			var pc = (pk[0] - dev).round.asInteger % 12;
			chroma[pc] = chroma[pc] + pk[1];
			if (pk[1] > 0.3 and: { lowest[pc].isNil or: { pk[2] < lowest[pc] } }) { lowest[pc] = pk[2] };
		};
		total = chroma.sum;
		^(chroma: chroma / total.max(1e-12), lowest: lowest, strength: total / frames.max(1), tuning: dev)
	}

	*correlate { |a, b|
		var ma = a.sum / a.size, mb = b.sum / b.size, num = 0.0, da = 0.0, db = 0.0;
		a.size.do { |i| var x = a[i] - ma, y = b[i] - mb; num = num + (x * y); da = da + (x * x); db = db + (y * y) };
		^if (da > 0 and: { db > 0 }) { num / (da * db).sqrt } { 0 }
	}

	// The key, or the note of a one-shot, or nothing for a sound without
	// pitch. tonal is 0..1: how far the chroma is from even (its entropy)
	// and the spectrum from noise (its flatness) together.
	*key { |ch, f, onsets|
		var chroma = ch[\chroma], none, entropy, tonal, top, share, rs, best, second, rel, scores;
		none = (chroma: chroma, tonal: 0, key: nil, tonic: nil, mode: nil, octave: nil, keyConfidence: 0, keySure: false, tuning: ch[\tuning] ? 0);
		if (chroma.sum <= 0) { ^none };
		entropy = chroma.sum { |c| if (c > 0) { c.neg * log(c) } { 0 } } / log(12);
		tonal = ((1 - entropy) * 2).clip(0, 1) * (1 - ((f[\flatness] ? 0) * 4)).clip(0, 1);
		none[\tonal] = tonal;
		if (tonal < 0.12 or: { (f[\audible] ? 0) < 0.06 }) { ^none };
		top = chroma.maxIndex;
		share = chroma[top];
		none[\pcShare] = share;
		// one pitch class, one attack: a note, named with the octave of its lowest strong partial
		if (share >= 0.35 or: { onsets.size <= 1 and: { share > 0.3 } }) {
			^none.putAll((
				key: noteNames[top] ++ ((ch[\lowest][top] !? { |hz| (hz.cpsmidi.round.asInteger div: 12) - 1 }) ? ""),
				tonic: top, mode: \note,
				octave: ch[\lowest][top] !? { |hz| (hz.cpsmidi.round.asInteger div: 12) - 1 },
				keyConfidence: (share * 1.5).clip(0, 1) * tonal.sqrt,
				keySure: share > 0.4 and: { tonal > 0.3 }
			))
		};
		scores = 24.collect { |i|
			var tonic = i % 12, profile = if (i < 12) { majorProfile } { minorProfile };
			this.correlate(chroma, profile.rotate(tonic))
		};
		best = scores.maxIndex;
		rel = if (best < 12) { ((best + 9) % 12) + 12 } { (best + 3) % 12 };
		rs = scores.collect { |s, i| if (i == best or: { i == rel }) { -1 } { s } };
		second = rs.maxItem;
		^none.putAll((
			key: noteNames[best % 12] ++ if (best >= 12) { "m" } { "" },
			tonic: best % 12, mode: if (best >= 12) { \minor } { \major },
			keyConfidence: (scores[best] * (scores[best] - second) * 4).clip(0, 1) * tonal.sqrt,
			keySure: scores[best] > 0.7 and: { (scores[best] - second) > 0.08 } and: { tonal > 0.25 }
		))
	}

	// ----------------------------------------------------------------- names

	// "Am", "F#", "a", "fsm", "bf", "A3" -> (tonic: 0..11, mode: \major, \minor or \note)
	// - the spellings Tidal can send too: s for sharp, f for flat, m for minor
	*parseKey { |str|
		var s = str.asString.toLower.replace(" ", ""), tonic, rest, mode;
		var base = (c: 0, d: 2, e: 4, f: 5, g: 7, a: 9, b: 11);
		if (s.isEmpty) { ^nil };
		tonic = base[s[0].asSymbol];
		if (tonic.isNil) { ^nil };
		rest = s.copyToEnd(1);
		case
		{ rest.beginsWith("#") or: { rest.beginsWith("s") and: { rest.beginsWith("sus").not } } } { tonic = tonic + 1; rest = rest.copyToEnd(1) }
		{ rest.beginsWith("b") or: { rest.beginsWith("f") } } { tonic = tonic - 1; rest = rest.copyToEnd(1) };
		mode = case
		{ rest.isEmpty or: { rest == "maj" } or: { rest == "major" } } { \major }
		{ rest == "m" or: { rest == "min" } or: { rest == "minor" } } { \minor }
		{ rest.every(_.isDecDigit) } { \note }
		{ nil };
		if (mode.isNil) { ^nil };
		^(tonic: tonic % 12, mode: mode)
	}
}
