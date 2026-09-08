#ifndef CPU_AFFINITY_H
#define CPU_AFFINITY_H

#include <vector>
#include <thread>
#include <sched.h>
#include <unistd.h>
#include <fstream>
#include <string>

namespace stt {

/**
 * CPU affinity utilities for big.LITTLE SoCs.
 * 
 * On ARM big.LITTLE architectures (most Android devices):
 * - Little cores (0-3): Power efficient, slower
 * - Big cores (4-7): High performance, more power
 * 
 * For audio processing, we want big cores for lower latency.
 */
class CpuAffinity {
public:
    /**
     * Pin current thread to big (performance) cores.
     * On typical 8-core big.LITTLE: cores 4-7
     * 
     * @return true if successful
     */
    static bool pinToBigCores() {
        auto bigCores = detectBigCores();
        if (bigCores.empty()) {
            // Fallback: use upper half of cores
            int numCores = getNumCores();
            for (int i = numCores / 2; i < numCores; ++i) {
                bigCores.push_back(i);
            }
        }
        return pinToCores(bigCores);
    }
    
    /**
     * Pin current thread to little (efficiency) cores.
     * 
     * @return true if successful
     */
    static bool pinToLittleCores() {
        auto littleCores = detectLittleCores();
        if (littleCores.empty()) {
            // Fallback: use lower half of cores
            int numCores = getNumCores();
            for (int i = 0; i < numCores / 2; ++i) {
                littleCores.push_back(i);
            }
        }
        return pinToCores(littleCores);
    }
    
    /**
     * Pin current thread to specific cores.
     * 
     * @param cores Vector of core indices
     * @return true if successful
     */
    static bool pinToCores(const std::vector<int>& cores) {
        if (cores.empty()) return false;
        
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        
        for (int core : cores) {
            if (core >= 0 && core < CPU_SETSIZE) {
                CPU_SET(core, &cpuset);
            }
        }
        
        return sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0;
    }
    
    /**
     * Reset affinity to all cores (system default).
     */
    static bool resetAffinity() {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        
        int numCores = getNumCores();
        for (int i = 0; i < numCores; ++i) {
            CPU_SET(i, &cpuset);
        }
        
        return sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0;
    }
    
    /**
     * Get number of CPU cores.
     */
    static int getNumCores() {
        return static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    }
    
    /**
     * Detect big cores by reading max frequency.
     * Cores with higher max frequency are "big" cores.
     */
    static std::vector<int> detectBigCores() {
        std::vector<int> bigCores;
        int numCores = getNumCores();
        
        // Read max frequencies
        std::vector<long> maxFreqs(numCores, 0);
        long highestFreq = 0;
        
        for (int i = 0; i < numCores; ++i) {
            std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + 
                              "/cpufreq/cpuinfo_max_freq";
            std::ifstream file(path);
            if (file.is_open()) {
                file >> maxFreqs[i];
                if (maxFreqs[i] > highestFreq) {
                    highestFreq = maxFreqs[i];
                }
            }
        }
        
        // Big cores have the highest frequency
        if (highestFreq > 0) {
            for (int i = 0; i < numCores; ++i) {
                // Within 10% of highest is considered "big"
                if (maxFreqs[i] >= highestFreq * 0.9) {
                    bigCores.push_back(i);
                }
            }
        }
        
        return bigCores;
    }
    
    /**
     * Detect little cores (efficiency cores).
     */
    static std::vector<int> detectLittleCores() {
        std::vector<int> littleCores;
        int numCores = getNumCores();
        auto bigCores = detectBigCores();
        
        for (int i = 0; i < numCores; ++i) {
            bool isBig = false;
            for (int big : bigCores) {
                if (big == i) {
                    isBig = true;
                    break;
                }
            }
            if (!isBig) {
                littleCores.push_back(i);
            }
        }
        
        return littleCores;
    }
    
    /**
     * Get current thread's affinity mask.
     */
    static std::vector<int> getCurrentAffinity() {
        std::vector<int> cores;
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        
        if (sched_getaffinity(0, sizeof(cpu_set_t), &cpuset) == 0) {
            int numCores = getNumCores();
            for (int i = 0; i < numCores; ++i) {
                if (CPU_ISSET(i, &cpuset)) {
                    cores.push_back(i);
                }
            }
        }
        
        return cores;
    }
};

/**
 * RAII guard for temporary CPU affinity change.
 */
class ScopedCpuAffinity {
public:
    explicit ScopedCpuAffinity(const std::vector<int>& cores) {
        originalCores_ = CpuAffinity::getCurrentAffinity();
        CpuAffinity::pinToCores(cores);
    }
    
    ~ScopedCpuAffinity() {
        if (!originalCores_.empty()) {
            CpuAffinity::pinToCores(originalCores_);
        }
    }
    
    // Non-copyable
    ScopedCpuAffinity(const ScopedCpuAffinity&) = delete;
    ScopedCpuAffinity& operator=(const ScopedCpuAffinity&) = delete;

private:
    std::vector<int> originalCores_;
};

} // namespace stt

#endif // CPU_AFFINITY_H

