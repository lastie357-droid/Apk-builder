cmake_minimum_required(VERSION 3.22.1)
project("guard")

add_library(guard SHARED guard.c)

find_library(log-lib log)

target_compile_options(guard PRIVATE
    -O2
    -fvisibility=hidden
    -fstack-protector-all
    -ffunction-sections
    -fdata-sections
)

target_link_libraries(guard ${log-lib})
