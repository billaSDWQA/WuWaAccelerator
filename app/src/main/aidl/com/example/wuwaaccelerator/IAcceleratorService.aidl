package com.example.wuwaaccelerator;

interface IAcceleratorService {
    boolean startMonitoring();
    boolean stopAndRestore();
    String getStatus();

    // Reserved transaction used by the Shizuku server when destroying UserService.
    void destroy() = 16777114;
}
