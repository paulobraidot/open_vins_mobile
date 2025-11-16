#!/bin/bash

# Script to download calibration datasets from Android device via ADB
# Usage: ./step02_kalibr_download_dataset.sh [dataset_name] [device_serial]
#
# This script downloads datasets from the Android device to app_kalibr/ directory.
# Device path: /storage/emulated/0/Documents/openvins/<dataset_name>/

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

# Function to check if ADB is installed
check_adb() {
    if ! command -v adb &> /dev/null; then
        print_error "ADB is not installed. Please install Android Debug Bridge."
        echo "Install from: https://developer.android.com/studio/releases/platform-tools"
        exit 1
    fi
}

# Function to check if device is connected
check_device() {
    local device_serial="$1"
    
    if [ -n "${device_serial}" ]; then
        if ! adb -s "${device_serial}" get-state &> /dev/null; then
            print_error "Device with serial '${device_serial}' not found or not authorized."
            exit 1
        fi
        print_info "Using device: ${device_serial}"
    else
        local device_count=$(adb devices | grep -v "List" | grep -c "device$" || true)
        if [ "${device_count}" -eq 0 ]; then
            print_error "No Android device found. Please connect a device and enable USB debugging."
            echo "Run 'adb devices' to check connected devices."
            exit 1
        elif [ "${device_count}" -gt 1 ]; then
            print_warn "Multiple devices found. Please specify device serial:"
            adb devices
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
    local adb_cmd="adb"
    
    if [ -n "${device_serial}" ]; then
        adb_cmd="adb -s ${device_serial}"
    fi
    
    print_info "Available datasets on device:"
    echo ""
    
    if ! ${adb_cmd} shell "test -d ${DEVICE_BASE_PATH}" 2>/dev/null; then
        print_warn "Base path ${DEVICE_BASE_PATH} does not exist on device."
        print_info "No datasets found."
        return
    fi
    
    local datasets=$(${adb_cmd} shell "ls -d ${DEVICE_BASE_PATH}/*/ 2>/dev/null" | sed 's|/||g' | awk -F'/' '{print $NF}' | tr -d '\r' || true)
    
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

# Function to download dataset
download_dataset() {
    local dataset_name="$1"
    local device_serial="$2"
    local adb_cmd="adb"
    
    if [ -z "${dataset_name}" ]; then
        print_error "Dataset name not specified."
        echo ""
        echo "Usage: $0 <dataset_name> [device_serial]"
        echo "   or: $0 list [device_serial]  (to list available datasets)"
        echo ""
        exit 1
    fi
    
    if [ "${dataset_name}" = "list" ]; then
        list_datasets "${device_serial}"
        exit 0
    fi
    
    if [ -n "${device_serial}" ]; then
        adb_cmd="adb -s ${device_serial}"
    fi
    
    local device_path="${DEVICE_BASE_PATH}/${dataset_name}"
    local local_path="${OUTPUT_DIR}/${dataset_name}"
    
    # Check if dataset exists on device
    print_info "Checking if dataset exists on device..."
    if ! ${adb_cmd} shell "test -d ${device_path}" 2>/dev/null; then
        print_error "Dataset '${dataset_name}' not found on device at ${device_path}"
        echo ""
        print_info "Available datasets:"
        list_datasets "${device_serial}"
        exit 1
    fi
    
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
        
        # Check if target files already exist in dataset folder (from step01)
        local existing_targets=$(find "${local_path}" -maxdepth 1 -type f \( -name "*.pdf" -o -name "*.yaml" \) 2>/dev/null || true)
        if [ -n "${existing_targets}" ]; then
            print_info "Target files already in dataset folder:"
            echo "${existing_targets}" | while read -r target_file; do
                if [ -n "${target_file}" ] && [ -f "${target_file}" ]; then
                    print_info "  - $(basename "${target_file}")"
                fi
            done
        else
            # Copy target files from root directory to dataset folder if they exist
            print_info "Checking for calibration target files to associate with dataset..."
            local target_files=$(find "${OUTPUT_DIR}" -maxdepth 1 -type f \( -name "*.pdf" -o -name "*.yaml" \) ! -path "*/kalibr_repo/*" ! -path "*/\.*" 2>/dev/null || true)
            
            if [ -n "${target_files}" ]; then
                echo "${target_files}" | while read -r target_file; do
                    if [ -n "${target_file}" ] && [ -f "${target_file}" ]; then
                        local target_name=$(basename "${target_file}")
                        cp "${target_file}" "${local_path}/"
                        print_info "Copied target file to dataset folder: ${target_name}"
                    fi
                done
            else
                print_info "No target files found. Create target with: ./step01_kalibr_target_create.sh ${dataset_name}"
            fi
        fi
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
DATASET_NAME="${1:-}"
download_dataset "${DATASET_NAME}" "${DEVICE_SERIAL}"

