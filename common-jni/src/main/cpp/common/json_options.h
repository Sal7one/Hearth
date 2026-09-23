#ifndef STT_JSON_OPTIONS_H
#define STT_JSON_OPTIONS_H

#include <cstdint>
#include <cmath>
#include <iomanip>
#include <limits>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "json_utils.h"

namespace stt {

/**
 * Table-driven typed option parsing over JsonValue (common/json_utils.h).
 *
 * Replaces the ad-hoc optStr/optNum pattern: a consumer registers an
 * OptionsTable (name, type, required/default, numeric bounds), parses a
 * JSON object once, and reads typed values back with defaults. Errors are
 * specific ("option 'threads' must be an int between 1 and 16", "unknown
 * option 'lang' (allowed: language, threads)") instead of silent
 * fallbacks. Lenient mode (ignoreUnknown) exists for public boundaries
 * where callers already send extra keys today.
 */
class OptionsTable {
public:
    enum class Type { String, Int, Double, Bool };

    /** One parsed, typed option value. */
    class Value {
    public:
        Value() = default;
        Value(std::string s) : type_(Type::String), string_(std::move(s)) {}
        Value(int64_t i) : type_(Type::Int), int_(i) {}
        Value(double d) : type_(Type::Double), double_(d) {}
        Value(bool b) : type_(Type::Bool), bool_(b) {}

        Type type() const { return type_; }
        const std::string& asString() const { return string_; }
        int64_t asInt() const { return int_; }
        double asDouble() const { return double_; }
        bool asBool() const { return bool_; }

    private:
        Type type_ = Type::String;
        std::string string_;
        int64_t int_ = 0;
        double double_ = 0.0;
        bool bool_ = false;
    };

    struct Spec {
        std::string name;
        Type type = Type::String;
        bool required = false;
        std::string defaultValue;  // legacy add(Spec) input; normalized at registration
        bool hasMin = false;
        double minValue = 0.0;
        bool hasMax = false;
        double maxValue = 0.0;
        std::string description;  // surfaces in error messages
        std::optional<Value> typedDefault;
    };

    using Options = std::map<std::string, Value>;

    OptionsTable& add(Spec spec) {
        if ((spec.hasMin && !std::isfinite(spec.minValue)) ||
            (spec.hasMax && !std::isfinite(spec.maxValue)) ||
            (spec.hasMin && spec.hasMax && spec.minValue > spec.maxValue)) {
            throw std::invalid_argument("invalid bounds for option '" + spec.name + "'");
        }
        if (!spec.typedDefault && (!spec.required || !spec.defaultValue.empty())) {
            spec.typedDefault = parseLegacyDefault(spec);
        }
        if (spec.typedDefault) validateDefault(spec);
        specs_.push_back(std::move(spec));
        return *this;
    }

    OptionsTable& string(const std::string& name, std::string def = "",
                         const std::string& description = "") {
        return add(Spec{name, Type::String, false, std::move(def), false, 0, false, 0, description, std::nullopt});
    }
    OptionsTable& requiredString(const std::string& name, const std::string& description = "") {
        return add(Spec{name, Type::String, true, {}, false, 0, false, 0, description, std::nullopt});
    }
    OptionsTable& integer(const std::string& name, int64_t def, double minValue, double maxValue,
                          const std::string& description = "") {
        Spec spec{name, Type::Int, false, {}, true, minValue, true, maxValue, description, std::nullopt};
        spec.typedDefault = Value(def);
        return add(std::move(spec));
    }
    OptionsTable& boolean(const std::string& name, bool def, const std::string& description = "") {
        Spec spec{name, Type::Bool, false, {}, false, 0, false, 0, description, std::nullopt};
        spec.typedDefault = Value(def);
        return add(std::move(spec));
    }
    OptionsTable& number(const std::string& name, double def, double minValue, double maxValue,
                         const std::string& description = "") {
        Spec spec{name, Type::Double, false, {}, true, minValue, true, maxValue, description, std::nullopt};
        spec.typedDefault = Value(def);
        return add(std::move(spec));
    }

    /**
     * Parse options out of a JSON object.
     * - rejectUnknown: fail on keys not in the table (default) or accept
     *   and skip them for lenient public boundaries.
     * - Returns false and fills error with a specific message on the
     *   first problem found; out is untouched on failure.
     */
    bool parseValue(const JsonValue& object, Options& out, std::string* error,
               bool rejectUnknown = true) const {
        if (!object.isObject()) {
            return fail(error, "options must be a JSON object");
        }
        Options parsed;
        std::vector<std::string> allowed;
        allowed.reserve(specs_.size());
        for (const auto& spec : specs_) {
            allowed.push_back(spec.name);
            const JsonValue* field = object.find(spec.name);
            if (field == nullptr) {
                if (spec.required) {
                    return fail(error, "missing required option '" + spec.name + "'");
                }
                if (!applyDefault(spec, parsed, error)) return false;
                continue;
            }
            if (!applyField(spec, *field, parsed, error)) return false;
        }
        if (rejectUnknown) {
            for (const auto& [key, value] : object.objectItems()) {
                bool known = false;
                for (const auto& spec : specs_) known = known || spec.name == key;
                if (!known) {
                    return fail(error, "unknown option '" + key + "' (allowed: " +
                                           joinNames() + ")");
                }
            }
        }
        out = std::move(parsed);
        return true;
    }

    /** Parse from a JSON document string (whole document must be an object). */
    bool parse(const std::string& json, Options& out, std::string* error,
               bool rejectUnknown = true) const {
        JsonValue root;
        if (!JsonUtils::parse(json, root, error)) {
            if (error) *error = "options JSON is invalid: " + *error;
            return false;
        }
        return parseValue(root, out, error, rejectUnknown);
    }

    // Typed getters with fallbacks, for consumers that read a few fields.
    static std::string getString(const Options& options, const std::string& name,
                                 const std::string& def = "") {
        auto it = options.find(name);
        return it != options.end() ? it->second.asString() : def;
    }
    static int64_t getInt(const Options& options, const std::string& name, int64_t def = 0) {
        auto it = options.find(name);
        return it != options.end() ? it->second.asInt() : def;
    }
    static double getDouble(const Options& options, const std::string& name, double def = 0.0) {
        auto it = options.find(name);
        return it != options.end() ? it->second.asDouble() : def;
    }
    static bool getBool(const Options& options, const std::string& name, bool def = false) {
        auto it = options.find(name);
        return it != options.end() ? it->second.asBool() : def;
    }

private:
    static std::optional<Value> parseLegacyDefault(const Spec& spec) {
        if (spec.type == Type::String) return Value(spec.defaultValue);
        if (spec.defaultValue.empty()) return std::nullopt;
        try {
            std::size_t consumed = 0;
            switch (spec.type) {
                case Type::String: break;
                case Type::Int: {
                    const auto value = std::stoll(spec.defaultValue, &consumed);
                    if (consumed == spec.defaultValue.size()) return Value(static_cast<int64_t>(value));
                    break;
                }
                case Type::Double: {
                    const auto value = std::stod(spec.defaultValue, &consumed);
                    if (consumed == spec.defaultValue.size()) return Value(value);
                    break;
                }
                case Type::Bool:
                    if (spec.defaultValue == "true") return Value(true);
                    if (spec.defaultValue == "false") return Value(false);
                    break;
            }
        } catch (const std::exception&) {
            // Invalid user-constructed Specs fail at registration, not parse.
        }
        throw std::invalid_argument("invalid default for option '" + spec.name + "'");
    }

    static void validateDefault(const Spec& spec) {
        const Value& value = *spec.typedDefault;
        if (value.type() != spec.type) {
            throw std::invalid_argument("default type mismatch for option '" + spec.name + "'");
        }
        if (spec.type != Type::Int && spec.type != Type::Double) return;
        const double number = spec.type == Type::Int
            ? static_cast<double>(value.asInt()) : value.asDouble();
        if (!std::isfinite(number) ||
            (spec.hasMin && number < spec.minValue) ||
            (spec.hasMax && number > spec.maxValue)) {
            throw std::invalid_argument("default outside bounds for option '" + spec.name + "'");
        }
    }

    static bool fail(std::string* error, const std::string& message) {
        if (error) *error = message;
        return false;
    }

    std::string joinNames() const {
        std::string joined;
        for (const auto& spec : specs_) {
            if (!joined.empty()) joined += ", ";
            joined += spec.name;
        }
        return joined;
    }

    bool applyDefault(const Spec& spec, Options& parsed, std::string* error) const {
        if (!spec.typedDefault) {
            // A numeric/bool spec without a default is treated as required.
            return fail(error, "missing required option '" + spec.name + "'");
        }
        parsed.emplace(spec.name, *spec.typedDefault);
        return true;
    }

    bool applyField(const Spec& spec, const JsonValue& field, Options& parsed,
                    std::string* error) const {
        const std::string label = "option '" + spec.name + "'";
        switch (spec.type) {
            case Type::String: {
                std::string value;
                if (!field.asString(value)) {
                    return fail(error, label + " must be a string");
                }
                parsed.emplace(spec.name, Value(std::move(value)));
                return true;
            }
            case Type::Int: {
                int64_t value = 0;
                if (!field.asInt64(value)) {
                    return fail(error, label + " must be an int");
                }
                if (spec.hasMin && static_cast<double>(value) < spec.minValue) {
                    return fail(error, label + " must be >= " + trim(spec.minValue));
                }
                if (spec.hasMax && static_cast<double>(value) > spec.maxValue) {
                    return fail(error, label + " must be <= " + trim(spec.maxValue));
                }
                parsed.emplace(spec.name, Value(value));
                return true;
            }
            case Type::Double: {
                double value = 0.0;
                if (!field.asDouble(value)) {
                    return fail(error, label + " must be a number");
                }
                if (spec.hasMin && value < spec.minValue) {
                    return fail(error, label + " must be >= " + trim(spec.minValue));
                }
                if (spec.hasMax && value > spec.maxValue) {
                    return fail(error, label + " must be <= " + trim(spec.maxValue));
                }
                parsed.emplace(spec.name, Value(value));
                return true;
            }
            case Type::Bool: {
                bool value = false;
                if (!field.asBool(value)) {
                    return fail(error, label + " must be a bool");
                }
                parsed.emplace(spec.name, Value(value));
                return true;
            }
        }
        return false;
    }

    static std::string trim(double value) {
        std::ostringstream out;
        out << std::setprecision(std::numeric_limits<double>::max_digits10) << value;
        return out.str();
    }

    std::vector<Spec> specs_;
};

} // namespace stt

#endif // STT_JSON_OPTIONS_H
