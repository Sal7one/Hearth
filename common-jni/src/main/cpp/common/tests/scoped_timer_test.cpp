#include "scoped_timer.h"

#include <cassert>
#include <cstdio>

int main() {
    stt::PerformanceStats::reset();
    {
        PROFILE_SCOPE_SILENT("first");
        PROFILE_SCOPE_SILENT("second"); // Two expansions in one scope must compile.
    }
    const auto first = stt::PerformanceStats::get("first");
    const auto second = stt::PerformanceStats::get("second");
    assert(first.count == 1 && second.count == 1);
    assert(first.minUs >= 0 && first.maxUs >= first.minUs);
    assert(second.minUs >= 0 && second.maxUs >= second.minUs);
    assert(stt::PerformanceStats::getAll().size() == 2);

    stt::PerformanceStats::record("first", 120);
    const auto updated = stt::PerformanceStats::get("first");
    assert(updated.count == 2 && updated.totalUs >= 120);
    stt::PerformanceStats::reset("first");
    assert(stt::PerformanceStats::get("first").count == 0);
    stt::PerformanceStats::reset();
    assert(stt::PerformanceStats::getAll().empty());
    std::puts("scoped_timer: macro uniqueness and stats PASS");
}
