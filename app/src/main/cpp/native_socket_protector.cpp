#include <jni.h>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>

#include <string>

extern "C" int tun2proxy_run_with_cli_args(const char* cli_args, unsigned short tun_mtu, bool packet_information);
extern "C" int tun2proxy_stop();
extern "C" void tun2proxy_clear_stop_request();

struct Tun2ProxyTrafficStatus { uint64_t tx; uint64_t rx; };
using Tun2ProxyTrafficCallback = void (*)(const Tun2ProxyTrafficStatus*, void*);
extern "C" void tun2proxy_set_traffic_status_callback(uint32_t, Tun2ProxyTrafficCallback, void*);

namespace {
JavaVM* g_vm = nullptr;
std::mutex g_mutex;
std::condition_variable g_idle;
jobject g_service = nullptr;
jmethodID g_protect_method = nullptr;
jmethodID g_traffic_method = nullptr;
unsigned int g_in_flight = 0;
bool g_shutting_down = false;
std::mutex g_engine_mutex;
bool g_engine_running = false;
bool g_engine_pending_start = false;
bool g_engine_stop_requested = false;

bool protect_fd(JNIEnv* env, jobject service, jmethodID method, int fd) {
    if (fd < 0 || service == nullptr || method == nullptr) return false;
    const jboolean allowed = env->CallBooleanMethod(service, method, static_cast<jint>(fd));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return allowed == JNI_TRUE;
}

void on_native_traffic_status(const Tun2ProxyTrafficStatus* status, void*) {
    if (status == nullptr || g_vm == nullptr) return;

    jobject service = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_shutting_down || g_service == nullptr || g_traffic_method == nullptr) return;
        ++g_in_flight;
        service = g_service;
        method = g_traffic_method;
    }

    JNIEnv* env = nullptr;
    bool attached_here = false;
    const jint get_env = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached_here = true;
        }
    } else if (get_env != JNI_OK) {
        env = nullptr;
    }

    if (env != nullptr) {
        env->CallVoidMethod(service, method, static_cast<jlong>(status->tx), static_cast<jlong>(status->rx));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached_here) g_vm->DetachCurrentThread();

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_in_flight > 0) --g_in_flight;
        if (g_in_flight == 0) g_idle.notify_all();
    }
}

bool protect_fd_from_native(int fd) {
    if (fd < 0 || g_vm == nullptr) return false;

    jobject service = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_shutting_down || g_service == nullptr || g_protect_method == nullptr) {
            return false;
        }
        ++g_in_flight;
        service = g_service;
        method = g_protect_method;
    }

    JNIEnv* env = nullptr;
    bool attached_here = false;
    bool allowed = false;
    const jint get_env = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached_here = true;
        }
    } else if (get_env != JNI_OK) {
        env = nullptr;
    }

    if (env != nullptr) {
        allowed = protect_fd(env, service, method, fd);
    }

    if (attached_here) {
        g_vm->DetachCurrentThread();
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_in_flight > 0) --g_in_flight;
        if (g_in_flight == 0) g_idle.notify_all();
    }
    return allowed;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeBindService(
        JNIEnv* env, jclass, jobject service) {
    if (env == nullptr || service == nullptr) return JNI_FALSE;

    jclass service_class = env->GetObjectClass(service);
    if (service_class == nullptr) return JNI_FALSE;
    jmethodID method = env->GetMethodID(service_class, "protectNativeSocket", "(I)Z");
    jmethodID traffic_method = env->GetMethodID(service_class, "onNativeTrafficStatus", "(JJ)V");
    env->DeleteLocalRef(service_class);
    if (method == nullptr || traffic_method == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    jobject global_service = env->NewGlobalRef(service);
    if (global_service == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_service != nullptr || g_shutting_down || g_in_flight != 0) {
        env->DeleteGlobalRef(global_service);
        return JNI_FALSE;
    }
    g_service = global_service;
    g_protect_method = method;
    g_traffic_method = traffic_method;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeUnbindService(
        JNIEnv* env, jclass) {
    jobject old_service = nullptr;
    {
        std::unique_lock<std::mutex> lock(g_mutex);
        g_shutting_down = true;
        g_idle.wait(lock, [] { return g_in_flight == 0; });
        old_service = g_service;
        g_service = nullptr;
        g_protect_method = nullptr;
        g_traffic_method = nullptr;
        g_shutting_down = false;
    }
    if (env != nullptr && old_service != nullptr) {
        env->DeleteGlobalRef(old_service);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeProtectSocket(
        JNIEnv*, jclass, jint fd) {
    return protect_fd_from_native(static_cast<int>(fd)) ? JNI_TRUE : JNI_FALSE;
}

// Stable C ABI for the patched tun2proxy socket factory.
// A missing service or a failed Java protection call always fails closed.
extern "C" bool abz_protect_socket_fd(int fd) {
    return protect_fd_from_native(fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeArmEngineStart(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (g_engine_running || g_engine_pending_start) return JNI_FALSE;
    g_engine_pending_start = true;
    g_engine_stop_requested = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeCancelEngineStart(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_engine_running) {
        g_engine_pending_start = false;
        g_engine_stop_requested = false;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeRunEngine(
        JNIEnv* env, jclass, jstring cli_args, jint tun_mtu, jboolean packet_information) {
    if (env == nullptr || cli_args == nullptr || tun_mtu < 576 || tun_mtu > 65535) {
        return -10;
    }
    const char* chars = env->GetStringUTFChars(cli_args, nullptr);
    if (chars == nullptr) return -11;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        if (g_engine_running) {
            env->ReleaseStringUTFChars(cli_args, chars);
            return -12;
        }
        if (!g_engine_pending_start) {
            env->ReleaseStringUTFChars(cli_args, chars);
            return -14;
        }
        if (g_engine_stop_requested) {
            g_engine_pending_start = false;
            g_engine_stop_requested = false;
            env->ReleaseStringUTFChars(cli_args, chars);
            return -13;
        }
        g_engine_pending_start = false;
        g_engine_running = true;
    }

    tun2proxy_set_traffic_status_callback(1, &on_native_traffic_status, nullptr);
    const int result = tun2proxy_run_with_cli_args(
        chars,
        static_cast<unsigned short>(tun_mtu),
        packet_information == JNI_TRUE
    );
    env->ReleaseStringUTFChars(cli_args, chars);
    // Mark the native run idle before clearing a stop request that raced
    // with Rust dropping its cancellation token. Stop calls serialize on the
    // same mutex, so no late request can survive into the next run.
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        g_engine_running = false;
        g_engine_pending_start = false;
        g_engine_stop_requested = false;
    }
    tun2proxy_clear_stop_request();
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aboezzat_vpn_vpn_NativeSocketProtectorBridge_nativeStopEngine(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_engine_running) {
        // Only queue a stop while Kotlin has armed a specific pending run.
        if (g_engine_pending_start) {
            g_engine_stop_requested = true;
            return 0;
        }
        return -1;
    }
    // Serialize the Rust cancellation request with the transition to IDLE.
    return tun2proxy_stop();
}
