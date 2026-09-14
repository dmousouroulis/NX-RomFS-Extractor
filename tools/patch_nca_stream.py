from pathlib import Path

header = Path('app/src/main/jni/NcaProcess.h')
source = Path('app/src/main/jni/NcaProcess.cpp')

h = header.read_text()
s = source.read_text()

old_h1 = 'void setBaseNcaPath(const tc::Optional<tc::io::Path>& nca_path);'
new_h1 = old_h1 + '\n\tvoid setBaseNcaStream(const std::shared_ptr<tc::io::IStream>& nca_stream);'
if old_h1 not in h:
    raise SystemExit('NcaProcess.h setter anchor not found')
h = h.replace(old_h1, new_h1, 1)

old_h2 = 'tc::Optional<tc::io::Path> mBaseNcaPath;'
new_h2 = old_h2 + '\n\tstd::shared_ptr<tc::io::IStream> mBaseNcaStream;'
if old_h2 not in h:
    raise SystemExit('NcaProcess.h member anchor not found')
h = h.replace(old_h2, new_h2, 1)

old_s1 = '''void nstool::NcaProcess::setBaseNcaPath(const tc::Optional<tc::io::Path>& nca_path)
{
\tmBaseNcaPath = nca_path;
}
'''
new_s1 = old_s1 + '''
void nstool::NcaProcess::setBaseNcaStream(const std::shared_ptr<tc::io::IStream>& nca_stream)
{
\tmBaseNcaStream = nca_stream;
}
'''
if old_s1 not in s:
    raise SystemExit('NcaProcess.cpp setter anchor not found')
s = s.replace(old_s1, new_s1, 1)

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
if old_s2 not in s:
    raise SystemExit('NcaProcess.cpp readBaseNCA anchor not found')
s = s.replace(old_s2, new_s2, 1)

header.write_text(h)
source.write_text(s)
print('Patched NcaProcess for stream-backed base NCA support')
