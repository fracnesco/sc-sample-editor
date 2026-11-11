# The sample editor, in full

One sound file in a window: trim it, gain it, take the DC out, fade the edges, read its numbers,
slice it, save it. With a SuperDirt host, Tidal plays the result at once under the same name and
number.

```supercollider
SampleEditor("~/x.wav");                                  // with SampleEditor.defaultHost
SampleEditor("~/x.wav", SampleEditorDirtHost(~dirt), 2);  // previewing through orbit 2
SampleEditor.sample(\bd, 3);                              // bd:3 of the host's library
SampleEditor.all;                                         // the open editors
```

Opening a file that is already open fronts its window. In SuperDirtGUI, **edit...** in the sound
explorer, or `e`, opens the selected sample.

## Hosts

What the editor knows of the world is its host. It is asked where the preview plays, which Buffer to
play from, what a library calls a file, and it hears of every file written.

| host | preview | a file the library holds | save | to bank..., export slices... | bpm, key, tags |
|------|---------|--------------------------|------|------------------------------|----------------|
| `SampleEditorHost(s)` | the hardware outputs, in pairs | - | writes the file | write the files | for as long as the window is open |
| `SampleEditorDirtHost(~dirt)` | an orbit's dry bus, through its effects | opens as `bd:3`, plays from the library's Buffer | read back into the same Buffer | loaded as the bank named after the folder | for as long as the window is open |
| SuperDirtGUI's (`~sdg.editorHost`) | as above, through its inserts and fader | as above | as above, and the explorer hears of it | as above, and the explorer shows the bank | kept per file in its tag store, seen by the explorer and Tidal |

`SampleEditor.defaultHost` is the host an editor gets when given none: a plain one on `Server.default`,
until something sets another. SuperDirtGUI sets its own while it runs, and puts the plain one back when
freed. A host of your own answers the messages listed in `SampleEditorHost`'s help.

## Files from anywhere

Drop a sound file from the Finder on the editor - the wave, the title, anywhere in the window - and it
opens. When nothing was changed in that editor yet (no trim, gain, DC, fades or cuts by hand) and it is
not playing, the new file takes its place; otherwise it opens beside it. Several files open one editor
each. **open...** does the same through the Finder's dialog. A file the library already holds opens as
that sample (`bd:3`), so a save is heard at once; any other opens on its own, and a save writes only the
file. In SuperDirtGUI, a file dropped on the sound explorer opens in the editor and a folder is loaded as a
bank.

From there everything works as for a sample of the library: the analysis, the grid, the slicer and its
export.

**to bank...** puts a part of the file into SuperDirt. A small window asks for the bank's name (the one
used last, or the file's name the first time) and shows where its folder is - Dirt-Samples the first time,
where you added last after that - and what it will write: return or **add** writes, escape cancels,
**change...** picks another place. Picking a bank's own folder there (one with sound files in it) picks
that bank. A folder that exists gets one more file, a new name makes the folder. The file is the trim, S to
E, with the gain, the DC and the fades as set, plus 1 ms in and 4 ms out where it cuts into the file, so it
does not click; it is named after the bank with the next free number (`vox/vox_03.wav`), so after a
restart, when SuperDirt reads the folder in name order, it keeps its place among the ones written this way.
It is loaded at once and the hint says how to play it (`s "vox:3"`); with a bpm on the grid the file gets
it too. To cut a file into parts by hand: drag S and E over a part, **to bank...**, return, the next part.

A flac, ogg or mp3 opens (the editor reads anything libsndfile does) but SuperDirt loads only wav and
aiff, so **to bank...** and **export slices...** write those as WAV, in the same sample format, or 24-bit.

## In the window

The waveform has one lane per channel, with three kinds of drag:

| where | drag |
|-------|------|
| top strip | the **S** and **E** markers: the trim |
| bottom strip | the fade in and fade out handles |
| middle | the whole trim along |

Everything outside the trim is darkened: it will not be in the file. The gain is drawn into the wave,
the DC of each channel is a dashed line until it is taken out, and the fades are the envelope over the
top.

| key | does |
|-----|------|
| space | play, stop |
| `0` | the whole file |
| `-`, `=` | zoom out, in |
| `f` | zoom to the trim |
| `g` | the beat grid, on and off |
| left, right | slide |
| cmd-s (ctrl-s on Windows and Linux) | save |

| gesture | does |
|---------|------|
| cmd + two fingers up and down (or the wheel) | zoom at the pointer |
| two fingers, any direction | slide |
| double click | the whole file |
| alt + double click | zoom to the trim |
| alt + drag | slide the beat grid along |
| shift + click | a cut for the slicer, or, on a cut, take it away |

The other controls are the start, end and fade boxes in milliseconds, the gain knob and box, **normalize
to** with its dBFS box, **remove DC**, the output menu (the host's: orbits, or hardware outputs), **reset**, **revert**, **save**, **save as...**.

## The bpm, the key, the tags

Under the header is what the analysis says about the file: its bpm, sure, unsure or set by hand; where
a beat falls; its key, or its note; its tags; and below them the numbers it came from - how many attacks,
the attack time, how long it sounds, its centroid, how tonal it is, and the analysis's own bpm and key with
their confidence. The window analyses the whole file when it opens, in the background. **analyse** does it
again. How it works is in [sample-tags.md](sample-tags.md).

With a bpm, the **beat grid** is drawn over the wave: bars in the accent colour and numbered, beats
lighter, and the slicer's division lighter still, each only when there is room for its lines. To line it
up with the music:

- **÷2** and **×2** halve and double the bpm, when the analysis heard the tempo an octave off.
- **to 1st hit** puts a beat on the first attack of the file, to the sample.
- **alt + drag** on the wave slides the grid; **beat 1 ms** says, and takes, where a beat falls.
- typing a bpm sets it, and 0 takes the grid away.

With a host that keeps tags (SuperDirtGUI's), whatever is set here is the file's bpm from then on, by
hand: its explorer and Tidal's `sdgbpm` see it. The key field and the tags field work the same way.
Without one they last as long as the window. Leave the key empty to go back to the analysis's.

## The slicer

Tick **slicer** and the trim is cut into slices, shown on the wave as green lines numbered along the
bottom, every other slice shaded. They move as you change what makes them:

| | cuts |
|-|------|
| **on the grid** | on every line of the beat grid at the division in the menu: a bar, 1/2 to 1/32, or triplets |
| **on attacks** | wherever the analysis hears an attack. **sensitivity** is how weak an attack still makes a cut: more is more slices. The onset curve the cuts come from is drawn in pink under them, with a dashed line for the sensitivity |

Each cut on an attack is made exact: the first sample within 15 ms that reaches 30% of the loudest
there, a millisecond earlier, then back to where the wave crosses zero. **min ms** keeps any slice from
being shorter. **shift + click** adds a cut where there is none, or takes away the one under the pointer.
These cuts by hand stay through any change of mode or sensitivity, until **clear cuts**.

The first slice starts at the trim's start, and the last ends at its end.

**export slices...** asks where and under what name. The Finder's folder dialog opens first, at
Dirt-Samples the first time (the folder `~dirt.loadSoundFiles` reads, so the bank is there at every
start) and where you exported last after that. Then a small window asks for the folder's name,
`<file>_slices` to start with, and shows the files it will write: **change...** picks another place,
return or **export** writes, escape cancels. Every slice goes to its own file, named after the folder
with the slice's index from 0, which is the `n` Tidal plays it as: `brk/brk_00.wav`, `brk/brk_01.wav`
... A name that is taken there gets `_2`, `_3`. A slice is written in the file's own format, with the gain and the DC as set, and fades of 1 ms in and 4 ms
out, so no cut clicks. The folder is then loaded as a bank under its own name, every slice tagged `slice`,
and on the grid given the grid's bpm. The sample itself is never written to: export is not save.

```haskell
d1 $ s "brk*8" # n "0 3 1 2 5 4 7 6"
```

### Keep

The second line of the slicer chooses which slices are exported, on the grid or on attacks. The cuts stay
where they are; a slice that is not kept is greyed and crossed on the wave, and **export slices...** leaves it
out. It is dropped, not merged into the slice before it: that part of the file is in no file, and the kept
slices are numbered from 0 without gaps, so `n "0 .. k"` is every one of them.

| rule | keeps |
|------|-------|
| **level over dB** | slices within that many dB of the loudest slice. A slice's level is its loudest 10 ms, so a hit at the start of a long slice counts and its tail does not pull it down. -60 keeps every level; -12 keeps the hits near the top and drops ghost notes, room noise, bleed |
| **length ms** *from* **to** | slices at least and at most this long; 0 for no limit. The minimum drops flams and fragments, the maximum long tails |

**cmd + click** (ctrl on Windows and Linux) on a slice puts it in or out whatever the rules say, and it stays
so through any change of rules, mode or sensitivity until **keep all** (every rule open again, nothing by
hand) or **clear cuts**. The count beside the rules says how many slices export writes: `11 of 24 kept`.

**min ms** in the first line is not the same: it stops a cut being made closer than that to the one before,
so what would have been a short slice stays part of the one before it. **length ms** drops a slice instead.

## What you hear

**play** starts a synth where the host says: with a SuperDirt host at the head of the orbit's group,
writing into the orbit's dry bus, so it goes through that orbit's effects (and SuperDirtGUI's inserts and
fader); with the plain host at the tail of the default group, into a pair of hardware outputs. It reads the
library's own buffer (a temporary one for a file outside a library): the trim, gain, fades and DC are all
applied live in the synth, and no samples change until you save. The playhead comes back on
`/sampleeditor_pos`.

Nothing else wakes an orbit that has gone quiet, so the preview asks the host to wake it first (`wake`,
which sets the orbit's always-on effects) and starts its synth in a bundle at `server.latency`, in line with
the resume bundle. Otherwise the start of the
preview would be lost.

## The numbers

Two columns, *original* and *result*: the file as it is, and what a save would write. All of it runs in
the language (`SampleAnalysis`), with no server and no plugins, on the samples the file holds.

| reading | is |
|---------|----|
| duration | seconds and frames |
| peak | the loudest sample, dBFS. Over 0 and an integer file clips |
| rms | the mean level over every channel, dBFS |
| crest | peak minus rms, dB |
| loudness | integrated, K-weighted and gated as ITU-R BS.1770 says, in LUFS |
| loudest 400 ms | the highest momentary block, LUFS |
| dc offset | the mean of each channel. **remove DC** takes out the whole file's mean |
| clipped | samples already at full scale in the file, and whether the result would pass it |

They stay cheap as you drag because two passes over the audio are kept and the rest is arithmetic. `stats`
holds, per channel, the min, max, sum, sum of squares and clipped count, which give peak, rms, crest and
DC for any gain, with or without the DC, without touching the samples again. `kHops` holds the mean
square of the K-weighted signal in every 20 ms hop, from which the loudness of any stretch is a gated
average of 400 ms blocks at a 100 ms step, with gates at -70 LUFS and 10 LU under the mean. The K-weighting
pass stops at 120 s.

A stretch shorter than a block is measured as one block over what there is, and says so: a drum hit has
a loudness too, just not the spec's.

## What a save writes

The result is, in this order: the trim, the DC out, the gain, then sine fades on the edges so a trim cannot
click. The fades together never cover more than the trim, and the gain is clipped to -60 to +24 dB.

| action | does |
|--------|------|
| **save** | Writes over the file, in its own header and sample format. The **first** save copies the original beside it as `<file>.bak`, and refuses to save if that fails. Later saves leave the `.bak` alone, so revert always means the file as it was before the editor touched it. The host is then told: a SuperDirt host reads the library's buffer again |
| **revert** | Puts the `.bak` back over the file and reads it again. The `.bak` stays |
| **save as...** | Writes a new file. Into the same folder as the sample, it is appended to the bank as its last index, whatever its name. Elsewhere, nothing in the library changes |
| **reset** | Settings back to none. No file is touched |

`.bak` is not a sound file extension, so the library skips those files when it reads the folder.

An integer format is clipped just under full scale first (0.9999), so a gain past 0 dBFS clips rather than
wraps. A float format is not clipped. If the file's own format cannot be written, it warns and writes WAV
int24.

After a save, a SuperDirt host reads the header first, so the event's frame count is right before the server
has even read the file, then `allocRead`s into **the same Buffer** and rebuilds the event. So every event
keeps its bufnum, and nothing needs restarting.

A file over 24,000,000 samples (frames × channels, `SampleEdit.maxSamples`, about 4.5 minutes of stereo at
44.1 kHz) is refused: the whole file is read into the language, at about 0.2 s per million samples, and sclang
does nothing else meanwhile - SuperDirt, which runs in sclang, plays nothing while a long file opens.
SuperDirtGUI's settings window sets `maxSamples` in minutes. Above 536,000,000 (`SampleEdit.hardLimit`,
about 101 minutes of stereo) nothing opens whatever `maxSamples` says: sclang crashes, with no error, on any
object of 2 GiB or more.

## Without the window

`SampleEdit` is the model and needs no server. Reading, the numbers and the writing are all in the language.

```supercollider
e = SampleEdit("~/x.wav");             // with SampleEditor.defaultHost
e.setRegion(0.1, 0.9);                 // fractions of the file
e.gainDb = -3;
e.removeDC = true;
e.fadeIn = 0.005;                      // seconds
e.normalize(-1);                       // the gain that brings the peak to -1 dBFS
e.metrics;                             // (peak:, rms:, crest:, lufs: ...) of the result
e.metrics(false);                      // of the file as it is
e.save;                                // over the file, .bak kept
e.revert;                              // the .bak back
e.saveAs("~/kick-short.wav");
e.addToBank("~/samples/kicks");        // the trim as kicks/kicks_00.wav, handed to the host
```
