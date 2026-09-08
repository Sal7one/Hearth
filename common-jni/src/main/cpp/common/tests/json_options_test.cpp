// json_options_test.cpp — direct unit tests for common/json_options.h.
#include <iostream>
#include <string>

#include "json_options.h"

using stt::JsonValue;
using stt::OptionsTable;

static int failures = 0;
#define CHECK(cond)                                              \
    do {                                                         \
        if (!(cond)) {                                           \
            ++failures;                                          \
            std::cerr << "FAIL " << __LINE__ << ": " #cond "\n"; \
        }                                                        \
    } while (0)

static OptionsTable sampleTable() {
    OptionsTable table;
    table.requiredString("language", "BCP-47 code or auto");
    table.integer("threads", 4, 1, 16, "worker threads");
    table.number("temperature", 0.0, 0.0, 2.0);
    table.boolean("translate", false);
    table.string("model", "ggml-base.bin");
    return table;
}

int main() {
    std::string error;
    OptionsTable::Options options;

    // Happy path: values, defaults, typed getters
    {
        auto table = sampleTable();
        error.clear();
        CHECK(table.parse(R"({"language":"ar","threads":8,"temperature":0.5})", options, &error));
        CHECK(error.empty());
        CHECK(OptionsTable::getString(options, "language") == "ar");
        CHECK(OptionsTable::getInt(options, "threads") == 8);
        CHECK(OptionsTable::getDouble(options, "temperature") == 0.5);
        CHECK(OptionsTable::getBool(options, "translate") == false);   // default
        CHECK(OptionsTable::getString(options, "model") == "ggml-base.bin");  // default
    }

    // Required option missing → specific error naming it
    {
        auto table = sampleTable();
        error.clear();
        CHECK(!table.parse(R"({"threads":4})", options, &error));
        CHECK(error.find("missing required option 'language'") != std::string::npos);
    }

    // Type mismatch → specific error
    {
        auto table = sampleTable();
        error.clear();
        CHECK(!table.parse(R"({"language":"ar","threads":"eight"})", options, &error));
        CHECK(error.find("option 'threads' must be an int") != std::string::npos);
    }

    // Range violations
    {
        auto table = sampleTable();
        error.clear();
        CHECK(!table.parse(R"({"language":"ar","threads":32})", options, &error));
        CHECK(error.find("option 'threads' must be <= 16") != std::string::npos);
        error.clear();
        CHECK(!table.parse(R"({"language":"ar","threads":0})", options, &error));
        CHECK(error.find("option 'threads' must be >= 1") != std::string::npos);
        error.clear();
        CHECK(!table.parse(R"({"language":"ar","temperature":5.0})", options, &error));
        CHECK(error.find("option 'temperature' must be <= 2") != std::string::npos);
    }

    // Unknown keys: rejected by default with the allowed list…
    {
        auto table = sampleTable();
        error.clear();
        CHECK(!table.parse(R"({"language":"ar","lang":"ar"})", options, &error));
        CHECK(error.find("unknown option 'lang'") != std::string::npos);
        CHECK(error.find("language") != std::string::npos);
        // …and tolerated in lenient mode (public boundaries that send extras)
        error.clear();
        CHECK(table.parse(R"({"language":"ar","lang":"ar"})", options, &error, /*rejectUnknown=*/false));
        CHECK(OptionsTable::getString(options, "language") == "ar");
        CHECK(options.find("lang") == options.end());
    }

    // Malformed JSON / non-object payloads
    {
        auto table = sampleTable();
        error.clear();
        CHECK(!table.parse("not json", options, &error));
        CHECK(error.find("options JSON is invalid") != std::string::npos);
        error.clear();
        CHECK(!table.parse("[1,2]", options, &error));
        CHECK(error.find("options must be a JSON object") != std::string::npos);
    }

    // JsonValue entry point works too (composition with json_utils parsing)
    {
        auto table = sampleTable();
        JsonValue root;
        std::string parseError;
        CHECK(stt::JsonUtils::parse(R"({"language":"en","translate":true})", root, &parseError));
        error.clear();
        CHECK(table.parseValue(root, options, &error));
        CHECK(OptionsTable::getBool(options, "translate") == true);
    }

    // Duplicate keys are rejected by json_utils itself (no silent override)
    {
        auto table = sampleTable();
        error.clear();
        CHECK(!table.parse(R"({"language":"en","language":"ar"})", options, &error));
    }

    if (failures == 0) {
        std::cout << "json_options_test: ALL PASS\n";
        return 0;
    }
    std::cout << "json_options_test: " << failures << " FAILURES\n";
    return 1;
}
