#!/bin/bash

# Script to sync app_device files to Android device
# Usage: ./sync_device.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DEVICE_DIR="${SCRIPT_DIR}"
DEVICE_PATH="/storage/emulated/0/Documents/openvins"

# Check if we're in the app_device directory (should have config subdirectory)
if [ ! -d "${APP_DEVICE_DIR}/config" ]; then
    echo "Error: config directory not found. This script should be run from app_device directory."
    exit 1
fi

# Find adb in common locations
ADB=""
if command -v adb &> /dev/null; then
    ADB="adb"
elif [ -n "${ANDROID_HOME}" ]; then
    ADB="${ANDROID_HOME}/platform-tools/adb"
elif [ -n "${ANDROID_SDK_ROOT}" ]; then
    ADB="${ANDROID_SDK_ROOT}/platform-tools/adb"
else
    echo "Error: adb not found. Please ensure Android SDK platform-tools is in PATH or set ANDROID_HOME/ANDROID_SDK_ROOT"
    exit 1
fi

# Check if device is connected
echo "Checking for connected devices..."
DEVICES=$(${ADB} devices | grep -v "List" | grep "device$" | wc -l)
if [ "${DEVICES}" -eq 0 ]; then
    echo "Error: No Android device connected. Please connect a device and enable USB debugging."
    exit 1
fi

echo "Found ${DEVICES} device(s)"
echo "Syncing ${APP_DEVICE_DIR} to ${DEVICE_PATH} on device..."

# Create the target directory on device
${ADB} shell mkdir -p "${DEVICE_PATH}"

# Push all files from app_device to the device
# We need to push the contents, not the directory itself
find "${APP_DEVICE_DIR}" -type f | while read -r file; do
    # Get relative path from app_device directory
    # Remove the app_device directory prefix
    rel_path="${file#${APP_DEVICE_DIR}/}"
    target_dir="${DEVICE_PATH}/$(dirname "${rel_path}")"
    
    # Normalize target_dir (remove double slashes, etc.)
    target_dir=$(echo "${target_dir}" | sed 's|//|/|g')
    
    # Create target directory on device
    ${ADB} shell mkdir -p "${target_dir}"
    
    # Push the file
    ${ADB} push "${file}" "${target_dir}/"
done

echo "Successfully synced files to device!"
echo "Files are now available at: ${DEVICE_PATH}"

