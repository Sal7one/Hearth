#ifndef STT_MARIAN_TOKENIZER_H
#define STT_MARIAN_TOKENIZER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace stt {
namespace marian {

/**
 * OPUS-MT/Marian tokenizer matching the transformers slow MarianTokenizer:
 * source SentencePiece model (source.spm protobuf) for normalization and
 * unigram segmentation, with piece-to-id mapping through the HF joint vocab
 * (tokenizer.json model.vocab — equivalent to vocab.json). Pieces missing
 * from the joint vocab become the HF unk id, mirroring
 * MarianTokenizer._convert_token_to_id.
 *
 * Pipeline:
 *   1. Normalizer: the model's precompiled charsmap (Darts double-array trie
 *      inside the spm protobuf) with add_dummy_prefix / escape_whitespaces /
 *      remove_extra_whitespaces exactly as SentencePiece applies them.
 *   2. Unigram Viterbi (byte positions, score maximization, unk per rune when
 *      no single-rune token matches) over the spm pieces, excluding
 *      CONTROL/UNKNOWN/UNUSED pieces.
 *   3. Each resulting piece string maps to the joint-vocab id; missing pieces
 *      map to the unk id.
 *   4. The </s> (EOS) id is appended, as MarianTokenizer does.
 *
 * Decode joins joint-vocab strings with U+2581 replaced by a space and strips
 * outer whitespace (clean_up_tokenization_spaces).
 *
 * Both inputs are parsed with small dependency-free readers: the spm file is
 * a protobuf (~800 KB) and tokenizer.json is ~6.7 MB (larger than JsonUtils
 * limits), so this class keeps its own bounded parsers.
 */
class MarianTokenizer {
public:
    struct EncodeResult {
        bool ok = false;
        std::string error;
        std::vector<int64_t> ids;
    };

    /**
     * Loads the tokenizer from a source.spm SentencePiece model and a
     * tokenizer.json joint vocab. Returns true on success.
     */
    static bool load(const std::string& spmPath,
                     const std::string& tokenizerJsonPath,
                     MarianTokenizer& tokenizer,
                     std::string* error);

    /** Encodes one caption utterance (includes the trailing </s> id). */
    EncodeResult encode(const std::string& text) const;

    /** Decodes a joint-vocab id sequence back to display text. */
    std::string decode(const std::vector<int64_t>& ids) const;

    int64_t eosTokenId() const { return hfEosId_; }
    int64_t padTokenId() const { return hfPadId_; }
    int64_t unkTokenId() const { return hfUnkId_; }
    size_t vocabSize() const { return hfTokens_.size(); }

private:
    // Packed byte trie over the spm pieces; edges grouped per node (sorted by
    // byte) so a 32k-piece vocab stays a few MB.
    struct TrieNode {
        uint32_t firstChild = 0;
        uint8_t childCount = 0;
        int32_t pieceIndex = -1;  // index into spPieces_
    };

    struct SpPiece {
        std::string piece;
        float score = 0.0f;
    };

    // Charsmap (SentencePiece Darts double array).
    // Blob layout: <uint32 LE trie byte size><units><NUL-delimited
    // replacement strings>. See sentencepiece normalizer.cc.
    std::vector<uint32_t> charsmapUnits_;
    std::string charsmapValues_;
    bool addDummyPrefix_ = true;
    bool removeExtraWhitespaces_ = true;
    bool escapeWhitespaces_ = true;
    bool treatWhitespaceAsSuffix_ = false;

    // SentencePiece side: eligible pieces (NORMAL / USER_DEFINED), stable
    // sorted by score descending to mirror SP's trie insertion order.
    std::vector<SpPiece> spPieces_;
    std::vector<TrieNode> trie_;
    std::vector<std::pair<uint8_t, int32_t>> edges_;
    float unkScore_ = 0.0f;

    // Joint-vocab side.
    std::vector<std::string> hfTokens_;
    std::unordered_map<std::string, int64_t> hfIdByPiece_;
    int64_t hfUnkId_ = -1;
    int64_t hfEosId_ = -1;
    int64_t hfPadId_ = -1;

    static constexpr char kMetaspacePrefix[] = "\u2581";

    bool buildTrie(std::string* error);
    int32_t trieChild(int32_t node, uint8_t c) const;

    // SentencePiece Normalize (see sentencepiece normalizer.cc).
    bool normalize(const std::string& input, std::string& out) const;

    // SentencePiece EncodeOptimized (see sentencepiece unigram_model.cc):
    // byte-position Viterbi with score maximization. Returns spPieces_ indices.
    bool segment(const std::string& normalized,
                 std::vector<int32_t>& pieceIndices) const;
};

} // namespace marian
} // namespace stt

#endif // STT_MARIAN_TOKENIZER_H
