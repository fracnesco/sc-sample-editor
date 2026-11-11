// =====================================================================
// SampleWaveView - the sample editor's waveform: one lane per channel, zoomable
//
//   top strip      drag the S / E markers - the trim
//   bottom strip   drag the fade in / fade out handles
//   middle         drag the whole trim along
//
// The zoom is the one the OneShot and LongSample editors have:
//
//   cmd + two fingers up / down   zoom around the pointer (wheel too)
//   two fingers up / down         slide the view along
//   two fingers left / right      slide the view along
//   double click                  the whole file again
//   alt + double click            zoom to the trim
//   alt + drag                    slide the beat grid along (when there is one)
//   shift + click                 a cut for the slicer; on a cut, takes it away
//   cmd + click (ctrl elsewhere)  a slice in or out of the slicer's keep
//
// It draws a SampleEdit: the gain is drawn into the wave, the DC of
// each channel is a dashed line until it is taken out, and the fades are
// the envelope over the top. Everything outside the trim is darkened -
// it will not be in the file.
//
// With a bpm on the edit, the beat grid is drawn over the lanes: bars
// strongest, then beats, then the division the slicer uses, each only when
// there is room for it. With a slicer, its cuts are lines numbered along the
// top of the lanes, every other slice shaded, and in transients mode the
// onset curve the cuts come from runs faintly along the bottom.
// =====================================================================

SampleWaveView {
	classvar <>zoomPerNotch = 1.5;
	classvar <>panPerNotch = 0.3;
	classvar <>wheelNotch = 15;
	classvar <>minViewFrames = 24;

	var <view, <edit, <>action, <>hints;
	var drag, dragOrigin, dragStart, dragEnd, lastMod;
	var band = 15, readoutH = 16;
	var <viewLo = 0, <viewHi = 1;		// the window on the file, as fractions of it
	var <playhead;						// a fraction of the file, or nil
	var cols, colsKey;					// the columns of every lane for the window on screen
	var font, tagFont;
	var <>slicer, <>showGrid = true, <>gridDivision = 0.25, <>sliceAction, <>gridAction;
	var dragOffset;

	*new { |parent, bounds| ^super.new.init(parent, bounds) }

	init { |parent, bounds|
		font = Font(Font.defaultSansFace, 10);
		tagFont = Font(Font.defaultSansFace, 9, true);
		view = UserView(parent, bounds ? Rect(0, 0, 700, 240));
		view.minHeight_(160);
		view.background_(SampleEditor.colWaveBg);
		view.drawFunc = { |v| this.prDraw(v) };
		view.mouseDownAction = { |v, x, y, mod, but, count|
			if ((but ? 0) == 1) {
				drag = nil;
			} {
				lastMod = mod;
				this.prMouseDown(x, y, count);
			};
		};
		view.mouseMoveAction = { |v, x, y| this.prApply(x) };
		view.mouseUpAction = { |v, x, y|
			if (drag == \grid) { gridAction.value(this, true) };
			drag = nil
		};
		view.mouseWheelAction = { |v, x, y, mod, xDelta, yDelta|
			this.prWheel(x, y, mod, xDelta ? 0, yDelta ? 0);
		};
		view.acceptsMouseOver_(true);
		view.mouseOverAction = { |v, x, y| this.prHint(y) };
		^this
	}

	edit_ { |anEdit|
		if (anEdit !== edit) {
			edit = anEdit;
			this.prResetZoom;
		};
		this.prInvalidate;
		this.refresh;
	}

	refresh { view !? { if (view.isClosed.not) { view.refresh } } }

	// the columns are kept between redraws, so a moving playhead costs a
	// redraw and not a pass over the samples; an edit that changes the
	// samples - a save, a revert - drops them
	invalidate { this.prInvalidate; this.refresh }

	playhead_ { |frac|
		playhead = frac;
		this.refresh;
	}

	// ----------------------------------------------------------------- zoom

	span { ^(viewHi - viewLo).max(this.minSpan) }

	isZoomed { ^(viewLo > 0) or: { viewHi < 1 } }

	minSpan {
		var n = edit !? (_.numFrames);
		^if (n.notNil and: { n > minViewFrames }) { (minViewFrames / n).clip(1e-9, 1) } { 1 }
	}

	zoomTo { |lo, hi|
		var r = SampleWaveView.clampRange(lo, hi, this.minSpan);
		if (r[0] == viewLo and: { r[1] == viewHi }) { ^this };
		viewLo = r[0];
		viewHi = r[1];
		this.prInvalidate;
		this.refresh;
	}

	zoomAll { this.zoomTo(0, 1) }

	// the trim, with a little air either side so its markers are on screen
	zoomToRegion {
		var s, e, air;
		if (edit.isNil) { ^this };
		s = edit.start;
		e = edit.end;
		air = ((e - s) * 0.06).max(this.minSpan * 0.1);
		this.zoomTo(s - air, e + air);
	}

	// factor > 1 zooms in; the point under `anchor` stays where it is
	zoomBy { |factor, anchor|
		var sp = viewHi - viewLo, newSp;
		if (edit.isNil or: { factor == 1 } or: { sp <= 0 }) { ^this };
		anchor = (anchor ? ((viewLo + viewHi) * 0.5)).clip(0, 1);
		newSp = (sp / factor).clip(this.minSpan, 1);
		this.zoomTo(
			anchor - ((anchor - viewLo) * newSp / sp),
			anchor + ((viewHi - anchor) * newSp / sp)
		);
	}

	zoom { |factor = 2| this.zoomBy(factor, this.prXToFrac(this.prW * 0.5)) }

	panBy { |frac|
		var d = frac * this.span;
		if (this.isZoomed.not) { ^this };
		this.zoomTo(viewLo + d, viewHi + d);
	}

	// ------------------------------------------------------------- geometry

	prW { ^view.bounds.width.max(1) }

	prFracToX { |f| ^(f - viewLo) / this.span * this.prW }

	prXToFrac { |x| ^viewLo + (x / this.prW * this.span) }

	// where the lanes are: between the two strips, above the readout
	prLanesTop { ^band }
	prLanesHeight { ^(view.bounds.height - band - band - readoutH).max(10) }
	prReadoutTop { ^view.bounds.height - band - readoutH }

	prLaneRect { |c|
		var n = edit.numChannels.max(1), h = this.prLanesHeight / n;
		^Rect(0, this.prLanesTop + (c * h), this.prW, h)
	}

	// the fades as fractions of the file
	prFadeFracs {
		var dur = edit.duration;
		if (dur <= 0) { ^[0, 0] };
		^[edit.fadeIn / dur, edit.fadeOut / dur]
	}

	// ---------------------------------------------------------------- mouse

	prMouseDown { |x, y, count|
		var h = view.bounds.height, sX, eX, fades;
		if (edit.isNil) { ^this };
		if (count > 1) {
			drag = nil;
			if (lastMod.notNil and: { lastMod.isAlt }) { this.zoomToRegion } { this.zoomAll };
			^this
		};
		sX = this.prFracToX(edit.start);
		eX = this.prFracToX(edit.end);
		fades = this.prFadeFracs;
		// in the lanes: shift cuts for the slicer, alt slides the grid
		if (y > band and: { y < this.prReadoutTop } and: { lastMod.notNil }) {
			if (lastMod.isShift and: { slicer.notNil }) {
				drag = nil;
				^this.prCut(x)
			};
			if (SampleEditor.isShortcut(lastMod) and: { slicer.notNil }) {
				drag = nil;
				^this.prToggleKeep(x)
			};
			if (lastMod.isAlt and: { edit.bpm.notNil }) {
				drag = \grid;
				dragOrigin = x;
				dragOffset = edit.beatOffset;
				^this
			};
		};
		case
		{ y <= band } {
			drag = if ((x - sX).abs <= (x - eX).abs) { \start } { \end };
		}
		{ y >= (h - band) } {
			drag = if ((x - this.prFracToX(edit.start + fades[0])).abs
				<= (x - this.prFracToX(edit.end - fades[1])).abs) { \fadeIn } { \fadeOut };
		}
		{ y >= this.prReadoutTop } { drag = nil }
		{ x > sX and: { x < eX } } {
			drag = \region;
			dragOrigin = x;
			dragStart = edit.start;
			dragEnd = edit.end;
		}
		{ true } { drag = nil };
		this.prApply(x);
	}

	prApply { |x|
		var frac, len, newStart, dur;
		if (drag.isNil or: { edit.isNil }) { ^this };
		frac = this.prXToFrac(x).clip(0, 1);
		dur = edit.duration;
		if (drag == \grid) {
			// one beat either way is the whole grid: kept inside the first beat
			edit.beatOffset_((dragOffset + ((x - dragOrigin) / this.prW * this.span * edit.duration))
				.wrap(0, edit.beatSeconds ? 1));
			gridAction.value(this, false);
			^this.refresh
		};
		switch (drag,
			\start, { edit.start_(frac) },
			\end, { edit.end_(frac) },
			\fadeIn, { edit.fadeIn_(((frac - edit.start) * dur).max(0)) },
			\fadeOut, { edit.fadeOut_(((edit.end - frac) * dur).max(0)) },
			\region, {
				len = dragEnd - dragStart;
				newStart = (dragStart + ((x - dragOrigin) / this.prW * this.span)).clip(0, 1 - len);
				edit.setRegion(newStart, newStart + len);
			}
		);
		action.value(this);
	}

	// a cut where there is none near, none where there is one (6 pixels)
	prCut { |x|
		var frame = (this.prXToFrac(x).clip(0, 1) * edit.numFrames).round.asInteger;
		var tol = 6 / this.prW * this.span * edit.numFrames;
		if (slicer.removeSliceNear(frame, tol).not) { slicer.addSliceAt(frame) };
		sliceAction.value(this);
		this.refresh;
	}

	// the slice under the pointer in or out of the export
	prToggleKeep { |x|
		var frame = (this.prXToFrac(x).clip(0, 1) * edit.numFrames).round.asInteger;
		slicer.toggleKeepAt(frame);
		sliceAction.value(this);
		this.refresh;
	}

	*isZoomMod { |mod| ^mod.notNil and: { mod.isCmd or: { mod.isCtrl } } }

	// cmd and up or down zooms around the pointer; anything else slides
	prWheel { |x, y, mod, xDelta, yDelta|
		var notch = wheelNotch.max(1), steps;
		if (edit.isNil) { ^this };
		if (xDelta != 0) { this.panBy((xDelta / notch).clip(-3, 3) * panPerNotch) };
		if (yDelta == 0) { ^this };
		steps = (yDelta / notch).clip(-3, 3);
		if (SampleWaveView.isZoomMod(mod)) {
			this.zoomBy(zoomPerNotch.pow(steps), this.prXToFrac(x));
		} {
			this.panBy(steps.neg * panPerNotch);
		};
	}

	prHint { |y|
		var h = view.bounds.height;
		if (hints.isNil) { ^this };
		if (edit.isNil) { ^hints.string_("nothing loaded") };
		case
		{ y <= band } { hints.string_("the trim - drag S and E; what is outside them will not be in the file") }
		{ y >= (h - band) } { hints.string_("the fades - drag the handles in from the edges of the trim") }
		{ y >= this.prReadoutTop } { hints.string_("a readout, not a control: the trim, the fades, the gain, the zoom") }
		{ true } { hints.string_("drag inside the trim to move it along - cmd + two fingers up and down zooms, two fingers on their own slide, double click shows the whole file, alt + double click the trim"
			++ if (edit.bpm.notNil) { ", alt + drag slides the beat grid" } { "" }
			++ if (slicer.notNil) { ", shift + click adds a cut or takes one away, %-click puts a slice in or out of the export".format(SampleEditor.clickModName) } { "" }) };
	}

	// ------------------------------------------------------------- columns

	prInvalidate {
		cols = nil;
		colsKey = nil;
	}

	// [min, max] per pixel of every lane for the window on screen, or the
	// samples themselves when there are fewer of them than pixels
	prColumns { |w|
		var key = [viewLo, viewHi, w];
		var n = edit.numFrames, f0, f1;
		if (colsKey == key and: { cols.notNil }) { ^cols };
		f0 = (viewLo * n).floor.asInteger.clip(0, n);
		f1 = (viewHi * n).ceil.asInteger.clip(f0, n);
		cols = if ((f1 - f0) < (w * 0.75)) {
			edit.numChannels.collect { |c| (kind: \samples, f0: f0, f1: f1, data: edit.samples(c, f0, f1)) }
		} {
			edit.numChannels.collect { |c| (kind: \peaks, data: edit.peaks(c, w.floor.asInteger.max(1), viewLo, viewHi)) }
		};
		colsKey = key;
		^cols
	}

	// -------------------------------------------------------------- drawing

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height, sX, eX, fades, fiX, foX, g, dc, lanes;
		Pen.fillColor = SampleEditor.colWaveBg;
		Pen.fillRect(Rect(0, 0, w, h));
		if (edit.isNil or: { edit.loaded.not }) {
			Pen.stringCenteredIn("nothing loaded", Rect(0, 0, w, h), font, SampleEditor.colDim);
			^this
		};
		sX = this.prFracToX(edit.start);
		eX = this.prFracToX(edit.end);
		fades = this.prFadeFracs;
		fiX = this.prFracToX(edit.start + fades[0]);
		foX = this.prFracToX(edit.end - fades[1]);
		g = edit.gainDb.dbamp;
		dc = edit.dcOffsets;
		lanes = this.prColumns(w);

		edit.numChannels.do { |c|
			var r = this.prLaneRect(c), mid = r.top + (r.height * 0.5), half = r.height * 0.46;
			var off = if (edit.removeDC) { dc[c] } { 0.0 };
			this.prDrawLane(lanes[c], r, mid, half, g, off, sX, eX);
			// the zero line, and the DC of this channel while it is still in
			Pen.strokeColor = SampleEditor.colDim.copy.alpha_(0.35);
			Pen.width = 1;
			Pen.line(Point(0, mid), Point(w, mid));
			Pen.stroke;
			if (edit.removeDC.not and: { dc[c].abs > 1e-4 }) {
				Pen.strokeColor = SampleEditor.colWarn.copy.alpha_(0.7);
				Pen.lineDash = FloatArray[4, 4];
				Pen.line(Point(0, (mid - (dc[c] * g * half)).clip(r.top, r.bottom)),
					Point(w, (mid - (dc[c] * g * half)).clip(r.top, r.bottom)));
				Pen.stroke;
				Pen.lineDash = FloatArray[1, 0];
			};
			if (c > 0) {
				Pen.strokeColor = SampleEditor.colPanel;
				Pen.line(Point(0, r.top), Point(w, r.top));
				Pen.stroke;
			};
		};

		this.prDrawSlices(w, h);
		this.prDrawGrid(w, h);

		// everything outside the trim is not in the file
		Pen.fillColor = Color(0, 0, 0, 0.45);
		Pen.fillRect(Rect(0, band, sX.clip(0, w), h - band - band));
		Pen.fillRect(Rect(eX.clip(0, w), band, (w - eX).clip(0, w), h - band - band));

		// the fades, as the envelope they make
		Pen.strokeColor = SampleEditor.colAccent.copy.alpha_(0.85);
		Pen.width = 1.5;
		Pen.moveTo(Point(sX, h - band - readoutH - 2));
		Pen.lineTo(Point(fiX, band + 2));
		Pen.lineTo(Point(foX, band + 2));
		Pen.lineTo(Point(eX, h - band - readoutH - 2));
		Pen.stroke;

		this.prDrawMarkers(w, h, sX, eX);

		Pen.fillColor = SampleEditor.colHandle;
		[fiX, foX].do { |x|
			if (x >= -1 and: { x <= (w + 1) }) {
				Pen.fillOval(Rect((x - 5).clip(0, w - 10), h - band + 2, 10, 10));
			};
		};

		this.prDrawPlayhead(w, h);
		this.prDrawReadout(w, h);
		this.prDrawZoomBar(w, h);
	}

	// One vertical min/max line per pixel while there is more than a frame
	// behind each, the samples joined up once there is not. Two passes, so
	// the part inside the trim is drawn over the part outside it.
	prDrawLane { |lane, r, mid, half, g, off, sX, eX|
		var w = r.width, data, n, f0, f1;
		if (lane.isNil or: { lane[\data].isNil }) { ^this };
		data = lane[\data];
		n = data.size;
		if (n < 1) { ^this };
		Pen.width = 1;
		#[0, 1].do { |pass|
			var bright = (pass == 1);
			Pen.strokeColor = if (bright) { SampleEditor.colWave } { SampleEditor.colWaveDim };
			if (lane[\kind] == \samples) {
				var drawing = false;
				f0 = lane[\f0];
				n.do { |i|
					var x = this.prFracToX((f0 + i) / edit.numFrames);
					var inside = (x >= sX) and: { x <= eX };
					var y = (mid - ((data[i] - off) * g * half)).clip(r.top, r.bottom);
					if (inside == bright) {
						if (drawing) { Pen.lineTo(Point(x, y)) } {
							Pen.moveTo(Point(x, y));
							drawing = true;
						};
					} {
						drawing = false;
					};
				};
			} {
				w.floor.asInteger.max(1).do { |x|
					var c = data[(x * n / w).floor.asInteger.clip(0, n - 1)];
					var inside = (x >= sX) and: { x <= eX };
					if (inside == bright) {
						Pen.line(
							Point(x + 0.5, (mid - ((c[1] - off) * g * half)).clip(r.top, r.bottom)),
							Point(x + 0.5, (mid - ((c[0] - off) * g * half)).clip(r.top, r.bottom))
						);
					};
				};
			};
			Pen.stroke;
		};
	}

	// the trim markers; one the zoom has pushed off the side becomes a
	// triangle on that edge, so a trim off screen still says which way
	prDrawMarkers { |w, h, sX, eX|
		Pen.width = 1;
		[[sX, "S"], [eX, "E"]].do { |item|
			var x = item[0], txt = item[1], tag;
			if (x < 0 or: { x > w }) {
				var left = x < 0, edge = if (left) { 0 } { w }, dir = if (left) { 1 } { -1 };
				Pen.fillColor = SampleEditor.colAccent.copy.alpha_(0.5);
				Pen.moveTo(Point(edge, band * 0.5));
				Pen.lineTo(Point(edge + (7 * dir), 1));
				Pen.lineTo(Point(edge + (7 * dir), band - 1));
				Pen.fill;
			} {
				tag = Rect((x - 9).clip(0, w - 18), 0, 18, band);
				Pen.fillColor = SampleEditor.colAccent;
				Pen.strokeColor = SampleEditor.colAccent;
				Pen.line(Point(x, 0), Point(x, h - band - readoutH));
				Pen.stroke;
				Pen.fillRect(tag);
				Pen.stringCenteredIn(txt, tag.insetBy(0, 1), tagFont, SampleEditor.colWaveBg);
			};
		};
	}

	// The bars, the beats and the division in view, each kind only when its
	// lines are at least 5 pixels apart; bar numbers along the top of the lanes.
	prDrawGrid { |w, h|
		var dur = edit.duration, t0, t1, top = this.prLanesTop, bottom = this.prReadoutTop, beatPx, lines;
		if (showGrid.not or: { edit.bpm.isNil } or: { dur <= 0 }) { ^this };
		t0 = viewLo * dur;
		t1 = viewHi * dur;
		beatPx = edit.beatSeconds / (this.span * dur) * w;
		lines = case
		{ (beatPx * 4 * gridDivision) >= 5 } { edit.gridLines(gridDivision, t0, t1) }
		{ beatPx >= 5 } { edit.gridLines(0.25, t0, t1) }
		{ (beatPx * edit.beatsPerBar) >= 5 } { edit.gridLines(edit.beatsPerBar / 4, t0, t1) }
		{ [] };
		Pen.width = 1;
		lines.do { |line|
			var x = this.prFracToX(line[0] / dur).round + 0.5, beat = line[1], bar, isBeat;
			isBeat = (beat - beat.round).abs < 1e-6;
			bar = isBeat and: { (beat.round.asInteger % edit.beatsPerBar) == 0 };
			Pen.strokeColor = case
			{ bar } { SampleEditor.colAccent.copy.alpha_(0.55) }
			{ isBeat } { Color.grey(0.75, 0.32) }
			{ Color.grey(0.6, 0.16) };
			Pen.line(Point(x, top), Point(x, bottom));
			Pen.stroke;
			if (bar) {
				Pen.stringLeftJustIn(((beat.round.asInteger div: edit.beatsPerBar) + 1).asString,
					Rect(x + 2, top, 30, 12), font, SampleEditor.colAccent.copy.alpha_(0.8));
			};
		};
	}

	// the slicer's cuts: every other slice shaded, a line and a number at each
	// start; in transients mode the onset curve under them
	prDrawSlices { |w, h|
		var top = this.prLanesTop, bottom = this.prReadoutTop, starts, n, f;
		if (slicer.isNil) { ^this };
		f = edit.features;
		if (slicer.mode == \transients and: { f.notNil } and: { f[\odf].notNil }) {
			this.prDrawOnsetCurve(f, w, top, bottom);
		};
		starts = slicer.starts;
		n = edit.numFrames.max(1);
		slicer.slices.do { |pair, i|
			var x0 = this.prFracToX(pair[0] / n), x1 = this.prFracToX(pair[1] / n);
			if (x1 >= 0 and: { x0 <= w }) {
				if (i.odd) {
					Pen.fillColor = Color(1, 1, 1, 0.045);
					Pen.fillRect(Rect(x0, top, x1 - x0, bottom - top));
				};
				// dropped by keep: greyed over and crossed
				if ((slicer.kept[i] ? true).not) {
					Pen.fillColor = Color(0, 0, 0, 0.55);
					Pen.fillRect(Rect(x0, top, x1 - x0, bottom - top));
					if ((x1 - x0) > 6) {
						Pen.strokeColor = SampleEditor.colWarn.copy.alpha_(0.5);
						Pen.width = 1;
						Pen.line(Point(x0, top), Point(x1, bottom));
						Pen.line(Point(x0, bottom), Point(x1, top));
						Pen.stroke;
					};
				};
				Pen.strokeColor = SampleEditor.colPlay.copy.alpha_(0.85);
				Pen.width = 1;
				Pen.line(Point(x0.round + 0.5, top), Point(x0.round + 0.5, bottom));
				Pen.stroke;
				if ((x1 - x0) > 14 or: { i == 0 }) {
					Pen.fillColor = SampleEditor.colPlay.copy.alpha_(0.85);
					Pen.fillRect(Rect(x0, bottom - 13, (i + 1).asString.size * 7 + 4, 12));
					Pen.stringLeftJustIn((i + 1).asString, Rect(x0 + 2, bottom - 13, 30, 12), tagFont, SampleEditor.colWaveBg);
				};
			};
		};
	}

	prDrawOnsetCurve { |f, w, top, bottom|
		var odf = f[\odf], hop = f[\hopSeconds], dur = edit.duration, peak, hgt = (bottom - top) * 0.3, first = true;
		var thr = SampleFeatures.thresholdFor(slicer.sensitivity);
		peak = (odf.maxItem ? 0).max(SampleFeatures.salienceFloor);
		Pen.strokeColor = Color(0.95, 0.55, 0.85, 0.5);
		Pen.width = 1;
		w.asInteger.do { |x|
			var t = this.prXToFrac(x) * dur, k = (t / hop).round.asInteger, v, y;
			if (k >= 0 and: { k < odf.size }) {
				v = odf[k] / peak;
				y = bottom - (v.clip(0, 1) * hgt);
				if (first) { Pen.moveTo(Point(x, y)); first = false } { Pen.lineTo(Point(x, y)) };
			};
		};
		Pen.stroke;
		// how far above its surroundings an attack must stand at this sensitivity
		Pen.strokeColor = Color(0.95, 0.55, 0.85, 0.35);
		Pen.lineDash = FloatArray[3, 3];
		Pen.line(Point(0, bottom - (thr.clip(0, 1) * hgt)), Point(w, bottom - (thr.clip(0, 1) * hgt)));
		Pen.stroke;
		Pen.lineDash = FloatArray[1, 0];
	}

	prDrawPlayhead { |w, h|
		var x;
		if (playhead.isNil) { ^this };
		x = this.prFracToX(playhead);
		if (x < 0 or: { x > w }) { ^this };
		Pen.strokeColor = SampleEditor.colPlay;
		Pen.width = 1;
		Pen.line(Point(x, band), Point(x, h - band - readoutH));
		Pen.stroke;
	}

	prDrawReadout { |w, h|
		var top = this.prReadoutTop, ms = { |f| (f * edit.duration * 1000).round(0.1) };
		var txt = " % | % > % ms (% ms) | fade % / % ms".format(
			edit.label, ms.(edit.start), ms.(edit.end),
			(edit.regionDuration * 1000).round(0.1),
			(edit.fadeIn * 1000).round(0.1), (edit.fadeOut * 1000).round(0.1));
		if (edit.gainDb != 0) {
			txt = txt ++ " | gain % dB".format(
				if (edit.gainDb > 0) { "+" ++ edit.gainDb.round(0.1) } { edit.gainDb.round(0.1) });
		};
		if (edit.removeDC) { txt = txt ++ " | dc out" };
		txt = txt ++ this.zoomString;
		Pen.fillColor = SampleEditor.colPanel;
		Pen.fillRect(Rect(0, top, w, readoutH));
		Pen.stringLeftJustIn(txt, Rect(2, top + 1, w - 4, readoutH - 2), font, SampleEditor.colDim);
	}

	zoomString {
		var f;
		if (this.isZoomed.not) { ^"" };
		f = 1 / this.span;
		^" | zoom %x".format(if (f < 10) { f.round(0.1) } { f.round(1).asInteger })
	}

	prDrawZoomBar { |w, h|
		if (this.isZoomed.not) { ^this };
		Pen.fillColor = SampleEditor.colPanel.copy.alpha_(0.7);
		Pen.fillRect(Rect(0, h - 3, w, 3));
		Pen.fillColor = SampleEditor.colAccent;
		Pen.fillRect(Rect(viewLo * w, h - 3, (this.span * w).max(2), 3));
	}

	prResetZoom {
		viewLo = 0;
		viewHi = 1;
		this.prInvalidate;
	}

	// a range put back inside 0 .. 1 without changing how wide it is
	*clampRange { |lo, hi, minSpan = 0|
		var sp;
		lo = lo ? 0;
		hi = hi ? 1;
		if (hi < lo) { #lo, hi = [hi, lo] };
		sp = (hi - lo).clip(minSpan.clip(0, 1), 1);
		lo = lo.clip(0, 1 - sp);
		^[lo, lo + sp]
	}
}
