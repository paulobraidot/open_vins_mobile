#!/bin/bash

# Script to download calibration datasets from Android device via ADB
# Usage: ./step02_android_offload_last_dataset.sh <date-device-name> [device_serial]
#
# This script automatically finds and downloads the newest dataset from the Android device.
# Downloads to: app_kalibr/<date-device-name>/dataset-<remote_folder_name>/
# Device path: /storage/emulated/0/Documents/openvins/<remote_folder_name>/
# Example: ./step02_android_offload_last_dataset.sh 2021-01-19-pixel6

set -e  # Exit on error

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}"
DEVICE_BASE_PATH="/storage/emulated/0/Documents/openvins"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if ADB is installed and find it
find_adb() {
    # Check if adb is in PATH
    if command -v adb &> /dev/null; then
        echo "adb"
        return 0
    fi
    
    # Check Android SDK location
    local sdk_adb="${HOME}/Android/Sdk/platform-tools/adb"
    if [ -f "${sdk_adb}" ] && [ -x "${sdk_adb}" ]; then
        echo "${sdk_adb}"
        return 0
    fi
    
    print_error "ADB is not installed. Please install Android Debug Bridge."
    echo "Install from: https://developer.android.com/studio/releases/platform-tools"
    echo "Or ensure ~/Android/Sdk/platform-tools/adb exists"
    exit 1
}

# Function to check if ADB is installed
check_adb() {
    ADB_CMD=$(find_adb)
}

# Function to check if device is connected
check_device() {
    local device_serial="$1"
    
    if [ -n "${device_serial}" ]; then
        if ! ${ADB_CMD} -s "${device_serial}" get-state &> /dev/null; then
            print_error "Device with serial '${device_serial}' not found or not authorized."
            exit 1
        fi
        print_info "Using device: ${device_serial}"
    else
        local device_count=$(${ADB_CMD} devices | grep -v "List" | grep -c "device$" || true)
        if [ "${device_count}" -eq 0 ]; then
            print_error "No Android device found. Please connect a device and enable USB debugging."
            echo "Run '${ADB_CMD} devices' to check connected devices."
            exit 1
        elif [ "${device_count}" -gt 1 ]; then
            print_warn "Multiple devices found. Please specify device serial:"
            ${ADB_CMD} devices
            echo ""
            echo "Usage: $0 <dataset_name> <device_serial>"
            exit 1
        fi
        print_info "Using default device"
    fi
}

# Function to list available datasets on device
list_datasets() {
    local device_serial="$1"
    local adb_cmd="${ADB_CMD}"
    
    if [ -n "${device_serial}" ]; then
        adb_cmd="${ADB_CMD} -s ${device_serial}"
    fi
    
    print_info "Available datasets on device:"
    echo ""
    
    if ! ${adb_cmd} shell "test -d ${DEVICE_BASE_PATH}" 2>/dev/null; then
        print_warn "Base path ${DEVICE_BASE_PATH} does not exist on device."
        print_info "No datasets found."
        return
    fi
    
    local datasets=$(${adb_cmd} shell "ls -d ${DEVICE_BASE_PATH}/*/ 2>/dev/null" | tr -d '\r\n' | sed 's|/*$||' | awk -F'/' '{print $NF}' || true)
    
    if [ -z "${datasets}" ]; then
        print_info "No datasets found in ${DEVICE_BASE_PATH}"
    else
        echo "${datasets}" | while read -r dataset; do
            if [ -n "${dataset}" ]; then
                echo "  - ${dataset}"
            fi
        done
    fi
}

# Function to find the newest dataset folder on device
find_newest_dataset() {
    local device_serial="$1"
    local adb_cmd="${ADB_CMD}"
    
    if [ -n "${device_serial}" ]; then
        adb_cmd="${ADB_CMD} -s ${device_serial}"
    fi
    
    if ! ${adb_cmd} shell "test -d ${DEVICE_BASE_PATH}" 2>/dev/null; then
        print_error "Base path ${DEVICE_BASE_PATH} does not exist on device."
        return 1
    fi
    
    # Find the newest folder by modification time
    # Use 'ls -td' to sort by modification time (newest first), then get the first one
    local raw_output=$(${adb_cmd} shell "ls -td ${DEVICE_BASE_PATH}/*/ 2>/dev/null" | head -1 | tr -d '\r\n' | xargs)
    
    if [ -z "${raw_output}" ]; then
        print_error "No datasets found in ${DEVICE_BASE_PATH}"
        return 1
    fi
    
    # Extract just the folder name (last component of path)
    local newest_folder=$(echo "${raw_output}" | sed 's|/*$||' | awk -F'/' '{print $NF}' | xargs)
    
    if [ -z "${newest_folder}" ]; then
        print_error "Could not parse folder name from: ${raw_output}"
        return 1
    fi
    
    echo "${newest_folder}"
    return 0
}

# Function to download dataset
download_dataset() {
    local device_folder="$1"
    local device_serial="$2"
    local adb_cmd="${ADB_CMD}"
    
    if [ -z "${device_folder}" ]; then
        print_error "Device folder name (date-device-name) is required."
        echo ""
        echo "Usage: $0 <date-device-name> [device_serial]"
        echo "   or: $0 list [device_serial]  (to list available datasets)"
        echo "Example: $0 2021-01-19-pixel6"
        echo ""
        exit 1
    fi
    
    if [ "${device_folder}" = "list" ]; then
        list_datasets "${device_serial}"
        exit 0
    fi
    
    if [ -n "${device_serial}" ]; then
        adb_cmd="${ADB_CMD} -s ${device_serial}"
    fi
    
    # Find the newest dataset on device
    print_info "Finding newest dataset on device..."
    local remote_folder_name
    remote_folder_name=$(find_newest_dataset "${device_serial}")
    local find_result=$?
    
    if [ ${find_result} -ne 0 ] || [ -z "${remote_folder_name}" ]; then
        print_error "Could not find any datasets on device."
        echo ""
        print_info "Available datasets:"
        list_datasets "${device_serial}"
        exit 1
    fi
    
    print_info "Found newest dataset: ${remote_folder_name}"
    echo ""
    
    local device_path="${DEVICE_BASE_PATH}/${remote_folder_name}"
    local local_path="${OUTPUT_DIR}/${device_folder}/dataset-${remote_folder_name}"
    
    # Check if local directory already exists
    if [ -d "${local_path}" ]; then
        print_warn "Local directory already exists: ${local_path}"
        read -p "Do you want to overwrite it? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            print_info "Skipping download."
            exit 0
        fi
        print_info "Removing existing directory..."
        rm -rf "${local_path}"
    fi
    
    # Create local directory
    mkdir -p "${local_path}"
    
    print_info "Downloading dataset from device..."
    print_info "  Device path: ${device_path}"
    print_info "  Local path: ${local_path}"
    echo ""
    
    # Pull the entire directory
    ${adb_cmd} pull "${device_path}" "${local_path}/"
    
    if [ $? -eq 0 ]; then
        print_info "Dataset downloaded successfully!"
        print_info "Location: ${local_path}"
        
        # Show what was downloaded
        local file_count=$(find "${local_path}" -type f | wc -l)
        local dir_count=$(find "${local_path}" -type d | wc -l)
        print_info "Downloaded: ${file_count} files in ${dir_count} directories"
        
        # Check if target files exist in device folder (from step01)
        local device_folder_path="${OUTPUT_DIR}/${device_folder}"
        local existing_targets=$(find "${device_folder_path}" -maxdepth 1 -type f \( -name "*.pdf" -o -name "*.yaml" \) 2>/dev/null || true)
        if [ -n "${existing_targets}" ]; then
            print_info "Target files found in device folder:"
            echo "${existing_targets}" | while read -r target_file; do
                if [ -n "${target_file}" ] && [ -f "${target_file}" ]; then
                    print_info "  - $(basename "${target_file}")"
                fi
            done
        else
            print_warn "No target files found in ${device_folder_path}"
            print_info "Create target with: ./app_kalibr/step01_kalibr_target_create.sh ${device_folder}"
        fi
        
        # Print the remote folder name for use in step03
        echo ""
        print_info "=========================================="
        print_info "Remote folder name: ${remote_folder_name}"
        print_info "=========================================="
        print_info "Use this in step03:"
        print_info "  ./app_kalibr/step03_kalibr_run_calibration.sh create_bag ${device_folder} ${remote_folder_name}"
        print_info "  ./app_kalibr/step03_kalibr_run_calibration.sh calibrate_cam ${device_folder} ${remote_folder_name}"
        print_info "  ./app_kalibr/step03_kalibr_run_calibration.sh calibrate_camimu ${device_folder} ${remote_folder_name}"
        echo ""
    else
        print_error "Download failed!"
        exit 1
    fi
}

# Main script
check_adb

# Check for device
DEVICE_SERIAL="${2:-}"
check_device "${DEVICE_SERIAL}"

# Download dataset
DEVICE_FOLDER="${1:-}"
download_dataset "${DEVICE_FOLDER}" "${DEVICE_SERIAL}"

