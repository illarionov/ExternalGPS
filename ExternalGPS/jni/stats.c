/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"

#define TAG "nativeStats"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

void stats_init(struct stats_t *stats)
{
  memset(&stats, 0, sizeof(stats));
  pthread_mutex_init(&stats->mtx, NULL);
}

void stats_destroy(struct stats_t *stats)
{
  pthread_mutex_destroy(&stats->mtx);
}

void stats_lock(struct stats_t *stats)
{
  pthread_mutex_lock(&stats->mtx);
}

void stats_unlock(struct stats_t *stats)
{
  pthread_mutex_unlock(&stats->mtx);
}

void stats_reset_unlocked(struct stats_t *stats)
{
  memset(&stats->rcvd, 0, sizeof(stats->rcvd));
  stats->start_ts.tv_sec = 0;
  stats->start_ts.tv_nsec = 0;
}


void stats_start_unlocked(struct stats_t *stats)
{
  stats_reset_unlocked(stats);
  clock_gettime(CLOCK_MONOTONIC, &stats->start_ts);
}

static inline jlong timespec2java_ts(struct timespec ts)
{
  return (jlong)1000 * ts.tv_sec + ts.tv_nsec / 1000000;
}

void stats_export_to_java(JNIEnv *env, struct stats_t *stats, jobject j_dst)
{
  static jmethodID method_set_stats;
  static jmethodID method_set_nmea_stats;
  static jmethodID method_set_sirf_stats;
  static jmethodID method_set_ublox_stats;

 if (method_set_stats == NULL) {
    jclass class_stats_native;

    class_stats_native = (*env)->GetObjectClass(env, j_dst);

    method_set_stats = (*env)->GetMethodID(env,
        class_stats_native, "setStats", "(JJJJ)V");
    if (method_set_stats == NULL)
      return;
    method_set_nmea_stats = (*env)->GetMethodID(env,
        class_stats_native, "setNmeaStats", "(JJJJJJJJJJJJ)V");
    if (method_set_nmea_stats == NULL)
      return;
    method_set_sirf_stats = (*env)->GetMethodID(env,
        class_stats_native, "setSirfStats", "(JJJ)V");
    if (method_set_sirf_stats == NULL)
      return;
    method_set_ublox_stats = (*env)->GetMethodID(env,
        class_stats_native, "setUbloxStats", "(JJ)V");
    if (method_set_ublox_stats == NULL)
      return;
  }

  stats_lock(stats);

  (*env)->CallVoidMethod(env, j_dst, method_set_stats,
      timespec2java_ts(stats->start_ts),
      timespec2java_ts(stats->rcvd.last_byte_ts),
      (jlong)stats->rcvd.bytes,
      (jlong)stats->rcvd.junk
      );
  if ((*env)->ExceptionOccurred(env))
    goto stats_to_java_return;

  (*env)->CallVoidMethod(env, j_dst, method_set_nmea_stats,
      timespec2java_ts(stats->rcvd.nmea.last_msg_ts),
      (jlong)stats->rcvd.nmea.total,
      (jlong)stats->rcvd.nmea.gga,
      (jlong)stats->rcvd.nmea.rmc,
      (jlong)stats->rcvd.nmea.gll,
      (jlong)stats->rcvd.nmea.gst,
      (jlong)stats->rcvd.nmea.gsa,
      (jlong)stats->rcvd.nmea.vtg,
      (jlong)stats->rcvd.nmea.zda,
      (jlong)stats->rcvd.nmea.gsv,
      (jlong)stats->rcvd.nmea.pubx,
      (jlong)stats->rcvd.nmea.other
      );
  if ((*env)->ExceptionOccurred(env))
    goto stats_to_java_return;

  (*env)->CallVoidMethod(env, j_dst, method_set_sirf_stats,
      timespec2java_ts(stats->rcvd.sirf.last_msg_ts),
      (jlong)stats->rcvd.sirf.total,
      (jlong)stats->rcvd.sirf.mid41
      );
  if ((*env)->ExceptionOccurred(env))
    goto stats_to_java_return;

  (*env)->CallVoidMethod(env, j_dst, method_set_ublox_stats,
      timespec2java_ts(stats->rcvd.ublox.last_msg_ts),
      (jlong)stats->rcvd.ublox.total);
  if ((*env)->ExceptionOccurred(env))
    goto stats_to_java_return;

stats_to_java_return:
  stats_unlock(stats);
}

