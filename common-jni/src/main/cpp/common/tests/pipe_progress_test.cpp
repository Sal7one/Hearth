// pipe_progress_test.cpp — direct unit tests for common/pipe_progress.h.
#include <cassert>
#include <chrono>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <string>
#include <thread>
#include <unistd.h>

#include "pipe_progress.h"
#include "json_utils.h"

using stt::PipeProgress;

static int failures = 0;
#define CHECK(cond)                                              \
    do {                                                         \
        if (!(cond)) {                                           \
            ++failures;                                          \
            std::cerr << "FAIL " << __LINE__ << ": " #cond "\n"; \
        }                                                        \
    } while (0)

static std::string readLine(int fd) {
    std::string line;
    char c = 0;
    while (::read(fd, &c, 1) == 1 && c != '\n') line += c;
    return line;
}

int main() {
    ::signal(SIGPIPE, SIG_IGN);  // Android ignores SIGPIPE; host tests do the same

    int fds[2];
    CHECK(::pipe(fds) == 0);
    // Non-blocking read end: "nothing else was written" checks see EAGAIN
    // immediately instead of blocking on a pipe whose write end stays open.
    CHECK(::fcntl(fds[0], F_SETFL, O_NONBLOCK) == 0);

    // Wire format: one JSON line per status, exact field vocabulary
    {
        PipeProgress progress(fds[1]);
        CHECK(progress.valid());
        progress.reportPreparing();
        progress.report(0.42F);
        progress.reportDone("/out/x.wav");
        // Terminal status written once; later reports dropped
        progress.report(0.9F);
        progress.reportFailed("late");

        CHECK(readLine(fds[0]) == R"({"progress":0.0,"status":"PREPARING"})");
        CHECK(readLine(fds[0]) == R"({"progress":0.4200,"status":"PROCESSING"})");
        CHECK(readLine(fds[0]) == R"({"status":"DONE","output":"/out/x.wav"})");
        char extra = 'x';
        CHECK(::read(fds[0], &extra, 1) == -1);  // EAGAIN: nothing else was written
    }

    // Clamping and non-finite rejection
    {
        PipeProgress progress(fds[1]);
        progress.report(2.0F);
        progress.report(-0.5F);
        progress.report(1.0F);
        progress.reportCancelled();
        CHECK(readLine(fds[0]) == R"({"progress":1.0000,"status":"PROCESSING"})");
        CHECK(readLine(fds[0]) == R"({"progress":0.0000,"status":"PROCESSING"})");
        CHECK(readLine(fds[0]) == R"({"progress":1.0000,"status":"PROCESSING"})");
        CHECK(readLine(fds[0]) == R"({"status":"CANCELLED"})");
    }

    // FAILED escapes its message
    {
        PipeProgress progress(fds[1]);
        progress.reportFailed("boom \"quoted\"");
        CHECK(readLine(fds[0]) == R"({"status":"FAILED","message":"boom \"quoted\""})");
    }

    // A long terminal field is explicit about truncation, remains parseable,
    // and fits in one atomic pipe write even after Unicode JSON escaping.
    {
        int large[2];
        CHECK(::pipe(large) == 0);
        PipeProgress progress(large[1]);
        progress.reportFailed(std::string(2000, 'x') + "\xe4\xb8\xad");
        const std::string line = readLine(large[0]);
        CHECK(line.size() + 1 <= static_cast<size_t>(::fpathconf(large[1], _PC_PIPE_BUF)));
        stt::JsonValue parsed;
        std::string error;
        CHECK(stt::JsonUtils::parse(line, parsed, &error));
        CHECK(stt::JsonUtils::getBool(line, "truncated"));
        CHECK(stt::JsonUtils::getString(line, "status") == "FAILED");
        CHECK(!stt::JsonUtils::getString(line, "message").empty());
        ::close(large[0]);
        ::close(large[1]);
    }

    // With less than PIPE_BUF free, an oversized FAILED line is wholly
    // dropped; the next reader sees no partial JSON fragment.
    {
        int full[2];
        CHECK(::pipe(full) == 0);
        PipeProgress progress(full[1]);
        CHECK(::fcntl(full[0], F_SETFL, O_NONBLOCK) == 0);
        char junk[512];
        std::memset(junk, 'x', sizeof(junk));
        size_t filled = 0;
        while (true) {
            const ssize_t n = ::write(full[1], junk, sizeof(junk));
            if (n < 0 && errno == EAGAIN) break;
            CHECK(n > 0);
            if (n <= 0) break;
            filled += static_cast<size_t>(n);
        }
        char consumed[64];
        CHECK(::read(full[0], consumed, sizeof(consumed)) == sizeof(consumed));
        progress.reportFailed(std::string(2000, 'y'));
        CHECK(progress.droppedLines() == 1);
        size_t remaining = 0;
        while (true) {
            const ssize_t n = ::read(full[0], junk, sizeof(junk));
            if (n < 0 && errno == EAGAIN) break;
            CHECK(n > 0);
            if (n <= 0) break;
            remaining += static_cast<size_t>(n);
            for (ssize_t i = 0; i < n; ++i) CHECK(junk[i] == 'x');
        }
        CHECK(remaining + sizeof(consumed) == filled);
        ::close(full[0]);
        ::close(full[1]);
    }

    // Dead pipe: writes latch dead, never throw, invalidate detaches
    {
        int dead[2];
        CHECK(::pipe(dead) == 0);
        ::close(dead[0]);
        PipeProgress progress(dead[1]);
        progress.reportPreparing();
        CHECK(!progress.valid());
        progress.report(0.5F);  // silently dropped
        progress.invalidate();
        ::close(dead[1]);
    }

    // Invalid fd is inert
    {
        PipeProgress progress(-1);
        CHECK(!progress.valid());
        progress.reportDone();
    }

    // FULL PIPE (device regression, hearth-v45): the writer must never
    // block and never crash — a full pipe degrades to dropping lines.
    {
        int full[2];
        CHECK(::pipe(full) == 0);
        // Fill the pipe to capacity using non-blocking writes.
        const int flags = ::fcntl(full[1], F_GETFL, 0);
        ::fcntl(full[1], F_SETFL, flags | O_NONBLOCK);
        char junk[512] = {};
        bool filled = false;
        for (int i = 0; i < 4096; ++i) {
            if (::write(full[1], junk, sizeof(junk)) < 0 && errno == EAGAIN) {
                filled = true;
                break;
            }
        }
        CHECK(filled);  // pipe is now full — writes must return immediately
        PipeProgress progress(full[1]);
        const auto begin = std::chrono::steady_clock::now();
        progress.reportPreparing();
        for (int i = 0; i < 1000; ++i) progress.report(0.5F);  // would block forever on a blocking fd
        const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - begin).count();
        CHECK(elapsedMs < 2000);          // non-blocking: returns promptly
        CHECK(progress.droppedLines() > 0);  // and the lines were dropped, not written
        CHECK(progress.valid());          // still alive: a later reader unblocks it
        ::close(full[0]);
        ::close(full[1]);
    }

    // CLOSED READ END: writes latch dead quickly (bounded terminal retry),
    // never block, never crash.
    {
        int closed[2];
        CHECK(::pipe(closed) == 0);
        ::close(closed[0]);
        PipeProgress progress(closed[1]);
        const auto begin = std::chrono::steady_clock::now();
        progress.reportPreparing();
        progress.report(0.25F);
        progress.reportDone("/out.wav");
        const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - begin).count();
        CHECK(elapsedMs < 2000);  // EPIPE latches dead instead of blocking/signalling
        CHECK(!progress.valid());
        ::close(closed[1]);
    }

    // Thread-safety: two threads interleaving reports never corrupt a line
    {
        PipeProgress progress(fds[1]);
        std::thread a([&] {
            for (int i = 0; i < 50; ++i) progress.report(0.01F * (i % 100));
        });
        std::thread b([&] {
            for (int i = 0; i < 50; ++i) progress.report(0.01F * ((i + 50) % 100));
        });
        a.join();
        b.join();
        int lines = 0;
        std::string line;
        char c = 0;
        while (::read(fds[0], &c, 1) == 1) {
            if (c == '\n') {
                ++lines;
                CHECK(line.find("\"status\":\"PROCESSING\"") != std::string::npos);
                CHECK(line.front() == '{' && line.back() == '}');
                line.clear();
            } else {
                line += c;
            }
        }
        CHECK(lines == 100);
    }

    // State readers and rebinding may run concurrently with progress writers.
    {
        int concurrent[2];
        CHECK(::pipe(concurrent) == 0);
        PipeProgress progress(concurrent[1]);
        std::thread writer([&] {
            for (int i = 0; i < 200; ++i) progress.report(0.5F);
        });
        std::thread rebinder([&] {
            for (int i = 0; i < 200; ++i) {
                progress.invalidate();
                progress.reattach(concurrent[1]);
            }
        });
        std::thread reader([&] {
            for (int i = 0; i < 200; ++i) {
                (void)progress.valid();
                (void)progress.droppedLines();
            }
        });
        writer.join();
        rebinder.join();
        reader.join();
        CHECK(progress.valid());
        ::close(concurrent[0]);
        ::close(concurrent[1]);
    }

    ::close(fds[0]);
    ::close(fds[1]);

    if (failures == 0) {
        std::cout << "pipe_progress_test: ALL PASS\n";
        return 0;
    }
    std::cout << "pipe_progress_test: " << failures << " FAILURES\n";
    return 1;
}
