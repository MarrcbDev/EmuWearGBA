cmake_minimum_required(VERSION 3.22)
project(mgbajni C)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
add_library(mgbajni SHARED mgba_jni.c)
# La variante debug de Gradle compila en -O0: forzamos optimización
target_compile_options(mgbajni PRIVATE -O3 -DNDEBUG)
target_include_directories(mgbajni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(mgbajni log jnigraphics android dl)
