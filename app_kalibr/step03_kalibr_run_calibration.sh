#!/bin/bash

# Run Kalibr calibration workflow
# Usage: ./step03_kalibr_run_calibration.sh <command> <args...>
# Commands: create_bag, calibrate_cam, calibrate_camimu, all

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KALIBR_IMAGE="kalibr"
DATA_MOUNT="/data"

# Check Docker image
if ! docker image inspect "${KALIBR_IMAGE}" &> /dev/null; then
    echo "ERROR: Docker image '${KALIBR_IMAGE}' not found. Run step00_kalibr_setup_docker.sh first."
    exit 1
fi

# Helper function to run script in Docker
run_script_in_docker() {
    local script_file="$1"
    local log_file="$2"
    local mount_dirs="$3"
    
    local script_name=$(basename "${script_file}")
    local script_dir=$(dirname "${script_file}")
    local script_dir_abs=$(realpath "${script_dir}")
    
    xhost +local:root 2>/dev/null || true
    
    # Build docker command
    local docker_cmd=(
        docker run --rm -i
        --entrypoint=""
        -e "DISPLAY"
        -e "QT_X11_NO_MITSHM=1"
        -v "/tmp/.X11-unix:/tmp/.X11-unix:rw"
    )
    
    # Add mounts
    if [[ "${mount_dirs}" == *":"* ]]; then
        for mount in ${mount_dirs}; do
            local host_path=$(echo "${mount}" | cut -d: -f1)
            local container_path=$(echo "${mount}" | cut -d: -f2)
            docker_cmd+=(-v "$(realpath "${host_path}"):${container_path}")
        done
    else
        docker_cmd+=(-v "$(realpath "${mount_dirs}"):${DATA_MOUNT}")
    fi
    
    # Mount script directory and set working directory
    docker_cmd+=(-v "${script_dir_abs}:${DATA_MOUNT}/scripts")
    docker_cmd+=(-w "${DATA_MOUNT}/scripts")
    docker_cmd+=("${KALIBR_IMAGE}")
    docker_cmd+=(bash "${DATA_MOUNT}/scripts/${script_name}")
    
    # Run and tee to log
    "${docker_cmd[@]}" 2>&1 | tee "${log_file}"
    return ${PIPESTATUS[0]}
}

# Create ROS bag from dataset
create_bag() {
    local device_folder="$1"
    local remote_folder_name="$2"
    local output_bag="${3:-${remote_folder_name}.bag}"
    
    if [ -z "${device_folder}" ] || [ -z "${remote_folder_name}" ]; then
        echo "Usage: $0 create_bag <date-device-name> <remote_folder_name> [output_bag_name]"
        exit 1
    fi
    
    local dataset_folder="${SCRIPT_DIR}/${device_folder}/dataset-${remote_folder_name}"
    local result_folder="${SCRIPT_DIR}/${device_folder}/result-${remote_folder_name}"
    
    if [ ! -d "${dataset_folder}" ]; then
        echo "ERROR: Dataset folder not found: ${dataset_folder}"
        exit 1
    fi
    
    # Find the actual data folder (might be nested: dataset-XXX/XXX/)
    local data_folder="${dataset_folder}/${remote_folder_name}"
    if [ ! -d "${data_folder}" ]; then
        # Try to find folder with cam0 or imu0.csv
        data_folder=$(find "${dataset_folder}" -type d -exec test -d {}/cam0 \; -print | head -1)
    fi
    if [ -z "${data_folder}" ] || [ ! -d "${data_folder}" ]; then
        # Fall back to dataset folder itself
        data_folder="${dataset_folder}"
    fi
    
    mkdir -p "${result_folder}"
    
    local script_name="bag_creation_script.sh"
    local log_name="bag_creation.log"
    local script_file="${result_folder}/${script_name}"
    local log_file="${result_folder}/${log_name}"
    
    local data_folder_abs=$(realpath "${data_folder}")
    
    cat > "${script_file}" << EOF
#!/bin/bash
set -e
cd "${DATA_MOUNT}/scripts"

echo "=== Bag Creation ==="
echo "Date: \$(date)"
echo "Dataset folder: ${DATA_MOUNT}/dataset"
echo ""

source /catkin_ws/devel/setup.bash

echo "Listing dataset contents..."
ls -la ${DATA_MOUNT}/dataset/
echo ""

echo "Creating ROS bag..."
rosrun kalibr kalibr_bagcreater --folder ${DATA_MOUNT}/dataset --output-bag ${DATA_MOUNT}/scripts/${output_bag}

echo ""
echo "Bag info:"
rosbag info ${DATA_MOUNT}/scripts/${output_bag}
EOF

    chmod +x "${script_file}"
    run_script_in_docker "${script_file}" "${log_file}" "${data_folder_abs}:${DATA_MOUNT}/dataset ${result_folder}:${DATA_MOUNT}/scripts"
    
    if [ ! -f "${result_folder}/${output_bag}" ]; then
        echo "ERROR: Bag file was not created"
        exit 1
    fi
    
    echo "SUCCESS: Created ${output_bag} in ${result_folder}"
}

# Calibrate cameras
calibrate_cam() {
    local device_folder="$1"
    local remote_folder_name="$2"
    local models="${3:-pinhole-radtan}"
    local topics="${4:-/cam0/image_raw}"
    local bag_freq="${5:-10.0}"
    
    if [ -z "${device_folder}" ] || [ -z "${remote_folder_name}" ]; then
        echo "Usage: $0 calibrate_cam <date-device-name> <remote_folder_name> [models] [topics] [bag_freq]"
        exit 1
    fi
    
    local result_folder="${SCRIPT_DIR}/${device_folder}/result-${remote_folder_name}"
    local bag_file="${result_folder}/${remote_folder_name}.bag"
    local target_yaml="${SCRIPT_DIR}/${device_folder}/target.yaml"
    
    if [ ! -f "${target_yaml}" ]; then
        target_yaml=$(find "${SCRIPT_DIR}/${device_folder}" -maxdepth 1 -name "*.yaml" ! -name "camchain*" ! -name "imu*" | head -1)
        if [ ! -f "${target_yaml}" ]; then
            echo "ERROR: Target YAML not found"
            exit 1
        fi
    fi
    
    if [ ! -f "${bag_file}" ]; then
        echo "ERROR: Bag file not found: ${bag_file}"
        exit 1
    fi
    
    local script_name="camera_calibration_script.sh"
    local log_name="camera_calibration.log"
    local script_file="${result_folder}/${script_name}"
    local log_file="${result_folder}/${log_name}"
    
    local bag_dir=$(dirname "$(realpath "${bag_file}")")
    local bag_name=$(basename "${bag_file}")
    local target_dir=$(dirname "$(realpath "${target_yaml}")")
    local target_name=$(basename "${target_yaml}")
    
    cat > "${script_file}" << EOF
#!/bin/bash
set -e
cd "${DATA_MOUNT}/output"

echo "=== Camera Calibration ==="
echo "Date: \$(date)"
echo ""

source /catkin_ws/devel/setup.bash

export MPLBACKEND=Agg

echo "Running camera calibration..."
rosrun kalibr kalibr_calibrate_cameras \
    --bag ${DATA_MOUNT}/bag/${bag_name} \
    --target ${DATA_MOUNT}/target/${target_name} \
    --models ${models} \
    --topics ${topics} \
    --bag-freq ${bag_freq} \
    --dont-show-report

mv camchain-*.yaml ${DATA_MOUNT}/output/ 2>/dev/null || true
mv results-cam-*.txt ${DATA_MOUNT}/output/ 2>/dev/null || true
EOF

    chmod +x "${script_file}"
    run_script_in_docker "${script_file}" "${log_file}" "${bag_dir}:${DATA_MOUNT}/bag ${target_dir}:${DATA_MOUNT}/target ${result_folder}:${DATA_MOUNT}/output"
    
    echo "SUCCESS: Camera calibration completed. Check ${result_folder}"
}

# Calibrate camera-IMU
calibrate_camimu() {
    local device_folder="$1"
    local remote_folder_name="$2"
    local imu_yaml="${3:-${SCRIPT_DIR}/../app_device/config/kalibr_imu_chain.yaml}"
    
    if [ -z "${device_folder}" ] || [ -z "${remote_folder_name}" ]; then
        echo "Usage: $0 calibrate_camimu <date-device-name> <remote_folder_name> [imu_yaml]"
        exit 1
    fi
    
    local result_folder="${SCRIPT_DIR}/${device_folder}/result-${remote_folder_name}"
    local bag_file="${result_folder}/${remote_folder_name}.bag"
    local target_yaml="${SCRIPT_DIR}/${device_folder}/target.yaml"
    local camchain_yaml=$(find "${result_folder}" -name "*-camchain.yaml" -o -name "camchain-*.yaml" | head -1)
    
    if [ ! -f "${target_yaml}" ]; then
        target_yaml=$(find "${SCRIPT_DIR}/${device_folder}" -maxdepth 1 -name "*.yaml" ! -name "*camchain*" ! -name "*imu*" | head -1)
    fi
    
    if [ ! -f "${bag_file}" ]; then
        echo "ERROR: Bag file not found: ${bag_file}"
        exit 1
    fi
    if [ ! -f "${target_yaml}" ]; then
        echo "ERROR: Target YAML not found: ${target_yaml}"
        exit 1
    fi
    if [ ! -f "${camchain_yaml}" ]; then
        echo "ERROR: Camchain YAML not found in ${result_folder}"
        echo "Looking for files matching *-camchain.yaml or camchain-*.yaml"
        exit 1
    fi
    if [ ! -f "${imu_yaml}" ]; then
        echo "IMU YAML not found, creating default: ${imu_yaml}"
        mkdir -p "$(dirname "${imu_yaml}")"
        cat > "${imu_yaml}" << 'IMU_EOF'
rostopic: /imu0
update_rate: 200.0 #Hz

accelerometer_noise_density: 2.0000e-3  # [ m / s^2 / sqrt(Hz) ]   ( accel "white noise" )
accelerometer_random_walk: 3.0000e-3    # [ m / s^3 / sqrt(Hz) ].  ( accel bias diffusion )
gyroscope_noise_density: 1.6968e-04     # [ rad / s / sqrt(Hz) ]   ( gyro "white noise" )
gyroscope_random_walk: 1.9393e-05       # [ rad / s^2 / sqrt(Hz) ] ( gyro bias diffusion )
IMU_EOF
        echo "Created default IMU YAML at ${imu_yaml}"
    fi
    
    local script_name="camera_imu_calibration_script.sh"
    local log_name="camera_imu_calibration.log"
    local script_file="${result_folder}/${script_name}"
    local log_file="${result_folder}/${log_name}"
    
    local bag_dir=$(dirname "$(realpath "${bag_file}")")
    local bag_name=$(basename "${bag_file}")
    local target_dir=$(dirname "$(realpath "${target_yaml}")")
    local target_name=$(basename "${target_yaml}")
    local camchain_dir=$(dirname "$(realpath "${camchain_yaml}")")
    local camchain_name=$(basename "${camchain_yaml}")
    local imu_dir=$(dirname "$(realpath "${imu_yaml}")")
    local imu_name=$(basename "${imu_yaml}")
    
    cat > "${script_file}" << EOF
#!/bin/bash
set -e
cd "${DATA_MOUNT}/output"

echo "=== Camera-IMU Calibration ==="
echo "Date: \$(date)"
echo ""

source /catkin_ws/devel/setup.bash

export MPLBACKEND=Agg

echo "Running camera-IMU calibration..."
rosrun kalibr kalibr_calibrate_imu_camera \
    --target ${DATA_MOUNT}/target/${target_name} \
    --imu ${DATA_MOUNT}/imu/${imu_name} \
    --imu-models calibrated \
    --cam ${DATA_MOUNT}/camchain/${camchain_name} \
    --bag ${DATA_MOUNT}/bag/${bag_name} \
    --dont-show-report

mv imu-camchain.yaml ${DATA_MOUNT}/output/ 2>/dev/null || true
mv results-imu-cam-*.txt ${DATA_MOUNT}/output/ 2>/dev/null || true
EOF

    chmod +x "${script_file}"
    run_script_in_docker "${script_file}" "${log_file}" "${bag_dir}:${DATA_MOUNT}/bag ${target_dir}:${DATA_MOUNT}/target ${camchain_dir}:${DATA_MOUNT}/camchain ${imu_dir}:${DATA_MOUNT}/imu ${result_folder}:${DATA_MOUNT}/output"
    
    echo "SUCCESS: Camera-IMU calibration completed. Check ${result_folder}"
}

# Run all steps interactively
run_all() {
    echo "Enter device folder name (e.g., 2021-01-19-pixel6):"
    read -r device_folder
    echo "Enter remote folder name (e.g., 2021-01-19_14-14-47):"
    read -r remote_folder_name
    
    create_bag "${device_folder}" "${remote_folder_name}"
    calibrate_cam "${device_folder}" "${remote_folder_name}"
    calibrate_camimu "${device_folder}" "${remote_folder_name}"
}

# Main
command="${1:-}"
case "${command}" in
    create_bag)
        shift
        create_bag "$@"
        ;;
    calibrate_cam)
        shift
        calibrate_cam "$@"
        ;;
    calibrate_camimu)
        shift
        calibrate_camimu "$@"
        ;;
    all)
        shift
        run_all "$@"
        ;;
    *)
        echo "Usage: $0 <command> [args...]"
        echo "Commands: create_bag, calibrate_cam, calibrate_camimu, all"
        exit 1
        ;;
esac
