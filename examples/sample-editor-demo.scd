// SampleEditor, block by block. Evaluate one ( ... ) at a time - never .load this file.

// ---- 1. alone: any file, the preview on the hardware outputs
(
s.waitForBoot {
	SampleEditor(Platform.resourceDir +/+ "sounds/a11wlk01.wav");
};
)

// ---- 2. inside a running SuperDirt (~dirt): previews through the orbits,
// a save is read back into the library, to bank... loads the bank
(
SampleEditor.defaultHost = SampleEditorDirtHost(~dirt);
SampleEditor.sample(\bd, 3);
)

// ---- 3. without the window
(
e = SampleEdit(Platform.resourceDir +/+ "sounds/a11wlk01.wav");
e.metrics.postln;                        // peak, rms, crest, loudness ...
fork { SampleFeatures.breathing = true; e.analyseFeatures; SampleFeatures.breathing = false; e.bpm.postln };
)

(
x = SampleCutter(e);
x.mode = \transients; x.sensitivity = 0.6;
x.keepLevel = -18;                       // only the louder slices
"% slices, % kept".format(x.numSlices, x.numKept).postln;
// x.export(Platform.defaultTempDir +/+ "walk");   // walk/walk_00.wav ...
)
