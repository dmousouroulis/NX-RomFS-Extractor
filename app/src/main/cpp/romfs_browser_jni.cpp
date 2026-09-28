#include <jni.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <sstream>

#include <tc/io/IStream.h>
#include <tc/io/Path.h>
#include <tc/io/IOException.h>
#include <tc/NotSupportedException.h>
#include <pietendo/hac/ContentArchiveHeader.h>

#include "PfsProcess.h"
#include "GameCardProcess.h"
#include "NcaProcess.h"
#include "EsTikProcess.h"
#include "KeyBag.h"

namespace {

std::string toString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* ptr = env->GetStringUTFChars(value, nullptr);
    std::string out(ptr ? ptr : "");
    if (ptr) env->ReleaseStringUTFChars(value, ptr);
    return out;
}

std::string toUpperHex(uint64_t value) {
    char buf[17];
    std::snprintf(buf, sizeof(buf), "%016llX", static_cast<unsigned long long>(value));
    return std::string(buf);
}

std::string extensionLower(const std::string& name) {
    const auto p = name.find_last_of('.');
    if (p == std::string::npos || p + 1 >= name.size()) return {};
    std::string ext = name.substr(p + 1);
    for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return ext;
}

bool hasExtension(const std::string& name, const std::string& wanted) {
    return extensionLower(name) == wanted;
}

bool isSupportedContainer(const std::string& name) {
    const auto ext = extensionLower(name);
    return ext == "nsp" || ext == "xci";
}

std::string joinPhysical(const std::string& parent, const std::string& name) {
    if (parent.empty() || parent == "/") return "/" + name;
    return parent + "/" + name;
}

std::string joinLogical(const std::string& parent, const std::string& name) {
    if (parent.empty()) return name;
    return parent + "/" + name;
}

class AndroidFdStream final : public tc::io::IStream {
public:
    explicit AndroidFdStream(int fd) : fd_(-1) {
        if (fd < 0) throw tc::io::IOException("Invalid file descriptor");
        fd_ = dup(fd);
        if (fd_ < 0) throw tc::io::IOException("Could not duplicate file descriptor");
        if (lseek64(fd_, 0, SEEK_CUR) == (off64_t)-1) {
            close(fd_);
            fd_ = -1;
            throw tc::NotSupportedException("Selected document provider does not expose a seekable file");
        }
    }

    ~AndroidFdStream() override { dispose(); }
    bool canRead() const override { return true; }
    bool canWrite() const override { return false; }
    bool canSeek() const override { return true; }

    int64_t length() override {
        struct stat st{};
        if (fstat(fd_, &st) != 0) throw tc::io::IOException("fstat failed");
        return st.st_size;
    }

    int64_t position() override { return lseek64(fd_, 0, SEEK_CUR); }

    size_t read(byte_t* ptr, size_t count) override {
        ssize_t result = ::read(fd_, ptr, count);
        if (result < 0) throw tc::io::IOException("read failed");
        return static_cast<size_t>(result);
    }

    size_t write(const byte_t*, size_t) override {
        throw tc::NotSupportedException("Read-only stream");
    }

    int64_t seek(int64_t offset, tc::io::SeekOrigin origin) override {
        int whence = SEEK_SET;
        if (origin == tc::io::SeekOrigin::Current) whence = SEEK_CUR;
        else if (origin == tc::io::SeekOrigin::End) whence = SEEK_END;
        off64_t result = lseek64(fd_, offset, whence);
        if (result == (off64_t)-1) throw tc::io::IOException("seek failed");
        return result;
    }

    void setLength(int64_t) override { throw tc::NotSupportedException("Read-only stream"); }
    void flush() override {}
    void dispose() override {
        if (fd_ >= 0) {
            close(fd_);
            fd_ = -1;
        }
    }

private:
    int fd_;
};

std::shared_ptr<tc::io::IFileSystem> openContainer(
        const std::shared_ptr<tc::io::IStream>& stream,
        const std::string& fileName,
        const nstool::KeyBag& keys) {
    const auto ext = extensionLower(fileName);

    if (ext == "nsp") {
        nstool::PfsProcess pfs;
        pfs.setInputFile(stream);
        pfs.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
        pfs.process();
        return pfs.getFileSystem();
    }

    if (ext == "xci") {
        nstool::GameCardProcess gameCard;
        gameCard.setInputFile(stream);
        gameCard.setKeyCfg(keys);
        gameCard.setVerifyMode(false);
        gameCard.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
        gameCard.process();
        return gameCard.getFileSystem();
    }

    throw tc::Exception("Unsupported container. Select an NSP or XCI file");
}

void collectPathsByExtension(
        const std::shared_ptr<tc::io::IFileSystem>& fs,
        const std::string& directory,
        const std::string& extension,
        std::vector<std::string>& out,
        int depth = 0) {
    if (!fs || depth > 32) return;

    tc::io::sDirectoryListing listing;
    fs->getDirectoryListing(tc::io::Path(directory), listing);

    for (const auto& name : listing.file_list) {
        if (hasExtension(name, extension)) {
            out.push_back(joinPhysical(directory, name));
        }
    }

    for (const auto& dir : listing.dir_list) {
        collectPathsByExtension(fs, joinPhysical(directory, dir), extension, out, depth + 1);
    }
}

std::vector<std::string> listNcas(const std::shared_ptr<tc::io::IFileSystem>& fs) {
    std::vector<std::string> result;
    collectPathsByExtension(fs, "/", "nca", result);
    std::sort(result.begin(), result.end());
    return result;
}

void importTickets(const std::shared_ptr<tc::io::IFileSystem>& fs, nstool::KeyBag& keys) {
    if (!fs) return;

    std::vector<std::string> tickets;
    collectPathsByExtension(fs, "/", "tik", tickets);

    for (const auto& path : tickets) {
        try {
            std::shared_ptr<tc::io::IStream> stream;
            fs->openFile(tc::io::Path(path), tc::io::FileMode::Open, tc::io::FileAccess::Read, stream);
            nstool::EsTikProcess tik;
            tik.setInputFile(stream);
            tik.setKeyCfg(keys);
            tik.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
            tik.process();

            const auto& body = tik.getTicket().getBody();
            nstool::KeyBag::rights_id_t rightsId;
            std::memcpy(rightsId.data(), body.getRightsId(), 16);
            nstool::KeyBag::aes128_key_t titleKey;
            std::memcpy(titleKey.data(), body.getEncTitleKey(), 16);
            keys.external_enc_content_keys[rightsId] = titleKey;
        } catch (...) {
        }
    }
}

void collectFilesRecursive(
        const std::shared_ptr<tc::io::IFileSystem>& fs,
        const std::string& physicalDir,
        const std::string& logicalDir,
        std::vector<std::string>& out,
        size_t limit,
        int depth = 0) {
    if (!fs || depth > 96 || out.size() >= limit) return;

    tc::io::sDirectoryListing listing;
    fs->getDirectoryListing(tc::io::Path(physicalDir), listing);

    for (const auto& file : listing.file_list) {
        if (out.size() >= limit) break;
        out.push_back(joinLogical(logicalDir, file));
    }

    for (const auto& dir : listing.dir_list) {
        if (out.size() >= limit) break;
        collectFilesRecursive(
            fs,
            joinPhysical(physicalDir, dir),
            joinLogical(logicalDir, dir),
            out,
            limit,
            depth + 1);
    }
}

std::vector<std::string> buildLogicalRomFsIndex(const std::shared_ptr<tc::io::IFileSystem>& fs) {
    constexpr size_t kMaxFiles = 200000;
    std::vector<std::string> best;

    for (int i = 0; i < 8; i++) {
        std::vector<std::string> candidate;
        try {
            collectFilesRecursive(fs, "/" + std::to_string(i), "", candidate, kMaxFiles);
        } catch (...) {
            candidate.clear();
        }
        if (candidate.size() > best.size()) best.swap(candidate);
    }

    if (best.empty()) {
        try {
            collectFilesRecursive(fs, "/", "", best, kMaxFiles);
        } catch (...) {
            best.clear();
        }
    }

    std::sort(best.begin(), best.end());
    best.erase(std::unique(best.begin(), best.end()), best.end());
    return best;
}

struct ProgramNcaInfo {
    std::shared_ptr<tc::io::IStream> stream;
    uint64_t programId = 0;
};

ProgramNcaInfo findBaseProgramNca(
        const std::shared_ptr<tc::io::IFileSystem>& fs,
        const nstool::KeyBag& keys) {
    std::string lastError;

    for (const auto& path : listNcas(fs)) {
        try {
            std::shared_ptr<tc::io::IStream> stream;
            fs->openFile(tc::io::Path(path), tc::io::FileMode::Open, tc::io::FileAccess::Read, stream);

            nstool::NcaProcess nca;
            nca.setInputFile(stream);
            nca.setKeyCfg(keys);
            nca.setVerifyMode(false);
            nca.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
            nca.process();

            const auto& header = nca.getHeader();
            if (header.getContentType() != pie::hac::nca::ContentType_Program) continue;

            const auto files = buildLogicalRomFsIndex(nca.getFileSystem());
            if (files.empty()) {
                const auto diagnostic = nca.getPartitionFailureSummary();
                if (!diagnostic.empty()) lastError = diagnostic;
                continue;
            }

            stream->seek(0, tc::io::SeekOrigin::Begin);
            return {stream, header.getProgramId()};
        } catch (const std::exception& e) {
            lastError = e.what();
        } catch (...) {
            lastError = "unknown native error";
        }
    }

    if (!lastError.empty()) {
        throw tc::Exception("Could not open the base Program RomFS: " + lastError);
    }
    return {};
}

std::vector<std::string> indexBaseRomFs(
        const ProgramNcaInfo& baseProgram,
        const nstool::KeyBag& keys) {
    baseProgram.stream->seek(0, tc::io::SeekOrigin::Begin);

    nstool::NcaProcess nca;
    nca.setInputFile(baseProgram.stream);
    nca.setKeyCfg(keys);
    nca.setVerifyMode(false);
    nca.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
    nca.process();

    auto files = buildLogicalRomFsIndex(nca.getFileSystem());
    if (files.empty()) {
        const auto diagnostic = nca.getPartitionFailureSummary();
        if (!diagnostic.empty()) {
            throw tc::Exception("Could not read the base RomFS: " + diagnostic);
        }
    }
    return files;
}

std::vector<std::string> indexUpdatedRomFs(
        const ProgramNcaInfo& baseProgram,
        const std::shared_ptr<tc::io::IFileSystem>& updateFs,
        const nstool::KeyBag& keys) {
    std::string lastError;
    bool sawProgramCandidate = false;

    for (const auto& path : listNcas(updateFs)) {
        try {
            std::shared_ptr<tc::io::IStream> updateNcaStream;
            updateFs->openFile(
                tc::io::Path(path),
                tc::io::FileMode::Open,
                tc::io::FileAccess::Read,
                updateNcaStream);

            baseProgram.stream->seek(0, tc::io::SeekOrigin::Begin);

            nstool::NcaProcess nca;
            nca.setInputFile(updateNcaStream);
            nca.setBaseNcaStream(baseProgram.stream);
            nca.setKeyCfg(keys);
            nca.setVerifyMode(false);
            nca.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
            nca.process();

            const auto& header = nca.getHeader();
            if (header.getContentType() != pie::hac::nca::ContentType_Program) continue;

            sawProgramCandidate = true;
            auto files = buildLogicalRomFsIndex(nca.getFileSystem());
            if (!files.empty()) return files;

            const auto diagnostic = nca.getPartitionFailureSummary();
            if (!diagnostic.empty()) lastError = diagnostic;
        } catch (const std::exception& e) {
            lastError = e.what();
        } catch (...) {
            lastError = "unknown native error";
        }
    }

    if (!lastError.empty()) {
        throw tc::Exception("Could not reconstruct the updated RomFS: " + lastError);
    }
    if (sawProgramCandidate) {
        throw tc::Exception("The updated Program NCA opened, but no RomFS filesystem was mounted");
    }
    throw tc::Exception("Could not find a usable updated Program NCA");
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_dmousouroulis_nxromfsextractor_RomFsExtractorActivity_listRomFsFilesNative(
        JNIEnv* env,
        jobject,
        jint baseFd,
        jstring baseFileName,
        jint updateFd,
        jstring updateFileName,
        jstring keysFile) {
    const std::string baseName = toString(env, baseFileName);
    const std::string updateName = toString(env, updateFileName);
    const std::string keyPath = toString(env, keysFile);

    try {
        if (keyPath.empty()) throw tc::Exception("prod.keys path is empty");
        if (!isSupportedContainer(baseName)) {
            throw tc::Exception("Base package must be an NSP or XCI file");
        }
        if (updateFd >= 0 && !isSupportedContainer(updateName)) {
            throw tc::Exception("Update package must be an NSP or XCI file");
        }

        nstool::KeyBag keys = nstool::KeyBagInitializer(
            false,
            tc::Optional<tc::io::Path>(keyPath),
            tc::Optional<tc::io::Path>(),
            std::vector<tc::io::Path>(),
            tc::Optional<tc::io::Path>());

        auto baseRoot = std::make_shared<AndroidFdStream>(baseFd);
        auto baseFs = openContainer(baseRoot, baseName, keys);
        importTickets(baseFs, keys);

        auto baseProgram = findBaseProgramNca(baseFs, keys);
        if (!baseProgram.stream) {
            throw tc::Exception("Could not find a readable Program RomFS in the base package");
        }

        std::vector<std::string> files;
        if (updateFd >= 0) {
            auto updateRoot = std::make_shared<AndroidFdStream>(updateFd);
            auto updateFs = openContainer(updateRoot, updateName, keys);
            importTickets(updateFs, keys);
            files = indexUpdatedRomFs(baseProgram, updateFs, keys);
        } else {
            files = indexBaseRomFs(baseProgram, keys);
        }

        if (files.empty()) throw tc::Exception("No RomFS files were found");

        std::ostringstream out;
        out << "OK|" << toUpperHex(baseProgram.programId) << "|" << files.size();
        for (const auto& path : files) out << '\n' << path;
        return env->NewStringUTF(out.str().c_str());
    } catch (const tc::Exception& e) {
        const std::string error = std::string("ERROR|") + e.error();
        return env->NewStringUTF(error.c_str());
    } catch (const std::exception& e) {
        const std::string error = std::string("ERROR|") + e.what();
        return env->NewStringUTF(error.c_str());
    } catch (...) {
        return env->NewStringUTF("ERROR|Unknown native error while indexing RomFS");
    }
}
