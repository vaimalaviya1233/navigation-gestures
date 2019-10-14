package com.xda.nobar;

interface IActionsBinder {
    void addBar();
    void addBlackout();
    void remBar();
    void remBlackout();
    void addImmersiveHelper();
    void removeImmersiveHelper();
    void sendAction(int action);
    void addBarAndBlackout();
    void remBarAndBlackout();
}