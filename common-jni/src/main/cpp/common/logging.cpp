#include "logging.h"
#include "error_codes.h"

namespace stt {

thread_local std::string ErrorStore::lastError_;

void ErrorStore::set(const std::string& error) {
    lastError_ = error;
    ThreadLocalError::get().set(ErrorCode::UNKNOWN, error);
    LOG_E(TAG_STT, "Error: %s", error.c_str());
}

std::string ErrorStore::get() {
    // Prefer ThreadLocalError if it has content
    if (ThreadLocalError::get().hasError()) {
        return ThreadLocalError::get().message();
    }
    return lastError_;
}

void ErrorStore::clear() {
    lastError_.clear();
    ThreadLocalError::get().clear();
}

} // namespace stt
