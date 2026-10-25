cmake_minimum_required(VERSION 3.20)
project(blsnip_badlion LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# jni.h only. Nothing links against jvm.lib: the agent resolves
# JNI_GetCreatedJavaVMs out of the already-loaded jvm.dll at runtime.
if(NOT JAVA_HOME)
  set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
if(NOT JAVA_HOME OR NOT EXISTS "${JAVA_HOME}/include/jni.h")
  message(FATAL_ERROR
    "JAVA_HOME must point at a JDK 8 install. "
    "Pass -DJAVA_HOME=C:/path/to/jdk8 or set the environment variable.")
endif()

add_library(blagent SHARED Agent.cpp)
target_include_directories(blagent PRIVATE
  "${JAVA_HOME}/include"
  "${JAVA_HOME}/include/win32")
target_compile_options(blagent PRIVATE /W4 /permissive-)

add_executable(inject Injector.cpp)
target_compile_options(inject PRIVATE /W4 /permissive-)
# NtQueryInformationProcess / NtCreateThreadEx come from ntdll's import lib.
target_link_libraries(inject PRIVATE ntdll)

# exe and payload must sit next to each other: inject.exe defaults to
# <its own dir>\blagent.dll, and the dll looks for <its own dir>\blsnip-loader.jar.
set_target_properties(blagent inject PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
  ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  PREFIX "")
