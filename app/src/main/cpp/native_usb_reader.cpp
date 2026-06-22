#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

// Continuous isochronous capture for the miniDSP UMA-8 mic array, ported from the
// uma8 diagnostic project. Reaps + re-arms URBs so the stream never gaps, and
// deinterleaves the 7 real mic channels (dropping the always-zero 8th slot),
// emitting full-range 32-bit LE PCM. See NativeUsbReader.kt for the Kotlin side.

namespace {

std::string errno_text() {
    std::ostringstream out;
    out << errno << " (" << std::strerror(errno) << ")";
    return out.str();
}

int32_t read_i32_le(const uint8_t* data) {
    return static_cast<int32_t>(
        static_cast<uint32_t>(data[0]) |
        (static_cast<uint32_t>(data[1]) << 8) |
        (static_cast<uint32_t>(data[2]) << 16) |
        (static_cast<uint32_t>(data[3]) << 24)
    );
}

struct IsoUrb {
    std::vector<uint8_t> urb_storage;
    std::vector<uint8_t> buffer;

    usbdevfs_urb* urb() {
        return reinterpret_cast<usbdevfs_urb*>(urb_storage.data());
    }
};

// Persistent state for a capture session; the pointer is handed to Kotlin as a
// jlong handle and lives between startStream() and stopStream().
struct IsoStream {
    int fd = -1;
    int endpoint = 0;
    int packet_bytes = 0;
    int packets_per_urb = 0;
    int urb_count = 0;
    int frame_bytes = 0;     // bytes per interleaved device frame (8ch * 4 = 32)
    int out_channels = 0;    // channels emitted to Kotlin (7 real mics)
    std::vector<IsoUrb> urbs;
    std::string error;
};

void configure_iso_urb(IsoUrb& item, int endpoint, int packet_bytes,
                       int packets_per_urb, int transfer_bytes) {
    item.buffer.assign(static_cast<size_t>(transfer_bytes), 0);
    item.urb_storage.assign(
        sizeof(usbdevfs_urb) +
            sizeof(usbdevfs_iso_packet_desc) * static_cast<size_t>(packets_per_urb),
        0
    );

    usbdevfs_urb* urb = item.urb();
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = static_cast<unsigned char>(endpoint);
    urb->status = 0;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = item.buffer.data();
    urb->buffer_length = transfer_bytes;
    urb->actual_length = 0;
    urb->start_frame = 0;
    urb->number_of_packets = packets_per_urb;
    urb->error_count = 0;
    urb->usercontext = &item;

    for (int packet = 0; packet < packets_per_urb; ++packet) {
        urb->iso_frame_desc[packet].length = packet_bytes;
        urb->iso_frame_desc[packet].actual_length = 0;
        urb->iso_frame_desc[packet].status = 0;
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_nsn8_vued_capture_NativeUsbReader_startStream(
    JNIEnv*,
    jobject,
    jint fd,
    jint endpoint,
    jint packet_bytes,
    jint packets_per_urb,
    jint urb_count,
    jint frame_bytes,
    jint out_channels
) {
    if (fd < 0 || packet_bytes <= 0 || packets_per_urb <= 0 || urb_count <= 0 ||
        frame_bytes <= 0 || out_channels <= 0 || out_channels * 4 > frame_bytes) {
        return 0;
    }

    auto* stream = new IsoStream();
    stream->fd = fd;
    stream->endpoint = endpoint;
    stream->packet_bytes = packet_bytes;
    stream->packets_per_urb = packets_per_urb;
    stream->urb_count = urb_count;
    stream->frame_bytes = frame_bytes;
    stream->out_channels = out_channels;

    const int transfer_bytes = packet_bytes * packets_per_urb;
    stream->urbs.resize(static_cast<size_t>(urb_count));

    int submitted = 0;
    for (int i = 0; i < urb_count; ++i) {
        IsoUrb& item = stream->urbs[static_cast<size_t>(i)];
        configure_iso_urb(item, endpoint, packet_bytes, packets_per_urb, transfer_bytes);
        if (ioctl(fd, USBDEVFS_SUBMITURB, item.urb()) < 0) {
            stream->error = std::string("submit#") + std::to_string(i) + " " + errno_text();
            break;
        }
        submitted += 1;
    }

    if (submitted == 0) {
        delete stream;
        return 0;
    }

    return reinterpret_cast<jlong>(stream);
}

// Reaps every URB the kernel has completed, deinterleaves valid frames into `out`
// (out_channels int32 LE samples per frame, scaled to full 32-bit range), and
// immediately re-arms each URB. Returns bytes written, 0 if nothing was ready, or
// -1 on a fatal error (inspect streamError()).
extern "C" JNIEXPORT jint JNICALL
Java_com_nsn8_vued_capture_NativeUsbReader_pollStream(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray out
) {
    auto* stream = reinterpret_cast<IsoStream*>(handle);
    if (stream == nullptr) {
        return -1;
    }

    const jsize out_capacity = env->GetArrayLength(out);
    jbyte* out_ptr = env->GetByteArrayElements(out, nullptr);
    if (out_ptr == nullptr) {
        return -1;
    }

    const int max_frames_per_packet = stream->packet_bytes / stream->frame_bytes;
    const int per_urb_max_bytes =
        stream->packets_per_urb * max_frames_per_packet * stream->out_channels * 4;

    int written = 0;
    bool fatal = false;
    int empty_polls = 0;

    while (out_capacity - written >= per_urb_max_bytes) {
        usbdevfs_urb* urb = nullptr;
        if (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
            if (errno == EAGAIN) {
                if (written > 0 || empty_polls >= 10) {
                    break;
                }
                empty_polls += 1;
                usleep(1'000);
                continue;
            }
            stream->error = std::string("reap ") + errno_text();
            fatal = true;
            break;
        }
        if (urb == nullptr) {
            break;
        }

        const auto* buffer = static_cast<const uint8_t*>(urb->buffer);
        int packet_offset = 0;
        for (int packet = 0; packet < urb->number_of_packets; ++packet) {
            const auto& desc = urb->iso_frame_desc[packet];
            if (desc.status == 0 && desc.actual_length > 0) {
                const int frames = desc.actual_length / stream->frame_bytes;
                for (int frame = 0; frame < frames; ++frame) {
                    const uint8_t* frame_ptr =
                        buffer + packet_offset + frame * stream->frame_bytes;
                    for (int channel = 0; channel < stream->out_channels; ++channel) {
                        // UAC2 stores the 24-bit sample left-justified (MSB-aligned)
                        // in the 4-byte subslot, so `raw` is already at full 32-bit
                        // scale. Do NOT shift it up again — that would multiply by 256
                        // (+48 dB) and integer-overflow/wrap on peaks. RAW-mode makeup
                        // gain is applied cleanly (with clamping) downstream in Kotlin.
                        const int32_t sample = read_i32_le(frame_ptr + channel * 4);
                        std::memcpy(out_ptr + written, &sample, 4);
                        written += 4;
                    }
                }
            }
            packet_offset += desc.length;
        }

        urb->status = 0;
        urb->actual_length = 0;
        for (int packet = 0; packet < urb->number_of_packets; ++packet) {
            urb->iso_frame_desc[packet].length = stream->packet_bytes;
            urb->iso_frame_desc[packet].actual_length = 0;
            urb->iso_frame_desc[packet].status = 0;
        }
        if (ioctl(stream->fd, USBDEVFS_SUBMITURB, urb) < 0) {
            stream->error = std::string("resubmit ") + errno_text();
            fatal = true;
            break;
        }
    }

    env->ReleaseByteArrayElements(out, out_ptr, 0);
    return fatal ? -1 : written;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nsn8_vued_capture_NativeUsbReader_stopStream(JNIEnv*, jobject, jlong handle) {
    auto* stream = reinterpret_cast<IsoStream*>(handle);
    if (stream == nullptr) {
        return;
    }
    for (auto& item : stream->urbs) {
        ioctl(stream->fd, USBDEVFS_DISCARDURB, item.urb());
    }
    for (size_t i = 0; i < stream->urbs.size(); ++i) {
        usbdevfs_urb* urb = nullptr;
        if (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
            break;
        }
    }
    delete stream;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nsn8_vued_capture_NativeUsbReader_streamError(JNIEnv* env, jobject, jlong handle) {
    auto* stream = reinterpret_cast<IsoStream*>(handle);
    const char* message = (stream != nullptr) ? stream->error.c_str() : "invalid handle";
    return env->NewStringUTF(message);
}
