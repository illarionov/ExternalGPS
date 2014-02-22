/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>

#include <linux/usbdevice_fs.h>
#include <asm/byteorder.h>

#include <jni.h>
#include <android/log.h>

#include "usbreader.h"

#define READ_TIMEOUT_MS 1100
#define DEFAULT_CYCLE_US 16666 /* (192 * (1+8+0+1) * 1000000ll / 115200) */

#define USB_IOCTL_WATERMARK 64

#define MIN(a, b) ((a)<(b)?(a):(b))

#define TAG "NativeUsbReader"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_VERBOSE,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

static const JavaVMAttachArgs USB_READER_THREAD_ATTACH_ARGS = {
  JNI_VERSION_1_2,
  "NativeUsbReader\0",
  NULL
};

static unsigned share(struct usb_reader_thread_ctx_t * ctx,
    uint8_t *buf, unsigned rxbuf_pos);
static void usb_reader_cleanup(void *arg);
static inline void sleep_cycle(struct usb_reader_thread_ctx_t *ctx);

void usb_reader_init(struct usb_reader_thread_ctx_t *ctx,
        JavaVM *jvm,
        int fd, int endpoint, int max_pkt_size)
{
  ctx->jvm = jvm;
  ctx->fd = fd;
  ctx->endpoint = endpoint;
  ctx->max_pkt_size = max_pkt_size;

  ctx->cycle_us = DEFAULT_CYCLE_US;
  ctx->fast_cycle = true;

  pthread_mutex_init(&ctx->mtx, NULL);
  pthread_cond_init(&ctx->data_available_cond, NULL);
  ctx->shared_rxbuf_pos = 0;
  ctx->is_running = true; // XXX
  ctx->last_event_errno = 0;
}

void usb_reader_destroy(struct usb_reader_thread_ctx_t *ctx)
{
  pthread_mutex_destroy(&ctx->mtx);
  pthread_cond_destroy(&ctx->data_available_cond);
}

void *usb_reader_thread(void *arg)
{
  int rcvd;
  int last_event_errno;
  struct usb_reader_thread_ctx_t *ctx;
  struct usbdevfs_bulktransfer ctrl;
  JavaVMAttachArgs attachArgs;
  unsigned rxbuf_pos;
  unsigned req_len;
  uint8_t rx_buf[USB_READER_BUF_SIZE];

  ctx = (struct usb_reader_thread_ctx_t *)arg;
  attachArgs = USB_READER_THREAD_ATTACH_ARGS;
  (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &ctx->jniEnv, &attachArgs);
  pthread_cleanup_push(&usb_reader_cleanup, ctx);

  pthread_mutex_lock(&ctx->mtx);
  ctx->is_running = true;
  ctx->cycle_us = DEFAULT_CYCLE_US;
  ctx->fast_cycle = true;
  pthread_mutex_unlock(&ctx->mtx);

  LOGV("istream_fd: %i, endpoint: 0x%x, max_pkt_size: %i", ctx->fd,
      ctx->endpoint, ctx->max_pkt_size);

  rxbuf_pos = 0;
  for (;;) {
    sleep_cycle(ctx);

    req_len = MIN(ctx->max_pkt_size, (int)(sizeof(rx_buf)-rxbuf_pos));

    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.ep = ctx->endpoint;
    ctrl.len = req_len;
    ctrl.data = &rx_buf[rxbuf_pos];
    ctrl.timeout = READ_TIMEOUT_MS;

    rcvd = ioctl(ctx->fd, USBDEVFS_BULK, &ctrl);
    last_event_errno = errno;
    //LOGV("rcvd %i pos %i req_len %i", rcvd, rxbuf_pos, req_len);
    if (rcvd < 0) {
      if (last_event_errno == ETIMEDOUT) {
        LOGV("usb read timeout");
        continue;
      }else {
        pthread_mutex_lock(&ctx->mtx);
        ctx->last_event_errno = last_event_errno;
        pthread_mutex_unlock(&ctx->mtx);
        LOGV("read_loop(): rcvd %i, error: %s", rcvd, strerror(errno));
        break;
      }
    }else if (rcvd == 0) {
      // XXX: EOF
      continue;
    }else {
      if ((unsigned)rcvd >= req_len) {
        ctx->fast_cycle = true;
      }

      rxbuf_pos += rcvd;
      rxbuf_pos = share(ctx, rx_buf, rxbuf_pos);
      if (rxbuf_pos >= sizeof(rx_buf)-USB_IOCTL_WATERMARK) {
        // XXX
        rxbuf_pos = 0;
      }
    }
  } //

  if (rxbuf_pos != 0) {
    share(ctx, rx_buf, rxbuf_pos);
  }

  pthread_cleanup_pop(1);

  return NULL;
}

ssize_t usb_read(struct usb_reader_thread_ctx_t *ctx,
    uint8_t *dst,
    size_t dst_size,
    const struct timespec *timeout)
{
  ssize_t bytes_read;

  pthread_mutex_lock(&ctx->mtx);

  if ( (ctx->shared_rxbuf_pos == 0) && (!ctx->is_running)) {
    errno = ctx->last_event_errno;
    pthread_mutex_unlock(&ctx->mtx);
    return -1;
  }

  if (ctx->shared_rxbuf_pos == 0)  {
    if (pthread_cond_timedwait_relative_np(&ctx->data_available_cond, &ctx->mtx, timeout) != 0) {
      if (!ctx->is_running) {
        errno = ctx->last_event_errno;
      }else {
        errno = ETIMEDOUT;
      }
      pthread_mutex_unlock(&ctx->mtx);
      return -1;
    }
  }

  if (dst_size == 0) {
    pthread_mutex_unlock(&ctx->mtx);
    return 0;
  }

  bytes_read = MIN(dst_size, ctx->shared_rxbuf_pos);
  memcpy(dst, ctx->shared_rxbuf, bytes_read);
  if ((unsigned)bytes_read != ctx->shared_rxbuf_pos) {
    memmove(ctx->shared_rxbuf, &ctx->shared_rxbuf[bytes_read], ctx->shared_rxbuf_pos - bytes_read);
  }
  ctx->shared_rxbuf_pos -= bytes_read;

  pthread_mutex_unlock(&ctx->mtx);

  return bytes_read;
}

static void usb_reader_cleanup(void *arg)
{
  struct usb_reader_thread_ctx_t *ctx;

  LOGV("usb_reader_cleanup()");
  ctx = (struct usb_reader_thread_ctx_t *)arg;
  pthread_mutex_lock(&ctx->mtx);
  ctx->is_running = false;
  pthread_mutex_unlock(&ctx->mtx);
  (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
}

static unsigned share(struct usb_reader_thread_ctx_t *ctx,
    uint8_t *buf, unsigned rxbuf_pos)
{
  unsigned moved_bytes;

  if (rxbuf_pos == 0)
    return 0;

  pthread_mutex_lock(&ctx->mtx);
  moved_bytes = MIN(rxbuf_pos, sizeof(ctx->shared_rxbuf) - ctx->shared_rxbuf_pos);
  if (moved_bytes > 0) {
    memcpy(&ctx->shared_rxbuf[ctx->shared_rxbuf_pos], buf, moved_bytes);
    ctx->shared_rxbuf_pos += moved_bytes;
    if (ctx->shared_rxbuf_pos == moved_bytes)
      pthread_cond_signal(&ctx->data_available_cond);
  }
  pthread_mutex_unlock(&ctx->mtx);

  if ((rxbuf_pos != moved_bytes) && (moved_bytes > 0)) {
    memmove(buf, &buf[moved_bytes], rxbuf_pos - moved_bytes);
  }

  return rxbuf_pos - moved_bytes;
}

static inline void sleep_cycle(struct usb_reader_thread_ctx_t *ctx)
{
  if (ctx->fast_cycle) {
    ctx->fast_cycle = false;
    clock_gettime(CLOCK_MONOTONIC, &ctx->last_cycle_ts);
  }else {
    int diff_sec;
    struct timespec ts;

    ts = ctx->last_cycle_ts;
    clock_gettime(CLOCK_MONOTONIC, &ctx->last_cycle_ts);

    diff_sec = ctx->last_cycle_ts.tv_sec - ts.tv_sec;
    if (diff_sec >= 0 && diff_sec <= 1) {
      long diff_usec;
      diff_usec = 1000 * diff_sec + (long)(ctx->last_cycle_ts.tv_nsec / 1000) - (long)(ts.tv_nsec / 1000);
      if (diff_usec < DEFAULT_CYCLE_US && diff_usec >= 0) {
        usleep(DEFAULT_CYCLE_US - (useconds_t)diff_usec);
        clock_gettime(CLOCK_MONOTONIC, &ctx->last_cycle_ts);
      }
    }
  }
}
