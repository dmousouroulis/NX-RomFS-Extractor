#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <cctype>
#include <cstdio>

#include <tc/io/IStream.h>
#include <tc/io/Path.h>
#include <tc/io/IOException.h>
#include <tc/NotSupportedException.h>
#include <pietendo/hac/ContentArchiveHeader.h>

#include "PfsProcess.h"
#include "NcaProcess.h"
#include "EsTikProcess.h"
#include "KeyBag.h"

#define RX_TAG "nx_romfs_extractor"
#define RXI(...) __android_log_print(ANDROID_LOG_INFO, RX_TAG, __VA_ARGS__)
#define RXE(...) __android_log_print(ANDROID_LOG_ERROR, RX_TAG, __VA_ARGS__)

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

std::shared_ptr<tc::io::IFileSystem> openNsp(const std::shared_ptr<tc::io::IStream>& stream) {
    nstool::PfsProcess pfs;
    pfs.setInputFile(stream);
    pfs.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
    pfs.process();
    return pfs.getFileSystem();
}

void importTickets(const std::shared_ptr<tc::io::IFileSystem>& fs, nstool::KeyBag& keys) {
    if (!fs) return;
    tc::io::sDirectoryListing listing;
    fs->getDirectoryListing(tc::io::Path("/"), listing);
    for (const auto& name : listing.file_list) {
        if (name.size() < 4 || name.substr(name.size() - 4) != ".tik") continue;
        try {
            std::shared_ptr<tc::io::IStream> stream;
            fs->openFile(tc::io::Path("/" + name), tc::io::FileMode::Open, tc::io::FileAccess::Read, stream);
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
        } catch (const std::exception& e) {
            RXI("Skipping ticket %s: %s", name.c_str(), e.what());
        }
    }
}

std::vector<std::string> listNcas(const std::shared_ptr<tc::io::IFileSystem>& fs) {
    std::vector<std::string> result;
    tc::io::sDirectoryListing listing;
    fs->getDirectoryListing(tc::io::Path("/"), listing);
    for (const auto& name : listing.file_list) {
        if (name.size() >= 4 && name.substr(name.size() - 4) == ".nca") result.push_back("/" + name);
    }
    return result;
}

struct ProgramNcaInfo {
    std::shared_ptr<tc::io::IStream> stream;
    uint64_t programId = 0;
};

ProgramNcaInfo findBaseProgramNca(const std::shared_ptr<tc::io::IFileSystem>& fs,
                                  const nstool::KeyBag& keys) {
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
            if (header.getContentType() == pie::hac::nca::ContentType_Program) {
                stream->seek(0, tc::io::SeekOrigin::Begin);
                return {stream, header.getProgramId()};
            }
        } catch (...) {}
    }
    return {};
}

std::string normalizeRomFsPath(std::string path) {
    std::replace(path.begin(), path.end(), '\\', '/');
    while (!path.empty() && std::isspace(static_cast<unsigned char>(path.front()))) path.erase(path.begin());
    while (!path.empty() && std::isspace(static_cast<unsigned char>(path.back()))) path.pop_back();
    while (!path.empty() && path.front() == '/') path.erase(path.begin());
    const std::string prefix = "romfs/";
    if (path.rfind(prefix, 0) == 0) path.erase(0, prefix.size());
    if (path.empty()) throw tc::Exception("RomFS path is empty");
    if (path.find("..") != std::string::npos) throw tc::Exception("Parent path segments (..) are not allowed");
    return path;
}

bool openRequestedFile(const std::shared_ptr<tc::io::IFileSystem>& fs,
                       const std::string& requestedPath,
                       std::shared_ptr<tc::io::IStream>& target,
                       std::string& foundPath) {
    if (!fs) return false;
    const std::string normalized = normalizeRomFsPath(requestedPath);
    std::vector<std::string> candidates;
    candidates.push_back("/" + normalized);
    for (int i = 0; i < 8; i++) candidates.push_back("/" + std::to_string(i) + "/" + normalized);
    for (const auto& path : candidates) {
        try {
            fs->openFile(tc::io::Path(path), tc::io::FileMode::Open, tc::io::FileAccess::Read, target);
            foundPath = path;
            return true;
        } catch (...) {}
    }
    return false;
}

int64_t copyToFd(const std::shared_ptr<tc::io::IStream>& input, int outputFd) {
    if (!input || outputFd < 0) throw tc::io::IOException("Invalid input/output stream");
    if (ftruncate(outputFd, 0) != 0) throw tc::io::IOException("Could not truncate output");
    if (lseek64(outputFd, 0, SEEK_SET) == (off64_t)-1) throw tc::io::IOException("Could not seek output");
    input->seek(0, tc::io::SeekOrigin::Begin);
    std::vector<byte_t> buffer(1024 * 1024);
    int64_t total = 0;
    while (true) {
        size_t got = input->read(buffer.data(), buffer.size());
        if (got == 0) break;
        size_t offset = 0;
        while (offset < got) {
            ssize_t wrote = ::write(outputFd, buffer.data() + offset, got - offset);
            if (wrote <= 0) throw tc::io::IOException("Output write failed");
            offset += static_cast<size_t>(wrote);
        }
        total += static_cast<int64_t>(got);
    }
    fsync(outputFd);
    return total;
}

int64_t extractFromBase(const ProgramNcaInfo& baseProgram,
                        const nstool::KeyBag& keys,
                        const std::string& requestedPath,
                        int outputFd,
                        std::string& foundPath) {
    baseProgram.stream->seek(0, tc::io::SeekOrigin::Begin);
    nstool::NcaProcess nca;
    nca.setInputFile(baseProgram.stream);
    nca.setKeyCfg(keys);
    nca.setVerifyMode(false);
    nca.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
    nca.process();
    std::shared_ptr<tc::io::IStream> target;
    if (!openRequestedFile(nca.getFileSystem(), requestedPath, target, foundPath)) {
        throw tc::Exception("Requested file was not found in the base RomFS");
    }
    return copyToFd(target, outputFd);
}

int64_t extractFromUpdated(const ProgramNcaInfo& baseProgram,
                           const std::shared_ptr<tc::io::IFileSystem>& updateFs,
                           const nstool::KeyBag& keys,
                           const std::string& requestedPath,
                           int outputFd,
                           std::string& foundPath) {
    for (const auto& path : listNcas(updateFs)) {
        try {
            std::shared_ptr<tc::io::IStream> updateNcaStream;
            updateFs->openFile(tc::io::Path(path), tc::io::FileMode::Open, tc::io::FileAccess::Read, updateNcaStream);
            baseProgram.stream->seek(0, tc::io::SeekOrigin::Begin);
            nstool::NcaProcess nca;
            nca.setInputFile(updateNcaStream);
            nca.setBaseNcaStream(baseProgram.stream);
            nca.setKeyCfg(keys);
            nca.setVerifyMode(false);
            nca.setCliOutputMode(nstool::CliOutputMode(false, false, false, false));
            nca.process();
            const auto& header = nca.getHeader();
            if (header.getContentType() != pie::hac::nca::ContentType_Program ||
                header.getProgramId() != baseProgram.programId) continue;
            std::shared_ptr<tc::io::IStream> target;
            if (!openRequestedFile(nca.getFileSystem(), requestedPath, target, foundPath)) continue;
            return copyToFd(target, outputFd);
        } catch (...) {}
    }
    throw tc::Exception("A matching updated Program NCA was not found, or the requested RomFS file does not exist in it");
}

bool isNsp(const std::string& name) {
    auto p = name.find_last_of('.');
    if (p == std::string::npos) return false;
    std::string ext = name.substr(p + 1);
    for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return ext == "nsp";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_dmousouroulis_nxromfsextractor_RomFsExtractorActivity_extractRomFsFileNative(
        JNIEnv* env,
        jobject,
        jint baseFd,
        jstring baseFileName,
        jint updateFd,
        jstring updateFileName,
        jint outputFd,
        jstring keysFile,
        jstring requestedPathValue) {
    const std::string baseName = toString(env, baseFileName);
    const std::string updateName = toString(env, updateFileName);
    const std::string keyPath = toString(env, keysFile);
    const std::string requestedPath = toString(env, requestedPathValue);

    try {
        if (keyPath.empty()) throw tc::Exception("prod.keys path is empty");
        if (!isNsp(baseName)) throw tc::Exception("v0.1 currently supports NSP base packages only");
        if (updateFd >= 0 && !isNsp(updateName)) throw tc::Exception("v0.1 currently supports NSP update packages only");
        normalizeRomFsPath(requestedPath);

        nstool::KeyBag keys = nstool::KeyBagInitializer(
            false,
            tc::Optional<tc::io::Path>(keyPath),
            tc::Optional<tc::io::Path>(),
            std::vector<tc::io::Path>(),
            tc::Optional<tc::io::Path>());

        auto baseRoot = std::make_shared<AndroidFdStream>(baseFd);
        auto baseFs = openNsp(baseRoot);
        importTickets(baseFs, keys);
        auto baseProgram = findBaseProgramNca(baseFs, keys);
        if (!baseProgram.stream) throw tc::Exception("Could not find a Program NCA in the base NSP");

        std::string foundPath;
        int64_t bytes = 0;
        if (updateFd >= 0) {
            auto updateRoot = std::make_shared<AndroidFdStream>(updateFd);
            auto updateFs = openNsp(updateRoot);
            importTickets(updateFs, keys);
            bytes = extractFromUpdated(baseProgram, updateFs, keys, requestedPath, outputFd, foundPath);
        } else {
            bytes = extractFromBase(baseProgram, keys, requestedPath, outputFd, foundPath);
        }

        const std::string ok = "OK|" + foundPath + "|" + std::to_string(bytes) + "|" + toUpperHex(baseProgram.programId);
        return env->NewStringUTF(ok.c_str());
    } catch (const tc::Exception& e) {
        std::string msg = std::string("ERROR|") + e.error();
        RXE("%s", msg.c_str());
        return env->NewStringUTF(msg.c_str());
    } catch (const std::exception& e) {
        std::string msg = std::string("ERROR|") + e.what();
        RXE("%s", msg.c_str());
        return env->NewStringUTF(msg.c_str());
    } catch (...) {
        return env->NewStringUTF("ERROR|Unknown native extraction error");
    }
}
