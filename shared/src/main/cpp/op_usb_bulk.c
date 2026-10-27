#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <pthread.h>
#include <linux/usbdevice_fs.h>

/* Each blocking caller owns one buffer for its thread lifetime. No other endpoint/thread
 * can borrow it while ioctl is in flight, and the pthread destructor releases it at exit. */
typedef struct { void *bytes; size_t capacity; } bulk_scratch;
static pthread_key_t scratch_key;
static pthread_once_t scratch_once = PTHREAD_ONCE_INIT;
static int scratch_key_ready;
static void destroy_scratch(void *value) {
    bulk_scratch *scratch = value;
    if (scratch) { free(scratch->bytes); free(scratch); }
}
static void create_scratch_key(void) {
    scratch_key_ready = pthread_key_create(&scratch_key, destroy_scratch) == 0;
}
static void *transfer_buffer(size_t required, int *temporary) {
    *temporary = 0;
    pthread_once(&scratch_once, create_scratch_key);
    /* Keep per-thread retained storage bounded even for an unusual large caller. */
    if (!scratch_key_ready || required > 1024 * 1024) {
        *temporary = 1; return malloc(required);
    }
    bulk_scratch *scratch = pthread_getspecific(scratch_key);
    if (!scratch) {
        scratch = calloc(1, sizeof(*scratch));
        if (!scratch) return NULL;
        if (pthread_setspecific(scratch_key, scratch) != 0) { free(scratch); return NULL; }
    }
    if (scratch->capacity < required) {
        void *next = realloc(scratch->bytes, required);
        if (!next) return NULL;
        scratch->bytes = next; scratch->capacity = required;
    }
    return scratch->bytes;
}

/* Keep transfer semantics identical to USBDEVFS_BULK; Java arrays are copied only
 * before/after I/O, and are never pinned while the kernel waits for the device. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_NativeUsbBulk_nativeTransfer(
        JNIEnv *env, jobject self, jint fd, jint endpoint, jbyteArray array,
        jint length, jint timeout_ms) {
    (void)self;
    if (array == NULL || length < 0 || timeout_ms < 0 ||
            length > (*env)->GetArrayLength(env, array)) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (error) (*env)->ThrowNew(env, error, "Invalid USB bulk range or timeout");
        return -1;
    }
    if (fd < 0) return -1;
    int temporary;
    void *data = transfer_buffer(length > 0 ? (size_t)length : 1, &temporary);
    if (!data) return -1;
    if (!(endpoint & 0x80) && length > 0) {
        (*env)->GetByteArrayRegion(env, array, 0, length, data);
        if ((*env)->ExceptionCheck(env)) { if (temporary) free(data); return -1; }
    }
    struct usbdevfs_bulktransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.ep = (unsigned int)endpoint;
    transfer.len = (unsigned int)length;
    transfer.timeout = (unsigned int)timeout_ms;
    transfer.data = data;
    int result = ioctl(fd, USBDEVFS_BULK, &transfer);
    if (result > 0 && (endpoint & 0x80)) {
        if (result > length) { if (temporary) free(data); return -1; }
        (*env)->SetByteArrayRegion(env, array, 0, result, data);
    }
    if (temporary) free(data);
    return result;
}
