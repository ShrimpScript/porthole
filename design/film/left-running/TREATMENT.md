# Left Running: treatment

A 60-second story film for Porthole: 3D, playful, aimed at the heart rather than the spec sheet.
This is the thinking behind it: the research, the story and how it is told, the look and the
sound. How it is built is in [README.md](README.md). The clock both picture and sound read is
[timeline.json](timeline.json).

## The brief, in one line

Someone loves working with Claude Code on their computer and wants to step away without the
work stopping. Porthole lets their phone answer when it asks.

**Logline:** a little desk computer always waited, stuck, whenever its person went out. One day
its person goes out anyway, and the question finds them at the park.

## What the research said, and what it changed

**Story.**
- **The waiting has to happen before the product, not after.** If a waiting montage comes before
  the buzz, the film says the notification arrives hours late. So the waiting is the old way, remembered as a
  thought ("every time"); the new way ("one day") is instant. This is Pixar's story spine: every
  day, one day, because of that, until finally.
- **The waiting is played as gentle comedy, not sadness.**
  - A tumbleweed of dust rolls across the desk. The clock spins. A slide whistle goes wah-wah.
  - Sad-vertising makes the person look neglectful. Joy and comedy score as well on emotion.
  - Lego's and Buster the Boxer's ads show this.
- **The product is a brief catalyst, not the hero.** The app is on screen for about five seconds:
  a notification, a question, two buttons. Amazon's "Joy Ride" does the same with three.
- **The answer must visibly change the character.** The question the film uses is "Which theme
  should new users get?". Answered "Dark", the computer's own face turns dark. It is also a gentler
  hero than a permission prompt, which invites "should I approve commands from the park?"
- **Plant the phone early.** The computer nudges the phone across the desk to its person: it has
  agency, and the phone is in the story before it matters.
- **Peak, then end.** Memory keeps the peak and the ending (the peak-end rule). The tap, the
  flip to dark and the catch sit close to the end, then a quiet button.

**Character.**
- **Proportions.** Big heads and big, low-set eyes (the baby schema).
- **The computer's face.** Its eyes are the terminal's block cursor, and it has no mouth most of
  the time. Emotion comes from shape and body, as with Cozmo and the desk lamp of Luxo Jr.:
  - arches for happy
  - drooping outer lids for sad
  - wide for an idea
  - a lean, a nod, a hop, a sigh
- **Colour is state, and it is the app's colours.** Teal while it runs, amber while it needs you.
- **Everyone is built from one kit.** Person, dog and computer are the same rounded pieces at the
  same level of stylisation, so no one looks out of place.

**Picture.**
- **Soft toy look.** Matte, rounded, soft shadows and warm light.
- **Backgrounds calmer than the characters.**
- **A colour script that follows the feeling:**

| Act | Light | Accent |
|---|---|---|
| Together (morning) | Cream walls, honey wood, sun through the round window | Teal eyes on a light screen |
| The thought (last time) | Night, desaturated, cool | Amber eyes, the only warm thing |
| Alone | The afternoon passing, a round patch of sun | Amber eyes as the question comes |
| The park | Golden hour, sun low behind the trees | The phone; a teal-rimmed porthole |
| The answer | Late afternoon | The screen turns night-green, the eyes glow teal |
| Home (dusk) | Blue outside, a warm desk lamp | Teal glow: warm and teal together |
| End card | The brand's night green | The teal ring |

**Cinematography.**
- **The hook is an extreme close-up of a blinking cursor.** A second one blinks on beside it
  and they are eyes, then the camera pulls back as the computer stretches awake.
- **Shot and reverse shot**, including over the computer's shoulder onto its person's face.
- **A wide, lonely frame** when the question comes: a small computer and an empty chair.
- **Point of view for the phone**, so it is close enough to read, and for looking down at the dog.
- **Transitions are all the round window:**
  - The question leaves the screen as an amber ring that wipes to the park.
  - The phone's glass opens into a porthole that the camera goes through, and comes out at home.
  - At the end the camera leaves the room backwards through a round window in the wall. That
    window shrinks into the "o" of the wordmark: the old cartoon iris-out, and the brand's own ring.

**Sound.**
- **A leitmotif, not a licensed track.**
  - The motif is C E G A G. It rises and comes back, like the ring.
  - It has two endings. The question, E D, stops unresolved on the dominant. The answer, E D C,
    comes home.
  - While the computer waits it only ever sings the question. The answer arrives with the tap,
    a whole step higher (C to D), on the downbeat of the payoff.
- **Tension, silence, release.** The tap sits on a suspended chord and a rising reverse swell,
  then half a second of near-silence, then everything lands together.
- **The phone chime is the motif's head.** Its first three notes as bells are both the
  notification and the idea landing.
- **Mixed for the web:**
  - −14 LUFS integrated, true peak under −1.5 dBTP.
  - The waiting sits several LU below the payoff.
  - The music ducks under the few sounds that tell the story: the buzz, the chime, the taps,
    the woof.

**Risks, and what the film does about them.**
- **Claude and Anthropic are named only in plain text.**
  - No logo, spark or orange, and no copy of Claude Code's terminal: the computer's screen shows
    a generic log.
  - The end card's small print says Porthole is independent and not affiliated with Anthropic.
- **No real-world trade dress.**
  - The phone is the site's own generic Android model.
  - The computer's stand is a rounded plinth and two-jointed arm, not a dome and chrome stalk.
- **Seeing each other through the porthole reads as fantasy, not a video feature.**
  - The camera goes through the glass; nobody is on a call.
  - What the app really does is shown in its own UI, readable.

## The film, beat by beat

Times are seconds; the music is 120 BPM (a beat is 15 frames at 30 fps).

| Time | Beat | Picture | Sound |
|---|---|---|---|
| 0.0 | Hook | Black to a cream screen: `~ $` and a blinking block cursor. | Silence, cursor ticks. |
| 1.55 | A face | A second cursor blinks on: they are eyes. They dart left, right. | "Bip", two tiny blips. |
| 2.0 | Wake | Pull back: the computer stretches up on its arm and yawns, then settles with a bounce. | The theme's downbeat: ukulele, harp run; a yawn, a boing. |
| 4.2 | Every day | They work together. Its eyes are happy, its work scrolls faintly behind them, it bobs to the beat. The person seen over its shoulder. | Ukulele, pizzicato, brushes; the glockenspiel asks the motif's question. Keys clack. |
| 8.0 | Tests pass | A "✓ 12 passed" line; it hops. | "Bi-bip", and the motif's answer begins. |
| 9.3 | Fist bump | The person reaches out; it leans its corner into the fist. | "Boop", claps. |
| 11.2 | One day | The door swings open: the dog trots in with its lead. | Tag jingle, paws on wood, the band thins. |
| 13.05 | Walk time | The lead dropped, a sit, a woof. The person swivels round, delighted. | "Boof". |
| 14.0 | But… | The person looks back at the computer. Their face falls. | The music stops on one low pizzicato. |
| 14.6 | The thought | A thought bubble: last time. Night, amber eyes, its head sunk to the keyboard, the clock spinning, a tumbleweed of dust. | A music box plays the question, slow and minor; the clock whirs; wah-wah. |
| 17.5 | The idea | It sees the problem, and has an idea. | A glockenspiel "ding". |
| 17.8 | The nudge | It leans down and pushes the phone across the desk. The phone wakes to Porthole's ring. | Pizzicato creeping up; a slide on wood; the brand chime. |
| 19.0 | Go on | Two nods, happy eyes, a smile. Subtitle for its beeps: *Go on. I'll ask if I need you.* | Two blips. |
| 19.6 | Out | Up, the phone pocketed, out of the door with the dog. A wave back; it waves too. | Whistled motif, ending on the question as the door shuts. |
| 21.9 | Alone | It hums to itself at work. | An ocarina hums the motif; the clock ticks. |
| 23.5 | The question | "Which theme should new users get?" rises on its screen; its eyes turn amber. It looks at the door. The wide: a small computer, an empty chair. | A bell; the celesta sings the question over a suspended chord. |
| 24.5 | Old habit | It starts to sink into the old waiting pose… | A slide whistle falls. |
| 25.45 | Oh, right | …then remembers, and perks up. An amber ring goes out from its screen. | "!" and the chime; a harp sweeps up. |
| 26.6 | To the park | The amber ring wipes to the park. | Whoosh; birds, a breeze. |
| 28.0 | The park | Golden hour. A throw, the dog runs, comes back with the ball. | Flute and pizzicato, light. |
| 30.5 | Buzz | The phone buzzes in a pocket. | Vibration, the chime muffled; the music drops to a held breath. |
| 31.6 | It asks | Seen from their eyes: the app's notification, readable ("Add dark mode to settings is asking you"). A tap, and the question with two buttons. | A tap; the celesta asks. |
| 34.3 | The dog decides | A look down at the dark-coated dog, who tilts its head. | A squeak. |
| 35.8 | Dark | A thumb taps "Dark". | Tap. |
| 36.0 | The porthole | A porthole ring rises out of the phone's glass. Through it: the computer at home, amber, hopeful. "Dark" lights up on its screen. The camera goes in. | Whoosh and shimmer; Bb add9, C sus4, rising; a reverse swell; half a second of nothing. |
| 38.5 | The answer | A circle of night green opens across its face: dark mode. Its eyes glow teal. It hops for joy. | The key lifts to D: everyone in, the motif answered at last. A soft whoomp. |
| 41.0 | Done | "✓ Dark mode is in", a happy wiggle. | "Bi-bip". |
| 42.5 | Back to the dog | The phone back in the pocket. A big throw, and the dog leaps against the sun and catches it. | Whistle and flute sing the answer; a cymbal on the catch. |
| 46.5 | Home | Dusk. The door opens, the dog runs to its bed and curls up; the person comes to the desk. | IV, iv, I, softly: the answer once more. |
| 49.5 | Pat pat | Two pats on its head; a small heart over its eyes. | Pats, two boops, a sparkle. |
| 50.6 | The window | The camera backs out through a round window in the wall into the night: the lit room in a porthole. | A slow harp climb; crickets. |
| 53.0 | The ring | The window shrinks to the "o"; the ring draws round it and the letters arrive. | A climbing pentatonic, panned round the ring. |
| 54.5 | Porthole | **Porthole**. *A window into the computer you left running.* porthole-one.vercel.app. Small print: for Claude Code on your Mac or Linux computer, from your Android phone; independent, not affiliated with Anthropic. | The button: one short D chord and a bell, then quiet. |
| 60.0 | End | | Half a second of room tone. |

## The drawn version

The same film, drawn by hand on paper. It is the same animation, timeline and sound, so every
beat and every sound lands in the same place. It is for channels where a crafted, storybook
feel suits better than soft 3D, and it leans further into the playful.

- **Paint.** Four flat tones per colour: shadow, core, light, highlight. The plastic sheen is
  gone, and the colours stay the film's.
- **Ink.** Warm near-black, not black.
  - It is traced where the distance jumps (silhouettes), where a surface turns (creases) and
    where one colour meets another (details: eyes, lettering, planks).
  - Each line is drawn twice a little apart, with pressure varying along it, so it overshoots
    and doubles like a quick pen.
  - Grass blades are painted, not inked; inked one by one, they turned to scribble.
- **Pencil and paint.** Grain in long diagonal strokes, heavier in the darks. The shadows are
  hatched, the deepest cross-hatched. The paint is printed a touch off register and pools
  slightly at its edges.
- **Paper.** One sheet under every drawing: cream, with fibres and tooth, darker at its edges,
  and still visible in the night scenes. The subtitle and the end card are roughened with the
  same wobble.
- **Timing.** Drawn on twos: 15 drawings a second, each held for two frames, the cadence of
  hand-drawn animation. The line boils on a three-drawing cycle, so even a held pose is alive.
  Each drawing is taken at the nearest moment, so picture and sound stay within one frame (33 ms) of each other.

## The cast

- **The computer.** A cream monitor on a dark-green, two-jointed arm and plinth.
  - Its face: two block-cursor eyes, teal (running) or amber (waiting), with the work scrolling
    faintly behind them.
  - It starts the film in a light theme and ends it dark.
  - It speaks in blips.
- **Their person.** Big-headed, soft, in a teal hoodie: they/them, never named. Warm, a bit
  torn between the desk and the door.
- **The dog.** A round, dark-coated pup with a cream bib, tan brow spots and a teal collar.
  Jingles. Decides the theme.
