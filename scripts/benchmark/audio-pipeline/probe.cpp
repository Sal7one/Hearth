#include <algorithm>
#include <array>
#include <chrono>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {

// These projects use positioned reads for editor probes and cap the work
// done while walking chunks. This standalone strict PCM probe compares
// transport strategies with identical parsing. Hearth uses platform decoders.
// https://github.com/storytold/filmcraft/blob/f4090d7b34a467a1bcabc91652d166a94f68dfbe/crates/media/src/reader.rs
// https://github.com/storytold/soundcraft/blob/ef58a90f4a418a282a15fc71d2e03c3d8e8f40fa/crates/audio-io/src/wav.rs
constexpr unsigned chunkBudget = 256;
constexpr uint32_t largeJunk = 32 * 1024 * 1024 + 1;

struct Fragment {
    uint64_t offset;
    std::vector<uint8_t> bytes;
};

struct VirtualFile {
    uint64_t size = 0;
    std::vector<Fragment> fragments;
};

enum class Access { sequential, positioned };
enum class Status { valid, truncated, malformed, unsupported, chunkBudgetExceeded, missingAudio };

struct Layout {
    Status status = Status::missingAudio;
    uint64_t dataOffset = 0;
    uint64_t dataBytes = 0;
    uint32_t sampleRate = 0;
    uint16_t channels = 0;
    uint16_t bits = 0;
    uint16_t block = 0;
};

struct ReadCount {
    uint64_t bytes = 0;
    uint64_t calls = 0;
};

bool within(uint64_t start, uint64_t count, uint64_t end) {
    return start <= end && count <= end - start;
}

class Reader {
public:
    Reader(const VirtualFile& source, Access access) : source_(source), access_(access) {}

    bool read(uint8_t* target, size_t count) {
        if (!within(position_, count, source_.size)) return false;
        readAt(target, count);
        position_ += count;
        return true;
    }

    bool skip(uint64_t count) {
        if (!within(position_, count, source_.size)) return false;
        if (access_ == Access::positioned) {
            position_ += count;
            return true;
        }
        // Read and discard with bounded scratch, as on a sequential stream
        // without a seek. The positioned reader skips the same byte range.
        std::array<uint8_t, 8192> scratch;
        while (count != 0) {
            const auto block = static_cast<size_t>(std::min<uint64_t>(count, scratch.size()));
            readAt(scratch.data(), block);
            discarded_ = scratch[block - 1];
            position_ += block;
            count -= block;
        }
        return true;
    }

    uint64_t position() const { return position_; }
    uint64_t length() const { return source_.size; }
    ReadCount count() const { return count_; }

private:
    // Expand virtual holes into the supplied buffer so the sequential
    // comparator pays for moving the requested bytes without disk variance.
#if defined(__clang__) || defined(__GNUC__)
    [[gnu::noinline]]
#endif
    void readAt(uint8_t* target, size_t count) {
        std::memset(target, 0, count);
        const uint64_t end = position_ + count;
        for (const auto& fragment : source_.fragments) {
            const uint64_t start = std::max(position_, fragment.offset);
            const uint64_t stop = std::min(end, fragment.offset + fragment.bytes.size());
            if (start < stop) {
                std::memcpy(target + (start - position_),
                            fragment.bytes.data() + (start - fragment.offset),
                            static_cast<size_t>(stop - start));
            }
        }
        count_.bytes += count;
        ++count_.calls;
    }

    const VirtualFile& source_;
    Access access_;
    uint64_t position_ = 0;
    ReadCount count_;
    volatile uint8_t discarded_ = 0;
};

uint16_t little16(const uint8_t* data) {
    return static_cast<uint16_t>(data[0] | static_cast<unsigned>(data[1]) << 8);
}

uint32_t little32(const uint8_t* data) {
    return uint32_t(data[0]) | uint32_t(data[1]) << 8 | uint32_t(data[2]) << 16 | uint32_t(data[3]) << 24;
}

bool hasTag(const uint8_t* data, const char (&tag)[5]) {
    return std::memcmp(data, tag, 4) == 0;
}

Layout probe(Reader& reader) {
    std::array<uint8_t, 12> header;
    if (!reader.read(header.data(), header.size())) return {Status::truncated};
    if (!hasTag(header.data(), "RIFF") || !hasTag(header.data() + 8, "WAVE")) return {Status::unsupported};
    const uint64_t end = 8 + uint64_t(little32(header.data() + 4));
    if (end < header.size()) return {Status::malformed};
    if (end > reader.length()) return {Status::truncated};

    Layout result;
    for (unsigned chunks = 0; reader.position() < end; ++chunks) {
        if (chunks == chunkBudget) return {Status::chunkBudgetExceeded};
        std::array<uint8_t, 8> chunk;
        if (!within(reader.position(), chunk.size(), end) || !reader.read(chunk.data(), chunk.size())) {
            return {Status::truncated};
        }
        const uint64_t size = little32(chunk.data() + 4);
        const uint64_t padded = size + (size & 1);
        if (!within(reader.position(), padded, end)) return {Status::truncated};
        if (hasTag(chunk.data(), "fmt ")) {
            // A strict finalized RIFF PCM/WAVE subset. Other layouts can go
            // to the platform decoder at integration time.
            if (result.block != 0 || size < 16) return {Status::malformed};
            std::array<uint8_t, 16> format;
            if (!reader.read(format.data(), format.size())) return {Status::truncated};
            if (little16(format.data()) != 1) return {Status::unsupported};
            result.channels = little16(format.data() + 2);
            result.sampleRate = little32(format.data() + 4);
            result.block = little16(format.data() + 12);
            result.bits = little16(format.data() + 14);
            const bool pcmBits = result.bits == 8 || result.bits == 16 ||
                                 result.bits == 24 || result.bits == 32;
            if (!pcmBits || result.channels == 0 || result.sampleRate == 0 ||
                uint32_t(result.channels) * (result.bits / 8) != result.block ||
                uint64_t(result.block) * result.sampleRate != little32(format.data() + 8)) {
                return {Status::malformed};
            }
            if (!reader.skip(padded - format.size())) return {Status::truncated};
        } else if (hasTag(chunk.data(), "data")) {
            if (result.block == 0 || size == 0 || size % result.block != 0) return {Status::malformed};
            result.status = Status::valid;
            result.dataOffset = reader.position();
            result.dataBytes = size;
            return result;
        } else {
            if (!reader.skip(padded)) return {Status::truncated};
        }
    }
    return {Status::missingAudio};
}

void appendTag(std::vector<uint8_t>& bytes, const char (&tag)[5]) {
    bytes.insert(bytes.end(), tag, tag + 4);
}

void appendLittle(std::vector<uint8_t>& bytes, uint32_t value, unsigned width) {
    for (unsigned i = 0; i < width; ++i) bytes.push_back(static_cast<uint8_t>(value >> (i * 8)));
}

class Fixture {
public:
    Fixture() {
        std::vector<uint8_t> header;
        appendTag(header, "RIFF");
        appendLittle(header, 0, 4);
        appendTag(header, "WAVE");
        file_ = {header.size(), {{0, std::move(header)}}};
    }

    uint64_t chunk(const char (&tag)[5], uint32_t size, std::vector<uint8_t> prefix = {}) {
        if (prefix.size() > size) throw std::logic_error("Fixture prefix exceeds declared chunk size");
        std::vector<uint8_t> header;
        appendTag(header, tag);
        appendLittle(header, size, 4);
        header.insert(header.end(), prefix.begin(), prefix.end());
        const uint64_t body = file_.size + 8;
        file_.fragments.push_back({file_.size, std::move(header)});
        file_.size = body + uint64_t(size) + (size & 1);
        return body;
    }

    void format(uint16_t tag = 1, uint16_t block = 4) {
        std::vector<uint8_t> format;
        appendLittle(format, tag, 2);
        appendLittle(format, 2, 2);
        appendLittle(format, 48000, 4);
        appendLittle(format, 192000, 4);
        appendLittle(format, block, 2);
        appendLittle(format, 16, 2);
        chunk("fmt ", static_cast<uint32_t>(format.size()), format);
    }

    VirtualFile finish() {
        if (file_.size - 8 > std::numeric_limits<uint32_t>::max()) {
            throw std::logic_error("Fixture exceeds RIFF length");
        }
        auto& bytes = file_.fragments.front().bytes;
        for (unsigned i = 0; i < 4; ++i) bytes[4 + i] = static_cast<uint8_t>((file_.size - 8) >> (i * 8));
        return std::move(file_);
    }

private:
    VirtualFile file_;
};

void require(bool valid, const char* message) {
    if (!valid) throw std::runtime_error(message);
}

void check(const VirtualFile& file, Status expected, uint64_t offset = 0) {
    for (const auto access : {Access::sequential, Access::positioned}) {
        Reader reader(file, access);
        const auto result = probe(reader);
        require(result.status == expected, "RIFF probe fixture status mismatch");
        if (result.status == Status::valid) {
            require(result.dataOffset == offset && result.dataBytes == 4096 &&
                    result.channels == 2 && result.sampleRate == 48000 &&
                    result.bits == 16 && result.block == 4,
                    "RIFF probe fixture layout mismatch");
        }
        require(reader.count().bytes <= file.size, "RIFF probe read beyond virtual extent");
    }
}

unsigned verifyFixtures(const VirtualFile& valid, uint64_t offset) {
    check(valid, Status::valid, offset); // Odd JUNK size and alignment.
    Fixture minimal;
    minimal.format();
    const auto start = minimal.chunk("data", 4096);
    const auto file = minimal.finish();
    check(file, Status::valid, start);
    auto shortFile = file;
    --shortFile.size;
    check(shortFile, Status::truncated);
    auto escapedChunk = file;
    std::fill(escapedChunk.fragments[1].bytes.begin() + 4,
              escapedChunk.fragments[1].bytes.begin() + 8, 0xff);
    check(escapedChunk, Status::truncated);
    auto invalidSignature = file;
    invalidSignature.fragments[0].bytes[8] = 'X';
    check(invalidSignature, Status::unsupported);
    Fixture noPad;
    noPad.chunk("JUNK", 3);
    auto unpadded = noPad.finish();
    --unpadded.size;
    --unpadded.fragments[0].bytes[4];
    check(unpadded, Status::truncated);
    Fixture budget;
    for (unsigned i = 0; i < chunkBudget; ++i) budget.chunk("JUNK", 0);
    budget.format();
    budget.chunk("data", 4096);
    check(budget.finish(), Status::chunkBudgetExceeded);
    Fixture invalidFrame;
    invalidFrame.format();
    invalidFrame.chunk("data", 4095);
    check(invalidFrame.finish(), Status::malformed);
    Fixture invalidFormat;
    invalidFormat.format(1, 2);
    check(invalidFormat.finish(), Status::malformed);
    Fixture codec;
    codec.format(3);
    check(codec.finish(), Status::unsupported);
    Fixture empty;
    empty.format();
    empty.chunk("data", 0);
    check(empty.finish(), Status::malformed);
    Fixture missing;
    missing.format();
    check(missing.finish(), Status::missingAudio);
    return 12;
}

struct Timing {
    std::vector<double> ns;
    ReadCount reads;
};

void measure(const VirtualFile& source, uint64_t offset, Access access, unsigned repeats, Timing& timing) {
    using Clock = std::chrono::steady_clock;
    const auto started = Clock::now();
    for (unsigned i = 0; i < repeats; ++i) {
        Reader reader(source, access);
        const auto found = probe(reader);
        require(found.status == Status::valid && found.dataOffset == offset &&
                found.dataBytes == 4096, "Timed RIFF probe failed");
        timing.reads = reader.count();
    }
    const double ns = std::chrono::duration<double, std::nano>(Clock::now() - started).count();
    timing.ns.push_back(ns / repeats);
}

void report(const VirtualFile& file, uint64_t offset, const char* scenario,
            const char* method, Timing& timing, unsigned cases, unsigned repeats) {
    std::sort(timing.ns.begin(), timing.ns.end());
    const size_t count = timing.ns.size();
    std::printf(
        "{\"suite\":\"probe\",\"scenario\":\"%s\",\"method\":\"%s\","
        "\"fixture\":\"virtual_sparse_riff_pcm\",\"file_bytes\":%" PRIu64 ","
        "\"bytes_read\":%" PRIu64 ",\"read_calls\":%" PRIu64 ","
        "\"data_offset\":%" PRIu64 ",\"data_bytes\":4096,"
        "\"p50_ns\":%.2f,\"p95_ns\":%.2f,\"trials\":%zu,\"probes_per_trial\":%u,"
        "\"fixture_cases_passed\":%u,\"chunk_budget\":%u}\n",
        scenario, method, file.size, timing.reads.bytes, timing.reads.calls,
        offset, timing.ns[count / 2], timing.ns[(count * 95 + 99) / 100 - 1],
        count, repeats, cases, chunkBudget);
}

void compare(const VirtualFile& file, uint64_t offset, const char* scenario, unsigned cases) {
    constexpr unsigned trials = 15;
    // Batch the short probes to reduce the effect of the clock's resolution.
    const unsigned sequentialRepeats = file.size > 1024 * 1024 ? 1 : 256;
    constexpr unsigned positionedRepeats = 256;
    Timing sequential, positioned;
    measure(file, offset, Access::sequential, 1, sequential);
    measure(file, offset, Access::positioned, 1, positioned);
    sequential.ns.clear();
    positioned.ns.clear();
    for (unsigned trial = 0; trial < trials; ++trial) {
        if (trial % 2 == 0) {
            measure(file, offset, Access::sequential, sequentialRepeats, sequential);
            measure(file, offset, Access::positioned, positionedRepeats, positioned);
        } else {
            measure(file, offset, Access::positioned, positionedRepeats, positioned);
            measure(file, offset, Access::sequential, sequentialRepeats, sequential);
        }
    }
    require(positioned.reads.bytes <= sequential.reads.bytes, "Positioned probe read excess bytes");
    report(file, offset, scenario, "sequential_read_discard", sequential, cases, sequentialRepeats);
    report(file, offset, scenario, "bounded_positioned_skip", positioned, cases, positionedRepeats);
}

}  // namespace

void run_probe_benchmark() {
    Fixture large;
    large.chunk("JUNK", largeJunk);
    large.format();
    const uint64_t largeOffset = large.chunk("data", 4096);
    const auto largeFile = large.finish();
    const unsigned cases = verifyFixtures(largeFile, largeOffset);

    Fixture small;
    small.chunk("JUNK", 127);
    small.format();
    const uint64_t smallOffset = small.chunk("data", 4096);
    compare(small.finish(), smallOffset, "127_byte_metadata", cases);
    compare(largeFile, largeOffset, "32_mib_metadata", cases);
}
