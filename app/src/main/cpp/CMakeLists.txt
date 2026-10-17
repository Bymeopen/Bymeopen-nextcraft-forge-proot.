cmake_minimum_required(VERSION 3.22.1)
project(nextcraft_forge_native C)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "NextCraft Forge currently supports only arm64-v8a")
endif()

# Pure Root Chroot Edition: Hanya membangun libforgespawn.so
# (talloc, android-shmem, proot, prootloader, dan executable_carrier dihapus total)
add_library(forgespawn SHARED forge_spawn.c)

target_compile_options(forgespawn PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
)

# Keamanan stack non-executable + kompatibilitas 16 KB page size (Android 15/16)
target_link_options(forgespawn PRIVATE
    "-Wl,-z,noexecstack"
    "-Wl,-z,max-page-size=16384"
)
