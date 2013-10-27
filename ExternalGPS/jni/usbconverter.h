/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#ifndef _USBCONVERTER_H
#define _USBCONVERTER_H

#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <time64.h>

#include "jni.h"

// $PUBX,00 ~ 120 bytes
#define NMEA_MAX 512
#define SIRF_MAX 1023
#define UBLOX_MAX 4096

#define LOOKS_NOT_LIKE_GPS_MSG 0
#define LOOKS_LIKE_TRUNCATED_MSG -1

struct location_t {
  long long time;

  double    latitude;
  double    longitude;
  double    altitude;

  float     accuracy;
  float     bearing;
  float     speed;

  /* - the number of satellites used to derive the fix */
  int satellites;

  bool is_valid;
  bool has_accuracy;
  bool has_altitude;
  bool has_bearing;
  bool has_speed;
};

struct nmea_fix_time_t {
  unsigned hhmmss;
  unsigned mss;
};

/* GPGGA Global Positioning System Fix Data */
struct nmea_gpgga_t {
  struct nmea_fix_time_t fix_time;
  unsigned fix_quality;     /* Fix Quality. 0 - invalid, 1 - GPS, 2 - DGPS, 6 - Estimated/DR */
  double latitude;
  double longitude;
  double altitude;
  int sattelites_nb;   /* Number of satellites in use (not those in view) */
  float hdop;
  double geoid_height;

  bool has_latitude;
  bool has_longitude;
  bool has_altitude;
  bool has_sattelites_nb;
  bool has_hdop;
  bool has_geoid_height;
};

/* GPRMC Recommended minimum specific GPS/Transit data  */
struct nmea_gprmc_t {
  struct nmea_fix_time_t fix_time;
  double latitude;
  double longitude;
  unsigned ddmmyy;    /* Date of fix */
  bool status_active;  /* GPRMC Status true - active (A), false - void (V) */
  float speed;         /* Speed over ground, m/s */
  float course;        /* True course */

  bool has_latitude;
  bool has_longitude;
  bool has_ddmmyy;
  bool has_status_active;
  bool has_speed;
  bool has_course;
};

/* GPVTG Track Made Good and Ground Speed */
struct nmea_gpvtg_t {
  float course_true;
  float course_magn;
  float speed_knots;
  float speed_kmph;
  int fix_mode;     /* 'A' - autonomous, D - differential, E - estimated, N - not valid, 0 - undefiend */

  bool has_course_true;
  bool has_course_magn;
  bool has_speed_knots;
  bool has_speed_kmph;

  bool is_valid;
};

/* GPGLL Geographic Position, Latitude / Longitude and time.  */
struct nmea_gpgll_t {
  struct nmea_fix_time_t fix_time;
  double latitude;
  double longitude;
  bool status;       /* Status true - active (A), false - void (V) */

  bool has_latitude;
  bool has_longitude;
};

struct nmea_gpgsa_t {
  int fix_mode;     /* 'M' - manual, 'A' - automatic, 0 - undefiend */
  int fix_type;     /* -1 - undefined, 1 - Fix not available, 2 - 2D, 3 - 3D */
  unsigned prn[12]; /* PRN's of Satellite Vechicles. 0 - unused */
  float pdop;
  float hdop;
  float vdop;

  bool has_pdop;
  bool has_hdop;
  bool has_vdop;

  bool is_valid;
};

/* GNSS Pseudo Range Error Statistics */
struct nmea_gpgst_t {
  struct nmea_fix_time_t fix_time;
  float range_rms;
  float std_major;
  float std_minor;
  float orient;
  float std_lat;       /* Standard deviation of latitude, error in meters */
  float std_lon;       /* Standard deviation of longitude, error in meters */
  float std_alt;       /* Standard deviation of altitude, error in meters */

  bool has_range_rms;
  bool has_std_major;
  bool has_std_minor;
  bool has_orient;
  bool has_std_lat;
  bool has_std_lon;
  bool has_std_alt;
};

struct nmea_gpzda_t {
  struct   nmea_fix_time_t fix_time; /* Current epoch time (hhmmss mss, UTC) */
  unsigned day;         /* UTC day (01 to 31). 0 - undefined */
  unsigned month;       /* UTC month( 01 to 12). 0 - undefined */
  unsigned year;        /* UTC year (4 digit format). 0 - undefined */
  int      zone_hours;  /* Offset to local time zone in hours (+- 13).*/
  unsigned zone_minutes; /* Offset to local time zone in minutes (00 to 59) */
};

struct nmea_fix_t {
  /* Current NMEA fix */
  struct nmea_fix_time_t fix_time; /* Current epoch time (hhmmss mss, UTC) */

  bool is_closed;

  bool gpgga_active;
  bool gprmc_active;
  bool gpgll_active;
  bool gpgst_active;
  bool gpzda_active;

  struct nmea_gpgga_t gpgga;
  struct nmea_gprmc_t gprmc;
  struct nmea_gpgll_t gpgll;
  struct nmea_gpgst_t gpgst;
  struct nmea_gpzda_t gpzda;
};

struct nmea_parser_t {
  struct tm time_full;        /* Curent time and date, UTC */
  struct nmea_fix_t fix;

  struct nmea_gpgsa_t gpgsa;
  struct nmea_gpvtg_t gpvtg;

  struct stats_t *stats;
};

struct sirf_parser_t {
  struct stats_t *stats;
};

struct gps_msg_status_t  {
  bool is_valid;
  bool location_changed;
  struct location_t location;
  char err[200];
};

struct stats_t {
  pthread_mutex_t mtx;

  struct timespec start_ts;

  struct {
    unsigned long long bytes;
    unsigned long long junk;

    struct timespec last_byte_ts;

    struct {
      unsigned total;
      unsigned gga;
      unsigned rmc;
      unsigned gll;
      unsigned gst;
      unsigned gsa;
      unsigned vtg;
      unsigned zda;
      unsigned gsv;
      unsigned pubx;
      unsigned other;

      struct timespec last_msg_ts;
    } nmea;

    struct {
      unsigned total;
      unsigned mid41;
      struct timespec last_msg_ts;
    } sirf;

    struct {
      unsigned total;
      struct timespec last_msg_ts;
    } ublox;

  } rcvd;
};

struct gps_msg_metadata_t {
  enum {
    MSG_TYPE_NMEA = 0,
    MSG_TYPE_SIRF = 1,
    MSG_TYPE_UBLOX = 2
  } type;
  size_t size;
  bool is_truncated;
};

struct datalogger_t;

/* usbconverter.c */
int register_usb_converter_natives(JNIEnv* env);

/* nmea.c */
int looks_like_nmea(const uint8_t *msg, size_t max_len);
void reset_nmea_parser(struct nmea_parser_t *ctx);
bool put_nmea_msg(struct nmea_parser_t *ctx, const uint8_t *msg, size_t msg_size, struct gps_msg_status_t *res);
void put_nmea_timedout(struct nmea_parser_t *ctx, struct gps_msg_status_t *status);

/* sirf.c */
int looks_like_sirf(const uint8_t *msg, size_t max_len);
void reset_sirf_parser(struct sirf_parser_t *ctx);
bool put_sirf_msg(struct sirf_parser_t *ctx, const uint8_t *msg, size_t msg_size, struct gps_msg_status_t *res);

/* ublox.c */
int looks_like_ublox(const uint8_t *msg, size_t max_len);

/* stats.c */
void stats_init(struct stats_t *stats);
void stats_destroy(struct stats_t *stats);
void stats_lock(struct stats_t *stats);
void stats_unlock(struct stats_t *stats);
void stats_reset_unlocked(struct stats_t *stats);
void stats_start_unlocked(struct stats_t *stats);
void stats_export_to_java(JNIEnv *env, struct stats_t *stats, jobject j_dst);

#endif /* _USBCONVERTER_H  */
