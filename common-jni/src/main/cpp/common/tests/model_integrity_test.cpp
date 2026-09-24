#include "model_integrity.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

namespace fs = std::filesystem;

struct TempTree {
    fs::path path;

    TempTree() {
        char name[] = "/tmp/hearth-model-integrity-XXXXXX";
        const char* created = ::mkdtemp(name);
        assert(created != nullptr);
        path = created;
    }

    ~TempTree() { fs::remove_all(path); }
};

void writeFile(const fs::path& path, const std::string& bytes) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    assert(out.good());
    out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    assert(out.good());
}

void testSharedTreeVectorAndPinnedIdentity() {
    TempTree tree;
    fs::create_directory(tree.path / "graph");
    fs::create_directory(tree.path / "am");
    writeFile(tree.path / "graph/b.txt", "bc");
    writeFile(tree.path / "am/a.bin", "a");

    // The same fixture and digest are asserted by Kotlin ModelIntegrityTest.
    constexpr const char* expected =
        "a6cfaf59b04050d1745b7088377985e3ada086b49b5528a199129ac6da124e36";
    const auto observed = stt::digestPath(tree.path.string());
    assert(observed);
    assert(observed.kind == stt::ModelPathKind::DIRECTORY_TREE);
    assert(observed.fileCount == 2);
    assert(observed.aggregateBytes == 3);
    assert(observed.digestHex() == expected);
    assert(stt::digestPath(tree.path.string(), expected));
    assert(stt::digestPath(tree.path.string(), std::string(64, '0')).error ==
           stt::ModelIntegrityError::SHA256_MISMATCH);

    fs::remove(tree.path / "graph/b.txt");
    fs::remove(tree.path / "am/a.bin");
    writeFile(tree.path / "am/a.bin", "a");
    writeFile(tree.path / "graph/b.txt", "bc");
    assert(stt::digestPath(tree.path.string()).digestHex() == expected);
}

void testSymlinksAndEmptyTrees() {
    TempTree tree;
    const auto empty = stt::digestPath(tree.path.string());
    assert(empty.error == stt::ModelIntegrityError::EMPTY_MODEL_TREE);

    writeFile(tree.path / "model.bin", "model");
    fs::create_symlink(tree.path / "model.bin", tree.path / "link.bin");
    assert(stt::digestPath((tree.path / "link.bin").string()).error ==
           stt::ModelIntegrityError::PATH_OPEN_FAILED);
    assert(stt::digestPath(tree.path.string()).error ==
           stt::ModelIntegrityError::NON_REGULAR_TREE_ENTRY);

    fs::remove(tree.path / "link.bin");
    fs::create_directory(tree.path / "subdir");
    fs::create_symlink(tree.path / "model.bin", tree.path / "subdir/link.bin");
    assert(stt::digestPath(tree.path.string()).error ==
           stt::ModelIntegrityError::NON_REGULAR_TREE_ENTRY);
}

void testPathAndSizeLimits() {
    using stt::ModelIntegrityError;
    using stt::ModelTreeEntry;
    using stt::ModelTreeEntryType;
    ModelTreeEntry entry;
    entry.relativePath = "safe/model.bin";
    entry.sizeBytes = 1;
    assert(stt::computeModelTreeSha256({entry}));

    for (const std::string& path : {"../escape", "a/./b", "a//b", "/absolute", "a\\b"}) {
        entry.relativePath = path;
        assert(stt::computeModelTreeSha256({entry}).error ==
               ModelIntegrityError::INVALID_TREE_PATH);
    }
    entry.relativePath = std::string("bad-") + static_cast<char>(0xff);
    assert(stt::computeModelTreeSha256({entry}).error ==
           ModelIntegrityError::INVALID_TREE_PATH);
    entry.relativePath = std::string(4097, 'x');
    assert(stt::computeModelTreeSha256({entry}).error ==
           ModelIntegrityError::INVALID_TREE_PATH);
    entry.relativePath.clear();
    for (int i = 0; i < 33; ++i) entry.relativePath += (i == 0 ? "d" : "/d");
    assert(stt::computeModelTreeSha256({entry}).error ==
           ModelIntegrityError::TREE_PATH_TOO_DEEP);

    entry.relativePath = "safe/model.bin";
    entry.type = ModelTreeEntryType::SYMBOLIC_LINK;
    assert(stt::computeModelTreeSha256({entry}).error ==
           ModelIntegrityError::NON_REGULAR_TREE_ENTRY);
    entry.type = ModelTreeEntryType::REGULAR_FILE;
    assert(stt::computeModelTreeSha256({entry, entry}).error ==
           ModelIntegrityError::DUPLICATE_TREE_PATH);
    entry.sizeBytes = stt::ModelIntegrityLimits::kMaxSingleFileBytes + 1;
    assert(stt::computeModelTreeSha256({entry}).error ==
           ModelIntegrityError::FILE_TOO_LARGE);
    entry.sizeBytes = stt::ModelIntegrityLimits::kMaxSingleFileBytes;
    ModelTreeEntry second = entry;
    second.relativePath = "other.bin";
    second.sizeBytes = stt::ModelIntegrityLimits::kMaxSingleFileBytes;
    ModelTreeEntry third = entry;
    third.relativePath = "third.bin";
    third.sizeBytes = 1;
    assert(stt::computeModelTreeSha256({entry, second, third}).error ==
           ModelIntegrityError::TREE_TOO_LARGE);
}

void testChangedFileSnapshot() {
    TempTree tree;
    const fs::path model = tree.path / "model.bin";
    writeFile(model, "first");
    const int fd = ::open(model.c_str(), O_RDONLY | O_CLOEXEC);
    assert(fd >= 0);
    struct stat before {};
    assert(::fstat(fd, &before) == 0);
    // Capture the old snapshot, then alter the same opened file before hashing.
    writeFile(model, "a much longer replacement");
    stt::Sha256::Digest digest{};
    stt::ModelIntegrityError error = stt::ModelIntegrityError::NONE;
    int systemError = 0;
    const bool ok = stt::model_integrity_detail::digestRegularFileFd(
        fd, before, digest, error, systemError);
    assert(!ok);
    assert(error == stt::ModelIntegrityError::PATH_CHANGED);
    ::close(fd);
}

} // namespace

int main() {
    testSharedTreeVectorAndPinnedIdentity();
    testSymlinksAndEmptyTrees();
    testPathAndSizeLimits();
    testChangedFileSnapshot();
    std::puts("model_integrity_test: ALL PASS");
}
