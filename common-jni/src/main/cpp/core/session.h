#ifndef STT_SESSION_H
#define STT_SESSION_H

#include <atomic>
#include <chrono>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <unordered_map>

namespace stt {

enum class SessionState {
    CREATED = 0,
    RUNNING = 1,
    COMPLETED = 2,
    FAILED = 3,
    CANCELLED = 4
};

enum class SessionType {
    TRANSCRIPTION = 1,
    VAD = 2,
    AUDIO_DECODE = 3
};

struct TranscriptionProgress {
    float progress;          // 0.0 - 1.0
    int64_t processedMs;     // Audio processed so far
    int64_t totalMs;         // Total audio duration
    int segmentsCompleted;
    std::string currentText; // Partial text
};

using ProgressCallback = std::function<void(const TranscriptionProgress&)>;
using LogCallback = std::function<void(int level, const std::string& message)>;
using CompleteCallback = std::function<void(long sessionId, SessionState state)>;

class Session {
public:
    explicit Session(SessionType type);
    ~Session();
    
    // Identifiers
    long getId() const { return id_; }
    SessionType getType() const { return type_; }
    
    // State
    SessionState getState() const { return state_.load(); }
    void setState(SessionState state);
    
    // Timing
    int64_t getCreateTimeMs() const { return createTimeMs_; }
    int64_t getStartTimeMs() const { return startTimeMs_; }
    int64_t getEndTimeMs() const { return endTimeMs_; }
    int64_t getDurationMs() const;
    
    void markStarted();
    void markEnded(SessionState finalState);
    
    // Cancellation
    void cancel();
    bool isCancelled() const { return state_.load() == SessionState::CANCELLED; }
    bool shouldStop() const;
    
    // Callbacks
    void setProgressCallback(ProgressCallback cb) { progressCb_ = std::move(cb); }
    void setLogCallback(LogCallback cb) { logCb_ = std::move(cb); }
    void setCompleteCallback(CompleteCallback cb) { completeCb_ = std::move(cb); }
    
    void reportProgress(const TranscriptionProgress& progress);
    void log(int level, const std::string& message);
    
    // Result
    void setResult(const std::string& result) { result_ = result; }
    const std::string& getResult() const { return result_; }
    
    void setError(const std::string& error) { error_ = error; }
    const std::string& getError() const { return error_; }

private:
    static std::atomic<long> nextId_;
    
    long id_;
    SessionType type_;
    std::atomic<SessionState> state_{SessionState::CREATED};
    
    int64_t createTimeMs_;
    int64_t startTimeMs_{0};
    int64_t endTimeMs_{0};
    
    ProgressCallback progressCb_;
    LogCallback logCb_;
    CompleteCallback completeCb_;
    
    std::string result_;
    std::string error_;
    
    mutable std::mutex mutex_;
};

// Session manager (singleton)
class SessionManager {
public:
    static SessionManager& getInstance();
    
    std::shared_ptr<Session> createSession(SessionType type);
    std::shared_ptr<Session> getSession(long id);
    void removeSession(long id);
    void cancelAll();
    
    std::vector<std::shared_ptr<Session>> getAllSessions();
    
private:
    SessionManager() = default;
    std::unordered_map<long, std::shared_ptr<Session>> sessions_;
    mutable std::mutex mutex_;
};

} // namespace stt

#endif // STT_SESSION_H

