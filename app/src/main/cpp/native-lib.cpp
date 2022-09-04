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
#include <thread>
#include <fstream>
#include <memory>
#include <mutex>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/opencv.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc/imgproc.hpp>


// when building boost we persisted the NDK version used (BOOST_BUILT_WITH_NDK_VERSION) in this custom header file
#include <boost/version_ndk.hpp>
#include <boost/chrono.hpp>
#include <boost/lexical_cast.hpp>
#include <thread>

// OpenVINS project
#include "core/VioManager.h"
#include "core/VioManagerOptions.h"
#include "state/State.h"
#include "utils/opencv_yaml_parse.h"
#include "utils/sensor_data.h"

#define TAG "OVLIB"

bool is_recording = false;
bool is_running_ov = false;
bool app_folder_set = false;
std::string app_folder = "/sdcard/";
std::string save_folder = "/sdcard/";
std::ofstream imu_csv;

//=========================================================
// OPENVINS SPECIFIC VARS - START
//=========================================================

// Master VIO system :)
std::shared_ptr<ov_msckf::VioManager> sys = nullptr;

// Thread atomics
std::atomic<bool> thread_update_running(false);

// Queue up camera measurements sorted by time and trigger once we have
// exactly one IMU measurement with timestamp newer than the camera measurement
// This also handles out-of-order camera measurements, which is rare, but
// a nice feature to have for general robustness to bad camera drivers.
std::deque<ov_core::CameraData> camera_queue;
std::mutex camera_queue_mtx;

// Last camera message timestamps we have received (mapped by cam id)
std::map<int, double> camera_last_timestamp;

//=========================================================
// OPENVINS SPECIFIC VARS - END
//=========================================================

// Visualization data files
double viz_rate = 20.0;
double viz_time = -1.0;
double viz_track_rate = 0.0;
cv::Mat viz_image;
std::string viz_state1 = "";
std::string viz_state2 = "";

extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_setAppFolderJNI(JNIEnv *env, jobject instance, jstring dir) {
    const char *temp = env->GetStringUTFChars(dir, NULL);
    app_folder = std::string(temp);
    app_folder_set = true;
    __android_log_print(ANDROID_LOG_INFO, TAG, "export app folder: %s\n", app_folder.c_str());
}


extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_setRecordStateJNI(JNIEnv *env, jobject instance,
                                                         jboolean stateAddr) {
    is_recording = (bool) stateAddr;
    if (is_recording) {

        // Create folder with the current time as the folder name
        auto time = std::time(nullptr);
        std::stringstream ss;
        ss << std::put_time(std::localtime(&time),
                            "%F_%T"); // ISO 8601 without timezone information.
        auto s = ss.str();
        std::replace(s.begin(), s.end(), ':', '-');
        save_folder = app_folder + "/" + s + "/";

        // Make the folder if not there
        struct stat st = {0};
        if (stat(save_folder.c_str(), &st) == -1) {
            mkdir(save_folder.c_str(), 0700);
        }
        mkdir((save_folder + "cam0/").c_str(), 0700);

        // Open our IMU csv file
        imu_csv.open(save_folder + "imu0.csv");
        imu_csv << "timestamp,omega_x,omega_y,omega_z,alpha_x,alpha_y,alpha_z" << std::endl;

    } else {

        // If the file was open, then close it
        if (imu_csv.is_open()) {
            imu_csv.close();
        }

    }

}

extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_toggleSystemJNI(JNIEnv *env, jobject instance,
                                                       jboolean stateAddr) {
    is_running_ov = (bool) stateAddr;
    if(!is_running_ov) {
        std::lock_guard<std::mutex> lck(camera_queue_mtx);
        sys = nullptr;
        viz_time = -1;
        viz_track_rate = 0.0;
        viz_state1 = "";
        viz_state2 = "";
    }
}


extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_processImageJNI(JNIEnv *env, jobject instance,
                                                       jlong matAddr) {

    // Record the current timestamp (is there a better way?)
    unsigned long long time_in_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    double time_in_sec = 1e-9 * (double) time_in_ns;

    // get Mat from raw address
    clock_t begin = clock();
    cv::Mat &mat = *(cv::Mat *) matAddr;

    // Convert to gray scale
    cv::Mat mat_gray;
    cv::cvtColor(mat, mat_gray, cv::COLOR_BGR2GRAY);

    // If recording save to disk
    if (is_recording) {
        std::string filename = save_folder + "cam0/" + std::to_string(time_in_ns) + ".png";
        cv::imwrite(filename, mat_gray);
        __android_log_print(ANDROID_LOG_INFO, TAG, "saved file: %s\n", filename.c_str());
    }

    // Return if the app folder has not been set yet
    if (!app_folder_set) {
        //__android_log_print(ANDROID_LOG_INFO, TAG, "here 0");
        return;
    }

    // Construct our tracker object if needed
    if (is_running_ov && sys == nullptr) {

        // Log level
        ov_core::Printer::setPrintLevel(ov_core::Printer::PrintLevel::ALL);

        // Load the config
        std::string config_path = app_folder + "/config/estimator_config.yaml";
        auto parser = std::make_shared<ov_core::YamlParser>(config_path, false);
        ov_msckf::VioManagerOptions params;
        params.print_and_load(parser);

        // Override some key parameters we need
        params.use_multi_threading_subs = true;
        params.use_klt = true;
        params.use_aruco = false;
        params.use_stereo = false;
        params.downsample_cameras = false;
        params.num_opencv_threads = 1;
        params.num_pts = 100;
        params.grid_x = 3;
        params.grid_y = 3;
        params.min_px_dist = 20;
        params.track_frequency = 30.0;
        params.retri_active_features = false;
        params.state_options.do_fej = true;
        params.state_options.imu_avg = true;
        params.state_options.use_rk4_integration = false; // seems to be very slow!
        params.state_options.do_calib_camera_pose = true;
        params.state_options.do_calib_camera_intrinsics = true;
        params.state_options.do_calib_camera_timeoffset = true;
        params.state_options.max_clone_size = 8;
        params.state_options.max_slam_features = 10;
        params.state_options.max_slam_in_update = 20;
        params.state_options.max_msckf_in_update = 20;
        params.state_options.num_cameras = 1;
        params.init_options.init_dyn_use = false;
        params.init_options.init_max_features = 25;
        params.init_options.init_window_time = 1.0;
        params.init_options.init_imu_thresh = 0.4;
        params.init_options.init_max_disparity = 2.0;

        // Feature triangulation
        params.featinit_options.triangulate_1d = true;
        params.featinit_options.refine_features = false;

        // Timing stats
        params.record_timing_information = false;
        params.record_timing_filepath = "ov_msckf_timing.txt";

        //=====================================================
        // Camera settings
        //=====================================================

        // Time offset
        params.calib_camimu_dt = 0.0;

        // Distortion parameters
        Eigen::VectorXd cam_calib = Eigen::VectorXd::Zero(8);
        cam_calib << 508.46260595099653, 508.60809677235125, 313.90116337712436, 239.12131316575451,
                    0.06825356240204992, -0.13805574171283572, -0.001705523739596709, -0.003549022763988628;
        cam_calib(0) /= (params.downsample_cameras) ? 2.0 : 1.0;
        cam_calib(1) /= (params.downsample_cameras) ? 2.0 : 1.0;
        cam_calib(2) /= (params.downsample_cameras) ? 2.0 : 1.0;
        cam_calib(3) /= (params.downsample_cameras) ? 2.0 : 1.0;

        // FOV / resolution
        std::pair<int, int> wh(640, 480);
        wh.first /= (params.downsample_cameras) ? 2.0 : 1.0;
        wh.second /= (params.downsample_cameras) ? 2.0 : 1.0;

        // Extrinsics
        Eigen::Matrix4d T_CtoI = Eigen::Matrix4d::Identity();
        Eigen::Matrix<double, 7, 1> cam_eigen;
        cam_eigen.block(0, 0, 4, 1) = ov_core::rot_2_quat(T_CtoI.block(0, 0, 3, 3).transpose());
        cam_eigen.block(4, 0, 3, 1) = -T_CtoI.block(0, 0, 3, 3).transpose() * T_CtoI.block(0, 3, 3, 1);

        // Create intrinsics model
        params.camera_intrinsics[0] = std::make_shared<ov_core::CamRadtan>(wh.first, wh.second);
        params.camera_intrinsics[0]->set_value(cam_calib);
        params.camera_extrinsics[0] = cam_eigen;

        // Ensure we read in all parameters required, create the VIO manager
        if (!parser->successful()) {
            //PRINT_ERROR(RED "[SERIAL]: unable to parse all parameters, please fix\n" RESET);
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "unable to parse all parameters, please fix!!!");
            return;
        } else {
            std::lock_guard<std::mutex> lck(camera_queue_mtx);
            sys = std::make_shared<ov_msckf::VioManager>(params);
        }

    }

    // Try to process the image, check if we should drop this image
    // We will append this image to the queue if we need to
    if (sys != nullptr) {

        // See if the message should be dropped / skipped
        int cam_id0 = 0;
        double time_delta = 1.0 / sys->get_params().track_frequency;
        if(camera_last_timestamp.find(cam_id0) == camera_last_timestamp.end() ||
            time_in_sec > camera_last_timestamp.at(cam_id0) + time_delta) {

            // Record the time we will append the queue
            camera_last_timestamp[cam_id0] = time_in_sec;

            // Create the measurement
            ov_core::CameraData message;
            message.timestamp = time_in_sec;
            message.sensor_ids.push_back(cam_id0);
            message.images.push_back(mat_gray.clone());
            message.masks.push_back(cv::Mat::zeros(mat_gray.rows, mat_gray.cols, CV_8UC1));

            // Append it to our queue of images
            std::lock_guard<std::mutex> lck(camera_queue_mtx);
            camera_queue.push_back(message);
            std::sort(camera_queue.begin(), camera_queue.end());
        }
    }

    // Apply our transformations
    //cv::adaptiveThreshold(mat, mat, 255, cv::ADAPTIVE_THRESH_MEAN_C, cv::THRESH_BINARY_INV, 21, 5);
    //std::vector<cv::KeyPoint> pts_new;
    //cv::FAST(mat_gray, pts_new, 15.0, true);
    //for(const auto &pt : pts_new) {
    //    cv::circle(mat, pt.pt, 1, cv::Scalar(255,0,0), cv::FILLED);
    //}

    // log computation time to Android Logcat
    // double totalTime = double(clock() - begin) / CLOCKS_PER_SEC;
    //__android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold computation time = %f seconds (%f hz)\n", totalTime, 1.0/totalTime);
    //__android_log_print(ANDROID_LOG_INFO, TAG, "adaptiveThreshold matrix size %d x %d\n", mat.rows, mat.cols);


    //================================================================
    //================================================================
    //================================================================

    // Get updated frame (if no frame, then just display the current)
    if (viz_time == -1 || (time_in_sec - viz_time) > 1.0 / viz_rate) {
        cv::Mat temp_img;
        if(sys != nullptr) {
            temp_img = sys->get_historical_viz_image();
        } else {
            viz_image = mat.clone();
        }
        if (!temp_img.empty()) {
            viz_image = temp_img.clone();
            viz_time = time_in_sec;
        }
    }
    if (viz_image.empty()) {
        viz_image = mat.clone();
    }
    cv::Mat mat_out = viz_image.clone();

    // Resize the display of the system to top left
    // TODO: insert the trajectory as the full screen mat here...
    //cv::Mat mat_out(mat.rows, mat.cols, mat.type(), cv::Scalar(0));
    // cv::Mat mat_out = mat_history;
    //cv::resize(mat_history, mat_history, cv::Size(214, 160), 0, 0, cv::INTER_LINEAR);
    //mat_history.copyTo(mat_out(cv::Rect(0, 0, mat_history.cols, mat_history.rows)));

    // Display framerate
    //std::string str = std::to_string((int) (totalTime * 1000)) + "ms";
    std::string str = std::to_string((int) (viz_track_rate)) + "hz";
    cv::putText(mat_out, str, cv::Point(mat_out.cols - 150, 30),
                cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5, cv::Scalar(0, 255, 0), 2);
    cv::putText(mat_out, ((is_recording) ? "recording" : "waiting"),
                cv::Point(mat_out.cols - 150, 60), cv::FONT_HERSHEY_COMPLEX_SMALL, 1.5,
                cv::Scalar(0, 255, 0), 2);

    // Show the current state estimate if we are estimating!
    if(sys != nullptr) {
        cv::putText(mat_out, viz_state1, cv::Point(10, mat_out.rows - 60),
                cv::FONT_HERSHEY_COMPLEX_SMALL, 1.0, cv::Scalar(255, 0, 0), 2);
        cv::putText(mat_out, viz_state2, cv::Point(10, mat_out.rows - 30),
                cv::FONT_HERSHEY_COMPLEX_SMALL, 1.0, cv::Scalar(255, 0, 0), 2);
    }

    // Finally replace the image that was passed in
    mat = mat_out.clone();

}


extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_processInertialJNI(JNIEnv *env, jobject instance,
                                                          jfloat ax, jfloat ay, jfloat az,
                                                          jfloat gx, jfloat gy, jfloat gz) {

    // Record the current timestamp (is there a better way?)
    unsigned long long time_in_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    double time_in_sec = 1e-9 * (double) time_in_ns;

    // Cast to our native type
    double n_ax = static_cast<double>(ax);
    double n_ay = static_cast<double>(ay);
    double n_az = static_cast<double>(az);
    double n_gx = static_cast<double>(gx);
    double n_gy = static_cast<double>(gy);
    double n_gz = static_cast<double>(gz);

    // If recording save to disk
    if (is_recording && imu_csv.is_open()) {
        imu_csv << time_in_ns << ","
                << n_gx << "," << n_gy << "," << n_gz << ","
                << n_ax << "," << n_ay << "," << n_az << std::endl;
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "%.4f, %.4f, %.4f | %.4f, %.4f, %.4f \n", n_ax, n_ay, n_az, n_gx, n_gy,
                            n_gz);
    }

    // Feed if the system is running!
    if (sys != nullptr) {

        // Send it into the system
        ov_core::ImuData message_imu;
        message_imu.timestamp = time_in_sec;
        message_imu.wm << n_gx, n_gy, n_gz;
        message_imu.am << n_ax, n_ay, n_az;
        sys->feed_measurement_imu(message_imu);

        // If the processing queue is currently active / running just return so we can keep getting measurements
        // Otherwise create a second thread to do our update in an async manor
        // The visualization of the state, images, and features will be synchronous with the update!
        if (!thread_update_running) {
            thread_update_running = true;
            std::thread thread([&] {
                // Lock on the queue (prevents new images from appending)
                std::lock_guard<std::mutex> lck(camera_queue_mtx);

                // Count how many unique image streams
                std::map<int, bool> unique_cam_ids;
                for (const auto &cam_msg: camera_queue) {
                    unique_cam_ids[cam_msg.sensor_ids.at(0)] = true;
                }

                // If we do not have enough unique cameras then we need to wait
                // We should wait till we have one of each camera to ensure we propagate in the correct order
                auto params = sys->get_params();
                size_t num_unique_cameras = 1; // params.state_options.num_cameras
                if (unique_cam_ids.size() == num_unique_cameras) {

                    // Loop through our queue and see if we are able to process any of our camera measurements
                    // We are able to process if we have at least one IMU measurement greater than the camera time
                    double timestamp_imu_inC =
                            time_in_sec - sys->get_state()->_calib_dt_CAMtoIMU->value()(0);
                    while (!camera_queue.empty() &&
                           camera_queue.at(0).timestamp < timestamp_imu_inC) {
                        auto rT0_1 = boost::posix_time::microsec_clock::local_time();
                        double update_dt =
                                100.0 * (timestamp_imu_inC - camera_queue.at(0).timestamp);
                        sys->feed_measurement_camera(camera_queue.at(0));
                        //visualize();
                        camera_queue.pop_front();
                        auto rT0_2 = boost::posix_time::microsec_clock::local_time();
                        double time_total = (rT0_2 - rT0_1).total_microseconds() * 1e-6;
                        PRINT_ERROR("[TIME]: %.4f seconds total (%.1f hz, %.2f ms behind)\n",
                                    time_total, 1.0 / time_total, update_dt);

                        // Update vizualization stuff here
                        viz_track_rate = 1.0 / time_total;
                        auto state = sys->get_state();
                        auto q = state->_imu->quat();
                        auto p = state->_imu->quat();
                        std::stringstream ss1, ss2;
                        ss1 << std::fixed << std::setprecision(3);
                        ss1 << "q = " << q(0) << "," << q(1) << "," << q(2) << "," << q(3);
                        ss2 << std::fixed << std::setprecision(2);
                        ss2 << "p = " << p(0) << "," << p(1) << "," << p(2);
                        viz_state1 = ss1.str();
                        viz_state2 = ss2.str();
                    }
                }
                thread_update_running = false;
            });
            thread.detach();
        }

    }

}