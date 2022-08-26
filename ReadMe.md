# OpenVINS Android Application

This folder contains the android application that allows for running of OpenVINS on an Android phone.
We leverage the Android NDK to allow for building of our C++ project directly in the toolchain.
If you want to use this application, open it up in the latest Android Studio (was 4.1.1 at time of writing).
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

- Android NDK toolset -- https://developer.android.com/ndk
- Guide to using OpenCV on android -- https://github.com/VlSomers/native-opencv-android-template
- Boost for Android -- https://github.com/dec1/Boost-for-Android
- JavaCameraView Example -- https://answers.opencv.org/question/19796/android-use-autofocus-with-camerabridgeviewbase/#19813





