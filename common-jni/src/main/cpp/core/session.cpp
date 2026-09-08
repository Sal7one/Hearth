#include "session.h"
#include <chrono>

namespace stt {

std::atomic<long> Session::nextId_{1};

static int64_t currentTimeMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

Session::Session(SessionType type) 
    : id_(nextId_.fetch_add(1))
    , type_(type)
    , createTimeMs_(currentTimeMs()) {
}

Session::~Session() = default;

void Session::setState(SessionState state) {
    state_.store(state);
}

int64_t Session::getDurationMs() const {
    if (endTimeMs_ > 0 && startTimeMs_ > 0) {
        return endTimeMs_ - startTimeMs_;
    }
    if (startTimeMs_ > 0) {
        return currentTimeMs() - startTimeMs_;
    }
    return 0;
}

void Session::markStarted() {
    startTimeMs_ = currentTimeMs();
    state_.store(SessionState::RUNNING);
}

void Session::markEnded(SessionState finalState) {
    endTimeMs_ = currentTimeMs();
    state_.store(finalState);
    
    if (completeCb_) {
        completeCb_(id_, finalState);
    }
}

void Session::cancel() {
    auto current = state_.load();
    if (current == SessionState::CREATED || current == SessionState::RUNNING) {
        state_.store(SessionState::CANCELLED);
    }
}

bool Session::shouldStop() const {
    auto state = state_.load();
    return state == SessionState::CANCELLED || 
           state == SessionState::FAILED ||
           state == SessionState::COMPLETED;
}

void Session::reportProgress(const TranscriptionProgress& progress) {
    if (progressCb_) {
        progressCb_(progress);
    }
}

void Session::log(int level, const std::string& message) {
    if (logCb_) {
        logCb_(level, message);
    }
}

// SessionManager
SessionManager& SessionManager::getInstance() {
    static SessionManager instance;
    return instance;
}

std::shared_ptr<Session> SessionManager::createSession(SessionType type) {
    auto session = std::make_shared<Session>(type);
    std::lock_guard<std::mutex> lock(mutex_);
    sessions_[session->getId()] = session;
    return session;
}

std::shared_ptr<Session> SessionManager::getSession(long id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = sessions_.find(id);
    return it != sessions_.end() ? it->second : nullptr;
}

void SessionManager::removeSession(long id) {
    std::lock_guard<std::mutex> lock(mutex_);
    sessions_.erase(id);
}

void SessionManager::cancelAll() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& [id, session] : sessions_) {
        session->cancel();
    }
}

std::vector<std::shared_ptr<Session>> SessionManager::getAllSessions() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::shared_ptr<Session>> result;
    result.reserve(sessions_.size());
    for (auto& [id, session] : sessions_) {
        result.push_back(session);
    }
    return result;
}

} // namespace stt

