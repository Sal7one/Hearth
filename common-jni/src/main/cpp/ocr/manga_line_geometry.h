#pragma once

#include "ocr_geometry.h"

#include <array>
#include <cstddef>
#include <limits>
#include <tuple>

namespace hearth::ocr {
namespace manga_geometry {

struct Point {
    int x;
    int y;
};

inline int turn(Point a, Point b, Point c) {
    return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
}

// Only narrow contours need the rotated-box size check after expansion.
inline double narrowSide(std::vector<Point> points) {
    const auto less = [](Point a, Point b) { return std::tie(a.x, a.y) < std::tie(b.x, b.y); };
    std::sort(points.begin(), points.end(), less);
    points.erase(std::unique(points.begin(), points.end(), [](Point a, Point b) {
        return a.x == b.x && a.y == b.y;
    }), points.end());
    if (points.size() < 3) return 0;
    std::vector<Point> hull;
    const auto add = [&](Point p, size_t floor) {
        while (hull.size() >= floor + 2 && turn(hull[hull.size() - 2], hull.back(), p) <= 0) {
            hull.pop_back();
        }
        hull.push_back(p);
    };
    for (Point point : points) add(point, 0);
    const size_t floor = hull.size() - 1;
    for (size_t index = points.size() - 1; index > 0; --index) add(points[index - 1], floor);
    hull.pop_back();

    double bestArea = std::numeric_limits<double>::infinity();
    double bestSide = 0;
    for (size_t index = 0; index < hull.size(); ++index) {
        const Point a = hull[index], b = hull[(index + 1) % hull.size()];
        const int dx = b.x - a.x, dy = b.y - a.y;
        int minU = std::numeric_limits<int>::max(), maxU = std::numeric_limits<int>::min();
        int minV = minU, maxV = maxU;
        for (Point p : hull) {
            const int u = p.x * dx + p.y * dy;
            const int v = p.y * dx - p.x * dy;
            minU = std::min(minU, u);
            maxU = std::max(maxU, u);
            minV = std::min(minV, v);
            maxV = std::max(maxV, v);
        }
        const double lengthSquared = dx * dx + dy * dy;
        const double area = static_cast<double>(maxU - minU) * (maxV - minV) / lengthSquared;
        if (area < bestArea) {
            bestArea = area;
            bestSide = std::min(maxU - minU, maxV - minV) / std::sqrt(lengthSquared);
        }
    }
    return bestSide;
}

struct ScanPoint {
    Point pixel;
    bool crossing;
};

// Unit-length contour edges cross scanlines at integer coordinates. Count the
// boundary and fill the intervals between crossings, including gaps in the map.
inline double filledMean(const std::vector<Point>& contour, const float* scores, int width,
                         size_t& remainingSamples) {
    std::vector<ScanPoint> events;
    events.reserve(contour.size() * 2);
    for (size_t index = 0; index < contour.size(); ++index) {
        const Point a = contour[index], b = contour[(index + 1) % contour.size()];
        events.push_back({a, false});
        if (a.y != b.y) events.push_back({a.y < b.y ? a : b, true});
    }
    std::sort(events.begin(), events.end(), [](const ScanPoint& a, const ScanPoint& b) {
        return std::tie(a.pixel.y, a.pixel.x) < std::tie(b.pixel.y, b.pixel.x);
    });

    double sum = 0;
    size_t count = 0;
    for (size_t index = 0; index < events.size();) {
        const int y = events[index].pixel.y;
        const float* row = scores + y * width;
        bool inside = false;
        int previous = 0;
        while (index < events.size() && events[index].pixel.y == y) {
            const int x = events[index].pixel.x;
            const int start = inside ? previous + 1 : x;
            const size_t span = static_cast<size_t>(x - start + 1);
            if (span > remainingSamples) throw std::runtime_error("Manga detector contours exceed the scoring budget");
            remainingSamples -= span;
            count += span;
            for (int column = start; column <= x; ++column) sum += row[column];
            do {
                if (events[index].crossing) inside = !inside;
                ++index;
            } while (index < events.size() && events[index].pixel.y == y && events[index].pixel.x == x);
            previous = x;
        }
    }
    return sum / count;
}

// Suzuki–Abe border following without hierarchy (DOI: 10.1016/0734-189X(85)90016-7).
// The sign of a border label distinguishes scanned right edges from new holes.
inline void trace(std::vector<int8_t>& map, int stride, int start, int incoming,
                  size_t& remainingSteps, std::vector<Point>& contour) {
    const std::array<int, 8> clockwise{1, stride + 1, stride, stride - 1,
                                       -1, -stride - 1, -stride, -stride + 1};
    int firstDirection = incoming;
    do {
        firstDirection = (firstDirection + 1) & 7;
        if (map[start + clockwise[firstDirection]]) break;
    } while (firstDirection != incoming);
    if (firstDirection == incoming) {
        map[start] = -2;
        return;
    }
    const int first = start + clockwise[firstDirection];
    int current = start;
    incoming = firstDirection;
    do {
        if (remainingSteps == 0) throw std::runtime_error("Manga detector contours exceed the tracing budget");
        --remainingSteps;
        int outgoing = incoming;
        bool scannedRightEdge = false;
        do {
            outgoing = (outgoing + 7) & 7;
            if (map[current + clockwise[outgoing]]) break;
            if (outgoing == 0) scannedRightEdge = true;
        } while (outgoing != incoming);
        if (scannedRightEdge) map[current] = -2;
        else if (map[current] == 1) map[current] = 2;
        contour.push_back({current % stride - 1, current / stride - 1});
        const int next = current + clockwise[outgoing];
        if (next == start && current == first) return;
        current = next;
        incoming = (outgoing + 4) & 7;
    } while (true);
}

inline void addBox(std::vector<Box>& boxes, const std::vector<Point>& contour,
                   const float* scores, int width, int height, int imageWidth, int imageHeight,
                   size_t& remainingSamples) {
    if (contour.size() < 4) return;
    Point low = contour.front(), high = low;
    int64_t areaTwice = 0;
    int diagonals = 0, corners = 0;
    Point previous = contour.back();
    Point direction{previous.x - contour[contour.size() - 2].x,
                    previous.y - contour[contour.size() - 2].y};
    for (Point current : contour) {
        low = {std::min(low.x, current.x), std::min(low.y, current.y)};
        high = {std::max(high.x, current.x), std::max(high.y, current.y)};
        areaTwice += previous.x * current.y - current.x * previous.y;
        const Point nextDirection{current.x - previous.x, current.y - previous.y};
        diagonals += nextDirection.x != 0 && nextDirection.y != 0;
        corners += direction.x != nextDirection.x || direction.y != nextDirection.y;
        previous = current;
        direction = nextDirection;
    }
    if (corners < 4 || areaTwice == 0) return;
    const double perimeter = static_cast<double>(contour.size()) + diagonals * (std::sqrt(2.0) - 1.0);
    const double radius = std::abs(static_cast<double>(areaTwice)) * 0.7 / perimeter;
    if (radius < 1.5 && narrowSide(contour) + 2 * radius < 3) return;
    const double score = filledMean(contour, scores, width, remainingSamples);
    if (score < 0.25) return;

    // Round once at detector resolution and again when mapping back to the page.
    const auto scale = [](double value, int source, int target) {
        return std::clamp(static_cast<int>(std::nearbyint(std::nearbyint(value) * target / source)), 0, target);
    };
    const int left = scale(low.x - radius, width, imageWidth);
    const int right = scale(high.x + radius, width, imageWidth);
    const int top = scale(low.y - radius, height, imageHeight);
    const int bottom = scale(high.y + radius, height, imageHeight);
    if (right <= left || bottom <= top) return;
    if (boxes.size() == 64) throw std::runtime_error("Page exceeds 64 manga text lines. Draw a smaller area.");
    boxes.push_back({left, top, right - left, bottom - top, static_cast<float>(std::min(1.0, score))});
}

} // namespace manga_geometry

inline std::vector<Box> mangaRegions(const float* scores, int mapWidth, int mapHeight,
                                     int imageWidth, int imageHeight) {
    const auto valid = [](int size) { return size >= 1 && size <= 2048; };
    if (!scores || !valid(mapWidth) || !valid(mapHeight) || !valid(imageWidth) || !valid(imageHeight)) {
        throw std::invalid_argument("Invalid manga detector map or image size");
    }
    const int stride = mapWidth + 2;
    std::vector<int8_t> map(static_cast<size_t>(stride) * (mapHeight + 2));
    for (int y = 0; y < mapHeight; ++y) {
        for (int x = 0; x < mapWidth; ++x) {
            const float score = scores[y * mapWidth + x];
            // The pinned detector can return -1 ULP at the lower endpoint.
            if (!(score >= -0.00001f && score <= 1.00001f)) throw std::runtime_error("Invalid manga detector score");
            map[(y + 1) * stride + x + 1] = score > 0.15f;
        }
    }
    size_t remainingSteps = static_cast<size_t>(mapWidth) * mapHeight * 8;
    size_t remainingSamples = static_cast<size_t>(mapWidth) * mapHeight * 16;
    std::vector<manga_geometry::Point> contour;
    std::vector<Box> boxes;
    for (int y = 1; y <= mapHeight; ++y) {
        for (int x = 1; x <= mapWidth; ++x) {
            const int index = y * stride + x;
            const bool outer = map[index] == 1 && map[index - 1] == 0;
            const bool hole = map[index] > 0 && map[index + 1] == 0;
            if (!outer && !hole) continue;
            contour.clear();
            manga_geometry::trace(map, stride, index, outer ? 4 : 0, remainingSteps, contour);
            manga_geometry::addBox(boxes, contour, scores, mapWidth, mapHeight,
                                   imageWidth, imageHeight, remainingSamples);
        }
    }
    std::sort(boxes.begin(), boxes.end(), [](const Box& a, const Box& b) {
        return std::tie(a.y, a.x, a.width, a.height) < std::tie(b.y, b.x, b.width, b.height);
    });
    return boxes;
}

} // namespace hearth::ocr
