use crate::model::{EqProfile, EQ_BANDS, EQ_BAND_Q, EQ_FREQUENCIES};
use cpal::traits::{DeviceTrait, HostTrait};
use rodio::source::SineWave;
use rodio::{Decoder, OutputStream, OutputStreamHandle, Sink, Source};
use std::fs::File;
use std::io::BufReader;
use std::path::Path;
use std::sync::{Arc, RwLock};
use std::time::Duration;

pub struct AudioEngine {
    _stream: OutputStream,
    handle: OutputStreamHandle,
    sink: Sink,
    shared: Arc<RwLock<EqProfile>>,
    device_name: String,
    volume: f32,
    speed: f32,
    queued_next: bool,
    loaded: bool,
}

impl AudioEngine {
    /// Every output the operating system will let us open. Names are the
    /// same strings the settings screen stores.
    pub fn output_devices() -> Vec<String> {
        let host = cpal::default_host();
        let mut names: Vec<String> = host
            .output_devices()
            .map(|devices| devices.filter_map(|device| device.name().ok()).collect())
            .unwrap_or_default();
        names.sort_by_key(|name| name.to_lowercase());
        names.dedup();
        names
    }

    pub fn default_output_device_name() -> Option<String> {
        cpal::default_host()
            .default_output_device()
            .and_then(|device| device.name().ok())
    }

    /// Names plus whether each one is the operating system's default.
    pub fn describe_outputs() -> Vec<(String, bool)> {
        let default = Self::default_output_device_name();
        Self::output_devices()
            .into_iter()
            .map(|name| {
                let is_default = default.as_deref() == Some(name.as_str());
                (name, is_default)
            })
            .collect()
    }

    pub fn new(shared: Arc<RwLock<EqProfile>>, wanted_device: &str) -> Result<Self, String> {
        let host = cpal::default_host();
        let mut device = None;
        if !wanted_device.trim().is_empty() {
            if let Ok(devices) = host.output_devices() {
                device = devices
                    .into_iter()
                    .find(|candidate| candidate.name().ok().as_deref() == Some(wanted_device));
            }
        }
        let mut device = device
            .or_else(|| host.default_output_device())
            .ok_or_else(|| "No audio output device is available.".to_owned())?;
        let mut opened = OutputStream::try_from_device(&device);
        // A default that opens but goes nowhere is a common Windows setup
        // (HDMI screen with no speakers). If the default fails outright, try
        // the other outputs before giving up.
        if opened.is_err() && wanted_device.trim().is_empty() {
            if let Ok(candidates) = host.output_devices() {
                for candidate in candidates {
                    let same = candidate.name().ok() == device.name().ok();
                    if same {
                        continue;
                    }
                    if let Ok(pair) = OutputStream::try_from_device(&candidate) {
                        device = candidate;
                        opened = Ok(pair);
                        break;
                    }
                }
            }
        }
        let device_name = device
            .name()
            .unwrap_or_else(|_| "System output".to_owned());
        let (stream, handle) = opened
            .map_err(|error| format!("Could not open {device_name}: {error}"))?;
        let sink =
            Sink::try_new(&handle).map_err(|error| format!("Could not open audio: {error}"))?;
        Ok(Self {
            _stream: stream,
            handle,
            sink,
            shared,
            device_name,
            volume: 0.8,
            speed: 1.0,
            queued_next: false,
            loaded: false,
        })
    }

    pub fn device_name(&self) -> &str {
        &self.device_name
    }

    /// Plays a short, quiet confirmation tone on the current output. The sink
    /// is detached so the tone finishes even if the caller drops it.
    pub fn play_test_tone(&mut self) -> Result<(), String> {
        let sink = Sink::try_new(&self.handle)
            .map_err(|error| format!("Could not open the test tone: {error}"))?;
        sink.set_volume(1.0);
        let tone = SineWave::new(523.25f32)
            .take_duration(Duration::from_millis(850))
            .amplify(0.22)
            .fade_in(Duration::from_millis(30))
            .fade_out(Duration::from_millis(180));
        sink.append(tone);
        sink.detach();
        Ok(())
    }


    fn make_source(
        &self,
        path: &Path,
    ) -> Result<EqSource<rodio::source::SamplesConverter<Decoder<BufReader<File>>, f32>>, String>
    {
        let file = File::open(path).map_err(|error| format!("Could not read file: {error}"))?;
        let decoder = Decoder::new(BufReader::new(file))
            .map_err(|error| format!("This file could not be decoded: {error}"))?;
        Ok(EqSource::new(
            decoder.convert_samples::<f32>(),
            self.shared.clone(),
        ))
    }

    pub fn play(&mut self, path: &Path, start: f64, volume: f32, speed: f32) -> Result<(), String> {
        let source = self.make_source(path)?;
        let sink = Sink::try_new(&self.handle)
            .map_err(|error| format!("Could not open audio: {error}"))?;
        sink.set_volume(volume);
        // `h71.onClick` clamps play speed to 0.50..=3.00.
        sink.set_speed(speed.clamp(0.5, 3.0));
        sink.append(source);
        if start > 0.1 {
            let _ = sink.try_seek(Duration::from_secs_f64(start));
        }
        self.volume = volume;
        self.speed = speed;
        self.queued_next = false;
        self.loaded = true;
        self.sink = sink;
        Ok(())
    }

    /// Appends the next decoded track to the gapless sink. Returns `false`
    /// when a track is already queued (or nothing is loaded), so callers do
    /// not record a pending transition that will never start.
    pub fn append_next(&mut self, path: &Path) -> Result<bool, String> {
        if self.queued_next || !self.loaded {
            return Ok(false);
        }
        let source = self.make_source(path)?;
        self.sink.append(source);
        self.queued_next = true;
        Ok(true)
    }

    pub fn set_volume(&mut self, volume: f32) {
        self.volume = volume.clamp(0.0, 1.0);
        self.sink.set_volume(self.volume);
    }

    /// The sink's current volume, including any fade in progress.
    pub fn volume(&self) -> f32 {
        self.sink.volume()
    }

    pub fn set_speed(&mut self, speed: f32) {
        self.speed = speed.clamp(0.5, 3.0);
        self.sink.set_speed(self.speed);
    }

    pub fn set_eq(&self, _profile: &EqProfile) {
        // The shared profile is read by the running source. This method exists
        // so callers use one obvious path when a profile is edited.
    }

    pub fn pause(&self) {
        self.sink.pause();
    }

    pub fn resume(&self) {
        self.sink.play();
    }

    pub fn stop(&mut self) {
        self.sink.stop();
        self.queued_next = false;
        self.loaded = false;
    }

    pub fn is_empty(&self) -> bool {
        self.sink.empty()
    }

    pub fn len(&self) -> usize {
        self.sink.len()
    }

    pub fn position(&self) -> f64 {
        self.sink.get_pos().as_secs_f64()
    }

    pub fn seek(&self, position: f64) {
        let _ = self
            .sink
            .try_seek(Duration::from_secs_f64(position.max(0.0)));
    }

    pub fn queued_next(&self) -> bool {
        self.queued_next
    }

    pub fn clear_queued_flag(&mut self) {
        self.queued_next = false;
    }

    pub fn loaded(&self) -> bool {
        self.loaded
    }

    pub fn take_transition(&mut self, new_len: usize) -> bool {
        // A queued next track has started when the sink loses one sound
        // without being empty.
        if transition_started(self.queued_next, new_len) {
            self.queued_next = false;
            return true;
        }
        false
    }
}

/// True when the sink has consumed the current source and the queued source
/// is now the only one playing. `len == 2` still means the old track is
/// playing; `len == 0` means the sink drained completely.
pub(crate) fn transition_started(queued_next: bool, new_len: usize) -> bool {
    queued_next && new_len == 1
}

#[cfg(test)]
mod tests {
    use super::transition_started;

    #[test]
    fn transition_requires_a_queued_source_and_one_remaining() {
        assert!(transition_started(true, 1));
        assert!(!transition_started(true, 2));
        assert!(!transition_started(true, 0));
        assert!(!transition_started(false, 1));
    }
}

#[derive(Clone, Copy, Debug)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

impl Default for Biquad {
    fn default() -> Self {
        Self {
            b0: 1.0,
            b1: 0.0,
            b2: 0.0,
            a1: 0.0,
            a2: 0.0,
            z1: 0.0,
            z2: 0.0,
        }
    }
}

impl Biquad {
    fn process(&mut self, input: f32) -> f32 {
        let output = self.b0 * input + self.z1;
        self.z1 = self.b1 * input - self.a1 * output + self.z2;
        self.z2 = self.b2 * input - self.a2 * output;
        output
    }

    fn reset(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
    }

    fn peaking(sample_rate: f32, frequency: f32, gain_db: f32, q: f32) -> Self {
        if gain_db.abs() < 0.001 {
            return Self::default();
        }
        let a = 10f32.powf(gain_db / 40.0);
        let omega = std::f32::consts::TAU * frequency.clamp(20.0, sample_rate * 0.45) / sample_rate;
        let alpha = omega.sin() / (2.0 * q);
        let cos = omega.cos();
        let a0 = 1.0 + alpha / a;
        Self {
            b0: (1.0 + alpha * a) / a0,
            b1: (-2.0 * cos) / a0,
            b2: (1.0 - alpha * a) / a0,
            a1: (-2.0 * cos) / a0,
            a2: (1.0 - alpha / a) / a0,
            z1: 0.0,
            z2: 0.0,
        }
    }

    fn low_shelf(sample_rate: f32, frequency: f32, gain_db: f32, slope: f32) -> Self {
        if gain_db.abs() < 0.001 {
            return Self::default();
        }
        let a = 10f32.powf(gain_db / 40.0);
        let omega = std::f32::consts::TAU * frequency.clamp(20.0, sample_rate * 0.45) / sample_rate;
        let cos = omega.cos();
        let sin = omega.sin();
        let alpha = sin / 2.0 * ((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0).sqrt();
        let sqrt_a = a.sqrt();
        let a0 = (a + 1.0) + (a - 1.0) * cos + 2.0 * sqrt_a * alpha;
        Self {
            b0: a * ((a + 1.0) - (a - 1.0) * cos + 2.0 * sqrt_a * alpha) / a0,
            b1: 2.0 * a * ((a - 1.0) - (a + 1.0) * cos) / a0,
            b2: a * ((a + 1.0) - (a - 1.0) * cos - 2.0 * sqrt_a * alpha) / a0,
            a1: -2.0 * ((a - 1.0) + (a + 1.0) * cos) / a0,
            a2: ((a + 1.0) + (a - 1.0) * cos - 2.0 * sqrt_a * alpha) / a0,
            z1: 0.0,
            z2: 0.0,
        }
    }
}

pub struct EqSource<S: Source<Item = f32>> {
    inner: S,
    shared: Arc<RwLock<EqProfile>>,
    filters: Vec<[Biquad; EQ_BANDS]>,
    bass: Vec<Biquad>,
    channel: usize,
    channels: u16,
    sample_rate: u32,
    last_left: f32,
    revision: u64,
    counter: u32,
    preamp: f32,
    virtualizer: f32,
    balance: f32,
    enabled: bool,
    cached: EqProfile,
}

impl<S: Source<Item = f32>> EqSource<S> {
    pub fn new(inner: S, shared: Arc<RwLock<EqProfile>>) -> Self {
        let channels = inner.channels().max(1) as usize;
        let sample_rate = inner.sample_rate();
        let cached = shared.read().map(|guard| guard.clone()).unwrap_or_default();
        let mut source = Self {
            inner,
            shared,
            filters: vec![[Biquad::default(); EQ_BANDS]; channels],
            bass: vec![Biquad::default(); channels],
            channel: 0,
            channels: channels as u16,
            sample_rate,
            last_left: 0.0,
            revision: cached.revision,
            counter: 0,
            preamp: 1.0,
            virtualizer: 0.0,
            balance: 100.0,
            enabled: cached.enabled,
            cached,
        };
        source.rebuild();
        source
    }

    fn rebuild(&mut self) {
        let sample_rate = self.sample_rate as f32;
        for channel in 0..self.filters.len() {
            for band in 0..EQ_BANDS {
                self.filters[channel][band] = if self.enabled {
                    Biquad::peaking(
                        sample_rate,
                        EQ_FREQUENCIES[band],
                        self.cached.gains[band]
                            .clamp(crate::model::EQ_GAIN_MIN_DB, crate::model::EQ_GAIN_MAX_DB),
                        EQ_BAND_Q,
                    )
                } else {
                    Biquad::default()
                };
            }
            self.bass[channel] = if self.enabled {
                // Android's BassBoost effect has no public dB curve. The
                // target's own headroom formula assumes up to +15 dB at 100 %
                // (`EqualizerActivity2.o0`: strength * 15 / 1000).
                Biquad::low_shelf(sample_rate, 90.0, self.cached.bass * 15.0, 0.9)
            } else {
                Biquad::default()
            };
        }
        self.preamp = if self.enabled {
            10f32.powf(self.cached.preamp_db() / 20.0)
        } else {
            1.0
        };
        self.virtualizer = if self.enabled {
            self.cached.virtualizer.clamp(0.0, 1.0) * 0.35
        } else {
            0.0
        };
        self.balance = if self.enabled {
            self.cached.balance.clamp(0.0, 200.0)
        } else {
            100.0
        };
    }

    fn refresh(&mut self) {
        let mut newly_enabled = None;
        {
            let Ok(guard) = self.shared.read() else {
                return;
            };
            if guard.revision == self.revision {
                return;
            }
            self.cached = guard.clone();
            self.revision = guard.revision;
            newly_enabled = Some(guard.enabled);
        }
        if let Some(enabled) = newly_enabled {
            self.enabled = enabled;
        }
        self.rebuild();
    }
}

impl<S: Source<Item = f32>> Iterator for EqSource<S> {
    type Item = f32;

    fn next(&mut self) -> Option<f32> {
        let sample = self.inner.next()?;
        self.counter = self.counter.wrapping_add(1);
        if self.counter & 0x1FF == 0 {
            self.refresh();
        }
        let channel = self.channel.min(self.filters.len().saturating_sub(1));
        let mut value = if self.enabled {
            let mut filtered = sample;
            for band in 0..EQ_BANDS {
                filtered = self.filters[channel][band].process(filtered);
            }
            filtered = self.bass[channel].process(filtered);
            filtered * self.preamp
        } else {
            sample
        };
        if self.channels >= 2 {
            if self.channel == 0 {
                self.last_left = value;
            } else {
                value = value * (1.0 - self.virtualizer * 0.45)
                    + self.last_left * self.virtualizer * 0.45;
            }
            // `w40.K0(int)`: 0 = full left, 100 = centre, 200 = full right.
            // The target keeps one side at full level and attenuates the other.
            let balance = self.balance;
            let gain = if self.channel == 0 {
                ((200.0 - balance) / 100.0).clamp(0.0, 1.0)
            } else {
                (balance / 100.0).clamp(0.0, 1.0)
            };
            value *= gain;
        }
        self.channel = (self.channel + 1) % self.channels.max(1) as usize;
        Some(value.clamp(-1.0, 1.0))
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        self.inner.size_hint()
    }
}

impl<S: Source<Item = f32>> Source for EqSource<S> {
    fn current_frame_len(&self) -> Option<usize> {
        self.inner.current_frame_len()
    }

    fn channels(&self) -> u16 {
        self.inner.channels()
    }

    fn sample_rate(&self) -> u32 {
        self.inner.sample_rate()
    }

    fn total_duration(&self) -> Option<Duration> {
        self.inner.total_duration()
    }

    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        self.channel = 0;
        self.last_left = 0.0;
        for channel in &mut self.filters {
            for filter in channel.iter_mut() {
                filter.reset();
            }
        }
        for filter in &mut self.bass {
            filter.reset();
        }
        self.inner.try_seek(pos)
    }
}
