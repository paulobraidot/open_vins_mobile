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


// when building boost we persisted the NDK version used (BOOST_BUILT_WITH_NDK_VERSION) in this custom header file
#include <boost/version_ndk.hpp>
#include <boost/chrono.hpp>
#include <boost/lexical_cast.hpp>

// OpenVINS project
#include "track/TrackKLT.h"

#define TAG "NativeLib"

bool is_recording = false;
std::string app_folder = "/sdcard/";
std::string save_folder = "/sdcard/";
std::ofstream imu_csv;
std::shared_ptr<ov_core::TrackKLT> tracker = nullptr;
std::deque<double> clonetimes;


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
    double time_in_sec = 1e-9*(double)time_in_ns;

    // get Mat from raw address
    clock_t begin = clock();
    cv::Mat &mat = *(cv::Mat *) matAddr;

    // Convert to gray scale
    cv::Mat mat_gray;
    cv::cvtColor(mat, mat_gray, cv::COLOR_BGR2GRAY);

    // If recording save to disk
    if(is_recording) {

        // Write to disk
        std::string filename = save_folder + "cam0/" + std::to_string(time_in_ns) + ".png";
        cv::imwrite(filename, mat_gray);
        __android_log_print(ANDROID_LOG_INFO, TAG, "saved file: %s\n", filename.c_str());

    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "here 0");

    // Construct our tracker object if needed
    if(tracker == nullptr) {

        // DEBUG: fake KLT tracking camera / settings
        int num_pts = 100;
        int num_aruco = 1024;
        int fast_threshold = 10;
        int grid_x = 3;
        int grid_y = 3;
        int min_px_dist = 10;
        Eigen::Matrix<double, 8, 1> cam0_calib;
        cam0_calib << 1, 1, 0, 0, 0, 0, 0, 0;
        std::map<size_t, bool> camera_fisheye;
        std::map<size_t, Eigen::VectorXd> camera_calibration;
        camera_fisheye.insert({0, false});
        camera_calibration.insert({0, cam0_calib});
        camera_fisheye.insert({1, false});
        camera_calibration.insert({1, cam0_calib});

        // DEBUG: create example klt tracker object
        tracker = std::make_shared<ov_core::TrackKLT>(num_pts, num_aruco, fast_threshold, grid_x, grid_y, min_px_dist);
        tracker->set_calibration(camera_calibration, camera_fisheye);

    }

    // Then call on our tracking
    tracker->feed_monocular(time_in_sec, mat_gray, 0);

    // Apply our transformations
    //cv::adaptiveThreshold(mat, mat, 255, cv::ADAPTIVE_THRESH_MEAN_C, cv::THRESH_BINARY_INV, 21, 5);
    //std::vector<cv::KeyPoint> pts_new;
    //cv::FAST(mat_gray, pts_new, 15.0, true);
    //for(const auto &pt : pts_new) {
    //    cv::circle(mat, pt.pt, 1, cv::Scalar(255,0,0), cv::FILLED);
    //}

    // log computation time to Android Logcat
    double totalTime = double(clock() - begin) / CLOCKS_PER_SEC;
    //__android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold computation time = %f seconds (%f hz)\n", totalTime, 1.0/totalTime);
    //__android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold matrix size %d x %d\n", mat.rows, mat.cols);


    //================================================================
    //================================================================
    //================================================================

    // Get updated frame
    tracker->display_history(mat, 0, 255, 255, 255, 255, 255);

    // Push back the current time, as a clone time
    clonetimes.push_back(time_in_sec);

    // Marginalized features if we have reached 5 frame tracks
    auto database = tracker->get_feature_database();
    if ((int)clonetimes.size() >= 10) {
        // Remove features that have reached their max track length
        double margtime = clonetimes.at(0);
        clonetimes.pop_front();
        std::vector<std::shared_ptr<ov_core::Feature>> feats_marg = database->features_containing(margtime);
        // Delete theses feature pointers
        for (size_t i = 0; i < feats_marg.size(); i++) {
            feats_marg[i]->to_delete = true;
        }
    }

    // Tell the feature database to delete old features
    database->cleanup();

    // Resize the display of the system
    cv::Mat mat_gray_small;
    cv::resize(mat, mat_gray_small, cv::Size(160, 120), 0, 0, cv::INTER_LINEAR);

    // TODO: insert the trajectory as full screen
    //cv::Mat mat_out(mat.rows, mat.cols, mat.type(), cv::Scalar(0));
    cv::Mat mat_out = mat;
    mat_gray_small.copyTo(mat_out(cv::Rect(0,0,mat_gray_small.cols,mat_gray_small.rows)));

    // Display framerate
    int total_time_ms = (int)(totalTime*1000);
    cv::putText(mat_out, std::to_string(total_time_ms)+"ms", cv::Point(mat_out.cols-150,30), cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5, cv::Scalar(0,255,0), 2);
    cv::putText(mat_out, ((is_recording)?"recording":"waiting"), cv::Point(mat_out.cols-150,60), cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5, cv::Scalar(0,255,0), 2);

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