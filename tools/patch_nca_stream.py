from pathlib import Path

nca_header = Path('app/src/main/jni/NcaProcess.h')
nca_source = Path('app/src/main/jni/NcaProcess.cpp')
tik_header = Path('app/src/main/jni/EsTikProcess.h')
gamecard_header = Path('app/src/main/jni/GameCardProcess.h')

h = nca_header.read_text()
s = nca_source.read_text()
t = tik_header.read_text()
g = gamecard_header.read_text()

# Android bridge metadata accessors.
fs_anchor = 'const std::shared_ptr<tc::io::IFileSystem>& getFileSystem() const;'
if 'getHeader() const' not in h:
    if fs_anchor not in h:
        raise SystemExit('NcaProcess.h filesystem getter anchor not found')
    h = h.replace(
        fs_anchor,
        fs_anchor + '\n\tconst pie::hac::ContentArchiveHeader& getHeader() const { return mHdr; }',
        1,
    )

if 'getPartitionFailureSummary() const' not in h:
    if fs_anchor not in h:
        raise SystemExit('NcaProcess.h filesystem getter anchor not found for diagnostics')
    h = h.replace(
        fs_anchor,
        fs_anchor + '\n\tstd::string getPartitionFailureSummary() const;',
        1,
    )

if 'getTicket() const' not in t:
    anchor = 'void setVerifyMode(bool verify);'
    if anchor not in t:
        raise SystemExit('EsTikProcess.h verify setter anchor not found')
    t = t.replace(
        anchor,
        anchor + '\n\n\tconst pie::hac::es::SignedData<pie::hac::es::TicketBody_V2>& getTicket() const { return mTik; }',
        1,
    )

# Expose the parsed filesystem so XCI containers can be traversed by the Android bridge.
if 'getFileSystem() const' not in g:
    anchor = 'void setExtractJobs(const std::vector<nstool::ExtractJob> extract_jobs);'
    if anchor not in g:
        raise SystemExit('GameCardProcess.h extract-jobs anchor not found')
    g = g.replace(
        anchor,
        anchor + '\n\n\tconst std::shared_ptr<tc::io::IFileSystem>& getFileSystem() const { return mFileSystem; }',
        1,
    )

# Allow an update NCA to use the base NCA directly from the selected Android
# file descriptor instead of requiring a copied temporary base NCA on disk.
old_h1 = 'void setBaseNcaPath(const tc::Optional<tc::io::Path>& nca_path);'
if 'setBaseNcaStream' not in h:
    if old_h1 not in h:
        raise SystemExit('NcaProcess.h setter anchor not found')
    h = h.replace(
        old_h1,
        old_h1 + '\n\tvoid setBaseNcaStream(const std::shared_ptr<tc::io::IStream>& nca_stream);',
        1,
    )

old_h2 = 'tc::Optional<tc::io::Path> mBaseNcaPath;'
if 'mBaseNcaStream' not in h:
    if old_h2 not in h:
        raise SystemExit('NcaProcess.h member anchor not found')
    h = h.replace(
        old_h2,
        old_h2 + '\n\tstd::shared_ptr<tc::io::IStream> mBaseNcaStream;',
        1,
    )

old_s1 = '''void nstool::NcaProcess::setBaseNcaPath(const tc::Optional<tc::io::Path>& nca_path)
{
\tmBaseNcaPath = nca_path;
}
'''
if 'NcaProcess::setBaseNcaStream' not in s:
    if old_s1 not in s:
        raise SystemExit('NcaProcess.cpp setter anchor not found')
    s = s.replace(
        old_s1,
        old_s1 + '''
void nstool::NcaProcess::setBaseNcaStream(const std::shared_ptr<tc::io::IStream>& nca_stream)
{
\tmBaseNcaStream = nca_stream;
}
''',
        1,
    )

old_s2 = '''\t// open base nca stream
\tif (mBaseNcaPath.isNull())
\t{
\t\tthrow tc::Exception(mModuleName, "Base NCA not supplied. Necessary for update NCA.");
\t}
\tstd::shared_ptr<tc::io::IStream> base_stream = std::make_shared<tc::io::FileStream>(tc::io::FileStream(mBaseNcaPath.get(), tc::io::FileMode::Open, tc::io::FileAccess::Read));
'''
new_s2 = '''\t// open base nca stream
\tstd::shared_ptr<tc::io::IStream> base_stream;
\tif (mBaseNcaStream != nullptr)
\t{
\t\tbase_stream = mBaseNcaStream;
\t\tbase_stream->seek(0, tc::io::SeekOrigin::Begin);
\t}
\telse if (mBaseNcaPath.isSet())
\t{
\t\tbase_stream = std::make_shared<tc::io::FileStream>(tc::io::FileStream(mBaseNcaPath.get(), tc::io::FileMode::Open, tc::io::FileAccess::Read));
\t}
\telse
\t{
\t\tthrow tc::Exception(mModuleName, "Base NCA not supplied. Necessary for update NCA.");
\t}
'''
if new_s2 not in s:
    if old_s2 not in s:
        raise SystemExit('NcaProcess.cpp readBaseNCA anchor not found')
    s = s.replace(old_s2, new_s2, 1)

# Base and update Program IDs can either match directly or use the standard
# +0x800 update offset. Keep rejecting unrelated base/update pairs.
old_program_check = '''\t\t\t\t\tif (nca_base.mHdr.getProgramId() != mHdr.getProgramId())
\t\t\t\t\t{
\t\t\t\t\t\tthrow tc::Exception(mModuleName, "Invalid base nca. ProgramID diferent.");
\t\t\t\t\t}
'''
new_program_check = '''\t\t\t\t\tconst uint64_t base_program_id = nca_base.mHdr.getProgramId();
\t\t\t\t\tconst uint64_t update_program_id = mHdr.getProgramId();
\t\t\t\t\tif (base_program_id != update_program_id && base_program_id + 0x800ULL != update_program_id)
\t\t\t\t\t{
\t\t\t\t\t\tthrow tc::Exception(mModuleName, "Invalid base nca. ProgramID does not match this update.");
\t\t\t\t\t}
'''
if new_program_check not in s:
    if old_program_check not in s:
        raise SystemExit('NcaProcess.cpp ProgramID compatibility anchor not found')
    s = s.replace(old_program_check, new_program_check, 1)

# Upstream constructs the combined NCA filesystem as a local variable, so the
# getter added for the Android bridge would otherwise keep returning null.
old_fs_assignment = '''\tstd::shared_ptr<tc::io::IFileSystem> nca_fs = std::make_shared<tc::io::VirtualFileSystem>(tc::io::VirtualFileSystem(fs_snapshot));

\tmFsProcess.setInputFileSystem(nca_fs);
'''
new_fs_assignment = '''\tmFileSystem = std::make_shared<tc::io::VirtualFileSystem>(tc::io::VirtualFileSystem(fs_snapshot));

\tmFsProcess.setInputFileSystem(mFileSystem);
'''
if new_fs_assignment not in s:
    if old_fs_assignment not in s:
        raise SystemExit('NcaProcess.cpp combined filesystem assignment anchor not found')
    s = s.replace(old_fs_assignment, new_fs_assignment, 1)

# Surface partition-level failures. Upstream records these per partition and
# continues processing, which otherwise turns a useful native error into an
# empty filesystem in the Android UI.
if 'NcaProcess::getPartitionFailureSummary() const' not in s:
    anchor = '''const std::shared_ptr<tc::io::IFileSystem>& nstool::NcaProcess::getFileSystem() const
{
\treturn mFileSystem;
}
'''
    if anchor not in s:
        raise SystemExit('NcaProcess.cpp filesystem getter anchor not found')
    diagnostics = '''
std::string nstool::NcaProcess::getPartitionFailureSummary() const
{
\tstd::string result;
\tfor (size_t i = 0; i < mPartitions.size(); i++)
\t{
\t\tif (mPartitions[i].fail_reason.empty()) continue;
\t\tif (!result.empty()) result += "; ";
\t\tresult += "partition " + std::to_string(i) + ": " + mPartitions[i].fail_reason;
\t}
\treturn result;
}
'''
    s = s.replace(anchor, anchor + diagnostics, 1)

nca_header.write_text(h)
nca_source.write_text(s)
tik_header.write_text(t)
gamecard_header.write_text(g)
print('Patched NSTool Android bridge accessors, diagnostics, XCI filesystem access, and update compatibility')
