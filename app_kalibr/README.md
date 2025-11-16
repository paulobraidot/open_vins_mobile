# Kalibr Calibration Scripts

This directory contains user-friendly scripts to automate the Kalibr calibration workflow using Docker. The workflow is designed for calibrating cameras and camera-IMU systems, with support for downloading datasets directly from Android devices.

## Prerequisites

1. **Docker** must be installed and running
   - Install from: https://docs.docker.com/get-docker/
   - Verify with: `docker --version` and `docker info`

2. **ADB (Android Debug Bridge)** - Required for step02
   - Install from: https://developer.android.com/studio/releases/platform-tools
   - Verify with: `adb version`

3. **X11 forwarding** (for GUI applications, optional)
   - On Linux: Usually works out of the box
   - On WSL: May need additional setup

## Folder Structure

All calibration data is organized by device and date:

```
app_kalibr/
├── <date-device-name>/              # e.g., 2021-01-19-pixel6
│   ├── target.yaml                  # Calibration target config (committed)
│   ├── target.pdf                   # Printable target (committed)
│   ├── dataset-<remote_name>/       # Raw dataset from device (gitignored)
│   └── result-<remote_name>/       # Calibration results (committed)
│       ├── <remote_name>.bag        # ROS bag file
│       ├── camchain-*.yaml          # Camera calibration results
│       ├── results-cam-*.txt        # Camera calibration statistics
│       ├── imu-camchain.yaml       # Camera-IMU calibration results
│       └── results-imu-cam-*.txt   # Camera-IMU calibration statistics
└── kalibr_repo/                     # Kalibr source code (gitignored)
```

**Key Points:**
- Each device gets its own folder: `<date-device-name>/` (e.g., `2021-01-19-pixel6`)
- Raw datasets in `dataset-*/` folders are **gitignored** (not committed)
- Calibration results in `result-*/` folders are **committed** to git
- Target files (`.pdf` and `.yaml`) are **committed** to git
- This allows sharing calibration results while keeping raw data local

## Workflow Overview

The calibration process consists of 4 steps:

1. **Step 00**: Setup Kalibr Docker image (one-time setup)
2. **Step 01**: Create calibration target PDF and YAML
3. **Step 02**: Download dataset from Android device
4. **Step 03**: Run calibration (create bag, calibrate cameras, calibrate camera-IMU)

## Step-by-Step Guide

### Step 00: Setup Kalibr Docker Image

Build the Kalibr Docker image from the project root:

```bash
./app_kalibr/step00_kalibr_setup_docker.sh
```

**Options:**
```bash
./app_kalibr/step00_kalibr_setup_docker.sh [KALIBR_DIR] [UBUNTU_VERSION]
```

**What it does:**
- Clones the Kalibr repository into `app_kalibr/kalibr_repo/` (gitignored)
- Builds the Docker image named `kalibr`
- Uses Ubuntu 20.04 by default (can be changed with second argument)

**Note:** 
- The first build takes 10-30 minutes depending on your system
- The Kalibr repository is cloned locally but is gitignored (won't be committed to git)

### Step 01: Create Calibration Target

Generate a printable PDF and YAML configuration file for the calibration target.

**Usage:**
```bash
./app_kalibr/step01_kalibr_target_create.sh <date-device-name> [nx] [ny] [tsize] [tspace] [output_name]
```

**Parameters:**
- `date-device-name` (required): Folder name like `2021-01-19-pixel6` or `2024-03-15-samsung-s23`
- `nx`: Number of columns (default: 6)
- `ny`: Number of rows (default: 6)
- `tsize`: Tag size in meters (default: 0.088)
- `tspace`: Tag spacing percentage (default: 0.3)
- `output_name`: Output file name (default: `target`)

**Examples:**

Create target for a specific device:
```bash
./app_kalibr/step01_kalibr_target_create.sh 2021-01-19-pixel6
```
Creates: `app_kalibr/2021-01-19-pixel6/target.pdf` and `target.yaml`

Create target with custom parameters:
```bash
./app_kalibr/step01_kalibr_target_create.sh 2021-01-19-pixel6 8 11 0.08 0.3
```

**Output:**
- `target.pdf` - Print this on A0 or A1 paper for data collection
- `target.yaml` - Target configuration file used in calibration

**Tip:** The device folder name should follow the format `YYYY-MM-DD-device-name` to keep things organized.

### Step 02: Download Dataset from Android Device

Automatically finds and downloads the newest dataset from your Android device via ADB.

**Usage:**
```bash
./app_kalibr/step02_android_offload_last_dataset.sh <date-device-name> [device_serial]
```

**Parameters:**
- `date-device-name`: Device folder name (must match step01)
- `device_serial`: Device serial number (optional, required if multiple devices connected)

**Device Path:**
Datasets are expected at: `/storage/emulated/0/Documents/openvins/<remote_folder_name>/`

**What it does:**
- Automatically finds the newest dataset folder on the device (by modification time)
- Downloads it to `app_kalibr/<date-device-name>/dataset-<remote_folder_name>/`
- Checks if target files exist in the device folder (from step01)
- Prints the remote folder name for use in step03
- The dataset folder is gitignored (raw data stays local)

**Examples:**

Download newest dataset:
```bash
./app_kalibr/step02_android_offload_last_dataset.sh 2021-01-19-pixel6
```

Download with specific device:
```bash
./app_kalibr/step02_android_offload_last_dataset.sh 2021-01-19-pixel6 ABC123XYZ
```

List available datasets (without downloading):
```bash
./app_kalibr/step02_android_offload_last_dataset.sh list
```

**Output:**
- Dataset folder: `app_kalibr/<date-device-name>/dataset-<remote_folder_name>/` containing all collected data
- **Remote folder name** printed at the end (use this in step03 commands)

### Step 03: Run Calibration

Run the complete calibration workflow: create ROS bag, calibrate cameras, and calibrate camera-IMU.

**Usage:**
```bash
./app_kalibr/step03_kalibr_run_calibration.sh [COMMAND] [OPTIONS]
```

**Commands:**

**Interactive workflow (recommended):**
```bash
./app_kalibr/step03_kalibr_run_calibration.sh all
```

**Individual steps:**

1. **Create ROS bag from dataset:**
```bash
./app_kalibr/step03_kalibr_run_calibration.sh create_bag <date-device-name> <remote_folder_name> [output_bag]
```
- `date-device-name`: Device folder name (must match step01 and step02)
- `remote_folder_name`: Dataset folder name from device
- `output_bag`: Output bag name (default: `<remote_folder_name>.bag`)

Example:
```bash
./app_kalibr/step03_kalibr_run_calibration.sh create_bag 2021-01-19-pixel6 2021-01-19_14-14-47
```

2. **Calibrate cameras:**
```bash
./app_kalibr/step03_kalibr_run_calibration.sh calibrate_cam <date-device-name> <remote_folder_name> [models] [topics] [bag_freq]
```
- `date-device-name`: Device folder name
- `remote_folder_name`: Dataset folder name
- `models`: Camera model (default: `pinhole-radtan`)
- `topics`: Image topic(s) (default: `/cam0/image_raw`)
- `bag_freq`: Bag frequency in Hz (default: `10.0`)

Example:
```bash
./app_kalibr/step03_kalibr_run_calibration.sh calibrate_cam 2021-01-19-pixel6 2021-01-19_14-14-47
```

3. **Calibrate camera-IMU:**
```bash
./app_kalibr/step03_kalibr_run_calibration.sh calibrate_camimu <date-device-name> <remote_folder_name> [imu_yaml]
```
- `date-device-name`: Device folder name
- `remote_folder_name`: Dataset folder name
- `imu_yaml`: IMU configuration file (default: `app_device/config/kalibr_imu_chain.yaml`)

Example:
```bash
./app_kalibr/step03_kalibr_run_calibration.sh calibrate_camimu 2021-01-19-pixel6 2021-01-19_14-14-47
```

**Note:** Camera-IMU calibration can take 30 minutes to several hours depending on dataset size.

**Outputs:**
- `app_kalibr/<date-device-name>/result-<remote_folder_name>/<remote_folder_name>.bag` - ROS bag file
- `app_kalibr/<date-device-name>/result-<remote_folder_name>/camchain-<timestamp>.yaml` - Camera calibration results
- `app_kalibr/<date-device-name>/result-<remote_folder_name>/results-cam-<timestamp>.txt` - Camera calibration statistics
- `app_kalibr/<date-device-name>/result-<remote_folder_name>/imu-camchain.yaml` - Camera-IMU calibration results
- `app_kalibr/<date-device-name>/result-<remote_folder_name>/results-imu-cam-<timestamp>.txt` - Camera-IMU calibration statistics

## Complete Workflow Example

Here's a complete example workflow for calibrating a Pixel 6 device:

```bash
# Step 00: Setup (one-time, takes 10-30 minutes)
./app_kalibr/step00_kalibr_setup_docker.sh

# Step 01: Create target for your device
./app_kalibr/step01_kalibr_target_create.sh 2021-01-19-pixel6

# Print the PDF and collect data on your Android device
# (Data should be saved to /storage/emulated/0/Documents/openvins/2021-01-19_14-14-47/)

# Step 02: Download newest dataset from device (automatically finds it)
./app_kalibr/step02_android_offload_last_dataset.sh 2021-01-19-pixel6
# Note: The script will print the remote folder name - use it in step03

# Step 03: Run calibration
# Use the remote folder name printed by step02 (e.g., 2021-01-19_14-14-47)
./app_kalibr/step03_kalibr_run_calibration.sh all

# Or run individual steps (replace <remote_folder_name> with the name from step02):
./app_kalibr/step03_kalibr_run_calibration.sh create_bag 2021-01-19-pixel6 <remote_folder_name>
./app_kalibr/step03_kalibr_run_calibration.sh calibrate_cam 2021-01-19-pixel6 <remote_folder_name>
./app_kalibr/step03_kalibr_run_calibration.sh calibrate_camimu 2021-01-19-pixel6 <remote_folder_name>
```

## File Organization

All calibration outputs are organized in the `app_kalibr/` directory:

```
open_vins_mobile/
├── app_kalibr/                    # All calibration files
│   ├── step00_kalibr_setup_docker.sh
│   ├── step01_kalibr_target_create.sh
│   ├── step02_android_offload_last_dataset.sh
│   ├── step03_kalibr_run_calibration.sh
│   ├── .gitignore                 # Ignores kalibr_repo/ and dataset-*/
│   ├── kalibr_repo/               # Kalibr source (gitignored)
│   └── <date-device-name>/        # Each device in its own folder
│       ├── target.pdf             # Calibration target PDF (committed)
│       ├── target.yaml            # Target config (committed)
│       ├── dataset-*/             # Raw datasets (gitignored)
│       └── result-*/              # Calibration results (committed)
│           ├── *.bag              # ROS bag files
│           ├── camchain-*.yaml    # Camera calibration
│           ├── results-*.txt     # Statistics
│           ├── imu-camchain.yaml  # Camera-IMU calibration
│           └── results-imu-cam-*.txt
└── app_device/config/             # IMU config examples
    ├── kalibr_imu_chain.yaml
    └── kalibr_imucam_chain.yaml
```

**Git Strategy:**
- ✅ **Committed**: Target files (`.pdf`, `.yaml`), calibration results (`result-*/`)
- ❌ **Gitignored**: Raw datasets (`dataset-*/`), Kalibr repository (`kalibr_repo/`)

This allows you to:
- Share calibration results and targets with your team
- Keep raw datasets local (they can be large)
- Track calibration history for different devices

## Using Calibration Results

After completing the calibration workflow, you can use the results in your OpenVINS configuration:

1. **Camera intrinsics**: Use the `camchain-*.yaml` file from `result-*/` folder
2. **Camera-IMU extrinsics**: Use the `imu-camchain.yaml` file from `result-*/` folder
3. **IMU noise parameters**: Use or update `app_device/config/kalibr_imu_chain.yaml`

The calibration files follow the Kalibr YAML format and can be directly used by OpenVINS.

## Debugging

### Interactive Docker Container

For debugging Kalibr commands, you can enter the Docker container interactively:

```bash
./app_kalibr/debug_enter_kalibr_docker.sh [directory_to_mount]
```

**Examples:**

Enter container with current directory mounted:
```bash
cd app_kalibr/2025-11-15-galaxy-s32-fe
../debug_enter_kalibr_docker.sh
```

Enter container with specific directory:
```bash
./app_kalibr/debug_enter_kalibr_docker.sh app_kalibr/2025-11-15-galaxy-s32-fe
```

**Inside the container:**
```bash
# Source the workspace
source /catkin_ws/devel/setup.bash

# Test commands
rosrun kalibr kalibr_create_target_pdf --help
rosrun kalibr kalibr_bagcreater --help
rosrun kalibr kalibr_calibrate_cameras --help

# Your files are available at /data
ls -la /data
```

### Viewing Command Output

All scripts now print the commands they execute before running them. Check the output for:
- Commands being executed
- Mount points being used
- Log file locations

Log files are saved in the output directories:
- `kalibr_target_creation.log` - Target creation output
- `bag_creation.log` - ROS bag creation output
- `camera_calibration.log` - Camera calibration output
- `imu_camera_calibration.log` - Camera-IMU calibration output

## Troubleshooting

### Docker image not found
If you see "Docker image 'kalibr' not found", run:
```bash
./app_kalibr/step00_kalibr_setup_docker.sh
```

### ADB device not found
- Make sure USB debugging is enabled on your Android device
- Check connection with: `adb devices`
- If multiple devices, specify device serial: `./app_kalibr/step02_android_offload_last_dataset.sh <date-device-name> <device_serial>`
- On some systems, you may need to run `adb` with `sudo` or configure udev rules

### Dataset not found on device
- Verify datasets exist: `adb shell ls /storage/emulated/0/Documents/openvins/`
- The script automatically finds the newest dataset, so make sure at least one exists
- Use `list` command to see available datasets: `./app_kalibr/step02_android_offload_last_dataset.sh list`
- If you need a specific (older) dataset, you may need to modify the script or use ADB directly

### Target files not found
- Make sure you ran step01 with the same device folder name
- Check that `target.yaml` exists in the device folder: `ls app_kalibr/<date-device-name>/target.yaml`

### X11 forwarding issues
If GUI applications don't work, try:
```bash
xhost +local:root
export DISPLAY=:0
```

### Permission errors
Make sure Docker has permission to access the directories. You may need to:
```bash
sudo usermod -aG docker $USER
# Then log out and back in
```

### Long calibration times
Camera-IMU calibration can take a very long time (hours) for large datasets. This is normal. Consider:
- Using smaller datasets (30-60 seconds)
- Reducing image resolution
- Using fewer frames

## References

- [Kalibr Installation](https://github.com/ethz-asl/kalibr/wiki/installation)
- [Kalibr Calibration Targets](https://github.com/ethz-asl/kalibr/wiki/calibration-targets)
- [Kalibr Bag Format](https://github.com/ethz-asl/kalibr/wiki/bag-format)
- [Kalibr Multiple Camera Calibration](https://github.com/ethz-asl/kalibr/wiki/multiple-camera-calibration)
- [Kalibr Camera-IMU Calibration](https://github.com/ethz-asl/kalibr/wiki/camera-imu-calibration)
- [OpenVINS Calibration Guide](https://github.com/rpng/open_vins/blob/master/docs/gs-calibration.dox)
