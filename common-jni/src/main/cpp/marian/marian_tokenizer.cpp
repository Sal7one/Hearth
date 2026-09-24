#include "marian_tokenizer.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <utility>

namespace stt {
namespace marian {

namespace {

constexpr size_t kMaxSpmBytes = 16U * 1024U * 1024U;
constexpr size_t kMaxDocumentBytes = 64U * 1024U * 1024U;
constexpr size_t kMaxVocabEntries = 100000U;
constexpr size_t kMaxTokenBytes = 4096U;
constexpr size_t kMaxJsonDepth = 32U;
constexpr size_t kMaxTextBytes = 1U * 1024U * 1024U;
constexpr size_t kMaxPieces = 100000U;

// ---------------------------------------------------------------------------
// UTF-8 helpers
// ---------------------------------------------------------------------------

// Returns the width in bytes of the rune starting at p, or 0 if invalid.
size_t runeWidth(const uint8_t* p, size_t remaining) {
    if (remaining == 0) return 0;
    const uint8_t lead = p[0];
    size_t width = 0;
    if (lead <= 0x7FU) {
        width = 1;
    } else if (lead >= 0xC2U && lead <= 0xDFU) {
        width = 2;
    } else if (lead >= 0xE0U && lead <= 0xEFU) {
        width = 3;
    } else if (lead >= 0xF0U && lead <= 0xF4U) {
        width = 4;
    } else {
        return 0;
    }
    if (width > remaining) return 0;
    for (size_t i = 1; i < width; ++i) {
        if ((p[i] & 0xC0U) != 0x80U) return 0;
    }
    uint32_t cp = 0;
    if (width == 1) {
        cp = lead;
    } else if (width == 2) {
        cp = (static_cast<uint32_t>(lead) & 0x1FU) << 6U;
        cp |= p[1] & 0x3FU;
        if (cp < 0x80U) return 0;
    } else if (width == 3) {
        cp = (static_cast<uint32_t>(lead) & 0x0FU) << 12U;
        cp |= (static_cast<uint32_t>(p[1]) & 0x3FU) << 6U;
        cp |= p[2] & 0x3FU;
        if (cp < 0x800U || (cp >= 0xD800U && cp <= 0xDFFFU)) return 0;
    } else {
        cp = (static_cast<uint32_t>(lead) & 0x07U) << 18U;
        cp |= (static_cast<uint32_t>(p[1]) & 0x3FU) << 12U;
        cp |= (static_cast<uint32_t>(p[2]) & 0x3FU) << 6U;
        cp |= p[3] & 0x3FU;
        if (cp < 0x10000U || cp > 0x10FFFFU) return 0;
    }
    return width;
}

// ---------------------------------------------------------------------------
// Minimal protobuf reader for SentencePiece ModelProto
// ---------------------------------------------------------------------------

class ProtoReader {
public:
    ProtoReader(const char* data, size_t size) : p_(data), end_(data + size) {}

    bool atEnd() const { return p_ >= end_; }
    uint32_t fieldNumber() const { return field_; }
    uint32_t wireType() const { return wire_; }
    uint64_t varint() const { return varint_; }
    uint32_t fixed32() const { return fixed32_; }
    const char* bytesData() const { return bytes_; }
    size_t bytesSize() const { return bytesSize_; }

    // Reads the next field; returns false at end or on malformed input.
    bool next() {
        if (p_ >= end_) return false;
        uint64_t key = 0;
        int shift = 0;
        while (true) {
            if (p_ >= end_) return false;
            const uint8_t b = static_cast<uint8_t>(*p_++);
            key |= static_cast<uint64_t>(b & 0x7FU) << shift;
            if (!(b & 0x80U)) break;
            shift += 7;
            if (shift > 63) return false;
        }
        field_ = static_cast<uint32_t>(key >> 3U);
        wire_ = static_cast<uint32_t>(key & 7U);
        switch (wire_) {
            case 0: {
                varint_ = 0;
                int vshift = 0;
                while (true) {
                    if (p_ >= end_) return false;
                    const uint8_t b = static_cast<uint8_t>(*p_++);
                    varint_ |= static_cast<uint64_t>(b & 0x7FU) << vshift;
                    if (!(b & 0x80U)) break;
                    vshift += 7;
                    if (vshift > 63) return false;
                }
                return true;
            }
            case 1:
                if (end_ - p_ < 8) return false;
                p_ += 8;
                return true;
            case 2: {
                uint64_t length = 0;
                int lshift = 0;
                while (true) {
                    if (p_ >= end_) return false;
                    const uint8_t b = static_cast<uint8_t>(*p_++);
                    length |= static_cast<uint64_t>(b & 0x7FU) << lshift;
                    if (!(b & 0x80U)) break;
                    lshift += 7;
                    if (lshift > 63) return false;
                }
                if (static_cast<size_t>(end_ - p_) < length) return false;
                bytes_ = p_;
                bytesSize_ = static_cast<size_t>(length);
                p_ += length;
                return true;
            }
            case 5:
                if (end_ - p_ < 4) return false;
                fixed32_ = static_cast<uint32_t>(static_cast<uint8_t>(p_[0])) |
                           (static_cast<uint32_t>(static_cast<uint8_t>(p_[1])) << 8U) |
                           (static_cast<uint32_t>(static_cast<uint8_t>(p_[2])) << 16U) |
                           (static_cast<uint32_t>(static_cast<uint8_t>(p_[3])) << 24U);
                p_ += 4;
                return true;
            default:
                return false;
        }
    }

private:
    const char* p_;
    const char* end_;
    uint32_t field_ = 0;
    uint32_t wire_ = 0;
    uint64_t varint_ = 0;
    uint32_t fixed32_ = 0;
    const char* bytes_ = nullptr;
    size_t bytesSize_ = 0;
};

// ---------------------------------------------------------------------------
// Bounded JSON walker (dependency-free; parses only what this tokenizer needs)
// ---------------------------------------------------------------------------

class JsonWalker {
public:
    JsonWalker(const char* data, size_t size)
        : p_(data), end_(data + size) {}

    bool skipWhitespace() {
        while (p_ < end_ && (*p_ == ' ' || *p_ == '\t' || *p_ == '\n' ||
                              *p_ == '\r')) {
            ++p_;
        }
        return p_ < end_;
    }

    bool expect(char c) {
        if (!skipWhitespace() || *p_ != c) return false;
        ++p_;
        return true;
    }

    bool peekIs(char c) const { return p_ < end_ && *p_ == c; }
    void advance() { if (p_ < end_) ++p_; }

    // Parses a JSON string (with standard escapes incl. \uXXXX pairs).
    bool parseString(std::string& out) {
        if (!skipWhitespace() || *p_ != '"') return false;
        ++p_;
        out.clear();
        out.reserve(64);
        while (true) {
            if (p_ >= end_) return false;
            const uint8_t c = static_cast<uint8_t>(*p_++);
            if (c == '"') return true;
            if (c == '\\') {
                if (p_ >= end_) return false;
                const char esc = *p_++;
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        uint32_t cp = 0;
                        if (!parseHex4(cp)) return false;
                        if (cp >= 0xD800U && cp <= 0xDBFFU) {
                            if (end_ - p_ < 2 || p_[0] != '\\' ||
                                p_[1] != 'u') {
                                return false;
                            }
                            p_ += 2;
                            uint32_t low = 0;
                            if (!parseHex4(low) || low < 0xDC00U ||
                                low > 0xDFFFU) {
                                return false;
                            }
                            cp = 0x10000U + ((cp - 0xD800U) << 10U) +
                                 (low - 0xDC00U);
                        } else if (cp >= 0xDC00U && cp <= 0xDFFFU) {
                            return false;
                        }
                        appendUtf8(out, cp);
                        break;
                    }
                    default: return false;
                }
            } else if (c < 0x20U) {
                return false;
            } else {
                out.push_back(static_cast<char>(c));
            }
            if (out.size() > kMaxTokenBytes) return false;
        }
    }

    // Parses a JSON number into a double.
    bool parseNumber(double& out) {
        if (!skipWhitespace()) return false;
        const char* start = p_;
        if (p_ < end_ && *p_ == '-') ++p_;
        if (p_ >= end_ || (*p_ < '0' || *p_ > '9')) return false;
        while (p_ < end_ && *p_ >= '0' && *p_ <= '9') ++p_;
        if (p_ < end_ && *p_ == '.') {
            ++p_;
            if (p_ >= end_ || (*p_ < '0' || *p_ > '9')) return false;
            while (p_ < end_ && *p_ >= '0' && *p_ <= '9') ++p_;
        }
        if (p_ < end_ && (*p_ == 'e' || *p_ == 'E')) {
            ++p_;
            if (p_ < end_ && (*p_ == '-' || *p_ == '+')) ++p_;
            if (p_ >= end_ || (*p_ < '0' || *p_ > '9')) return false;
            while (p_ < end_ && *p_ >= '0' && *p_ <= '9') ++p_;
        }
        const size_t length = static_cast<size_t>(p_ - start);
        if (length > 64 || length == 0) return false;
        char buffer[80];
        if (length >= sizeof(buffer)) return false;
        std::memcpy(buffer, start, length);
        buffer[length] = '\0';
        char* parseEnd = nullptr;
        out = std::strtod(buffer, &parseEnd);
        if (parseEnd != buffer + length) return false;
        return true;
    }

    // Skips one complete JSON value.
    bool skipValue(size_t depth) {
        if (depth > kMaxJsonDepth) return false;
        if (!skipWhitespace()) return false;
        if (p_ >= end_) return false;
        switch (*p_) {
            case '{': {
                ++p_;
                if (!skipWhitespace()) return false;
                if (p_ < end_ && *p_ == '}') {
                    ++p_;
                    return true;
                }
                while (true) {
                    std::string key;
                    if (!parseString(key)) return false;
                    if (!expect(':')) return false;
                    if (!skipValue(depth + 1)) return false;
                    if (!skipWhitespace()) return false;
                    if (p_ < end_ && *p_ == ',') {
                        ++p_;
                        continue;
                    }
                    return expect('}');
                }
            }
            case '[': {
                ++p_;
                if (!skipWhitespace()) return false;
                if (p_ < end_ && *p_ == ']') {
                    ++p_;
                    return true;
                }
                while (true) {
                    if (!skipValue(depth + 1)) return false;
                    if (!skipWhitespace()) return false;
                    if (p_ < end_ && *p_ == ',') {
                        ++p_;
                        continue;
                    }
                    return expect(']');
                }
            }
            case '"': {
                std::string ignored;
                return parseString(ignored);
            }
            case 't':
                if (end_ - p_ >= 4 && std::memcmp(p_, "true", 4) == 0) {
                    p_ += 4;
                    return true;
                }
                return false;
            case 'f':
                if (end_ - p_ >= 5 && std::memcmp(p_, "false", 5) == 0) {
                    p_ += 5;
                    return true;
                }
                return false;
            case 'n':
                if (end_ - p_ >= 4 && std::memcmp(p_, "null", 4) == 0) {
                    p_ += 4;
                    return true;
                }
                return false;
            default: {
                double ignored = 0.0;
                return parseNumber(ignored);
            }
        }
    }

private:
    bool parseHex4(uint32_t& out) {
        if (end_ - p_ < 4) return false;
        out = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = *p_++;
            out <<= 4U;
            if (c >= '0' && c <= '9') {
                out |= static_cast<uint32_t>(c - '0');
            } else if (c >= 'a' && c <= 'f') {
                out |= static_cast<uint32_t>(c - 'a' + 10);
            } else if (c >= 'A' && c <= 'F') {
                out |= static_cast<uint32_t>(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return true;
    }

    static void appendUtf8(std::string& out, uint32_t cp) {
        if (cp <= 0x7FU) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FFU) {
            out.push_back(static_cast<char>(0xC0U | (cp >> 6U)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else if (cp <= 0xFFFFU) {
            out.push_back(static_cast<char>(0xE0U | (cp >> 12U)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else {
            out.push_back(static_cast<char>(0xF0U | (cp >> 18U)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 12U) & 0x3FU)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        }
    }

    const char* p_;
    const char* end_;
};

std::string fail(const std::string& prefix, const std::string& detail) {
    return prefix + ": " + detail;
}

// ---------------------------------------------------------------------------
// Charsmap (SentencePiece Darts double array) — mirrors normalizer.cc
// ---------------------------------------------------------------------------

inline uint32_t dartsOffset(uint32_t unit) {
    return (unit >> 10U) << ((unit & (1U << 9U)) >> 6U);
}

inline uint32_t dartsLabel(uint32_t unit) {
    return unit & ((1U << 31U) | 0xFFU);
}

inline bool dartsHasLeaf(uint32_t unit) {
    return (unit >> 8U) & 1U;
}

inline uint32_t dartsValue(uint32_t unit) {
    return unit & ((1U << 31U) - 1U);
}

}  // namespace

// ---------------------------------------------------------------------------
// Normalization — sentencepiece normalizer.cc Normalize + NormalizePrefix
// ---------------------------------------------------------------------------

namespace {

struct NormalizedPrefix {
    std::string text;
    size_t consumedBytes = 0;
};

// Longest-prefix match in the charsmap; unmatched runes pass through.
bool normalizePrefix(const std::vector<uint32_t>& units,
                     const std::string& values,
                     const std::string& input, size_t offset,
                     NormalizedPrefix& out) {
    const uint8_t* bytes =
        reinterpret_cast<const uint8_t*>(input.data()) + offset;
    const size_t remaining = input.size() - offset;

    size_t bestLength = 0;
    uint32_t bestValue = 0;
    if (!units.empty()) {
        size_t nodePos = 0;
        uint32_t unit = units[0];
        nodePos ^= static_cast<size_t>(dartsOffset(unit));
        for (size_t k = 0; k < remaining; ++k) {
            const uint8_t c = bytes[k];
            nodePos ^= static_cast<size_t>(c);
            if (nodePos >= units.size()) break;
            unit = units[nodePos];
            if (dartsLabel(unit) != c) break;
            nodePos ^= static_cast<size_t>(dartsOffset(unit));
            if (dartsHasLeaf(unit)) {
                // Darts stores the leaf value in the unit at the advanced
                // child position (see darts.h commonPrefixSearch).
                if (nodePos >= units.size()) break;
                const uint32_t value = dartsValue(units[nodePos]);
                if (value < values.size()) {
                    bestLength = k + 1;
                    bestValue = value;
                }
            }
        }
    }

    if (bestLength > 0) {
        const char* replacement = values.data() + bestValue;
        out.text.assign(replacement, std::strlen(replacement));
        out.consumedBytes = bestLength;
        return true;
    }

    const size_t width = runeWidth(bytes, remaining);
    if (width == 0) return false;
    out.text.assign(input, offset, width);
    out.consumedBytes = width;
    return true;
}

bool isOnlySpaces(const std::string& text) {
    for (const char c : text) {
        if (c != ' ') return false;
    }
    return true;
}

}  // namespace

bool MarianTokenizer::normalize(const std::string& input,
                                std::string& out) const {
    out.clear();
    size_t offset = 0;
    NormalizedPrefix prefix;

    // 1. Ignores heading space.
    if (removeExtraWhitespaces_) {
        while (offset < input.size()) {
            if (!normalizePrefix(charsmapUnits_, charsmapValues_, input,
                                 offset, prefix)) {
                return false;
            }
            if (!isOnlySpaces(prefix.text)) break;
            offset += prefix.consumedBytes;
        }
        if (offset >= input.size()) return true;  // all whitespace
    }

    // 2. Adds a space symbol as a prefix (U+2581).
    if (!treatWhitespaceAsSuffix_ && addDummyPrefix_) {
        out.append(kMetaspacePrefix);
    }

    // 3. Main loop.
    bool isPrevSpace = removeExtraWhitespaces_;
    while (offset < input.size()) {
        if (!normalizePrefix(charsmapUnits_, charsmapValues_, input, offset,
                             prefix)) {
            return false;
        }
        std::string piece = prefix.text;
        if (isPrevSpace) {
            size_t lead = 0;
            while (lead < piece.size() && piece[lead] == ' ') ++lead;
            piece.erase(0, lead);
        }
        if (!piece.empty()) {
            for (const char c : piece) {
                if (c == ' ') {
                    out.append(escapeWhitespaces_ ? kMetaspacePrefix : " ");
                } else {
                    out.push_back(c);
                }
            }
            isPrevSpace = !piece.empty() && piece.back() == ' ';
        }
        offset += prefix.consumedBytes;
        if (!removeExtraWhitespaces_) isPrevSpace = false;
    }

    // 4. Ignores trailing space (the escaped form).
    if (removeExtraWhitespaces_) {
        const size_t symbolLen = escapeWhitespaces_ ? 3U : 1U;
        while (out.size() >= symbolLen &&
               out.compare(out.size() - symbolLen, symbolLen,
                           kMetaspacePrefix) == 0) {
            out.resize(out.size() - symbolLen);
        }
    }

    // 5. Adds a space symbol as a suffix (unused for Marian models).
    if (treatWhitespaceAsSuffix_ && addDummyPrefix_) {
        out.append(kMetaspacePrefix);
    }
    return true;
}

// ---------------------------------------------------------------------------
// Segmentation — sentencepiece unigram_model.cc EncodeOptimized
// ---------------------------------------------------------------------------

int32_t MarianTokenizer::trieChild(int32_t node, uint8_t c) const {
    const TrieNode& n = trie_[static_cast<size_t>(node)];
    for (size_t i = 0; i < static_cast<size_t>(n.childCount); ++i) {
        const auto& edge = edges_[static_cast<size_t>(n.firstChild) + i];
        if (edge.first == c) return edge.second;
        if (edge.first > c) break;
    }
    return -1;
}

bool MarianTokenizer::buildTrie(std::string* error) {
    struct BuildNode {
        std::vector<std::pair<uint8_t, int32_t>> children;
        int32_t pieceIndex = -1;
    };
    std::vector<BuildNode> build;
    build.reserve(spPieces_.size() * 4U + 16U);
    build.emplace_back();
    for (size_t index = 0; index < spPieces_.size(); ++index) {
        const std::string& piece = spPieces_[index].piece;
        if (piece.empty()) continue;
        size_t node = 0;
        for (const char raw : piece) {
            const uint8_t c = static_cast<uint8_t>(raw);
            size_t slot = 0;
            bool found = false;
            {
                const BuildNode& current = build[node];
                size_t j = 0;
                for (; j < current.children.size(); ++j) {
                    if (current.children[j].first == c) {
                        found = true;
                        break;
                    }
                    if (current.children[j].first > c) break;
                }
                slot = j;  // match index, or insertion point when absent
            }
            if (!found) {
                const int32_t nextId = static_cast<int32_t>(build.size());
                build.emplace_back();
                // Re-fetch after emplace_back: it may reallocate.
                build[node].children.insert(
                    build[node].children.begin() +
                        static_cast<std::ptrdiff_t>(slot),
                    std::make_pair(c, nextId));
                node = static_cast<size_t>(nextId);
            } else {
                node = static_cast<size_t>(
                    build[node].children[slot].second);
            }
        }
        build[node].pieceIndex = static_cast<int32_t>(index);
    }

    if (build.size() > kMaxPieces * 16U) {
        if (error) *error = "vocabulary trie exceeds the safety limit";
        return false;
    }

    trie_.clear();
    edges_.clear();
    trie_.reserve(build.size());
    size_t edgeCount = 0;
    for (const auto& node : build) edgeCount += node.children.size();
    edges_.reserve(edgeCount);
    for (auto& node : build) {
        std::sort(node.children.begin(), node.children.end(),
                  [](const auto& a, const auto& b) { return a.first < b.first; });
        if (node.children.size() > 255U) {
            if (error) *error = "vocabulary trie node has too many children";
            return false;
        }
        TrieNode packed;
        packed.firstChild = static_cast<uint32_t>(edges_.size());
        packed.childCount = static_cast<uint8_t>(node.children.size());
        packed.pieceIndex = node.pieceIndex;
        trie_.push_back(packed);
        for (const auto& edge : node.children) edges_.push_back(edge);
    }
    return true;
}

bool MarianTokenizer::segment(const std::string& normalized,
                              std::vector<int32_t>& pieceIndices) const {
    const size_t n = normalized.size();
    pieceIndices.clear();
    if (n == 0) return true;

    struct BestPathNode {
        int32_t index = -1;  // spPieces_ index (unk = -2)
        float bestPathScore = 0.0f;
        int64_t startsAt = -1;
    };
    std::vector<BestPathNode> best(n + 1);

    const uint8_t* bytes = reinterpret_cast<const uint8_t*>(normalized.data());
    size_t startsAt = 0;
    while (startsAt < n) {
        float baseScore = best[startsAt].bestPathScore;
        const size_t mblen = runeWidth(bytes + startsAt, n - startsAt);
        if (mblen == 0) return false;
        bool hasSingleNode = false;
        int32_t node = 0;
        for (size_t keyPos = startsAt; keyPos < n; ++keyPos) {
            node = trieChild(node, bytes[keyPos]);
            if (node < 0) break;
            const int32_t index = trie_[static_cast<size_t>(node)].pieceIndex;
            if (index >= 0) {
                const size_t length = keyPos + 1 - startsAt;
                const float candidate = spPieces_[static_cast<size_t>(index)].score + baseScore;
                BestPathNode& target = best[keyPos + 1];
                if (target.startsAt == -1 || candidate > target.bestPathScore) {
                    target.bestPathScore = candidate;
                    target.startsAt = static_cast<int64_t>(startsAt);
                    target.index = index;
                }
                if (!hasSingleNode && length == mblen) hasSingleNode = true;
            }
        }
        if (!hasSingleNode) {
            const size_t next = startsAt + mblen;
            BestPathNode& target = best[next];
            const float candidate = unkScore_ + baseScore;
            if (target.startsAt == -1 || candidate > target.bestPathScore) {
                target.bestPathScore = candidate;
                target.startsAt = static_cast<int64_t>(startsAt);
                target.index = -2;  // unk
            }
        }
        startsAt += mblen;
    }

    // Backtrack.
    int64_t endsAt = static_cast<int64_t>(n);
    while (endsAt > 0) {
        const BestPathNode& node = best[static_cast<size_t>(endsAt)];
        if (node.startsAt < 0 || node.index < -2 ||
            node.startsAt >= endsAt) {
            return false;
        }
        pieceIndices.push_back(node.index);
        endsAt = node.startsAt;
    }
    std::reverse(pieceIndices.begin(), pieceIndices.end());
    return true;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

MarianTokenizer::EncodeResult MarianTokenizer::encode(
    const std::string& text) const {
    EncodeResult result;
    if (text.size() > kMaxTextBytes) {
        result.error = "text too long to encode";
        return result;
    }

    std::string normalized;
    if (!normalize(text, normalized)) {
        result.error = "text contains malformed UTF-8";
        return result;
    }

    std::vector<int32_t> indices;
    if (!segment(normalized, indices)) {
        result.error = "vocabulary segmentation failed";
        return result;
    }

    result.ids.reserve(indices.size() + 1);
    for (const int32_t index : indices) {
        if (index == -2) {
            result.ids.push_back(hfUnkId_);
            continue;
        }
        if (index < 0 || static_cast<size_t>(index) >= spPieces_.size()) {
            result.error = "segmentation produced an invalid piece index";
            result.ids.clear();
            return result;
        }
        const std::string& piece = spPieces_[static_cast<size_t>(index)].piece;
        const auto found = hfIdByPiece_.find(piece);
        // Pieces absent from the joint vocab become unk, exactly like
        // MarianTokenizer._convert_token_to_id.
        result.ids.push_back(found == hfIdByPiece_.end() ? hfUnkId_
                                                         : found->second);
    }

    if (hfEosId_ < 0) {
        result.error = "tokenizer has no EOS id";
        result.ids.clear();
        return result;
    }
    result.ids.push_back(hfEosId_);
    result.ok = true;
    return result;
}

std::string MarianTokenizer::decode(const std::vector<int64_t>& ids) const {
    std::string out;
    for (const int64_t id : ids) {
        if (id == hfEosId_ || id == hfPadId_ || id == hfUnkId_) continue;
        if (id < 0 || static_cast<size_t>(id) >= hfTokens_.size()) continue;
        const std::string& token = hfTokens_[static_cast<size_t>(id)];
        // Metaspace decoder: replace U+2581 (E2 96 81) with a space.
        for (size_t i = 0; i < token.size(); ++i) {
            if (static_cast<uint8_t>(token[i]) == 0xE2U &&
                i + 2 < token.size() &&
                static_cast<uint8_t>(token[i + 1]) == 0x96U &&
                static_cast<uint8_t>(token[i + 2]) == 0x81U) {
                out.push_back(' ');
                i += 2;
            } else {
                out.push_back(token[i]);
            }
        }
    }
    size_t begin = 0;
    while (begin < out.size() && out[begin] == ' ') ++begin;
    size_t end = out.size();
    while (end > begin && out[end - 1] == ' ') --end;
    return out.substr(begin, end - begin);
}

bool MarianTokenizer::load(const std::string& spmPath,
                           const std::string& tokenizerJsonPath,
                           MarianTokenizer& tokenizer,
                           std::string* error) {
    // ---- 1. Parse the SentencePiece model protobuf. ----
    std::ifstream spmFile(spmPath, std::ios::binary);
    if (!spmFile.is_open()) {
        if (error) *error = fail("cannot open spm model", spmPath);
        return false;
    }
    spmFile.seekg(0, std::ios::end);
    const std::streamoff spmBytes = spmFile.tellg();
    if (spmBytes < 0 || static_cast<uint64_t>(spmBytes) > kMaxSpmBytes) {
        if (error) *error = "spm model exceeds the size limit";
        return false;
    }
    spmFile.seekg(0, std::ios::beg);
    std::string spmProto(static_cast<size_t>(spmBytes), '\0');
    if (!spmFile.read(spmProto.data(), spmBytes) || spmFile.peek() != std::char_traits<char>::eof()) {
        if (error) *error = "spm model changed while reading";
        return false;
    }

    std::vector<SpPiece> pieces;
    std::vector<uint32_t> charsmapUnits;
    std::string charsmapValues;
    bool addDummyPrefix = true;
    bool removeExtraWhitespaces = true;
    bool escapeWhitespaces = true;
    bool treatWhitespaceAsSuffix = false;
    int unknownCount = 0;

    ProtoReader reader(spmProto.data(), spmProto.size());
    while (reader.next()) {
        if (reader.fieldNumber() == 1 && reader.wireType() == 2) {
            // ModelPiece: piece(1 string), score(2 fixed32), type(3 varint).
            ProtoReader piece(reader.bytesData(), reader.bytesSize());
            std::string text;
            float score = 0.0f;
            uint32_t type = 1;
            while (piece.next()) {
                if (piece.fieldNumber() == 1 && piece.wireType() == 2) {
                    text.assign(piece.bytesData(), piece.bytesSize());
                } else if (piece.fieldNumber() == 2 && piece.wireType() == 5) {
                    // fixed32 holds the raw little-endian float bits.
                    const uint32_t bits = piece.fixed32();
                    std::memcpy(&score, &bits, sizeof(score));
                } else if (piece.fieldNumber() == 3 && piece.wireType() == 0) {
                    type = static_cast<uint32_t>(piece.varint());
                }
            }
            if (text.empty()) continue;
            if (type == 2) {  // UNKNOWN
                ++unknownCount;
                continue;
            }
            if (type == 3) continue;  // CONTROL
            if (type == 5) continue;  // UNUSED
            // NORMAL (1) and USER_DEFINED (4)
            if (pieces.size() >= kMaxPieces) {
                if (error) *error = "spm model has too many pieces";
                return false;
            }
            pieces.push_back(SpPiece{std::move(text), score});
        } else if (reader.fieldNumber() == 3 && reader.wireType() == 2) {
            // NormalizerSpec.
            ProtoReader spec(reader.bytesData(), reader.bytesSize());
            while (spec.next()) {
                if (spec.fieldNumber() == 2 && spec.wireType() == 2) {
                    const char* blob = spec.bytesData();
                    const size_t blobSize = spec.bytesSize();
                    if (blobSize >= 1028U) {
                        uint32_t trieBytes = 0;
                        std::memcpy(&trieBytes, blob, sizeof(trieBytes));
                        if (trieBytes > 0 &&
                            trieBytes <= blobSize - 4U &&
                            (trieBytes % 4U) == 0U) {
                            const size_t unitCount = trieBytes / 4U;
                            charsmapUnits.resize(unitCount);
                            std::memcpy(charsmapUnits.data(), blob + 4U,
                                        trieBytes);
                            charsmapValues.assign(blob + 4U + trieBytes,
                                                  blobSize - 4U - trieBytes);
                            if (charsmapValues.empty() ||
                                charsmapValues.back() != '\0') {
                                charsmapUnits.clear();
                                charsmapValues.clear();
                            }
                        }
                    }
                } else if (spec.fieldNumber() == 3 && spec.wireType() == 0) {
                    addDummyPrefix = spec.varint() != 0;
                } else if (spec.fieldNumber() == 4 && spec.wireType() == 0) {
                    removeExtraWhitespaces = spec.varint() != 0;
                } else if (spec.fieldNumber() == 5 && spec.wireType() == 0) {
                    escapeWhitespaces = spec.varint() != 0;
                }
            }
        } else if (reader.fieldNumber() == 2 && reader.wireType() == 2) {
            // TrainerSpec: treat_whitespace_as_suffix (33) / (34).
            ProtoReader spec(reader.bytesData(), reader.bytesSize());
            while (spec.next()) {
                if ((spec.fieldNumber() == 33 || spec.fieldNumber() == 34) &&
                    spec.wireType() == 0) {
                    if (spec.varint() != 0) treatWhitespaceAsSuffix = true;
                }
            }
        }
    }

    if (pieces.empty()) {
        if (error) *error = "spm model has no usable pieces";
        return false;
    }
    if (unknownCount != 1) {
        if (error) *error = "spm model must define exactly one UNKNOWN piece";
        return false;
    }

    // ---- 2. Parse tokenizer.json for the joint vocab and special ids. ----
    std::ifstream jsonFile(tokenizerJsonPath, std::ios::binary);
    if (!jsonFile.is_open()) {
        if (error) *error = fail("cannot open tokenizer.json", tokenizerJsonPath);
        return false;
    }
    jsonFile.seekg(0, std::ios::end);
    const std::streamoff jsonBytes = jsonFile.tellg();
    if (jsonBytes < 0 || static_cast<uint64_t>(jsonBytes) > kMaxDocumentBytes) {
        if (error) *error = "tokenizer.json exceeds the size limit";
        return false;
    }
    jsonFile.seekg(0, std::ios::beg);
    std::string document(static_cast<size_t>(jsonBytes), '\0');
    if (!jsonFile.read(document.data(), jsonBytes) || jsonFile.peek() != std::char_traits<char>::eof()) {
        if (error) *error = "tokenizer.json changed while reading";
        return false;
    }

    JsonWalker walker(document.data(), document.size());
    if (!walker.expect('{')) {
        if (error) *error = "tokenizer.json is not a JSON object";
        return false;
    }

    std::vector<std::string> hfTokens;
    int64_t unkAddedId = -1;
    int64_t eosAddedId = -1;
    int64_t padAddedId = -1;
    bool sawVocab = false;

    if (!walker.skipWhitespace()) {
        if (error) *error = "tokenizer.json is truncated";
        return false;
    }
    if (walker.peekIs('}')) {
        if (error) *error = "tokenizer.json is an empty object";
        return false;
    }

    while (true) {
        std::string key;
        if (!walker.parseString(key)) {
            if (error) *error = "invalid object key in tokenizer.json";
            return false;
        }
        if (!walker.expect(':')) {
            if (error) *error = "missing colon in tokenizer.json";
            return false;
        }

        if (key == "model") {
            if (!walker.expect('{')) {
                if (error) *error = "model is not an object";
                return false;
            }
            while (true) {
                std::string innerKey;
                if (!walker.parseString(innerKey)) {
                    if (error) *error = "invalid model key";
                    return false;
                }
                if (!walker.expect(':')) {
                    if (error) *error = "missing colon in model";
                    return false;
                }
                if (innerKey == "vocab") {
                    sawVocab = true;
                    if (!walker.expect('[')) {
                        if (error) *error = "model vocab is not an array";
                        return false;
                    }
                    while (true) {
                        if (!walker.skipWhitespace()) {
                            if (error) *error = "truncated model vocab";
                            return false;
                        }
                        if (walker.peekIs(']')) {
                            walker.advance();
                            break;
                        }
                        if (!walker.expect('[')) {
                            if (error) *error = "vocab entry is not an array";
                            return false;
                        }
                        std::string token;
                        double score = 0.0;
                        if (!walker.parseString(token) ||
                            !walker.expect(',') ||
                            !walker.parseNumber(score) ||
                            !walker.expect(']')) {
                            if (error) *error = "invalid vocab entry";
                            return false;
                        }
                        (void)score;
                        if (hfTokens.size() >= kMaxVocabEntries) {
                            if (error) *error = "vocabulary exceeds the limit";
                            return false;
                        }
                        hfTokens.push_back(std::move(token));
                        if (!walker.skipWhitespace()) {
                            if (error) *error = "truncated model vocab";
                            return false;
                        }
                        if (walker.peekIs(',')) {
                            walker.advance();
                            continue;
                        }
                        if (!walker.expect(']')) {
                            if (error) *error = "unterminated model vocab";
                            return false;
                        }
                        break;
                    }
                } else if (!walker.skipValue(0)) {
                    if (error) *error = "invalid model value";
                    return false;
                }
                if (!walker.skipWhitespace()) {
                    if (error) *error = "truncated model";
                    return false;
                }
                if (walker.peekIs(',')) {
                    walker.advance();
                    continue;
                }
                if (!walker.expect('}')) {
                    if (error) *error = "unterminated model";
                    return false;
                }
                break;
            }
        } else if (key == "added_tokens") {
            if (!walker.expect('[')) {
                if (error) *error = "added_tokens is not an array";
                return false;
            }
            while (true) {
                if (!walker.skipWhitespace()) {
                    if (error) *error = "truncated added_tokens";
                    return false;
                }
                if (walker.peekIs(']')) {
                    walker.advance();
                    break;
                }
                if (!walker.expect('{')) {
                    if (error) *error = "added token entry is not an object";
                    return false;
                }
                std::string content;
                int64_t id = -1;
                while (true) {
                    std::string field;
                    if (!walker.parseString(field)) {
                        if (error) *error = "invalid added token field";
                        return false;
                    }
                    if (!walker.expect(':')) {
                        if (error) *error = "missing colon in added token";
                        return false;
                    }
                    if (field == "content") {
                        if (!walker.parseString(content)) {
                            if (error) *error = "invalid added token content";
                            return false;
                        }
                    } else if (field == "id") {
                        double value = 0.0;
                        if (!walker.parseNumber(value)) {
                            if (error) *error = "invalid added token id";
                            return false;
                        }
                        // Conversion outside int64 range is undefined behavior.
                        if (!std::isfinite(value) || std::trunc(value) != value ||
                            value < -9223372036854775808.0 ||
                            value >= 9223372036854775808.0) {
                            if (error) *error = "added token id is not a finite int64";
                            return false;
                        }
                        id = static_cast<int64_t>(value);
                    } else if (!walker.skipValue(0)) {
                        if (error) *error = "invalid added token value";
                        return false;
                    }
                    if (!walker.skipWhitespace()) {
                        if (error) *error = "truncated added token";
                        return false;
                    }
                    if (walker.peekIs(',')) {
                        walker.advance();
                        continue;
                    }
                    if (!walker.expect('}')) {
                        if (error) *error = "unterminated added token";
                        return false;
                    }
                    break;
                }
                if (content == "<unk>") {
                    unkAddedId = id;
                } else if (content == "</s>") {
                    eosAddedId = id;
                } else if (content == "<pad>") {
                    padAddedId = id;
                }
                if (!walker.skipWhitespace()) {
                    if (error) *error = "truncated added_tokens";
                    return false;
                }
                if (walker.peekIs(',')) {
                    walker.advance();
                    continue;
                }
                if (!walker.expect(']')) {
                    if (error) *error = "unterminated added_tokens";
                    return false;
                }
                break;
            }
        } else if (!walker.skipValue(0)) {
            if (error) *error = "invalid top-level value in tokenizer.json";
            return false;
        }

        if (!walker.skipWhitespace()) {
            if (error) *error = "truncated tokenizer.json";
            return false;
        }
        if (walker.peekIs(',')) {
            walker.advance();
            continue;
        }
        if (!walker.expect('}')) {
            if (error) *error = "unterminated tokenizer.json object";
            return false;
        }
        break;
    }

    if (!sawVocab || hfTokens.empty()) {
        if (error) *error = "tokenizer.json has no vocabulary";
        return false;
    }

    // Resolve special ids. The added_tokens block is authoritative; fall back
    // to the vocab position when absent.
    int64_t unkId = unkAddedId;
    int64_t eosId = eosAddedId;
    int64_t padId = padAddedId;
    for (size_t i = 0; i < hfTokens.size(); ++i) {
        if (unkId < 0 && hfTokens[i] == "<unk>") {
            unkId = static_cast<int64_t>(i);
        }
        if (eosId < 0 && hfTokens[i] == "</s>") {
            eosId = static_cast<int64_t>(i);
        }
        if (padId < 0 && hfTokens[i] == "<pad>") {
            padId = static_cast<int64_t>(i);
        }
    }
    // Upper bounds are load-bearing: padId/unkId index the decoder logits
    // buffer, so an out-of-range id from a crafted file is a heap OOB write.
    const int64_t vocabLimit = static_cast<int64_t>(hfTokens.size());
    if (eosId < 0 || padId < 0 || unkId < 0 ||
        unkId >= vocabLimit || eosId >= vocabLimit || padId >= vocabLimit) {
        if (error) *error =
            "tokenizer.json special-token ids are missing or out of range";
        return false;
    }

    // ---- 3. Assemble. ----
    MarianTokenizer built;
    // Stable sort by score descending (SP trie insertion order for ties).
    std::stable_sort(pieces.begin(), pieces.end(),
                     [](const SpPiece& a, const SpPiece& b) {
                         return a.score > b.score;
                     });
    built.spPieces_ = std::move(pieces);
    built.charsmapUnits_ = std::move(charsmapUnits);
    built.charsmapValues_ = std::move(charsmapValues);
    built.addDummyPrefix_ = addDummyPrefix;
    built.removeExtraWhitespaces_ = removeExtraWhitespaces;
    built.escapeWhitespaces_ = escapeWhitespaces;
    built.treatWhitespaceAsSuffix_ = treatWhitespaceAsSuffix;

    // SentencePiece unigram: unk score = min score - kUnkPenalty (10.0).
    float minScore = 0.0f;
    if (!built.spPieces_.empty()) {
        minScore = built.spPieces_[0].score;
        for (const auto& piece : built.spPieces_) {
            minScore = std::min(minScore, piece.score);
        }
    }
    built.unkScore_ = minScore - 10.0f;

    built.hfTokens_ = std::move(hfTokens);
    built.hfUnkId_ = unkId;
    built.hfEosId_ = eosId;
    built.hfPadId_ = padId;
    built.hfIdByPiece_.reserve(built.hfTokens_.size() * 2U);
    for (size_t i = 0; i < built.hfTokens_.size(); ++i) {
        built.hfIdByPiece_.emplace(built.hfTokens_[i], static_cast<int64_t>(i));
    }

    if (!built.buildTrie(error)) return false;

    tokenizer = std::move(built);
    return true;
}

} // namespace marian
} // namespace stt
