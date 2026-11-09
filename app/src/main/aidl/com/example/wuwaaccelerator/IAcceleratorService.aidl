package com.example.wuwaaccelerator;

interface IAcceleratorService {
    boolean startMonitoring() = 1;
    boolean stopAndRestore() = 2;
    String getStatus() = 3;
    String getTelemetrySnapshot() = 4;

    // Shizuku UserService destroy transaction (AIDL-side code).
    void destroy() = 16777114;
}
