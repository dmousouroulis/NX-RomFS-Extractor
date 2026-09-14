cmake_minimum_required(VERSION 3.22.1)
project(nstool)

set(JNI_DIR ${CMAKE_SOURCE_DIR}/../jni)
set(DEPS_DIR ${JNI_DIR}/deps)

add_library(lz4 STATIC ${DEPS_DIR}/liblz4/src/lz4.c)
target_include_directories(lz4 PUBLIC ${DEPS_DIR}/liblz4/include)

file(GLOB FMT_SOURCES ${DEPS_DIR}/libfmt/src/*.cc)
add_library(fmt STATIC ${FMT_SOURCES})
target_include_directories(fmt PUBLIC ${DEPS_DIR}/libfmt/include)

file(GLOB_RECURSE MBEDTLS_SOURCES ${DEPS_DIR}/libmbedtls/src/*.c)
add_library(mbedtls STATIC ${MBEDTLS_SOURCES})
target_include_directories(mbedtls PUBLIC ${DEPS_DIR}/libmbedtls/include)

file(GLOB_RECURSE TOOLCHAIN_SOURCES ${DEPS_DIR}/libtoolchain/src/*.cpp)
add_library(toolchain STATIC ${TOOLCHAIN_SOURCES})
target_include_directories(toolchain PUBLIC ${DEPS_DIR}/libtoolchain/include)
target_link_libraries(toolchain PUBLIC mbedtls fmt)

file(GLOB_RECURSE PIETENDO_SOURCES ${DEPS_DIR}/libpietendo/src/*.cpp)
add_library(pietendo STATIC ${PIETENDO_SOURCES})
target_include_directories(pietendo PUBLIC ${DEPS_DIR}/libpietendo/include)
target_link_libraries(pietendo PUBLIC toolchain mbedtls fmt lz4)

add_library(nstool SHARED
    ${JNI_DIR}/AssetProcess.cpp
    ${JNI_DIR}/CnmtProcess.cpp
    ${JNI_DIR}/ElfSymbolParser.cpp
    ${JNI_DIR}/EsCertProcess.cpp
    ${JNI_DIR}/EsTikProcess.cpp
    ${JNI_DIR}/FsProcess.cpp
    ${JNI_DIR}/GameCardProcess.cpp
    ${JNI_DIR}/IniProcess.cpp
    ${JNI_DIR}/KeyBag.cpp
    ${JNI_DIR}/KipProcess.cpp
    ${JNI_DIR}/MetaProcess.cpp
    ${JNI_DIR}/NacpProcess.cpp
    ${JNI_DIR}/NcaProcess.cpp
    ${JNI_DIR}/NroProcess.cpp
    ${JNI_DIR}/NsoProcess.cpp
    ${JNI_DIR}/PfsProcess.cpp
    ${JNI_DIR}/PkiValidator.cpp
    ${JNI_DIR}/RoMetadataProcess.cpp
    ${JNI_DIR}/RomfsProcess.cpp
    ${JNI_DIR}/SdkApiString.cpp
    ${JNI_DIR}/Settings.cpp
    ${JNI_DIR}/util.cpp
    romfs_extractor_jni.cpp
    romfs_browser_jni.cpp
)

target_include_directories(nstool PRIVATE ${JNI_DIR})
target_link_libraries(nstool log pietendo toolchain mbedtls fmt lz4)
