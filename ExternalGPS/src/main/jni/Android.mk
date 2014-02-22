LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := usbconverter
LOCAL_CFLAGS += -fvisibility=hidden -W -Wall -D_POSIX_C_SOURCE=200112L
LOCAL_LDLIBS += -llog

LOCAL_SRC_FILES := \
        datalogger.c \
        nmea.c \
        onload.c \
        sirf.c \
        stats.c \
        ublox.c \
        usbreader.c \
        usbconverter.c \

include $(BUILD_SHARED_LIBRARY)
