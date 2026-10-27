LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

# API28 USB backend is part of the reproducible native build, not a post-build transplant.
include $(CLEAR_VARS)
LOCAL_MODULE := op_usb_bulk
LOCAL_SRC_FILES := ../cpp/op_usb_bulk.c
include $(BUILD_SHARED_LIBRARY)
