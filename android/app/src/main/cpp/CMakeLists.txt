cmake_minimum_required(VERSION 3.22.1)
project(cloudide-pty C)

add_library(cloudide-pty SHARED pty.c)
target_link_libraries(cloudide-pty log)
