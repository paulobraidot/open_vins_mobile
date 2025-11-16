# OpenVINS Mobile - Android Application

This folder contains the android application that allows for running of OpenVINS on an Android phone.
We leverage the Android NDK to allow for building of our C++ project directly in the toolchain.
If you want to use this application, open it up in Android Studio Otter | 2025.2.1.
You might need to use SDK manager to install CMake 3.18.1.
We use the Camera2 api to get the camera information from android and then pipe that into c++ via a JNI interfance.
Once in c++ we have a second background thread which is consuming the images and pushing to the VioManager directly.


## Getting Started

1. Clone this repository: `git clone --recurse-submodules https://github.com/goldbattle/open_vins_mobile.git`
2. Open the cloned folder in Android Studio and "run" / build it and install it on your device
    - This will build both the c++ library, but also the Kotlin java component of it
    - As a post step, all the things in [data_device/](data_device/) are copied to the private app folder
    - This is where you will update the calibration / yaml config for OpenVINS for the device
3. You will need to first perform calibration of your device
    - Follow the guide in [data_kalibr/](data_kalibr/).
    - You should be able to use the app to record a dataset to disk via the "save" icon
    - Perform the calibration, and update your device calibration [data_device/](data_device/)
4. You should now be able to click the "play" button and be able to run OpenVINS


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





