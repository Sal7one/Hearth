#include "json_utils.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <sstream>
#include <unordered_set>

namespace stt {

JsonValue::JsonValue(bool value) : type_(Type::Boolean), boolean_(value) {}
JsonValue::JsonValue(int value) : JsonValue(static_cast<int64_t>(value)) {}
JsonValue::JsonValue(int64_t value) : type_(Type::Integer), integer_(value) {}
JsonValue::JsonValue(uint64_t value) {
    type_ = Type::Unsigned;
    unsigned_ = value;
}
JsonValue::JsonValue(float value) : JsonValue(static_cast<double>(value)) {}
JsonValue::JsonValue(double value) : type_(Type::Number), number_(value) {}
JsonValue::JsonValue(const char* value) : JsonValue(std::string(value ? value : "")) {}
JsonValue::JsonValue(std::string value)
    : type_(Type::String), string_(std::move(value)) {}

JsonValue JsonValue::object(Object values) {
    JsonValue result;
    result.type_ = Type::Object;
    result.object_ = std::move(values);
    return result;
}

JsonValue JsonValue::array(Array values) {
    JsonValue result;
    result.type_ = Type::Array;
    result.array_ = std::move(values);
    return result;
}

const JsonValue* JsonValue::find(const std::string& key) const {
    if (type_ != Type::Object) return nullptr;
    for (const auto& item : object_) {
        if (item.first == key) return &item.second;
    }
    return nullptr;
}

bool JsonValue::asBool(bool& value) const {
    if (type_ != Type::Boolean) return false;
    value = boolean_;
    return true;
}

bool JsonValue::asInt64(int64_t& value) const {
    if (type_ != Type::Integer) return false;
    value = integer_;
    return true;
}

bool JsonValue::asUInt64(uint64_t& value) const {
    if (type_ == Type::Unsigned) {
        value = unsigned_;
        return true;
    }
    if (type_ == Type::Integer && integer_ >= 0) {
        value = static_cast<uint64_t>(integer_);
        return true;
    }
    return false;
}

bool JsonValue::asDouble(double& value) const {
    if (type_ == Type::Integer) {
        value = static_cast<double>(integer_);
        return true;
    }
    if (type_ == Type::Unsigned) {
        value = static_cast<double>(unsigned_);
        return true;
    }
    if (type_ != Type::Number || !std::isfinite(number_)) return false;
    value = number_;
    return true;
}

bool JsonValue::asString(std::string& value) const {
    if (type_ != Type::String) return false;
    value = string_;
    return true;
}

namespace {

void setError(std::string* error, size_t position, const std::string& message) {
    if (error) *error = message + " at byte " + std::to_string(position);
}

bool appendCodePoint(uint32_t cp, std::string& output) {
    if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
    if (cp <= 0x7F) {
        output.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        output.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        output.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        output.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        output.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        output.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
    return true;
}

size_t validUtf8SequenceLength(const std::string& input, size_t position) {
    const auto first = static_cast<unsigned char>(input[position]);
    size_t length = 0;
    uint32_t cp = 0;
    if (first >= 0xC2 && first <= 0xDF) {
        length = 2;
        cp = first & 0x1F;
    } else if (first >= 0xE0 && first <= 0xEF) {
        length = 3;
        cp = first & 0x0F;
    } else if (first >= 0xF0 && first <= 0xF4) {
        length = 4;
        cp = first & 0x07;
    } else {
        return 0;
    }
    if (position + length > input.size()) return 0;
    for (size_t i = 1; i < length; ++i) {
        const auto byte = static_cast<unsigned char>(input[position + i]);
        if ((byte & 0xC0) != 0x80) return 0;
        cp = (cp << 6) | (byte & 0x3F);
    }
    if ((length == 3 && cp < 0x800) || (length == 4 && cp < 0x10000) ||
        cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
        return 0;
    }
    return length;
}

uint32_t decodeUtf8CodePoint(const std::string& input, size_t position, size_t length) {
    const auto first = static_cast<unsigned char>(input[position]);
    uint32_t cp = length == 2 ? first & 0x1F : length == 3 ? first & 0x0F : first & 0x07;
    for (size_t i = 1; i < length; ++i) {
        cp = (cp << 6) | (static_cast<unsigned char>(input[position + i]) & 0x3F);
    }
    return cp;
}

class Parser {
public:
    Parser(const std::string& input, std::string* error)
        : input_(input), error_(error) {}

    bool run(JsonValue& output) {
        if (input_.size() > JsonUtils::kMaxInputBytes) {
            setError(error_, 0, "JSON input exceeds limit");
            return false;
        }
        skipWhitespace();
        if (!parseValue(output, 0)) return false;
        skipWhitespace();
        if (position_ != input_.size()) return fail("trailing characters");
        return true;
    }

private:
    bool fail(const std::string& message) {
        setError(error_, position_, message);
        return false;
    }

    void skipWhitespace() {
        while (position_ < input_.size()) {
            const char c = input_[position_];
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
            ++position_;
        }
    }

    bool consume(char expected) {
        if (position_ >= input_.size() || input_[position_] != expected) return false;
        ++position_;
        return true;
    }

    bool addNode() {
        if (++nodes_ > JsonUtils::kMaxNodes) return fail("JSON node limit exceeded");
        return true;
    }

    bool parseValue(JsonValue& output, size_t depth) {
        if (depth > JsonUtils::kMaxDepth) return fail("JSON nesting limit exceeded");
        if (!addNode()) return false;
        if (position_ >= input_.size()) return fail("expected value");
        switch (input_[position_]) {
            case 'n': return parseLiteral("null", JsonValue(), output);
            case 't': return parseLiteral("true", JsonValue(true), output);
            case 'f': return parseLiteral("false", JsonValue(false), output);
            case '"': {
                std::string string;
                if (!parseString(string)) return false;
                output = JsonValue(std::move(string));
                return true;
            }
            case '{': return parseObject(output, depth + 1);
            case '[': return parseArray(output, depth + 1);
            default: return parseNumber(output);
        }
    }

    bool parseLiteral(const char* literal, JsonValue value, JsonValue& output) {
        const std::string expected(literal);
        if (input_.compare(position_, expected.size(), expected) != 0) {
            return fail("invalid literal");
        }
        position_ += expected.size();
        output = std::move(value);
        return true;
    }

    bool parseHex4(uint32_t& value) {
        if (position_ + 4 > input_.size()) return fail("incomplete unicode escape");
        value = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = input_[position_++];
            value <<= 4;
            if (c >= '0' && c <= '9') value |= static_cast<uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f') value |= static_cast<uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') value |= static_cast<uint32_t>(c - 'A' + 10);
            else return fail("invalid unicode escape");
        }
        return true;
    }

    bool parseString(std::string& output) {
        if (!consume('"')) return fail("expected string");
        while (position_ < input_.size()) {
            const auto byte = static_cast<unsigned char>(input_[position_++]);
            if (byte == '"') return true;
            if (byte < 0x20) return fail("unescaped control character");
            if (byte == '\\') {
                if (position_ >= input_.size()) return fail("incomplete escape");
                const char escaped = input_[position_++];
                switch (escaped) {
                    case '"': output.push_back('"'); break;
                    case '\\': output.push_back('\\'); break;
                    case '/': output.push_back('/'); break;
                    case 'b': output.push_back('\b'); break;
                    case 'f': output.push_back('\f'); break;
                    case 'n': output.push_back('\n'); break;
                    case 'r': output.push_back('\r'); break;
                    case 't': output.push_back('\t'); break;
                    case 'u': {
                        uint32_t first;
                        if (!parseHex4(first)) return false;
                        uint32_t cp = first;
                        if (first >= 0xD800 && first <= 0xDBFF) {
                            if (position_ + 2 > input_.size() || input_[position_] != '\\' ||
                                input_[position_ + 1] != 'u') {
                                return fail("missing low surrogate");
                            }
                            position_ += 2;
                            uint32_t second;
                            if (!parseHex4(second)) return false;
                            if (second < 0xDC00 || second > 0xDFFF) {
                                return fail("invalid low surrogate");
                            }
                            cp = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
                        } else if (first >= 0xDC00 && first <= 0xDFFF) {
                            return fail("unexpected low surrogate");
                        }
                        if (!appendCodePoint(cp, output)) return fail("invalid unicode code point");
                        break;
                    }
                    default: return fail("invalid string escape");
                }
            } else if (byte < 0x80) {
                output.push_back(static_cast<char>(byte));
            } else {
                --position_;
                const size_t length = validUtf8SequenceLength(input_, position_);
                if (length == 0) return fail("invalid UTF-8");
                output.append(input_, position_, length);
                position_ += length;
            }
        }
        return fail("unterminated string");
    }

    bool parseObject(JsonValue& output, size_t depth) {
        consume('{');
        skipWhitespace();
        JsonValue::Object values;
        std::unordered_set<std::string> keys;
        if (consume('}')) {
            output = JsonValue::object(std::move(values));
            return true;
        }
        while (true) {
            std::string key;
            if (!parseString(key)) return false;
            if (!keys.insert(key).second) return fail("duplicate object key");
            skipWhitespace();
            if (!consume(':')) return fail("expected ':'");
            skipWhitespace();
            JsonValue value;
            if (!parseValue(value, depth)) return false;
            values.emplace_back(std::move(key), std::move(value));
            skipWhitespace();
            if (consume('}')) break;
            if (!consume(',')) return fail("expected ',' or '}'");
            skipWhitespace();
        }
        output = JsonValue::object(std::move(values));
        return true;
    }

    bool parseArray(JsonValue& output, size_t depth) {
        consume('[');
        skipWhitespace();
        JsonValue::Array values;
        if (consume(']')) {
            output = JsonValue::array(std::move(values));
            return true;
        }
        while (true) {
            JsonValue value;
            if (!parseValue(value, depth)) return false;
            values.emplace_back(std::move(value));
            skipWhitespace();
            if (consume(']')) break;
            if (!consume(',')) return fail("expected ',' or ']'");
            skipWhitespace();
        }
        output = JsonValue::array(std::move(values));
        return true;
    }

    bool parseNumber(JsonValue& output) {
        const size_t start = position_;
        if (consume('-') && position_ >= input_.size()) return fail("incomplete number");
        if (consume('0')) {
            if (position_ < input_.size() && input_[position_] >= '0' && input_[position_] <= '9') {
                return fail("leading zero in number");
            }
        } else {
            if (position_ >= input_.size() || input_[position_] < '1' || input_[position_] > '9') {
                return fail("expected value");
            }
            while (position_ < input_.size() && input_[position_] >= '0' && input_[position_] <= '9') {
                ++position_;
            }
        }
        bool integer = true;
        if (consume('.')) {
            integer = false;
            if (position_ >= input_.size() || input_[position_] < '0' || input_[position_] > '9') {
                return fail("missing fractional digits");
            }
            while (position_ < input_.size() && input_[position_] >= '0' && input_[position_] <= '9') {
                ++position_;
            }
        }
        if (position_ < input_.size() && (input_[position_] == 'e' || input_[position_] == 'E')) {
            integer = false;
            ++position_;
            if (position_ < input_.size() && (input_[position_] == '+' || input_[position_] == '-')) {
                ++position_;
            }
            if (position_ >= input_.size() || input_[position_] < '0' || input_[position_] > '9') {
                return fail("missing exponent digits");
            }
            while (position_ < input_.size() && input_[position_] >= '0' && input_[position_] <= '9') {
                ++position_;
            }
        }

        const std::string token = input_.substr(start, position_ - start);
        if (integer) {
            int64_t value = 0;
            const auto parsed = std::from_chars(token.data(), token.data() + token.size(), value);
            if (parsed.ec == std::errc() && parsed.ptr == token.data() + token.size()) {
                output = JsonValue(value);
                return true;
            }
            if (!token.empty() && token.front() != '-') {
                uint64_t unsignedValue = 0;
                const auto unsignedParsed = std::from_chars(
                    token.data(), token.data() + token.size(), unsignedValue
                );
                if (unsignedParsed.ec == std::errc() &&
                    unsignedParsed.ptr == token.data() + token.size()) {
                    output = JsonValue(unsignedValue);
                    return true;
                }
            }
        }
        std::istringstream stream(token);
        stream.imbue(std::locale::classic());
        double value = 0.0;
        stream >> value;
        if (!stream || !stream.eof() || !std::isfinite(value)) return fail("number out of range");
        output = JsonValue(value);
        return true;
    }

    const std::string& input_;
    std::string* error_;
    size_t position_ = 0;
    size_t nodes_ = 0;
};

void appendEscaped(const std::string& value, std::string& output) {
    static constexpr char kHex[] = "0123456789abcdef";
    const auto appendHex4 = [&](uint32_t codeUnit) {
        output += "\\u";
        output.push_back(kHex[(codeUnit >> 12) & 0x0F]);
        output.push_back(kHex[(codeUnit >> 8) & 0x0F]);
        output.push_back(kHex[(codeUnit >> 4) & 0x0F]);
        output.push_back(kHex[codeUnit & 0x0F]);
    };
    output.push_back('"');
    for (size_t i = 0; i < value.size();) {
        const auto byte = static_cast<unsigned char>(value[i]);
        switch (byte) {
            case '"': output += "\\\""; ++i; continue;
            case '\\': output += "\\\\"; ++i; continue;
            case '\b': output += "\\b"; ++i; continue;
            case '\f': output += "\\f"; ++i; continue;
            case '\n': output += "\\n"; ++i; continue;
            case '\r': output += "\\r"; ++i; continue;
            case '\t': output += "\\t"; ++i; continue;
            default: break;
        }
        if (byte < 0x20) {
            output += "\\u00";
            output.push_back(kHex[byte >> 4]);
            output.push_back(kHex[byte & 0x0F]);
            ++i;
        } else if (byte < 0x80) {
            output.push_back(static_cast<char>(byte));
            ++i;
        } else {
            const size_t length = validUtf8SequenceLength(value, i);
            if (length == 0) {
                appendHex4(0xFFFD);
                ++i;
            } else {
                const uint32_t cp = decodeUtf8CodePoint(value, i, length);
                if (cp <= 0xFFFF) {
                    appendHex4(cp);
                } else {
                    const uint32_t adjusted = cp - 0x10000;
                    appendHex4(0xD800 + (adjusted >> 10));
                    appendHex4(0xDC00 + (adjusted & 0x3FF));
                }
                i += length;
            }
        }
    }
    output.push_back('"');
}

void appendJson(const JsonValue& value, std::string& output) {
    switch (value.type()) {
        case JsonValue::Type::Null:
            output += "null";
            return;
        case JsonValue::Type::Boolean: {
            bool boolean = false;
            value.asBool(boolean);
            output += boolean ? "true" : "false";
            return;
        }
        case JsonValue::Type::Integer: {
            int64_t integer = 0;
            value.asInt64(integer);
            output += std::to_string(integer);
            return;
        }
        case JsonValue::Type::Unsigned: {
            uint64_t integer = 0;
            value.asUInt64(integer);
            output += std::to_string(integer);
            return;
        }
        case JsonValue::Type::Number: {
            double number = 0.0;
            if (!value.asDouble(number)) {
                output += "null";
                return;
            }
            std::ostringstream stream;
            stream.imbue(std::locale::classic());
            stream << std::setprecision(std::numeric_limits<double>::max_digits10) << number;
            output += stream.str();
            return;
        }
        case JsonValue::Type::String: {
            std::string string;
            value.asString(string);
            appendEscaped(string, output);
            return;
        }
        case JsonValue::Type::Object: {
            output.push_back('{');
            bool first = true;
            for (const auto& item : value.objectItems()) {
                if (!first) output.push_back(',');
                first = false;
                appendEscaped(item.first, output);
                output.push_back(':');
                appendJson(item.second, output);
            }
            output.push_back('}');
            return;
        }
        case JsonValue::Type::Array: {
            output.push_back('[');
            bool first = true;
            for (const auto& item : value.arrayItems()) {
                if (!first) output.push_back(',');
                first = false;
                appendJson(item, output);
            }
            output.push_back(']');
            return;
        }
    }
}

const JsonValue* parsedField(const std::string& json, const std::string& key, JsonValue& root) {
    if (!JsonUtils::parse(json, root) || !root.isObject()) return nullptr;
    return root.find(key);
}

double finiteOrZero(double value) {
    return std::isfinite(value) ? value : 0.0;
}

} // namespace

bool JsonUtils::parse(const std::string& json, JsonValue& value, std::string* error) {
    if (error) error->clear();
    Parser parser(json, error);
    return parser.run(value);
}

bool JsonUtils::validateObjectKeys(
    const JsonValue& object,
    const std::vector<std::string>& allowedKeys,
    std::string* error
) {
    if (!object.isObject()) {
        if (error) *error = "expected a JSON object";
        return false;
    }
    const std::unordered_set<std::string> allowed(allowedKeys.begin(), allowedKeys.end());
    for (const auto& item : object.objectItems()) {
        if (allowed.find(item.first) == allowed.end()) {
            if (error) *error = "unknown field '" + item.first + "'";
            return false;
        }
    }
    return true;
}

std::string JsonUtils::stringify(const JsonValue& value) {
    std::string output;
    output.reserve(256);
    appendJson(value, output);
    return output;
}

std::string JsonUtils::escape(const std::string& value) {
    std::string output;
    output.reserve(value.size() + 16);
    appendEscaped(value, output);
    return output.substr(1, output.size() - 2);
}

std::string JsonUtils::buildTranscriptJson(
    const std::string& text,
    const std::vector<TranscriptSegment>& segments,
    const std::string& language,
    int64_t processingTimeMs
) {
    JsonValue::Array segmentValues;
    segmentValues.reserve(segments.size());
    for (const auto& segment : segments) {
        segmentValues.emplace_back(JsonValue::object({
            {"text", segment.text},
            {"startMs", segment.startMs},
            {"endMs", segment.endMs},
            {"confidence", finiteOrZero(segment.confidence)}
        }));
    }
    return stringify(JsonValue::object({
        {"text", text},
        {"language", language},
        {"processingTimeMs", processingTimeMs},
        {"segments", JsonValue::array(std::move(segmentValues))}
    }));
}

std::string JsonUtils::getString(
    const std::string& json,
    const std::string& key,
    const std::string& def
) {
    JsonValue root;
    const JsonValue* value = parsedField(json, key, root);
    std::string result;
    return value && value->asString(result) ? result : def;
}

int JsonUtils::getInt(const std::string& json, const std::string& key, int def) {
    JsonValue root;
    const JsonValue* value = parsedField(json, key, root);
    int64_t result = 0;
    if (!value || !value->asInt64(result) || result < std::numeric_limits<int>::min() ||
        result > std::numeric_limits<int>::max()) {
        return def;
    }
    return static_cast<int>(result);
}

float JsonUtils::getFloat(const std::string& json, const std::string& key, float def) {
    JsonValue root;
    const JsonValue* value = parsedField(json, key, root);
    double result = 0.0;
    if (!value || !value->asDouble(result) || result < -std::numeric_limits<float>::max() ||
        result > std::numeric_limits<float>::max()) {
        return def;
    }
    return static_cast<float>(result);
}

bool JsonUtils::getBool(const std::string& json, const std::string& key, bool def) {
    JsonValue root;
    const JsonValue* value = parsedField(json, key, root);
    bool result = false;
    return value && value->asBool(result) ? result : def;
}

} // namespace stt
