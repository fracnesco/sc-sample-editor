# SampleEditor

**A window for one sound file: trim it, level it, read its numbers, slice it, and save the parts into SuperDirt banks.** A SuperCollider extension.

Drop a file on the window, drag the S and E markers over a part, and press
**to bank...**: the part is a new file in a SuperDirt folder, loaded, playable
from Tidal as `s "vox:0"`. Or let the slicer cut the whole file on the beat grid
or on its attacks, drop the slices too quiet or too short, and export the rest
as a bank.

```supercollider
s.boot;
SampleEditor("~/Music/break.wav");                        // plays to the hardware outputs

// inside a running SuperDirt: previews through its orbits, saves go into its library
SampleEditor.defaultHost = SampleEditorDirtHost(~dirt);
SampleEditor.sample(\bd, 3);
```

It depends on nothing - no plugins, no other quarks. **SuperDirt** is optional:
`SampleEditorDirtHost` uses one when you give it one, and the class compiles
without it. **SuperDirtGUI** uses this quark for its sample editor and sets its
own host, so inside it nothing needs setting up.

Written for SuperCollider 3.13 on macOS, and run under 3.14.1. Nothing in it is
platform specific apart from the symlink in `install.scd`, which Linux shares
and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-sample-editor.git");
thisProcess.recompile;
```

`Quarks.install` clones the repository into your `downloaded-quarks` folder and
puts it on the class path; the recompile is what makes the classes exist, the
same thing as `Language -> Recompile Class Library` (ctrl/cmd + shift + L).
SuperDirtGUI lists it as a dependency, so installing SuperDirtGUI installs this
too.

### As a linked clone

For a clone you want to keep editing: open `install.scd` in the IDE, evaluate
its first block, recompile. It links the folder into
`Platform.userExtensionDir` as `SampleEditor`.

**One or the other, not both.** Two copies on the class path is a duplicate
class error at compile time. On Windows there is no `ln -s`: use the quark.

### Check that it took

The second block of `install.scd` lists any class that did not compile. Or:

```supercollider
SampleEditor.help;
```

## The window

The waveform, one lane per channel: the **S** and **E** markers on the top strip
are the trim, the handles on the bottom strip the fades, a drag in the middle
moves the trim along. Everything outside the trim is darkened. Set a gain or
**normalize** to a ceiling, tick **remove DC**. The table at the bottom says
what the file is and what a save would make of it: peak, rms, crest, integrated
loudness (ITU-R BS.1770), the loudest 400 ms, DC offset, clipping - all worked
out in the language, exact.

**play** previews the trim with every setting applied, live, through the
output in the menu beside it. Nothing is written until you save:

| | |
|-|-|
| **save** | over the file; the original is kept beside it as `<file>.bak` the first time |
| **revert** | the `.bak` back |
| **save as...** | a new file |
| **to bank...** | the trim as a new file in a bank's folder, `<bank>_00.wav`, `<bank>_01.wav` ..., and loaded |
| **open...** | another file - or drop one from the Finder on the window |

Above the wave is what the analysis heard: the bpm (with a beat grid drawn over
the wave, to halve, double, slide, or put on the first attack), the key, the tags.

### The slicer

Tick **slicer** and the trim is cut on the grid (a bar down to 1/32, or triplets)
or on the attacks at a **sensitivity**; shift + click adds or takes away a cut.
The **keep** row drops slices from the export: too quiet (dB under the loudest
slice), too short, too long; cmd + click puts one in or out by hand. **export
slices...** writes every kept slice to a new folder, numbered from 0 as Tidal's
`n`, and hands the folder to the host. The file itself is never touched.

[docs/sample-editor.md](docs/sample-editor.md) has every gesture, key and number.

## Hosts

What the editor knows of the world is its **host**: where the preview plays,
which Buffer to play from, what a library calls a file, who hears of the files
it writes. That is the one thing this extension keeps apart from everything
else, so the same window works alone, in a bare SuperDirt and in SuperDirtGUI.

| host | preview | after a save | after to bank..., export slices... | bpm, key, tags |
|------|---------|--------------|-----------------------------------|----------------|
| `SampleEditorHost(s)` | hardware outputs | - | files written | for as long as the window is open |
| `SampleEditorDirtHost(~dirt)` | an orbit, through its effects | read back into the library's same Buffer: Tidal plays it at once | loaded as a bank | for as long as the window is open |
| SuperDirtGUI's | an orbit, through its inserts and fader | the same | the same, shown in its explorer | kept per file, seen by its explorer and Tidal |

`SampleEditor.defaultHost` is what an editor gets when given none. A host of
your own answers the messages listed in `SampleEditorHost`'s help.

## Without the window

```supercollider
e = SampleEdit("~/x.wav");
e.setRegion(0.1, 0.9); e.gainDb = -3; e.removeDC = true;
e.metrics;                              // (peak:, rms:, crest:, lufs: ...) of the result
e.analyseFeatures; e.bpm;               // onsets, tempo, key
x = SampleCutter(e); x.mode = \transients; x.keepLevel = -18;
x.export("~/samples/brk");              // brk/brk_00.wav ...
```

## Files

| file | what it is |
|------|------------|
| [classes/SampleEditor.sc](classes/SampleEditor.sc) | the window, its colours and knob |
| [classes/SampleEditorHost.sc](classes/SampleEditorHost.sc) | `SampleEditorHost` (alone) and `SampleEditorDirtHost` (inside a SuperDirt) |
| [classes/SampleEdit.sc](classes/SampleEdit.sc) | the model: the file as Signals, the settings, render, save, to bank, the grid, levels |
| [classes/SampleCutter.sc](classes/SampleCutter.sc) | the slicer: cuts, keep rules, export |
| [classes/SampleWaveView.sc](classes/SampleWaveView.sc) | the waveform view |
| [classes/SampleAnalysis.sc](classes/SampleAnalysis.sc) | peak, rms, crest, DC, BS.1770 loudness |
| [classes/SampleFeatures.sc](classes/SampleFeatures.sc) | onsets, tempo, key, the numbers tags are made from |
| [classes/SampleHintBar.sc](classes/SampleHintBar.sc) | the hint strip along the bottom |
| [docs/sample-editor.md](docs/sample-editor.md) | the long explanation |
| [install.scd](install.scd) | link, check, unlink |

## Issues

https://github.com/fracnesco/sc-sample-editor/issues

## License

MIT, see [LICENSE](LICENSE).
