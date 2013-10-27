/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#ifndef _USB_READER_H
#define _USB_READER_H

#define USB_READER_BUF_SIZE 8192

struct usb_reader_thread_ctx_t {
  int fd;
  int endpoint;
  int max_pkt_size;
  useconds_t cycle_us;

  JavaVM *jvm;
  JNIEnv *jniEnv;

  pthread_mutex_t mtx;
  pthread_cond_t data_available_cond;

  struct timespec last_cycle_ts;
  bool fast_cycle;

  bool is_running;
  int last_event_errno;
  unsigned shared_rxbuf_pos;
  uint8_t shared_rxbuf[USB_READER_BUF_SIZE];
};

void usb_reader_init(struct usb_reader_thread_ctx_t *ctx,
    JavaVM *jvm, int fd, int endpoint, int max_pkt_size);

void usb_reader_destroy(struct usb_reader_thread_ctx_t *ctx);

void *usb_reader_thread(void *ctx);

ssize_t usb_read(struct usb_reader_thread_ctx_t *ctx,
    uint8_t *dst,
    size_t dst_size,
    const struct timespec *timeout);

#endif
