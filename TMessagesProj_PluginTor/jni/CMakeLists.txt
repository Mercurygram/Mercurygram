cmake_minimum_required(VERSION 3.10.2)

# Tor stack lives in its own APK (plugin) — bind via IMgTorService from main.
# This CMakeLists builds the single shared lib `libmgtor.so` that ships in the
# plugin APK. The OpenSSL 1.1 / libevent / tor static archives are produced by
# build_openssl.sh / build_libevent.sh / build_tor.sh under ${CMAKE_HOME_DIRECTORY}/{openssl,libevent,tor}/build/${ANDROID_ABI}/.
#
# Build order:
#   patchNativeSources → buildNativeLibs<Abi> (runs the three shell scripts)
#       → externalNativeBuild<Variant>[<Abi>] (this CMakeLists) → APK assembly.

set(CMAKE_C_FLAGS "-w -std=c11 -DANDROID -D_LARGEFILE_SOURCE=1")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
# Reproducibility: filename-only __FILE__ — matches the strategy in
# TMessagesProj/jni/AGENTS.md and the openssl/libevent/tor build scripts.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,-Bsymbolic -Wl,--gc-sections")

# Imported static libs produced by build_openssl.sh / build_libevent.sh / build_tor.sh.
# IMPORTED_LOCATION uses ${CMAKE_HOME_DIRECTORY} which resolves to this file's directory
# (TMessagesProj_PluginTor/jni/), so the paths land at jni/{tor,openssl,libevent}/build/<abi>/lib/.
add_library(mg_tor STATIC IMPORTED)
set_target_properties(mg_tor PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/tor/build/${ANDROID_ABI}/lib/libtor.a)

add_library(mg_tor_ssl STATIC IMPORTED)
set_target_properties(mg_tor_ssl PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/openssl/build/${ANDROID_ABI}/lib/libssl.a)

add_library(mg_tor_crypto STATIC IMPORTED)
set_target_properties(mg_tor_crypto PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/openssl/build/${ANDROID_ABI}/lib/libcrypto.a)

add_library(mg_tor_event STATIC IMPORTED)
set_target_properties(mg_tor_event PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/libevent/build/${ANDROID_ABI}/lib/libevent.a)

add_library(mg_tor_event_pthreads STATIC IMPORTED)
set_target_properties(mg_tor_event_pthreads PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/libevent/build/${ANDROID_ABI}/lib/libevent_pthreads.a)

# libmgtor.so — JNI surface around tor_run_main(). Only Java_..._MgTorNative_*
# entry points are exported; the OpenSSL + libevent + libtor internals stay
# hidden via --exclude-libs, matching the symbol-isolation rationale from main
# (boringssl in libtmessages.so vs openssl in libmgtor.so). Since the plugin
# APK is a separate process, there is no cohabiting boringssl here, but the
# exclusion still keeps the dynsym table small and reproducible.
add_library(mgtor SHARED tor_jni.c)
target_include_directories(mgtor PRIVATE
        ${CMAKE_HOME_DIRECTORY}/tor/build/${ANDROID_ABI}/include
        ${CMAKE_HOME_DIRECTORY}/openssl/build/${ANDROID_ABI}/include
        ${CMAKE_HOME_DIRECTORY}/libevent/build/${ANDROID_ABI}/include)
target_link_options(mgtor PRIVATE
        "-Wl,--build-id=none"
        "-Wl,--hash-style=gnu"
        "-Wl,-Bsymbolic"
        "-Wl,--gc-sections"
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,--exclude-libs=libtor.a:libssl.a:libcrypto.a:libevent.a:libevent_pthreads.a")
# libtor's TOR_INTERNAL_LIBS bundle has back-references between subarchives
# and forward-references into openssl + libevent. Wrap in --start-group so
# the linker re-scans until all references resolve.
target_link_libraries(mgtor
        -Wl,--start-group
        mg_tor mg_tor_ssl mg_tor_crypto mg_tor_event mg_tor_event_pthreads
        -Wl,--end-group
        z log android dl m)
if(QUIET_BUILD)
    target_compile_options(mgtor PRIVATE -w)
endif()
