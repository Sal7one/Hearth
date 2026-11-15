//! Standalone benchmark candidate. SoundCraft's offline phase bank inspired this
//! streaming design. It uses a Blackman window, a bounded history, and an exact sample clock.
//! https://github.com/storytold/soundcraft/blob/ef58a90f4a418a282a15fc71d2e03c3d8e8f40fa/crates/dsp/src/offline.rs

use std::f64::consts::{PI, TAU};
use std::slice;

const OUTPUT_RATE: usize = 16_000;
const RADIUS: usize = 64;
const TAPS: usize = 2 * RADIUS + 1;
const HISTORY: usize = 256;
const MAX_INPUT_FRAMES: u64 = (u64::MAX - 48_000) / OUTPUT_RATE as u64;
const ERROR: usize = usize::MAX;

struct Resampler {
    input_rate: usize,
    phases: usize,
    step: usize,
    weights: Vec<f32>,
    history: [f32; 2 * HISTORY],
    received: u64,
    produced: u64,
    center: u64,
    phase: usize,
    finished: bool,
}

impl Resampler {
    fn new(input_rate: usize) -> Option<Self> {
        let (step, phases) = match input_rate {
            OUTPUT_RATE => (1, 1),
            44_100 => (441, 160),
            48_000 => (3, 1),
            _ => return None,
        };
        let mut weights = Vec::new();
        if input_rate != OUTPUT_RATE {
            // The transition band straddles 7.5 kHz, below the 8 kHz output Nyquist.
            let cutoff = 15_000.0 / input_rate as f64;
            weights.reserve_exact(phases * TAPS);
            for phase in 0..phases {
                let fraction = phase as f64 / phases as f64;
                let mut taps = [0.0; TAPS];
                for (index, tap) in taps.iter_mut().enumerate() {
                    let distance = index as f64 - RADIUS as f64 - fraction;
                    let window_phase = distance / RADIUS as f64;
                    if window_phase.abs() < 1.0 {
                        let x = cutoff * distance * PI;
                        let sinc = if x == 0.0 { 1.0 } else { x.sin() / x };
                        let window = 0.42
                            + 0.5 * (PI * window_phase).cos()
                            + 0.08 * (TAU * window_phase).cos();
                        *tap = cutoff * sinc * window;
                    }
                }
                let sum = taps.iter().sum::<f64>();
                weights.extend(taps.into_iter().map(|tap| (tap / sum) as f32));
            }
        }
        Some(Self {
            input_rate,
            phases,
            step,
            weights,
            history: [0.0; 2 * HISTORY],
            received: 0,
            produced: 0,
            center: 0,
            phase: 0,
            finished: false,
        })
    }

    fn total_output(&self, input_count: u64) -> u64 {
        // Match Hearth's lattice, including frame zero and the last real sample when aligned.
        match input_count.checked_sub(1) {
            Some(last) => last * OUTPUT_RATE as u64 / self.input_rate as u64 + 1,
            None => 0,
        }
    }

    fn store(&mut self, sample: f32) {
        let index = self.received as usize & (HISTORY - 1);
        self.history[index] = sample;
        // Mirroring gives each dot product a contiguous window even across a wrap.
        self.history[index + HISTORY] = sample;
        self.received += 1;
    }

    fn next(&mut self) -> f32 {
        let start = self.center.wrapping_sub(RADIUS as u64) as usize & (HISTORY - 1);
        let offset = self.phase * TAPS;
        let output = dot(
            &self.history[start..start + TAPS],
            &self.weights[offset..offset + TAPS],
        );
        self.phase += self.step;
        self.center += (self.phase / self.phases) as u64;
        self.phase %= self.phases;
        self.produced += 1;
        output
    }

    fn push(&mut self, input: &[f32], output: &mut [f32]) -> Option<usize> {
        if self.finished {
            return None;
        }
        let end = self.received.checked_add(input.len() as u64)?;
        if end > MAX_INPUT_FRAMES {
            return None;
        }
        let bypass = self.input_rate == OUTPUT_RATE;
        let ready = if bypass {
            end
        } else {
            let available = end.saturating_sub(RADIUS as u64);
            // A fractional output is ready once the tap window around its floor is present.
            (available * OUTPUT_RATE as u64).div_ceil(self.input_rate as u64)
        };
        let needed = usize::try_from(ready - self.produced).ok()?;
        let output = output.get_mut(..needed)?;
        if input.iter().any(|sample| !sample.is_finite()) {
            return None;
        }
        if bypass {
            output.copy_from_slice(input);
            self.received = end;
            self.produced += needed as u64;
        } else {
            let mut written = 0;
            for &sample in input {
                self.store(sample);
                if self.received > self.center + RADIUS as u64 {
                    output[written] = self.next();
                    written += 1;
                }
            }
            debug_assert_eq!(written, needed);
        }
        Some(needed)
    }

    fn finish(&mut self, output: &mut [f32]) -> Option<usize> {
        if self.finished {
            return Some(0);
        }
        let target = self.total_output(self.received);
        let needed = usize::try_from(target - self.produced).ok()?;
        let output = output.get_mut(..needed)?;
        for frame in output {
            while self.received <= self.center + RADIUS as u64 {
                self.store(0.0);
            }
            *frame = self.next();
        }
        self.finished = true;
        Some(needed)
    }
}

#[cfg(all(target_arch = "aarch64", target_feature = "neon"))]
fn dot(samples: &[f32], weights: &[f32]) -> f32 {
    use std::arch::aarch64::*;
    // Both slices contain exactly TAPS samples. Each vector load is in bounds.
    unsafe {
        let mut sum = vdupq_n_f32(0.0);
        let mut sum2 = vdupq_n_f32(0.0);
        for index in (0..TAPS - 1).step_by(8) {
            sum = vfmaq_f32(
                sum,
                vld1q_f32(samples.as_ptr().add(index)),
                vld1q_f32(weights.as_ptr().add(index)),
            );
            sum2 = vfmaq_f32(
                sum2,
                vld1q_f32(samples.as_ptr().add(index + 4)),
                vld1q_f32(weights.as_ptr().add(index + 4)),
            );
        }
        vaddvq_f32(vaddq_f32(sum, sum2)) + samples[TAPS - 1] * weights[TAPS - 1]
    }
}

#[cfg(not(all(target_arch = "aarch64", target_feature = "neon")))]
fn dot(samples: &[f32], weights: &[f32]) -> f32 {
    samples
        .iter()
        .zip(weights)
        .map(|(sample, weight)| sample * weight)
        .sum()
}

// The C++ runner serializes access and owns both disjoint PCM buffers. The handle
// comes from create and is consumed once by destroy. Null is only valid for empty buffers.
#[unsafe(no_mangle)]
extern "C" fn hearth_bench_src_create(input_rate: u32) -> *mut Resampler {
    Resampler::new(input_rate as usize)
        .map_or(std::ptr::null_mut(), |state| Box::into_raw(Box::new(state)))
}

#[unsafe(no_mangle)]
unsafe extern "C" fn hearth_bench_src_destroy(state: *mut Resampler) {
    if !state.is_null() {
        unsafe { drop(Box::from_raw(state)) };
    }
}

#[unsafe(no_mangle)]
unsafe extern "C" fn hearth_bench_src_push(
    state: *mut Resampler,
    input: *const f32,
    input_len: usize,
    output: *mut f32,
    output_len: usize,
) -> usize {
    let Some(state) = (unsafe { state.as_mut() }) else {
        return ERROR;
    };
    if !valid_buffer(input, input_len) || !valid_buffer(output.cast_const(), output_len) {
        return ERROR;
    }
    let input = if input_len == 0 {
        &[]
    } else {
        unsafe { slice::from_raw_parts(input, input_len) }
    };
    let output = if output_len == 0 {
        &mut []
    } else {
        unsafe { slice::from_raw_parts_mut(output, output_len) }
    };
    state.push(input, output).unwrap_or(ERROR)
}

#[unsafe(no_mangle)]
unsafe extern "C" fn hearth_bench_src_finish(
    state: *mut Resampler,
    output: *mut f32,
    output_len: usize,
) -> usize {
    let Some(state) = (unsafe { state.as_mut() }) else {
        return ERROR;
    };
    if !valid_buffer(output.cast_const(), output_len) {
        return ERROR;
    }
    let output = if output_len == 0 {
        &mut []
    } else {
        unsafe { slice::from_raw_parts_mut(output, output_len) }
    };
    state.finish(output).unwrap_or(ERROR)
}

fn valid_buffer(pointer: *const f32, frames: usize) -> bool {
    frames == 0
        || (!pointer.is_null()
            && pointer.is_aligned()
            && frames <= isize::MAX as usize / size_of::<f32>())
}
