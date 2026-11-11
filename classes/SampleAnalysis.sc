// =====================================================================
// SampleAnalysis - the numbers the sample editor shows, in the language
//
// Everything here runs on Signals in sclang: no server, no plugins, and
// exact on the samples the file holds. Two passes over the audio are kept,
// and every readout after that is arithmetic on them:
//
//   stats     per channel: min, max, sum, sum of squares, clipped count -
//             enough to derive peak, rms, crest and DC offset for any gain
//             and with or without the DC taken out, without touching the
//             samples again
//   kHops     per channel: the mean square of the K-weighted signal in every
//             20 ms hop (ITU-R BS.1770 - the same shelf and high pass
//             Insight runs on the server), from which the loudness of any
//             stretch is a gated average of 400 ms blocks
//
//   st = SampleAnalysis.stats(channels, 0, numFrames);
//   SampleAnalysis.metrics(st, gainDb: -6, removeDC: true);   // (peak:, rms:, crest:, dc: ...)
//   h = SampleAnalysis.kHops(channels, 44100);
//   SampleAnalysis.loudness(h, 44100, 0, numFrames);          // (integrated:, momentaryMax: ...)
//
// A stretch shorter than a block is measured as one block over what there
// is and says so (`whole: true`) - a drum hit has a loudness too, just not
// the spec's.
// =====================================================================

SampleAnalysis {
	classvar <>hopSeconds = 0.02;				// the fine hop the K-weighted power is kept at
	classvar <>blockSeconds = 0.4;				// a loudness block, BS.1770
	classvar <>blockHopSeconds = 0.1;			// blocks overlap by three quarters
	classvar <>absoluteGate = -70;				// LUFS: below this a block is not a block
	classvar <>relativeGate = -10;				// LU under the mean, for the integrated value
	classvar <>maxLoudnessSeconds = 120;		// the K-weighting pass stops here

	// ------------------------------------------------------------ k-weighting

	// [shelf, highpass], each [b0, b1, b2, a1, a2] normalised by a0 - the
	// second order sections of the K-weighting curve, for any sample rate
	*kCoefficients { |sampleRate = 48000|
		^[
			this.prBiquad(\highShelf, 1500, 4, 1 / 2.sqrt, sampleRate),
			this.prBiquad(\highPass, 38, 0, 0.5, sampleRate)
		]
	}

	*prBiquad { |type, fc, gainDb, q, sr|
		var a = 10 ** (gainDb / 40), w0 = 2pi * fc / sr;
		var cw = cos(w0), alpha = sin(w0) / (2 * q), sa = a.sqrt;
		var b0, b1, b2, a0, a1, a2;
		if (type == \highShelf) {
			b0 = a * ((a + 1) + ((a - 1) * cw) + (2 * sa * alpha));
			b1 = -2 * a * ((a - 1) + ((a + 1) * cw));
			b2 = a * ((a + 1) + ((a - 1) * cw) - (2 * sa * alpha));
			a0 = (a + 1) - ((a - 1) * cw) + (2 * sa * alpha);
			a1 = 2 * ((a - 1) - ((a + 1) * cw));
			a2 = (a + 1) - ((a - 1) * cw) - (2 * sa * alpha);
		} {
			b0 = (1 + cw) * 0.5;
			b1 = (1 + cw).neg;
			b2 = (1 + cw) * 0.5;
			a0 = 1 + alpha;
			a1 = -2 * cw;
			a2 = 1 - alpha;
		};
		^[b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0]
	}

	*hopFrames { |sampleRate| ^(hopSeconds * sampleRate).round.asInteger.max(1) }

	// one FloatArray per channel: the mean square of the K-weighted signal
	// in every hop, over the first maxLoudnessSeconds at most
	*kHops { |channels, sampleRate = 44100|
		var coefs = this.kCoefficients(sampleRate);
		var hop = this.hopFrames(sampleRate);
		var n = channels.first.size.min((maxLoudnessSeconds * sampleRate).asInteger);
		var numHops = n div: hop;
		var sh = coefs[0], hp = coefs[1];
		var b0 = sh[0], b1 = sh[1], b2 = sh[2], a1 = sh[3], a2 = sh[4];
		var c0 = hp[0], c1 = hp[1], c2 = hp[2], d1 = hp[3], d2 = hp[4];
		^channels.collect { |ch|
			var out = FloatArray.newClear(numHops);
			var z1 = 0.0, z2 = 0.0, w1 = 0.0, w2 = 0.0;
			numHops.do { |h|
				var acc = 0.0, base = h * hop;
				hop.do { |k|
					var x = ch[base + k], y, y2;
					// two biquads, direct form II transposed
					y = (b0 * x) + z1;
					z1 = (b1 * x) - (a1 * y) + z2;
					z2 = (b2 * x) - (a2 * y);
					y2 = (c0 * y) + w1;
					w1 = (c1 * y) - (d1 * y2) + w2;
					w2 = (c2 * y) - (d2 * y2);
					acc = acc + (y2 * y2);
				};
				out[h] = acc / hop;
			};
			out
		}
	}

	// L, R, C at 1, the LFE left out, the surrounds at +1.5 dB for a 5.1
	// layout; anything else evenly, as the spec does for mono and stereo
	*weightsFor { |n| ^if (n == 6) { [1, 1, 1, 0, 1.41, 1.41] } { 1 ! n } }

	// The loudness of [fromFrame, toFrame) from the hops: 400 ms blocks every
	// 100 ms, gated at -70 LUFS and then at 10 LU under the mean of what is
	// left, averaged - and the loudest single block. A gain in dB is applied
	// before the gates, so the reading is of the sample as it would be saved.
	// Answers nil when the stretch holds no whole hop.
	*loudness { |hops, sampleRate = 44100, fromFrame = 0, toFrame, gainDb = 0|
		var hop = this.hopFrames(sampleRate);
		var nc = hops.size, total = hops.first.size;
		var h0, h1, perBlock, stride, gain, weights, blocks, whole = false;
		var lufs, blockPower, above, mean, gated, integrated, powerMean;
		if (nc < 1 or: { total < 1 }) { ^nil };
		toFrame = toFrame ?? { total * hop };
		h0 = (fromFrame / hop).ceil.asInteger.clip(0, total);
		h1 = (toFrame / hop).floor.asInteger.clip(0, total);
		if ((h1 - h0) < 1) { ^nil };
		perBlock = (blockSeconds / hopSeconds).round.asInteger.max(1);
		stride = (blockHopSeconds / hopSeconds).round.asInteger.max(1);
		gain = gainDb.dbamp.squared;
		weights = this.weightsFor(nc);
		lufs = { |ms| if (ms > 0) { -0.691 + (10 * ms.log10) } { -inf } };
		powerMean = { |ls| ls.collect { |l| 10 ** ((l + 0.691) / 10) }.sum / ls.size };
		blockPower = { |a, b|
			var sum = 0.0;
			nc.do { |c|
				var hs = hops[c], s = 0.0;
				(b - a).do { |k| s = s + hs[a + k] };
				sum = sum + (weights[c] * s / (b - a));
			};
			sum * gain
		};
		if ((h1 - h0) < perBlock) {
			whole = true;
			blocks = [blockPower.(h0, h1)];
		} {
			blocks = [];
			h0.forBy(h1 - perBlock, stride) { |a| blocks = blocks.add(blockPower.(a, a + perBlock)) };
		};
		blocks = blocks.collect(lufs);
		above = blocks.select(_ > absoluteGate);
		integrated = if (above.isEmpty) { -inf } {
			mean = lufs.(powerMean.(above));
			gated = above.select(_ > (mean + relativeGate));
			if (gated.isEmpty) { -inf } { lufs.(powerMean.(gated)) }
		};
		^(integrated: integrated, momentaryMax: blocks.maxItem, numBlocks: blocks.size, whole: whole)
	}

	// -------------------------------------------------------------- the rest

	// per channel over [from, to): (min:, max:, sum:, sumsq:, clipped:, n:)
	*stats { |channels, from = 0, to|
		^channels.collect { |ch|
			var a = from.clip(0, ch.size), b = (to ? ch.size).clip(a, ch.size);
			var mn = 0.0, mx = 0.0, sum = 0.0, sq = 0.0, clipped = 0;
			(b - a).do { |k|
				var x = ch[a + k];
				if (x < mn) { mn = x };
				if (x > mx) { mx = x };
				sum = sum + x;
				sq = sq + (x * x);
				if (x.abs >= 1.0) { clipped = clipped + 1 };
			};
			(min: mn, max: mx, sum: sum, sumsq: sq, clipped: clipped, n: b - a)
		}
	}

	// What the stats come to with a gain applied and, optionally, the DC of
	// each channel taken out - worked out on paper, since both are linear:
	//   peak       the loudest sample, dBFS, and its amplitude
	//   rms        over every channel, dBFS
	//   crest      peak - rms, dB
	//   dc         the offset per channel before anything is done to it
	//   dcAfter    what is left of it
	//   overs      true when the peak would pass full scale
	//   clipped    samples already at or past full scale in the source
	*metrics { |stats, gainDb = 0, removeDC = false|
		var g = gainDb.dbamp, peak = 0.0, sumsq = 0.0, n = 0, clipped = 0, rms, dc;
		dc = stats.collect { |st| if (st[\n] > 0) { st[\sum] / st[\n] } { 0.0 } };
		stats.do { |st, c|
			var off = if (removeDC) { dc[c] } { 0.0 };
			var p = max((st[\max] - off).abs, (st[\min] - off).abs) * g;
			var sq = if (removeDC) { st[\sumsq] - (st[\n] * off * off) } { st[\sumsq] };
			peak = max(peak, p);
			sumsq = sumsq + (sq.max(0.0) * g * g);
			n = n + st[\n];
			clipped = clipped + st[\clipped];
		};
		rms = if (n > 0 and: { sumsq > 0 }) { 10 * (sumsq / n).log10 } { -inf };
		^(
			peak: if (peak > 0) { peak.ampdb } { -inf },
			peakAmp: peak,
			rms: rms,
			crest: if (peak > 0 and: { rms > -inf }) { peak.ampdb - rms } { 0 },
			dc: dc,
			dcAfter: if (removeDC) { 0.0 ! dc.size } { dc },
			overs: peak > 1.0,
			clipped: clipped
		)
	}

	// the gain, dB, that brings a peak amplitude to a ceiling
	*gainToCeiling { |peakAmp, ceilingDb = -1|
		^if (peakAmp > 0) { ceilingDb - peakAmp.ampdb } { 0 }
	}

	// a reading as a window shows it
	*dbString { |db, unit = " dB"|
		^if (db.isNil) { "-" } { if (db == -inf) { "-inf" } { db.round(0.1).asString ++ unit } }
	}
}
