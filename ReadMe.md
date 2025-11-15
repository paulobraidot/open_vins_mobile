# OpenVINS Android Application

This folder contains the android application that allows for running of OpenVINS on an Android phone.
We leverage the Android NDK to allow for building of our C++ project directly in the toolchain.
If you want to use this application, open it up in Android Studio Otter | 2025.2.1.
You might need to use SDK manager to install CMake 3.18.1.



## OpenVINS Submodule

You will either need to clone recursively to get the submodule or pull it after.
For example do the following to get the [OpenVINS repo](https://github.com/rpng/open_vins).
The main submodule is located in `app/src/main/cpp/OpenVINS`.

```
# clone with the submodule
git clone --recurse-submodules <repo_url>
# if you did not pull during clone
git submodule update --init --recursive
```



## References / Tutorials

### Core Android Development
- Android NDK toolset -- https://developer.android.com/ndk
- Android Camera2 API -- https://developer.android.com/reference/android/hardware/camera2/package-summary
- Camera2 API Guide -- https://developer.android.com/training/camera2
- CMake for Android -- https://developer.android.com/ndk/guides/cmake
- Kotlin Programming Language -- https://kotlinlang.org/docs/home.html

### Third-Party Libraries
- Guide to using OpenCV on android -- https://github.com/VlSomers/native-opencv-android-template
- OpenCV Documentation -- https://docs.opencv.org/
- Boost for Android -- https://github.com/dec1/Boost-for-Android
- Boost C++ Libraries -- https://www.boost.org/
- Eigen3 Linear Algebra Library -- https://eigen.tuxfamily.org/

### Examples and Tutorials
- JavaCameraView Example -- https://answers.opencv.org/question/19796/android-use-autofocus-with-camerabridgeviewbase/#19813





