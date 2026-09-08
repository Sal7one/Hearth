#ifndef STT_JSON_UTILS_H
#define STT_JSON_UTILS_H

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "engine_interface.h"

namespace stt {

/**
 * Small dependency-free JSON value used at the JNI boundary.
 *
 * This is deliberately not a permissive configuration format. Parsing consumes
 * the entire document, rejects duplicate keys and applies fixed input/depth/node
 * limits. That makes it suitable for native APIs exposed to untrusted callers
 * without adding a third-party JSON dependency to the AAR.
 */
class JsonValue {
public:
    enum class Type { Null, Boolean, Integer, Unsigned, Number, String, Object, Array };
    using Object = std::vector<std::pair<std::string, JsonValue>>;
    using Array = std::vector<JsonValue>;

    JsonValue() = default;
    JsonValue(std::nullptr_t) {}
    JsonValue(bool value);
    JsonValue(int value);
    JsonValue(int64_t value);
    JsonValue(uint64_t value);
    JsonValue(float value);
    JsonValue(double value);
    JsonValue(const char* value);
    JsonValue(std::string value);

    static JsonValue object(Object values);
    static JsonValue array(Array values);

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isObject() const { return type_ == Type::Object; }
    bool isArray() const { return type_ == Type::Array; }

    const JsonValue* find(const std::string& key) const;
    const Object& objectItems() const { return object_; }
    const Array& arrayItems() const { return array_; }

    bool asBool(bool& value) const;
    bool asInt64(int64_t& value) const;
    bool asUInt64(uint64_t& value) const;
    bool asDouble(double& value) const;
    bool asString(std::string& value) const;

private:
    Type type_ = Type::Null;
    bool boolean_ = false;
    int64_t integer_ = 0;
    uint64_t unsigned_ = 0;
    double number_ = 0.0;
    std::string string_;
    Object object_;
    Array array_;
};

class JsonUtils {
public:
    static constexpr size_t kMaxInputBytes = 1024 * 1024;
    static constexpr size_t kMaxDepth = 64;
    static constexpr size_t kMaxNodes = 100000;

    static bool parse(
        const std::string& json,
        JsonValue& value,
        std::string* error = nullptr
    );

    static bool validateObjectKeys(
        const JsonValue& object,
        const std::vector<std::string>& allowedKeys,
        std::string* error = nullptr
    );

    /** Serializes valid UTF-8 JSON. Invalid source UTF-8 is replaced with U+FFFD. */
    static std::string stringify(const JsonValue& value);

    /** Escapes a string for callers that need a JSON string fragment. */
    static std::string escape(const std::string& value);

    static std::string buildTranscriptJson(
        const std::string& text,
        const std::vector<TranscriptSegment>& segments,
        const std::string& language,
        int64_t processingTimeMs
    );

    // Strict compatibility accessors for small vendor result documents. They
    // parse the whole top-level object and return the default on any mismatch.
    static std::string getString(
        const std::string& json,
        const std::string& key,
        const std::string& def = ""
    );
    static int getInt(const std::string& json, const std::string& key, int def = 0);
    static float getFloat(const std::string& json, const std::string& key, float def = 0.0f);
    static bool getBool(const std::string& json, const std::string& key, bool def = false);
};

} // namespace stt

#endif // STT_JSON_UTILS_H
