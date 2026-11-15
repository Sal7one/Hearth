//! Benchmark-only frame ring. Inspired by SoundCraft's capture SPSC handoff.
//!
//! An owner creates and destroys the ring. Exactly one producer calls push/finish
//! and one consumer calls pop. The owner joins both sides before destroy.

use std::cell::UnsafeCell;
use std::ptr;
use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};

const FRAME_BYTES: usize = 3_200;
const CAPACITY: usize = 16;
const OPEN: i32 = 0;
const FRAME: i32 = 1;
const END: i32 = 2;
const INVALID: i32 = -1;
const OVERFLOW: i32 = -2;
const CLOSED: i32 = -3;

struct FrameSlot {
    length: usize,
    bytes: [u8; FRAME_BYTES],
}

#[repr(align(64))]
struct Cursor(AtomicUsize);

pub struct BenchPcmRing {
    head: Cursor,
    tail: Cursor,
    state: AtomicI32,
    slots: Box<[UnsafeCell<FrameSlot>]>,
}

// SAFETY: A producer owns a slot until it publishes head with Release. After
// observing that publication with Acquire, the consumer owns the slot until it
// releases tail. The producer checks tail with Acquire before reusing a slot.
unsafe impl Sync for BenchPcmRing {}

#[unsafe(no_mangle)]
pub extern "C" fn hearth_bench_pcm_ring_create() -> *mut BenchPcmRing {
    let slots = (0..CAPACITY)
        .map(|_| UnsafeCell::new(FrameSlot {
            length: 0,
            bytes: [0; FRAME_BYTES],
        }))
        .collect();
    Box::into_raw(Box::new(BenchPcmRing {
        head: Cursor(AtomicUsize::new(0)),
        tail: Cursor(AtomicUsize::new(0)),
        state: AtomicI32::new(OPEN),
        slots,
    }))
}

/// The caller must own the live pointer and join the producer and consumer first.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hearth_bench_pcm_ring_destroy(ring: *mut BenchPcmRing) {
    if !ring.is_null() {
        // SAFETY: The C caller gives back the unique handle created above.
        drop(unsafe { Box::from_raw(ring) });
    }
}

/// Copies one frame from the producer. A failed push makes the ring terminal.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hearth_bench_pcm_ring_push(
    ring: *const BenchPcmRing,
    input: *const u8,
    length: usize,
) -> i32 {
    // SAFETY: The handle must be live throughout each call, including on failure.
    let Some(ring) = (unsafe { ring.as_ref() }) else {
        return INVALID;
    };
    let state = ring.state.load(Ordering::Acquire);
    if state != OPEN {
        return if state == END { CLOSED } else { state };
    }
    if input.is_null() || length == 0 || length > FRAME_BYTES || length % 2 != 0 {
        ring.state.store(INVALID, Ordering::Release);
        return INVALID;
    }
    let head = ring.head.0.load(Ordering::Relaxed);
    let tail = ring.tail.0.load(Ordering::Acquire);
    if head.wrapping_sub(tail) >= CAPACITY {
        ring.state.store(OVERFLOW, Ordering::Release);
        return OVERFLOW;
    }
    // SAFETY: Only the producer owns this free slot. The caller supplies a
    // nonoverlapping readable region of length bytes.
    let slot = unsafe { &mut *ring.slots[head % CAPACITY].get() };
    unsafe { ptr::copy_nonoverlapping(input, slot.bytes.as_mut_ptr(), length) };
    slot.length = length;
    ring.head.0.store(head.wrapping_add(1), Ordering::Release);
    FRAME
}

/// Copies one frame into caller-owned storage. Output must fit a maximum-sized
/// frame. Returns 1 for a frame, 0 for empty, 2 for drained, or a negative error.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hearth_bench_pcm_ring_pop(
    ring: *const BenchPcmRing,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> i32 {
    // SAFETY: The handle must be live. Output and written must be writable,
    // nonoverlapping caller storage.
    let Some(ring) = (unsafe { ring.as_ref() }) else {
        return INVALID;
    };
    if output.is_null() || capacity < FRAME_BYTES || written.is_null() {
        return INVALID;
    }
    unsafe { written.write(0) };
    let state = ring.state.load(Ordering::Acquire);
    if state < 0 {
        return state;
    }
    let tail = ring.tail.0.load(Ordering::Relaxed);
    if tail == ring.head.0.load(Ordering::Acquire) {
        return state;
    }
    // SAFETY: The Acquire on head makes the initialized slot visible. The
    // producer cannot reuse it before this call releases tail.
    let slot = unsafe { &*ring.slots[tail % CAPACITY].get() };
    let length = slot.length;
    unsafe { ptr::copy_nonoverlapping(slot.bytes.as_ptr(), output, length) };
    ring.tail.0.store(tail.wrapping_add(1), Ordering::Release);
    let state = ring.state.load(Ordering::Acquire);
    if state < 0 {
        return state;
    }
    unsafe { written.write(length) };
    FRAME
}

/// The producer calls this after publishing its final short frame.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hearth_bench_pcm_ring_finish(ring: *const BenchPcmRing) {
    // SAFETY: The producer holds a live handle and all pushes have finished.
    if let Some(ring) = unsafe { ring.as_ref() } {
        let _ = ring.state.compare_exchange(OPEN, END, Ordering::Release, Ordering::Relaxed);
    }
}
