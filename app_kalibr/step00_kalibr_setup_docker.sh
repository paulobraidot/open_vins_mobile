#!/bin/bash

# Script to download Kalibr toolbox and build Docker container
# Usage: ./setup_kalibr_docker.sh [KALIBR_DIR] [UBUNTU_VERSION]
#
# This script will:
#   1. Check for Docker installation
#   2. Clone the Kalibr repository (if needed)
#   3. Build the Docker image for Kalibr

set -e  # Exit on error

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Default values - kalibr repo goes into app_kalibr/kalibr_repo/
KALIBR_DIR="${1:-${SCRIPT_DIR}/kalibr_repo}"
UBUNTU_VERSION="${2:-20.04}"
DOCKERFILE="Dockerfile_ros1_${UBUNTU_VERSION//./_}"

echo "=========================================="
echo "Kalibr Docker Setup Script"
echo "=========================================="
echo "Project root: ${PROJECT_ROOT}"
echo "Kalibr directory: ${KALIBR_DIR}"
echo "Ubuntu version: ${UBUNTU_VERSION}"
echo "Dockerfile: ${DOCKERFILE}"
echo "=========================================="
echo ""

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker is not installed. Please install Docker first."
    echo "Visit: https://docs.docker.com/get-docker/"
    exit 1
fi

# Check if Docker daemon is running
if ! docker info &> /dev/null; then
    echo "ERROR: Docker daemon is not running. Please start Docker first."
    exit 1
fi

# Create kalibr directory if it doesn't exist
if [ -d "${KALIBR_DIR}" ]; then
    echo "Directory ${KALIBR_DIR} already exists."
    read -p "Do you want to remove it and clone fresh? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Removing existing directory..."
        rm -rf "${KALIBR_DIR}"
    else
        echo "Using existing directory. Skipping clone."
        SKIP_CLONE=true
    fi
fi

# Clone Kalibr repository if needed
if [ "${SKIP_CLONE}" != "true" ]; then
    echo "Cloning Kalibr repository..."
    echo "Command: git clone https://github.com/ethz-asl/kalibr.git \"${KALIBR_DIR}\""
    git clone https://github.com/ethz-asl/kalibr.git "${KALIBR_DIR}"
fi

# Check if Dockerfile exists
cd "${KALIBR_DIR}"
if [ ! -f "${DOCKERFILE}" ]; then
    echo "ERROR: Dockerfile ${DOCKERFILE} not found in ${KALIBR_DIR}"
    echo "Available Dockerfiles:"
    ls -1 Dockerfile_ros1_* 2>/dev/null || echo "  (none found)"
    exit 1
fi

# Build Docker image
echo ""
echo "Building Docker image 'kalibr'..."
echo "This may take a while (10-30 minutes depending on your system)..."
echo "Command: docker build -t kalibr -f \"${DOCKERFILE}\" ."
echo "Working directory: $(pwd)"
echo ""
docker build -t kalibr -f "${DOCKERFILE}" .

echo ""
echo "=========================================="
echo "Kalibr Docker image built successfully!"
echo "=========================================="
echo "Image name: kalibr"
echo ""
echo "Next steps:"
echo "  1. Create calibration target: ./app_kalibr/step01_kalibr_target_create.sh"
echo "  2. Download dataset from device: ./app_kalibr/step02_android_offload_last_dataset.sh"
echo "  3. Run calibration: ./app_kalibr/step03_kalibr_run_calibration.sh"
echo ""

