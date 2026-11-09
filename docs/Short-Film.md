# The short-film hive

An objective becomes a short film made of **still frames**: a concept, a storyboard, one style sheet, one picture per shot from the image model, a review that can send single shots back, an `.mp4` that ffmpeg assembles from them, and your approval. The result is a `Media` artifact of type `video/mp4`.

**This hive needs the desktop app.** The frames step needs an OpenAI connection and the assemble step runs ffmpeg on your computer. On Android and the web the two steps have no executor, and the engine blocks them as "not available in this runtime" instead of waiting for them. It also **spends money** (one image per shot), so it runs only when you start it: it has no suggested schedule and is not part of the coding suite.

It is never run on a schedule: it spends money, so a schedule refuses a hive with the frames or assemble step (the schedule is turned off with a note saying so), and **Add the coding suite** never gives it one.

Put it on the device with **Keeper's room › Schedules › Add the short-film hive (desktop)**. It arrives as a hive you authored and can edit (`ShortFilmHive`, `workflow/ShortFilmWorkflow.kt`); one already on the device is left alone, so your edits survive. Then launch it like any hive and type the objective: *a film about a lighthouse keeper who finds a letter*.

## The steps

| Step | Who | What it makes |
|---|---|---|
| `concept` | Product Manager | A logline, tone, look and story beats; 6 to 24 shots unless the objective says otherwise. |
| `storyboard` | UX Designer | **One JSON artifact**: the shot list (below). |
| `style-sheet` | UX Designer | One style block that is added to every shot's prompt. |
| `frames` | `film-frames` (providers/llm) | One PNG per shot, drawn from the storyboard and style sheet. |
| `frame-review` | Adversarial Reviewer | A JSON object naming the shots to draw again. |
| `assemble` | `film-assemble` (desktop) | `film.mp4`, a `Media` artifact. |
| `approve-film` | You | The last step, and only a person completes it. |

The roles are built-in ones; nothing here invents a role. The storyboard step is asked for exactly this and nothing else:

~~~
{"shots":[
  {"id":"s01","prompt":"A lighthouse at dusk, wide shot, low sun","durationSeconds":4,"narration":"It stood alone."},
  {"id":"s02","prompt":"A letter on a wooden table, lamplight","durationSeconds":6}
]}
~~~

## Limits

Every limit is a named constant (`workflow/FilmStoryboard.kt`), quoted in the messages, and checked by `FilmStoryboardTest`. What the parser will not accept is an **error** and gives no storyboard; what it can fix it fixes and names in a **warning**.

| Limit | Value | Over it |
|---|---|---|
| `FILM_MAX_SHOTS` | 60 | Refused (a story with its end cut off is not the story). |
| `FILM_MIN_SHOT_SECONDS` .. `FILM_MAX_SHOT_SECONDS` | 1 .. 15 | Clamped, and a warning says so. A missing duration is `FILM_DEFAULT_SHOT_SECONDS` (4). |
| `FILM_MAX_TOTAL_SECONDS` | 300 | Refused. |
| `FILM_MAX_PROMPT_CHARS` | 1500 | Refused; a blank prompt is refused too. |
| `FILM_MAX_NARRATION_CHARS` | 600 | Cut, with a warning. |
| `FILM_MAX_STYLE_CHARS` | 2000 | Cut, with a warning. |
| `FILM_MAX_SHOT_ID_CHARS` | 40, letters, digits, `-` `_` | Refused: ids become file names. A missing id is made up (`s3`). |
| `FILM_MAX_STORYBOARD_CHARS` | 200000 | Not parsed. |
| `FILM_IMAGE_BUDGET_DEFAULT` | 40 images per workflow run | See below. |
| `FILM_FRAME_PARALLELISM` | 3 frames at a time | |
| `FILM_SHOT_MAX_RETRIES` | 2 (so at most 3 tries per shot) | The shot fails, and the step says which. |
| `FILM_FFMPEG_TIMEOUT_MILLIS` | 15 minutes | ffmpeg and everything it started is stopped. |

The parser accepts the JSON bare, in a fenced block (with or without a language tag), or with prose around it; trailing commas and comments are tolerated; `duration` and `duration_seconds` are read as `durationSeconds`; a bare array works. It never throws. All of it is model text and is treated as data: see the [threat model](THREAT_MODEL.md#short-film-desktop).

## The image budget

The frames step counts **every image it asks for, retries included**, per workflow run. If the storyboard needs more new images than the run has left, the step **fails before drawing anything** and says so:

~~~
The storyboard needs 50 new images, but this run's image budget is 40 and 0 are already used.
To raise the budget set the environment variable AIVE_FILM_IMAGE_BUDGET (now 40, at most 500),
restart the app and run this step again, or cut shots from the storyboard. Frames already drawn are kept.
~~~

Set `AIVE_FILM_IMAGE_BUDGET` before starting The Aive (a whole number, kept between 1 and `FILM_IMAGE_BUDGET_CEILING`, 500). The count is kept on this computer (in the frame store, taken before each picture is asked for), so restarting the app does not give it back; a run that is started again from a task is a new workflow run and starts a new count. Reusing frames that were already drawn costs nothing and is not counted.

## Frames are kept, and shots can be sent back

Frames are filed by the **film**, not by the run: `~/.aive/film/frames/film-<16 hex digits>/<shot id>.png`. The folder name is a hash of the style sheet and every shot's id and prompt (`FilmKey`; not the durations, which cost no image). So when the frames step runs again for the same storyboard it finds the pictures it already drew and draws only:

1. shots with no picture (a failed shot, a deleted file),
2. shots the **frame review** sent back, and
3. shots named in the frames step's own objective, if you edited it: a line `regenerate-shots: s03, s07` and **Run again from here** on the frames step. The line stays in the hive's text, so it is acted on **once per workflow run** (the same set of shots is not drawn again after a restart or a repeated check by the engine); name a different set, or send the shots back from the frame review, to draw them again.

The reviewer states its result as a JSON object (or a line `regenerate-shots: s03, s07`); `FilmFrameReview.parse` reads it and keeps only valid ids:

~~~
{"regenerate-shots":["s03","s07"],"notes":"s03 has a watermark; s07 breaks the style"}
~~~

**The assemble step is the gate.** While the review lists shots, `assemble` does not run: it fails, names the shots, writes them into the film's folder (`regenerate.txt`), and says to run the frames step again. A review that says `VERDICT: reject` or `revise` without naming shots stops it too, with the reason. Run again from the frames step ([Run again from a task](architecture/ARCHITECTURE.md)): only the listed shots are drawn, the rest are kept, and the list is cleared once they are drawn. If a sent-back shot fails again, it stays on the list.

## Assembling

`FfmpegFilmPlan` builds the ffmpeg argument list as a pure function (`FfmpegFilmPlanTest`): a looped still per shot, scaled to cover 1280 x 720 (even dimensions are forced), a gentle zoom of 8 % over the shot, a half-second crossfade into the next shot, H.264 in `yuv420p`, 25 frames a second, no audio. Each clip runs one crossfade longer than its shot, so a crossfade starts when the next shot's time begins and **the film is exactly as long as the shots add up**.

~~~
ffmpeg -nostdin -hide_banner -loglevel error -nostats -progress pipe:1
  -f image2 -pattern_type none -loop 1 -framerate 25 -t 4.500 -i /…/film-…/s01.png
  -f image2 -pattern_type none -loop 1 -framerate 25 -t 6.000 -i /…/film-…/s02.png
  -filter_complex "[0:v]scale=2560:1440:…,zoompan=…[v0];[1:v]…[v1];[v0][v1]xfade=transition=fade:duration=0.500:offset=4.000[vout]"
  -map [vout] -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -r 25 -movflags +faststart -an
  -metadata comment=AI-generated still frames assembled by The Aive with ffmpeg -y film.partial.mp4
~~~

It is run through the same argument-list runner as the CLI agents (`CliProcessRunner`), found with `CliLocator`, with an environment of `PATH`, the temp folders and locale only. The output goes to `~/.aive/film/output/<run>/film.mp4` (written as `film.partial.mp4` and renamed when it is whole; deleted on failure or cancel). If ffmpeg is not installed the step fails at once with how to install it. Progress is read from ffmpeg's `-progress` lines.

## Where things are

| Thing | Place |
|---|---|
| Hive, storyboard parser, limits, review parser | `shared/…/workflow/ShortFilmWorkflow.kt`, `FilmStoryboard.kt`, `FilmFrameStore.kt` |
| Frames step | `providers/llm/…/FilmFramesExecutorIntegration.kt` (uses `OpenAiNodeCharacterImageApi`, the node-character pipeline's client) |
| Assemble step, ffmpeg plan, file store | `desktopApp/…/FilmAssembleExecutorIntegration.kt`, `FfmpegFilmPlan.kt`, `FileFilmFrameStore.kt` |
| Registration | `desktopApp/…/Main.kt`, beside the test runner (see Open decisions) |
| Tests | `FilmStoryboardTest`, `ShortFilmWorkflowTest`, `FilmFramesExecutorIntegrationTest`, `FfmpegFilmPlanTest`, `FileFilmFrameStoreTest`, `FilmAssembleExecutorIntegrationTest` |

## What is verified, and what is not

- Verified by tests: the parser (fenced, malformed, over limits, hostile text), the hive's graph and order, the budget and the retries with a fake image API, three-at-a-time, partial regeneration, the argument builder, the path checks (including a link out of the folder), and the assemble step with a scripted runner.
- Verified against a real ffmpeg: `FfmpegFilmPlanTest` runs the command with the ffmpeg on the computer that runs the tests, when there is one, and checks codec, pixel format, dimensions, length and the absence of audio with ffprobe. It was run with ffmpeg 6.1.1; other versions are not tested (the build is the same one the flags are written for, but `zoompan`, `xfade` and `-pattern_type` have changed between releases).
- **Not verified: the image API.** No call to OpenAI was made while building this. The request is the node-character pipeline's, with a landscape size (`1536x1024`) and an opaque background; whether the model names that pipeline uses accept those sizes is not known.
- **Not verified: the whole hive end to end** in the running app, with real agents writing the storyboard.

## Open decisions

Each is a default chosen to keep moving. **Wrong if** says what would show it is wrong.

- **Frames are filed by what they are drawn from, not by run.** Wrong if you start a new run with an identical storyboard and style and expect new pictures: you get the old ones back, free. Change a prompt, name the shots in `regenerate-shots`, or delete the film's folder.
- **Editing one shot's prompt redraws every frame**, because the film's name includes every prompt. Wrong if you edit storyboards a shot at a time and want the other frames kept.
- **The frame review is a text step.** It is given each shot's prompt and the frame's file location, not its pixels. Wrong if you expect it to find a six-fingered hand: only a provider that opens image files can. Your approval of the film is where eyes land; there, send shots back with `regenerate-shots` in the frames step's objective.
- **The gate is the assemble step, not the review step.** The review is not made to require a Verification artifact, because a failed review could not then record which shots to redraw. Wrong if you want the review step itself to show as failed.
- **The budget counts every attempt, per workflow run, in memory.** Wrong if a failed call is free with your provider (it overcounts), or you want a daily or per-film cap, or a setting in the app instead of an environment variable.
- **40 images is lower than the 60 shots allowed.** A film of 41 to 60 shots needs the budget raised first. Wrong if you mean to make long films: raise the default.
- **Frames are 3:2 and cropped to 16:9.** About a sixth of each picture's height is trimmed. Wrong if you want portrait or a different shape: change `FILM_FRAME_SIZE` and `FfmpegFilmPlan.DEFAULT_*`.
- **Look: 8 % zoom, half-second crossfade, 25 fps, crf 20, 1280 x 720, silent.** Wrong if you want cuts, no motion, higher resolution or sound. Narration is kept in the storyboard but never drawn, spoken or sent to ffmpeg.
- **Parallelism 3, two retries.** Wrong if your image quota is lower (rate-limit failures will show as failed shots) or higher.
- **The two film steps are registered for this computer only,** beside the test runner and not with the node-character pipeline, because they read and write local files and run ffmpeg. Wrong if you want a pool node to draw the frames: the files would have to travel with it.
- **Films and frames are not cleaned up.** `~/.aive/film` grows by a few megabytes per frame. Wrong if disk space matters: delete old folders by hand.
- **No creative roles.** The hive reuses Product Manager, UX Designer and Adversarial Reviewer. Wrong if you want a storyboard artist or an art director: add the roles to your copy of the hive.
- **OpenAI only.** The frames step uses the image tool the node-character pipeline already uses. Wrong if you want another image provider: implement `FilmImageApi`.
- **Its own install button.** It is not in the coding suite, since it needs a desktop and spends money. Wrong if you would rather have it appear with the other suites.
