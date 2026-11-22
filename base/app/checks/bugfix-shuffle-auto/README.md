# Bug fix — shuffle + gapless auto-advance

## Symptom

With gapless playback and shuffle enabled, letting a track finish on its own made the
bottom bar jump through queue entries rapidly instead of settling on the next shuffled
song. Pressing next manually was fine.

## Root cause

`AudioEngine` has two pieces of gapless state:

- `queued_next` in `audio.rs` — a source has been appended to Rodio's sink.
- `pending_next` in `app.rs` — the queue index that should be shown when the appended
  source starts.

The queued flag was never cleared when Rodio moved to the appended source. Because
`append_next` still returned `Ok(())` when the flag was already set (without appending
anything), `queue_next` kept re-arming `pending_next`. `update_playback` then treated
every frame with `len < 2` as a transition and advanced the visible queue index again and
again, while the actual audio kept playing the one already-queued track.

## Fix

- `AudioEngine::append_next` now returns `Result<bool, String>` and reports `false` when
  a track is already queued.
- `App::queue_next` only records `pending_next` when the append actually happened.
- `update_playback` calls `AudioEngine::take_transition(len)`, which consumes the queued
  flag only when the sink has lost the old source (`len == 1`).
- The pending index is consumed only when that transition is reported, via
  `take_pending_transition`. The function returns after applying it, so the previous
  track's end position is not written onto the new track.

## Regression tests

```text
cargo test
# 14 passed; 0 failed

audio::tests::transition_requires_a_queued_source_and_one_remaining
app::tests::pending_transition_waits_for_the_queued_source
```

Release build and packaging:

```text
cargo build --release
.\package.ps1 pc
# dist/Lilt-0.1.0-win64-portable.zip
```

The shell used for this fix cannot open an interactive desktop window, so the visual
auto-advance check is left as a manual step: enable shuffle and gapless, play a track,
and let it finish. The title and queue selection should change exactly once per finished
track, including when the next song is chosen randomly.
