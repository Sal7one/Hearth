#pragma once
#include <memory>
#include <string>
#include <vector>
#include <cstdint>

namespace hearth::voice {
// One pinned Supertonic 3 CPU session. Input preparation belongs to the caller.
class Supertonic {
public:
    explicit Supertonic(const std::string& directory);
    ~Supertonic();
    std::vector<float> synthesize(const std::vector<int64_t>& ids,
        std::vector<float> styleTtl, std::vector<float> styleDp, int steps, float speed);
    void cancel();
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
}
