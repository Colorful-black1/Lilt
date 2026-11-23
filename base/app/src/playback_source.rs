//! Local audio decoding for the Rodio playback sink.
//!
//! Rodio 0.20 wraps every reader in a `ReadSeekSource` that reports
//! `byte_len() == None` while claiming to be seekable. Symphonia's ISO/MP4
//! reader treats that combination as a seek error, and Rodio turns a seek error
//! during decoder construction into `unreachable!()`. Opening an M4A/MP4 file
//! (including M4A audio saved with a `.mp3` name) therefore aborts the process.
//!
//! This decoder gives Symphonia the real `File` media source so the byte length
//! is known, and it returns ordinary errors for damaged files instead of
//! panicking.

use rodio::source::{SeekError, Source};
use std::fs::File;
use std::io::ErrorKind;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::time::Duration;
use symphonia::core::{
    audio::{AudioBufferRef, SampleBuffer, SignalSpec},
    codecs::{Decoder, DecoderOptions, CODEC_TYPE_NULL},
    errors::Error,
    formats::{FormatOptions, FormatReader, Packet, SeekMode, SeekTo},
    io::{MediaSourceStream, MediaSourceStreamOptions},
    meta::MetadataOptions,
    probe::Hint,
    units::{self, Time},
};

/// Decoder errors are not fatal on their own: skip a broken packet and try the
/// next one, but stop after a short run of consecutive failures.
const MAX_DECODE_RETRIES: usize = 3;

pub struct PlaybackSource {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn Decoder>,
    track_id: u32,
    buffer: SampleBuffer<f32>,
    offset: usize,
    spec: SignalSpec,
    total_duration: Option<Duration>,
}

impl PlaybackSource {
    pub fn open(path: &Path) -> Result<Self, String> {
        // Local audio files are untrusted input. A decoder should report a bad
        // file, but a panic in a third-party demuxer must not close the player.
        match catch_unwind(AssertUnwindSafe(|| Self::open_inner(path))) {
            Ok(result) => result,
            Err(_) => {
                Err("This file could not be decoded: the decoder failed unexpectedly.".to_owned())
            }
        }
    }

    fn open_inner(path: &Path) -> Result<Self, String> {
        let file = File::open(path).map_err(|error| format!("Could not read file: {error}"))?;
        let stream = MediaSourceStream::new(Box::new(file), MediaSourceStreamOptions::default());
        let probed = symphonia::default::get_probe()
            .format(
                &Hint::new(),
                stream,
                &FormatOptions {
                    enable_gapless: true,
                    ..Default::default()
                },
                &MetadataOptions::default(),
            )
            .map_err(decode_error)?;
        let track = probed
            .format
            .default_track()
            .filter(|track| track.codec_params.codec != CODEC_TYPE_NULL)
            .or_else(|| {
                probed
                    .format
                    .tracks()
                    .iter()
                    .find(|track| track.codec_params.codec != CODEC_TYPE_NULL)
            })
            .ok_or_else(|| "This file contains no supported audio track.".to_owned())?;
        let track_id = track.id;
        let params = track.codec_params.clone();
        let total_duration = params
            .time_base
            .zip(params.n_frames)
            .map(|(base, frames)| duration_from_time(base.calc_time(frames)));
        let mut decoder = symphonia::default::get_codecs()
            .make(&params, &DecoderOptions::default())
            .map_err(|error| format!("This file could not be decoded: {error}"))?;
        let mut format = probed.format;
        let (spec, buffer) = decode_first(&mut *format, &mut *decoder, track_id)?;
        Ok(Self {
            format,
            decoder,
            track_id,
            buffer,
            offset: 0,
            spec,
            total_duration,
        })
    }

    fn fill(&mut self) -> bool {
        let mut errors = 0;
        loop {
            let packet = match next_audio_packet(&mut *self.format, self.track_id) {
                Ok(Some(packet)) => packet,
                _ => return false,
            };
            match self.decoder.decode(&packet) {
                Ok(decoded) => {
                    let (spec, buffer) = copy_buffer(decoded);
                    self.spec = spec;
                    self.buffer = buffer;
                    self.offset = 0;
                    return !self.buffer.samples().is_empty();
                }
                Err(Error::DecodeError(_)) => {
                    errors += 1;
                    if errors > MAX_DECODE_RETRIES {
                        return false;
                    }
                }
                Err(_) => return false,
            }
        }
    }

    fn seek_inner(&mut self, position: Duration) -> Result<(), String> {
        let target = match self.total_duration {
            Some(total) if position >= total => {
                // Some demuxers cannot seek to the exact end of the stream.
                Duration::from_secs_f64((total.as_secs_f64() - 0.001).max(0.0))
            }
            _ => position,
        };
        let seeked = self
            .format
            .seek(
                SeekMode::Accurate,
                SeekTo::Time {
                    time: Time::from(target.as_secs_f64()),
                    track_id: Some(self.track_id),
                },
            )
            .map_err(|error| format!("Could not seek: {error}"))?;
        self.decoder.reset();
        let mut frames_to_skip = seeked.required_ts.saturating_sub(seeked.actual_ts);
        let mut errors = 0;
        let decoded = loop {
            let packet = next_audio_packet(&mut *self.format, self.track_id)
                .map_err(|error| format!("Could not seek: {error}"))?
                .ok_or_else(|| "Could not seek: the stream ended early.".to_owned())?;
            if packet.dur() <= frames_to_skip {
                frames_to_skip -= packet.dur();
                continue;
            }
            match self.decoder.decode(&packet) {
                Ok(decoded) => break decoded,
                Err(Error::DecodeError(_)) => {
                    errors += 1;
                    if errors > MAX_DECODE_RETRIES {
                        return Err("Could not seek: the audio data is damaged.".to_owned());
                    }
                }
                Err(error) => return Err(format!("Could not seek: {error}")),
            }
        };
        let (spec, buffer) = copy_buffer(decoded);
        self.spec = spec;
        self.offset = (frames_to_skip as usize)
            .saturating_mul(self.channels() as usize)
            .min(buffer.samples().len());
        self.buffer = buffer;
        Ok(())
    }
}

impl Iterator for PlaybackSource {
    type Item = f32;

    fn next(&mut self) -> Option<f32> {
        if self.offset >= self.buffer.samples().len() && !self.fill() {
            return None;
        }
        let sample = *self.buffer.samples().get(self.offset)?;
        self.offset += 1;
        Some(sample)
    }
}

impl Source for PlaybackSource {
    fn current_frame_len(&self) -> Option<usize> {
        Some(self.buffer.samples().len())
    }

    fn channels(&self) -> u16 {
        self.spec.channels.count().max(1) as u16
    }

    fn sample_rate(&self) -> u32 {
        self.spec.rate.max(1)
    }

    fn total_duration(&self) -> Option<Duration> {
        self.total_duration
    }

    fn try_seek(&mut self, position: Duration) -> Result<(), SeekError> {
        self.seek_inner(position).map_err(|error| {
            SeekError::Other(Box::new(std::io::Error::new(ErrorKind::Other, error)))
        })
    }
}

fn decode_first(
    format: &mut dyn FormatReader,
    decoder: &mut dyn Decoder,
    track_id: u32,
) -> Result<(SignalSpec, SampleBuffer<f32>), String> {
    let mut errors = 0;
    loop {
        let packet = next_audio_packet(format, track_id)
            .map_err(decode_error)?
            .ok_or_else(|| "This file contains no audio packets.".to_owned())?;
        match decoder.decode(&packet) {
            Ok(decoded) => return Ok(copy_buffer(decoded)),
            Err(Error::DecodeError(_)) => {
                errors += 1;
                if errors > MAX_DECODE_RETRIES {
                    return Err(
                        "This file could not be decoded: the audio data is damaged.".to_owned()
                    );
                }
            }
            Err(error) => return Err(format!("This file could not be decoded: {error}")),
        }
    }
}

fn next_audio_packet(
    format: &mut dyn FormatReader,
    track_id: u32,
) -> Result<Option<Packet>, Error> {
    loop {
        match format.next_packet() {
            Ok(packet) => {
                if packet.track_id() == track_id {
                    return Ok(Some(packet));
                }
            }
            Err(Error::IoError(error)) if error.kind() == ErrorKind::UnexpectedEof => {
                return Ok(None)
            }
            Err(Error::IoError(_)) => return Ok(None),
            Err(error) => return Err(error),
        }
    }
}

fn copy_buffer(decoded: AudioBufferRef<'_>) -> (SignalSpec, SampleBuffer<f32>) {
    let spec = *decoded.spec();
    let duration = units::Duration::from(decoded.capacity() as u64);
    let mut buffer = SampleBuffer::<f32>::new(duration, spec);
    buffer.copy_interleaved_ref(decoded);
    (spec, buffer)
}

fn duration_from_time(time: Time) -> Duration {
    let nanos = (time.frac.max(0.0) * 1_000_000_000.0) as u32;
    Duration::new(time.seconds, nanos.min(999_999_999))
}

fn decode_error(error: Error) -> String {
    match error {
        Error::Unsupported(_) => "This audio format is not supported.".to_owned(),
        Error::SeekError(_) => "This file has an unsupported container layout.".to_owned(),
        other => format!("This file could not be decoded: {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn fixture(name: &str) -> std::path::PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests")
            .join("fixtures")
            .join(name)
    }

    #[test]
    fn decodes_m4a_with_a_real_file_media_source() {
        let mut source = PlaybackSource::open(&fixture("tone.m4a")).expect("m4a should decode");
        assert!(source.channels() >= 1);
        assert!(source.sample_rate() > 0);
        assert!(source.total_duration().expect("duration") >= Duration::from_millis(1_900));
        let sample = source.next().expect("first sample");
        assert!(sample.is_finite());
        source
            .try_seek(Duration::from_millis(1_000))
            .expect("seek to one second");
        assert!(source.next().is_some());
    }

    #[test]
    fn decodes_the_starter_wav_and_seeks() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("Starter Music")
            .join("Aurora Drift")
            .join("01 - Glass Horizon.wav");
        let mut source = PlaybackSource::open(&path).expect("wav should decode");
        assert!(source.total_duration().expect("duration") > Duration::from_secs(40));
        source
            .try_seek(Duration::from_secs(10))
            .expect("seek into wav");
        assert!(source.next().is_some());
        source
            .try_seek(Duration::from_secs(47))
            .expect("seek near the end");
        assert!(source.next().is_some());
    }

    #[test]
    fn damaged_files_return_an_error_instead_of_panicking() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join("test-tmp");
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join(format!(
            "lilt-damaged-{}-{}.mp3",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let mut file = File::create(&path).expect("create temp file");
        file.write_all(b"not audio at all")
            .expect("write temp file");
        drop(file);
        let result = PlaybackSource::open(&path);
        let _ = std::fs::remove_file(&path);
        assert!(result.is_err());
    }
}
