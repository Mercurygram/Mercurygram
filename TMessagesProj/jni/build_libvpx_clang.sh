#!/bin/bash
set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }
function build_one {
	NDK_VERSION=$(basename "${NDK}")
	# Sentinel ties the cached .a to the NDK that built it; bumping NDK
	# invalidates stale archives on existing local checkouts (CI is clean).
	[ -f "${PREFIX}/lib/libvpx.a" ] && [ -f "${PREFIX}/.ndk-${NDK_VERSION}" ] && return

	echo "Building ${ARCH}..."

	export CC_PREFIX="${LLVM_BIN}/${CLANG_PREFIX}-linux-${BIN_MIDDLE}${ANDROID_API}-"

	export CC=${CC_PREFIX}clang
	export CXX=${CC_PREFIX}clang++
	export AS=${CC_PREFIX}clang++
	# NDK 23+ uses llvm- prefixed tools; use clang as linker driver
	export LD=${CC}
	export AR=${LLVM_BIN}/llvm-ar
	export STRIP=${LLVM_BIN}/llvm-strip
	export RANLIB=${LLVM_BIN}/llvm-ranlib
	export NM=${LLVM_BIN}/llvm-nm

	export CFLAGS="-DANDROID -fpic -fpie ${OPTIMIZE_CFLAGS}"
	export CPPFLAGS="${CFLAGS}"
	export CXXFLAGS="${CFLAGS} -std=c++11"
	export ASFLAGS="-D__ANDROID__"
	export LDFLAGS=""

	if [ "x86" = ${ARCH} ]; then
		sed -i '20s/^/#define rand() ((int)lrand48())\n/' vpx_dsp/add_noise.c
	fi

	echo "Cleaning..."
	make distclean || true

	echo "Configuring..."



	# Prepend NDK yasm to PATH for reproducible builds (libvpx 1.14+
	# configure only accepts bare tool names, not full paths).
	export PATH="${NDK}/prebuilt/${BUILD_PLATFORM}/bin:${PATH}"

	_quiet_redir ./configure \
	--prefix=${PREFIX} \
	--target=${TARGET} \
	${CPU_DETECT} \
	--as=yasm \
	--enable-static \
	--enable-pic \
	--disable-docs \
	--enable-libyuv \
	--enable-small \
	--enable-optimizations \
	--enable-better-hw-compatibility \
	--disable-examples \
	--disable-tools \
	--disable-debug \
	--disable-neon-asm \
	--disable-neon-dotprod \
	--disable-unit-tests \
	--disable-install-docs \
	--enable-realtime-only \
	--enable-vp8 \
	--enable-vp9 \
	--disable-webm-io

	# Normalize absolute NDK path in vpx_config for reproducible builds.
	find . -name "vpx_config.*" -exec sed -i "s|${NDK}|/opt/android-sdk/ndk/${NDK_VERSION}|g" {} +

	_quiet_redir make -j$COMPILATION_PROC_COUNT install

	touch "${PREFIX}/.ndk-${NDK_VERSION}"

	if [ "x86" = ${ARCH} ]; then
		sed -i '20d' vpx_dsp/add_noise.c
	fi
}

function setCurrentPlatform {

	CURRENT_PLATFORM="$(uname -s)"
	case "${CURRENT_PLATFORM}" in
		Darwin*)
			BUILD_PLATFORM=darwin-x86_64
			COMPILATION_PROC_COUNT=`sysctl -n hw.physicalcpu`
			;;
		Linux*)
			BUILD_PLATFORM=linux-x86_64
			COMPILATION_PROC_COUNT=$(nproc)
			;;
		*)
			echo -e "\033[33mWarning! Unknown platform ${CURRENT_PLATFORM}! falling back to linux-x86_64\033[0m"
			BUILD_PLATFORM=linux-x86_64
			COMPILATION_PROC_COUNT=1
			;;
	esac

	echo "Build platform: ${BUILD_PLATFORM}"
	echo "Parallel jobs: ${COMPILATION_PROC_COUNT}"

}

function checkPreRequisites {

	if ! [ -d "libvpx" ] || ! [ "$(ls -A libvpx)" ]; then
		echo -e "\033[31mFailed! Submodule 'libvpx' not found!\033[0m"
		echo -e "\033[31mTry to run: 'git submodule init && git submodule update'\033[0m"
		exit
	fi

	if [ -z "$NDK" -a "$NDK" == "" ]; then
		echo -e "\033[31mFailed! NDK is empty. Run 'export NDK=[PATH_TO_NDK]'\033[0m"
		exit
	fi
}

setCurrentPlatform
checkPreRequisites

cd libvpx

## common
LLVM_PREFIX="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
LLVM_BIN="${LLVM_PREFIX}/bin"
ANDROID_API=21

function build {
	for arg in "$@"; do
		case "${arg}" in
			x86_64)
				ARCH=x86_64
				CLANG_PREFIX=x86_64
				BIN_MIDDLE=android
				CPU=x86_64
				OPTIMIZE_CFLAGS="-O3 -march=x86-64 -mtune=generic -msse4.2 -mpopcnt -m64 -fPIC"
				TARGET="x86_64-android-gcc"
				PREFIX=./build/$CPU
				CPU_DETECT="--enable-runtime-cpu-detect"
				build_one
			;;
			x86)
				ARCH=x86
				CLANG_PREFIX=i686
				BIN_MIDDLE=android
				CPU=i686
				OPTIMIZE_CFLAGS="-O3 -march=i686 -mtune=generic -msse3 -mfpmath=sse -m32 -fPIC"
				TARGET="x86-android-gcc"
				PREFIX=./build/$ARCH
				CPU_DETECT="--enable-runtime-cpu-detect"
				build_one
			;;
			arm64)
				ARCH=arm64
				CLANG_PREFIX=aarch64
				BIN_MIDDLE=android
				CPU=arm64-v8a
				OPTIMIZE_CFLAGS="-O3 -march=armv8-a"
				TARGET="arm64-android-gcc"
				PREFIX=./build/$CPU
				CPU_DETECT="--disable-runtime-cpu-detect"
				build_one
			;;
			arm)
				ARCH=arm
				CLANG_PREFIX=armv7a
				BIN_MIDDLE=androideabi
				CPU=armeabi-v7a
				OPTIMIZE_CFLAGS="-Os -march=armv7-a -mfloat-abi=softfp -mfpu=neon -mtune=cortex-a8 -mthumb -D__thumb__"
				TARGET="armv7-android-gcc --enable-neon --disable-neon-asm"
				PREFIX=./build/$CPU
				CPU_DETECT="--disable-runtime-cpu-detect"
				build_one
			;;
			*)
			;;
		esac
	done
}

if (( $# == 0 )); then
	build x86_64 x86 arm arm64
else
	build $@
fi
