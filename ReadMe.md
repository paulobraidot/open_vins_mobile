# OpenVINS Mobile - Android Application

This folder contains the android application that allows for running of [OpenVINS](https://github.com/rpng/open_vins/) on an Android phone.
We leverage the Android NDK to allow for building of our C++ project directly in the toolchain.
If you want to use this application, open it up in Android Studio Otter | 2025.2.1.
You might need to use SDK manager to install CMake 3.18.1.
We use the Camera2 api to get the camera information [from android](app/src/main/java/com/openvins/android/) and then pipe that into c++ via a [JNI interfance](app/src/main/cpp/native-lib.cpp).
Once in c++ we have a second background thread which is consuming the images and pushing to the VioManager directly.



https://github.com/user-attachments/assets/2d706994-b87c-4a8e-bc83-50648ab8ad6a




## Getting Started

1. Clone this repository: `git clone --recurse-submodules https://github.com/goldbattle/open_vins_mobile.git`
    - OpenVINS will be located in [app/src/main/cpp/open_vins/](app/src/main/cpp/open_vins/)
    - The native code which passes information into OpenVINS is [native-lib.cpp](app/src/main/cpp/native-lib.cpp)
2. Open the cloned folder in Android Studio and "run" / build it and install it on your device
    - This will build both the [OpenVINS](https://github.com/rpng/open_vins/) c++ library, but also the Kotlin java component of it
    - As a post step, all the things in [app_device/](app_device/) are copied to the private app folder
    - This is where you will update the calibration / yaml config for OpenVINS for the device
3. You will need to first perform calibration of your device
    - Follow the guide in [app_kalibr/](app_kalibr/).
    - You should be able to use the app to record a dataset to disk via the "save" icon
    - Perform the calibration, and update your device calibration [app_device/](app_device/)
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



## References

- Core Android Development
    - Android NDK toolset -- https://developer.android.com/ndk
    - Android Camera2 API -- https://developer.android.com/reference/android/hardware/camera2/package-summary
    - Camera2 API Guide -- https://developer.android.com/training/camera2
    - CMake for Android -- https://developer.android.com/ndk/guides/cmake
    - Kotlin Programming Language -- https://kotlinlang.org/docs/home.html
- Third-Party Libraries
    - Guide to using OpenCV on android -- https://github.com/VlSomers/native-opencv-android-template
    - OpenCV Documentation -- https://docs.opencv.org/
    - Boost for Android -- https://github.com/dec1/Boost-for-Android
    - Boost C++ Libraries -- https://www.boost.org/
    - Eigen3 Linear Algebra Library -- https://eigen.tuxfamily.org/
- Examples and Tutorials
    - JavaCameraView Example -- https://answers.opencv.org/question/19796/android-use-autofocus-with-camerabridgeviewbase/#19813



## Credit / Licensing

For researchers that have leveraged or compared to this work, please cite the
following:

```txt
@Conference{Geneva2020ICRA,
  Title      = {{OpenVINS}: A Research Platform for Visual-Inertial Estimation},
  Author     = {Patrick Geneva and Kevin Eckenhoff and Woosik Lee and Yulin Yang and Guoquan Huang},
  Booktitle  = {Proc. of the IEEE International Conference on Robotics and Automation},
  Year       = {2020},
  Address    = {Paris, France},
  Url        = {\url{https://github.com/rpng/open_vins}}
}
```

OpenVINS is license under the [GNU General Public License v3 (GPL-3)](https://www.gnu.org/licenses/gpl-3.0.txt), please checkout its [LICENSE](https://github.com/rpng/open_vins/blob/master/LICENSE).
This mobile code codebase and native code is also licensed under the [GNU General Public License v3 (GPL-3)](https://www.gnu.org/licenses/gpl-3.0.txt).
You must preserve the copyright and license notices in your derivative work and make available the complete source code with modifications under the same license ([see this](https://choosealicense.com/licenses/gpl-3.0/); this is not legal advice).


