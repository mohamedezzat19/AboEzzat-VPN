#include <jni.h>
#include <condition_variable>
#include <mutex>

namespace {
JavaVM* g_vm = nullptr;
std::mutex g_mutex;
std::condition_variable g_idle;
jobject g_service = nullptr;
jmethodID g_protect_method = nullptr;
unsigned int g_in_flight = 0;
bool g_shutting_down = false;

bool protect_fd(JNIEnv* env, jobject service, jmethodID method, int fd) {
    if (fd < 0 || service == nullptr || method == nullptr) return false;
    const jboolean allowed = env->CallBooleanMethod(service, method, static_cast<jint>(fd));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return allowed == JNI_TRUE;
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
    env->DeleteLocalRef(service_class);
    if (method == nullptr || env->ExceptionCheck()) {
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
