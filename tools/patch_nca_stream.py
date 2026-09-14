from pathlib import Path

nca_header = Path('app/src/main/jni/NcaProcess.h')
nca_source = Path('app/src/main/jni/NcaProcess.cpp')
tik_header = Path('app/src/main/jni/EsTikProcess.h')

h = nca_header.read_text()
s = nca_source.read_text()
t = tik_header.read_text()

# The Android bridge needs read-only access to metadata that upstream NSTool
# keeps private. Add the same lightweight accessors used by the proven build.
if 'getHeader() const' not in h:
    anchor = 'const std::shared_ptr<tc::io::IFileSystem>& getFileSystem() const;'
    if anchor not in h:
        raise SystemExit('NcaProcess.h filesystem getter anchor not found')
    h = h.replace(
        anchor,
        anchor + '\n\tconst pie::hac::ContentArchiveHeader& getHeader() const { return mHdr; }',
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

nca_header.write_text(h)
nca_source.write_text(s)
tik_header.write_text(t)
print('Patched NSTool accessors and stream-backed base NCA support')
