# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.18.1)

# Declares and names the project.
project("android")

# Include OpenCV project
include_directories(${OpenCV_DIR}/jni/include)
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OpenCV_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so)

# Include Eigen3 Headers
include_directories(${EIGEN3_DIR})

# Include boost library
include_directories(${BOOST_DIR}/include)
add_library(boost_system SHARED IMPORTED)
set_target_properties(boost_system PROPERTIES IMPORTED_LOCATION ${BOOST_DIR}/libs/${ANDROID_ABI}/libboost_system.so)
add_library(boost_filesystem SHARED IMPORTED)
set_target_properties(boost_filesystem PROPERTIES IMPORTED_LOCATION ${BOOST_DIR}/libs/${ANDROID_ABI}/libboost_filesystem.so)
add_library(boost_thread SHARED IMPORTED)
set_target_properties(boost_thread PROPERTIES IMPORTED_LOCATION ${BOOST_DIR}/libs/${ANDROID_ABI}/libboost_thread.so)
add_library(boost_date_time SHARED IMPORTED)
set_target_properties(boost_date_time PROPERTIES IMPORTED_LOCATION ${BOOST_DIR}/libs/${ANDROID_ABI}/libboost_date_time.so)
add_library(boost_chrono SHARED IMPORTED)
set_target_properties(boost_chrono PROPERTIES IMPORTED_LOCATION ${BOOST_DIR}/libs/${ANDROID_ABI}/libboost_chrono.so)

# Include OpenVINS project and list the source files
include_directories(open_vins/ov_core/src/)
list(APPEND ov_core_sources
        open_vins/ov_core/src/init/InertialInitializer.cpp
        open_vins/ov_core/src/sim/BsplineSE3.cpp
        open_vins/ov_core/src/track/TrackBase.cpp
        open_vins/ov_core/src/track/TrackKLT.cpp
        open_vins/ov_core/src/track/TrackSIM.cpp
        open_vins/ov_core/src/types/Landmark.cpp
        open_vins/ov_core/src/feat/Feature.cpp
        open_vins/ov_core/src/feat/FeatureInitializer.cpp
        )


# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
add_library(
        # Sets the name of the library.
        native-lib
        # Sets the library as a shared library.
        SHARED
        # Provides a relative path to your source file(s).
        native-lib.cpp
        # OpenVINS source files
        ${ov_core_sources}
)

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.
find_library(
        # Sets the name of the path variable.
        log-lib
        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log
)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.
target_link_libraries(
        # Specifies the target library.
        native-lib
        # OpenCV library objects
        lib_opencv
        # Boost library objects
        boost_chrono
        boost_system
        boost_filesystem
        boost_thread
        boost_date_time
        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}
)
