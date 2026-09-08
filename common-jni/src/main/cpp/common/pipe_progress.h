#ifndef STT_PIPE_PROGRESS_H
#define STT_PIPE_PROGRESS_H

#include <algorithm>
#include <cerrno>
#include <cmath>
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
 * - open(fd) validates nothing but fd >= 0; the caller keeps ownership —
 *   this class never closes a descriptor it did not open (close() is the
 *   caller's job; invalidate() merely detaches).
 * - Thread-safe: internal mutex serializes writes.
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

    explicit PipeProgress(int fd = -1) noexcept : fd_(fd) {}

    bool valid() const noexcept { return fd_ >= 0 && !dead_; }

    /** Detach without closing — caller retains fd ownership. */
    void invalidate() noexcept { fd_ = -1; }

    /** Point at a new fd and clear latched state (rebinding between jobs). */
    void reattach(int fd) {
        std::lock_guard<std::mutex> lock(mutex_);
        fd_ = fd;
        dead_ = false;
        finished_ = false;
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
            writeLine("{\"status\":\"DONE\",\"output\":\"" + JsonUtils::escape(output) + "\"}", true);
        }
    }

    void reportCancelled() { writeLine(R"({"status":"CANCELLED"})", true); }

    void reportFailed(const std::string& message) {
        writeLine("{\"status\":\"FAILED\",\"message\":\"" + JsonUtils::escape(message) + "\"}", true);
    }

    /** Lines dropped because the pipe was full (diagnostics only). */
    unsigned long droppedLines() const noexcept { return droppedLines_; }

private:
    /**
     * NON-BLOCKING delivery: a full pipe (slow/absent reader) degrades to
     * DROPPING the line, never to blocking the encode thread. Writability
     * is probed with poll(POLLOUT, 0) first so even a blocking-mode fd
     * cannot stall us; EAGAIN/EWOULDBLOCK from the write itself drops the
     * line too. EPIPE/EBADF latch the reporter dead as before.
     *
     * Terminal lines (isTerminal=true) get a bounded retry window (up to
     * ~100 ms in 5 ms polls) because they carry the completion signal —
     * but the pipe is still never allowed to block the caller longer than
     * that, and the session's result does not depend on delivery.
     */
    void writeLine(const std::string& line, bool isTerminal) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (fd_ < 0 || dead_ || finished_) return;
        const std::string framed = line + "\n";
        const char* data = framed.data();
        std::size_t remaining = framed.size();
        int attempts = 0;
        int terminalRetries = isTerminal ? 20 : 0;
        while (remaining > 0) {
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
            const ssize_t written = ::write(fd_, data, remaining);
            if (written > 0) {
                data += written;
                remaining -= static_cast<std::size_t>(written);
                continue;
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
        if (isTerminal) {
            finished_ = true;
        }
    }

    int fd_;
    bool dead_ = false;
    unsigned long droppedLines_ = 0;
    bool finished_ = false;
    std::mutex mutex_;
};

} // namespace stt

#endif // STT_PIPE_PROGRESS_H
