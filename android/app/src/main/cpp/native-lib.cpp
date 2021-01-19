#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>
#include <algorithm>
#include <iomanip>
#include <sstream>
#include <chrono>
#include <iostream>
#include <fstream>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/opencv.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#define TAG "NativeLib"

bool is_recording = false;
std::string app_folder = "/sdcard/";
std::string save_folder = "/sdcard/";
std::ofstream imu_csv;

extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_setAppFolderJNI(JNIEnv* env, jobject instance, jstring dir) {
    const char* temp = env->GetStringUTFChars(dir, NULL);
    app_folder = std::string(temp);
    __android_log_print(ANDROID_LOG_INFO, TAG, "export app folder: %s\n", app_folder.c_str());
}


extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_setRecordStateJNI(JNIEnv* env, jobject instance, jboolean stateAddr) {
    is_recording = (bool)stateAddr;
    if(is_recording) {

        // Create folder with the current time as the folder name
        auto time = std::time(nullptr);
        std::stringstream ss;
        ss << std::put_time(std::localtime(&time), "%F_%T"); // ISO 8601 without timezone information.
        auto s = ss.str();
        std::replace(s.begin(), s.end(), ':', '-');
        save_folder = app_folder + "/" + s + "/";

        // Make the folder if not there
        struct stat st = {0};
        if (stat(save_folder.c_str(), &st) == -1) {
            mkdir(save_folder.c_str(), 0700);
        }
        mkdir((save_folder+"cam0/").c_str(), 0700);

        // Open our IMU csv file
        imu_csv.open(save_folder+"imu0.csv");
        imu_csv << "timestamp,omega_x,omega_y,omega_z,alpha_x,alpha_y,alpha_z" << std::endl;

    } else {

        // If the file was open, then close it
        if(imu_csv.is_open()) {
            imu_csv.close();
        }

    }
}


extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_processImageJNI(JNIEnv* env, jobject instance, jlong matAddr) {

    // Record the current timestamp (is there a better way?)
    unsigned long long time_in_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    // get Mat from raw address
    clock_t begin = clock();
    cv::Mat &mat = *(cv::Mat *) matAddr;

    // Convert to gray scale
    cv::Mat mat_gray;
    cv::cvtColor(mat, mat_gray, cv::COLOR_BGR2GRAY);

    // If recording save to disk
    if(is_recording) {
        std::string filename = save_folder + "cam0/" + std::to_string(time_in_ns) + ".png";
        cv::imwrite(filename, mat_gray);
        __android_log_print(ANDROID_LOG_INFO, TAG, "saved file: %s\n", filename.c_str());
    }

    // Apply our transformations
    //cv::adaptiveThreshold(mat, mat, 255, cv::ADAPTIVE_THRESH_MEAN_C, cv::THRESH_BINARY_INV, 21, 5);
    std::vector<cv::KeyPoint> pts_new;
    cv::FAST(mat_gray, pts_new, 15.0, true);
    for(const auto &pt : pts_new) {
        cv::circle(mat, pt.pt, 1, cv::Scalar(255,0,0), cv::FILLED);
    }

    // log computation time to Android Logcat
    double totalTime = double(clock() - begin) / CLOCKS_PER_SEC;
    //__android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold computation time = %f seconds (%f hz)\n", totalTime, 1.0/totalTime);
    //__android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold matrix size %d x %d\n", mat.rows, mat.cols);


    //================================================================
    //================================================================
    //================================================================


    // Resize the display of the system
    cv::Mat mat_gray_small;
    cv::resize(mat, mat_gray_small, cv::Size(160, 120), 0, 0, cv::INTER_LINEAR);

    // TODO: insert the trajectory as full screen
    //cv::Mat mat_out(mat.rows, mat.cols, mat.type(), cv::Scalar(0));
    cv::Mat mat_out = mat;
    mat_gray_small.copyTo(mat_out(cv::Rect(0,0,mat_gray_small.cols,mat_gray_small.rows)));

    // Display framerate
    int total_time_ms = (int)(totalTime*1000);
    cv::putText(mat_out, std::to_string(total_time_ms)+"ms", cv::Point(170,30), cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5, cv::Scalar(0,255,0), 2);
    cv::putText(mat_out, ((is_recording)?"recording":"waiting"), cv::Point(170,60), cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5, cv::Scalar(0,255,0), 2);

    // Finally replace the image that was passed in
    mat = mat_out.clone();

}


extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_processInertialJNI(JNIEnv* env, jobject instance,
                                                          jfloat ax, jfloat ay, jfloat az,
                                                          jfloat gx, jfloat gy, jfloat gz) {

    // Record the current timestamp (is there a better way?)
    unsigned long long time_in_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    // Cast to our native type
    double n_ax = static_cast<double>(ax);
    double n_ay = static_cast<double>(ay);
    double n_az = static_cast<double>(az);
    double n_gx = static_cast<double>(gx);
    double n_gy = static_cast<double>(gy);
    double n_gz = static_cast<double>(gz);

    // If recording save to disk
    if(is_recording && imu_csv.is_open()) {
        imu_csv << time_in_ns << ","
            << n_gx << "," << n_gy << "," << n_gz << ","
            << n_ax << "," << n_ay << "," << n_az << std::endl;
        __android_log_print(ANDROID_LOG_INFO, TAG,
                "%.4f, %.4f, %.4f | %.4f, %.4f, %.4f \n", n_ax, n_ay, n_az, n_gx, n_gy, n_gz);
    }

}