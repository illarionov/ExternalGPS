
#include <android/log.h>
#include <jni.h>
#include <strings.h>

#include "usbconverter.h"

#define TAG "native"
#if DEBUG
#define LOGV(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    (void)reserved;

    LOGV("Entering JNI_OnLoad");

    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK)
        goto bail;

    if (!register_usb_converter_natives(env))
        goto bail;

    /* success -- return valid version number */
    result = JNI_VERSION_1_6;

bail:
    LOGV("Leaving JNI_OnLoad (result=0x%x)", result);
    return result;
}

