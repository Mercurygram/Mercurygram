#!/bin/bash

set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }

function build_one {
	NDK_VERSION=$(basename "${NDK}")
	# Sentinel ties the cached .a to the NDK that built it; bumping NDK
	# invalidates stale archives on existing local checkouts (CI is clean).
	[ -f "${CPU}/libssl.a" ] && [ -f "${CPU}/.ndk-${NDK_VERSION}" ] && return

	# -p: an interrupted run leaves the directory without libssl.a, and a bare
	# mkdir would then fail on every later build until it is removed by hand.
	mkdir -p ${CPU}
	cd ${CPU}

	echo "Configuring ${CPU}..."
	_quiet_redir cmake \
	-DANDROID_NATIVE_API_LEVEL=${API} \
	-DANDROID_ABI=${CPU} \
	-DCMAKE_BUILD_TYPE=Release \
	-DANDROID_NDK=${NDK} \
	-DCMAKE_TOOLCHAIN_FILE=${NDK}/build/cmake/android.toolchain.cmake \
	"-DCMAKE_C_FLAGS=-Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__" \
	"-DCMAKE_CXX_FLAGS=-Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__" \
	-GNinja -DCMAKE_MAKE_PROGRAM=${NINJA_PATH} \
	../..

	echo "Building ${CPU}..."
	_quiet_redir cmake --build .

	cd ..
	touch "${CPU}/.ndk-${NDK_VERSION}"
}

function checkPreRequisites {

	# Bare `exit` here would return 0, so a missing NDK/ninja looked like a
	# successful build to the gradle task and CMake failed much later on the
	# absent .a instead.
	if ! [ -d "boringssl" ] || ! [ "$(ls -A boringssl)" ]; then
		echo -e "\033[31mFailed! Submodule 'boringssl' not found!\033[0m"
		echo -e "\033[31mTry to run: 'git submodule init && git submodule update'\033[0m"
		exit 1
	fi

	if [ -z "$NDK" ]; then
		echo -e "\033[31mFailed! NDK is empty. Run 'export NDK=[PATH_TO_NDK]'\033[0m"
		exit 1
	fi

	if [ -z "$NINJA_PATH" ]; then
		echo -e "\033[31mFailed! NINJA_PATH is empty. Run 'export NINJA_PATH=[PATH_TO_NINJA]'\033[0m"
		exit 1
	fi
}

ANDROID_NDK=$NDK
checkPreRequisites

cd boringssl

mkdir -p build
cd build

function build {
	for arg in "$@"; do
		# Android ABI names are accepted alongside the short names so every
		# jni/build_*.sh takes the same argument spelling.
		case "${arg}" in
			x86_64)
				API=21
				CPU=x86_64
				build_one
			;;
			arm64|arm64-v8a)
				API=21
				CPU=arm64-v8a
				build_one
			;;
			arm|armeabi-v7a)
				API=21
				CPU=armeabi-v7a
				build_one
			;;
			x86)
				API=21
				CPU=x86
				build_one
			;;
			*)
				echo "Error: unknown ABI '${arg}'." >&2
				exit 1
			;;
		esac
	done
}

if (( $# == 0 )); then
	build x86_64 arm64 arm x86
else
	build $@
fi
