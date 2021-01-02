#include <jni.h>
#include <android/log.h>
#include <string>
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include <opencv2/opencv.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#define TAG "NativeLib"

extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_adaptiveThresholdFromJNI(
        JNIEnv* env,
        jobject instance,
        jlong matAddr) {

    // get Mat from raw address
    cv::Mat &mat = *(cv::Mat *) matAddr;

    // Apply our transformation
    clock_t begin = clock();
    cv::Mat mat_gray;
    cv::cvtColor(mat, mat_gray, cv::COLOR_BGR2GRAY);
    //cv::adaptiveThreshold(mat, mat, 255, cv::ADAPTIVE_THRESH_MEAN_C, cv::THRESH_BINARY_INV, 21, 5);
    std::vector<cv::KeyPoint> pts_new;
    cv::FAST(mat_gray, pts_new, 15.0, true);
    for(const auto &pt : pts_new) {
        cv::circle(mat, pt.pt, 1, cv::Scalar(255,0,0), cv::FILLED);
    }

    // log computation time to Android Logcat
    double totalTime = double(clock() - begin) / CLOCKS_PER_SEC;
    __android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold computation time = %f seconds (%f hz)\n", totalTime, 1.0/totalTime);

    // Display framerate
    int total_time_ms = (int)(totalTime*1000);
    cv::putText(mat, std::to_string(total_time_ms)+"ms", cv::Point(10,30), cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5, cv::Scalar(0,255,0), 2);

    //std::string hello = "Hello from C++";
    //return env->NewStringUTF(hello.c_str());
}