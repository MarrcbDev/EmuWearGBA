cmake_minimum_required(VERSION 3.22)
project(mgbajni C)
add_library(mgbajni SHARED mgba_jni.c)
target_include_directories(mgbajni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(mgbajni log jnigraphics dl)

# El APK se compila como debug para facilitar la instalación, pero el bucle
# del emulador no debe quedar con código C sin optimizar (-O0).
target_compile_options(mgbajni PRIVATE
    -O3
    -DNDEBUG
    -fomit-frame-pointer
    -ffunction-sections
    -fdata-sections
)
target_link_options(mgbajni PRIVATE -Wl,--gc-sections)
