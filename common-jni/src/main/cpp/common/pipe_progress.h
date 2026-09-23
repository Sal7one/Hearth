#ifndef STT_PIPE_PROGRESS_H
#define STT_PIPE_PROGRESS_H

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <fcntl.h>
#include <limits.h>
#include <mutex>
#include <string>
#include <poll.h>
#include <unistd.h>

#include "json_utils.h"

namespace stt {

/**
 * Progress reporting over a POSIX pipe fd the caller owns.
 *
 * Successor to the file-polling design in ffmpeg/progress_reporter.h +
 * media's ProgressWriter: instead of Kotlin re-reading a JSON file every
 * 150 ms (and a watchdog abandoning jobs it cannot cancel), updates are
 * pushed as newline-delimited JSON lines through a pipe the caller
 * created. One line per report() call; statuses are
 * PREPARING/PROCESSING/DONE/CANCELLED/FAILED.
 *
 * Wire format (field vocabulary identical to the legacy progress file so
 * a reader that understood {"progress":0.42,"status":"PROCESSING"},
 * {"status":"DONE","output":"…"} and {"status":"ERROR","message":"…"}
 * needs only the new status names; the legacy reader's "ERROR" is now
 * "FAILED" and DONE may carry an optional "output"):
 *   {"progress":0.0,"status":"PREPARING"}
 *   {"progress":0.42,"status":"PROCESSING"}
 *   {"status":"DONE"} or {"status":"DONE","output":"/path"}
 *   {"status":"CANCELLED"}
 *   {"status":"FAILED","message":"whisper_full failed: -1"}
 *
 * Contract:
 * - Binding makes the caller-owned pipe fd nonblocking; the caller keeps ownership —
 *   this class never closes a descriptor it did not open (close() is the
 *   caller's job; invalidate() merely detaches).
 * - Thread-safe: internal mutex serializes writes and state access.
 * - One report is one atomic pipe write, no larger than PIPE_BUF. Oversized
 *   terminal fields carry "truncated":true; the engine error remains separate.
 * - Never throws into the caller's loop: all I/O errors (EPIPE, EBADF,
 *   EINTR-retry exhaustion) latch the reporter dead and it silently stops
 *   writing. SIGPIPE must be ignored by the embedding process — Android's
 *   runtime already ignores it; host tests do it in main().
 * - Terminal statuses (DONE/CANCELLED/FAILED) are written once; later
 *   reports are dropped so DONE stays authoritative.
 */
class PipeProgress {
public:
    enum class Status { Preparing, Processing, Done, Cancelled, Failed };

    explicit PipeProgress(int fd = -1) noexcept : fd_(fd) { configureFdLocked(); }

    bool valid() const noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        return fd_ >= 0 && !dead_;
    }

    /** Detach without closing — caller retains fd ownership. */
    void invalidate() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        fd_ = -1;
    }

    /** Point at a new fd and clear latched state (rebinding between jobs). */
    void reattach(int fd) {
        std::lock_guard<std::mutex> lock(mutex_);
        fd_ = fd;
        dead_ = false;
        finished_ = false;
        configureFdLocked();
    }

    void reportPreparing() { writeLine(R"({"progress":0.0,"status":"PREPARING"})", false); }

    /** Fraction is clamped to [0,1]; non-finite values are dropped. */
    void report(float fraction) {
        if (!std::isfinite(fraction)) return;
        const float clamped = std::clamp(fraction, 0.0F, 1.0F);
        char buffer[64];
        std::snprintf(buffer, sizeof(buffer), "{\"progress\":%.4f,\"status\":\"PROCESSING\"}",
                      static_cast<double>(clamped));
        writeLine(buffer, false);
    }

    void reportDone(const std::string& output = "") {
        if (output.empty()) {
            writeLine(R"({"status":"DONE"})", true);
        } else {
            writeTerminalField("DONE", "output", output);
        }
    }

    void reportCancelled() { writeLine(R"({"status":"CANCELLED"})", true); }

    void reportFailed(const std::string& message) {
        writeTerminalField("FAILED", "message", message);
    }

    /** Lines dropped because the pipe was full (diagnostics only). */
    unsigned long droppedLines() const noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        return droppedLines_;
    }

private:
    void configureFdLocked() noexcept {
        if (fd_ < 0) return;
        const int flags = ::fcntl(fd_, F_GETFL, 0);
        if (flags < 0 || ::fcntl(fd_, F_SETFL, flags | O_NONBLOCK) < 0) {
            dead_ = true;
            return;
        }
        const long atomicSize = ::fpathconf(fd_, _PC_PIPE_BUF);
        lineLimit_ = atomicSize > 0
            ? std::min<std::size_t>(static_cast<std::size_t>(atomicSize), 4096U)
            : static_cast<std::size_t>(_POSIX_PIPE_BUF);
    }

    static std::size_t utf8Prefix(const std::string& value, std::size_t length) {
        length = std::min(length, value.size());
        while (length < value.size() && length > 0 &&
               (static_cast<unsigned char>(value[length]) & 0xC0U) == 0x80U) {
            --length;
        }
        return length;
    }

    void writeTerminalField(const char* status, const char* field, const std::string& value) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (fd_ < 0 || dead_ || finished_) return;
        const std::string prefix = std::string("{\"status\":\"") + status + "\",\"" + field + "\":\"";
        const std::string suffix = "\"}";
        const std::string truncatedSuffix = "\",\"truncated\":true}";
        const std::size_t budget = lineLimit_ > prefix.size() + truncatedSuffix.size() + 1
            ? lineLimit_ - prefix.size() - truncatedSuffix.size() - 1 : 0;
        std::size_t low = 0;
        std::size_t high = std::min(value.size(), budget);
        while (low < high) {
            const std::size_t mid = low + (high - low + 1) / 2;
            const std::size_t candidate = utf8Prefix(value, mid);
            if (JsonUtils::escape(value.substr(0, candidate)).size() <= budget) low = mid;
            else high = mid - 1;
        }
        const std::size_t prefixLength = utf8Prefix(value, low);
        const bool truncated = prefixLength < value.size();
        const std::string line = prefix + JsonUtils::escape(value.substr(0, prefixLength)) +
            (truncated ? truncatedSuffix : suffix);
        writeLineLocked(line, true);
    }

    /**
     * NON-BLOCKING delivery: a full pipe (slow/absent reader) degrades to
     * DROPPING the line, never to blocking the encode thread. Writability
     * is probed with poll(POLLOUT, 0) first. Binding makes the descriptor
     * nonblocking; a frame at most PIPE_BUF bytes is written atomically or
     * dropped. EPIPE/EBADF latch the reporter dead as before.
     *
     * Terminal lines (isTerminal=true) get a bounded retry window (up to
     * ~100 ms in 5 ms polls) because they carry the completion signal —
     * but the pipe is still never allowed to block the caller longer than
     * that, and the session's result does not depend on delivery.
     */
    void writeLine(const std::string& line, bool isTerminal) {
        std::lock_guard<std::mutex> lock(mutex_);
        writeLineLocked(line, isTerminal);
    }

    void writeLineLocked(const std::string& line, bool isTerminal) {
        if (fd_ < 0 || dead_ || finished_) return;
        const std::string framed = line + "\n";
        if (framed.size() > lineLimit_) {
            ++droppedLines_;
            return;
        }
        if (isTerminal) finished_ = true;
        int attempts = 0;
        int terminalRetries = isTerminal ? 20 : 0;
        while (true) {
            struct pollfd pfd{};
            pfd.fd = fd_;
            pfd.events = POLLOUT;
            const int ready = ::poll(&pfd, 1, isTerminal ? 5 : 0);
            if (ready == 0) {
                if (terminalRetries-- > 0) continue;  // bounded terminal retry
                ++droppedLines_;  // pipe full — progress is lossy, never blocking
                return;
            }
            if (ready < 0) {
                if (errno == EINTR && ++attempts < 8) continue;
                dead_ = true;
                return;
            }
            const ssize_t written = ::write(fd_, framed.data(), framed.size());
            if (written == static_cast<ssize_t>(framed.size())) return;
            if (written >= 0) {
                // Not expected for a nonblocking pipe write <= PIPE_BUF.
                dead_ = true;
                return;
            }
            if (written < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
                if (errno == EINTR && ++attempts < 8) continue;
                if (terminalRetries-- > 0) continue;
                ++droppedLines_;
                return;
            }
            dead_ = true;  // EPIPE, EBADF — go quiet
            return;
        }
    }

    int fd_;
    bool dead_ = false;
    unsigned long droppedLines_ = 0;
    bool finished_ = false;
    std::size_t lineLimit_ = _POSIX_PIPE_BUF;
    mutable std::mutex mutex_;
};

} // namespace stt

#endif // STT_PIPE_PROGRESS_H
