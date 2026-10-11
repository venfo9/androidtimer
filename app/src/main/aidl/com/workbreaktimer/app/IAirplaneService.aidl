package com.workbreaktimer.app;

interface IAirplaneService {

    // Transaction code Shizuku itself uses to stop a user service.
    void destroy() = 16777114;

    // Returns null when the command succeeded, otherwise a readable reason.
    String setAirplaneMode(boolean enabled) = 1;
}
