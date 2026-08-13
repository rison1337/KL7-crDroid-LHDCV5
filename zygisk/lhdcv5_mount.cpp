#include <sys/types.h>

#include "zygisk.hpp"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LhdcV5Mount", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LhdcV5Mount", __VA_ARGS__)

namespace {

constexpr const char *kBluetoothProcess = "com.android.bluetooth";
constexpr const char *kGuardOk =
    "/data/adb/modules/lhdcv5_kl7/.guard_ok";
constexpr const char *kApkSource =
    "/data/adb/modules/lhdcv5_kl7/payload/Bluetooth.apk";
constexpr const char *kJniSource =
    "/data/adb/modules/lhdcv5_kl7/payload/libbluetooth_jni.so";
constexpr const char *kApkTarget =
    "/apex/com.android.bt/app/Bluetooth@BP4A.251205.006/Bluetooth.apk";
constexpr const char *kJniTarget =
    "/apex/com.android.bt/lib64/libbluetooth_jni.so";

struct MountRequest {
    int32_t pid;
    int32_t ppid;
};

bool ReadAll(int fd, void *buffer, size_t size) {
    auto *out = static_cast<uint8_t *>(buffer);
    while (size > 0) {
        const ssize_t done = read(fd, out, size);
        if (done < 0 && errno == EINTR) continue;
        if (done <= 0) return false;
        out += done;
        size -= static_cast<size_t>(done);
    }
    return true;
}

bool WriteAll(int fd, const void *buffer, size_t size) {
    const auto *in = static_cast<const uint8_t *>(buffer);
    while (size > 0) {
        const ssize_t done = write(fd, in, size);
        if (done < 0 && errno == EINTR) continue;
        if (done <= 0) return false;
        in += done;
        size -= static_cast<size_t>(done);
    }
    return true;
}

bool BindPathToTarget(const char *source, const char *target) {
    if (mount(source, target, nullptr, MS_BIND, nullptr) != 0) {
        LOGE("bind failed: %s -> %s errno=%d", source, target, errno);
        return false;
    }
    return true;
}

bool MountIntoProcess(int32_t pid) {
    const int apk = open(kApkSource, O_RDONLY | O_CLOEXEC);
    const int jni = open(kJniSource, O_RDONLY | O_CLOEXEC);
    const int original_ns = open("/proc/self/ns/mnt", O_RDONLY | O_CLOEXEC);

    char namespace_path[64];
    snprintf(namespace_path, sizeof(namespace_path), "/proc/%d/ns/mnt", pid);
    const int target_ns = open(namespace_path, O_RDONLY | O_CLOEXEC);

    bool result = false;
    // Zygisk Next companions share fs_struct with the daemon. Linux rejects a
    // mount-namespace setns in that state, so give this handler a private copy.
    if (unshare(CLONE_FS) != 0) {
        LOGE("unshare(CLONE_FS) failed errno=%d", errno);
    }
    if (apk >= 0 && jni >= 0 && original_ns >= 0 && target_ns >= 0 &&
        setns(target_ns, CLONE_NEWNS) == 0) {
        const bool apk_ok = BindPathToTarget(kApkSource, kApkTarget);
        const bool jni_ok = BindPathToTarget(kJniSource, kJniTarget);
        result = apk_ok && jni_ok;
        if (setns(original_ns, CLONE_NEWNS) != 0) {
            LOGE("failed to restore companion mount namespace errno=%d", errno);
        }
    } else {
        LOGE("companion setup failed pid=%d apk=%d jni=%d ns=%d errno=%d",
             pid, apk, jni, target_ns, errno);
    }

    if (apk >= 0) close(apk);
    if (jni >= 0) close(jni);
    if (target_ns >= 0) close(target_ns);
    if (original_ns >= 0) close(original_ns);
    return result;
}

bool NamespaceInode(int32_t pid, ino_t *inode) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/ns/mnt", pid);
    struct stat info{};
    if (stat(path, &info) != 0) return false;
    *inode = info.st_ino;
    return true;
}

void CompanionHandler(int socket) {
    MountRequest request{};
    if (!ReadAll(socket, &request, sizeof(request)) || request.pid <= 0 ||
        request.ppid <= 0) {
        return;
    }

    ino_t process_ns = 0;
    ino_t parent_ns = 0;
    if (!NamespaceInode(request.pid, &process_ns) ||
        !NamespaceInode(request.ppid, &parent_ns)) {
        const uint8_t failed = 0;
        WriteAll(socket, &failed, sizeof(failed));
        return;
    }

    if (process_ns != parent_ns) {
        const uint8_t result = MountIntoProcess(request.pid) ? 1 : 0;
        WriteAll(socket, &result, sizeof(result));
        LOGI("immediate namespace mount pid=%d result=%d", request.pid, result);
        return;
    }

    // Specialization will create the final per-app mount namespace after the
    // pre callback returns. Acknowledge first, then watch for that transition.
    const uint8_t watching = 2;
    if (!WriteAll(socket, &watching, sizeof(watching))) return;

    ino_t current_ns = process_ns;
    for (int attempt = 0; attempt < 50000; ++attempt) {
        if (!NamespaceInode(request.pid, &current_ns)) return;
        if (current_ns != process_ns) {
            kill(request.pid, SIGSTOP);
            usleep(1000);
            const bool mounted = MountIntoProcess(request.pid);
            kill(request.pid, SIGCONT);
            LOGI("final namespace mount pid=%d result=%d", request.pid, mounted);
            return;
        }
        usleep(100);
    }
    LOGE("namespace did not change for pid=%d", request.pid);
}

class LhdcV5Mount : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *process = env_->GetStringUTFChars(args->nice_name, nullptr);
        target_ = process != nullptr && strcmp(process, kBluetoothProcess) == 0;
        if (process != nullptr) {
            env_->ReleaseStringUTFChars(args->nice_name, process);
        }

        if (!target_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        // post-fs-data.sh creates this marker only after checking the exact
        // device, ROM build and stock APEX payload hashes. Never mount the
        // Android 17 backport into an unknown Bluetooth process.
        if (access(kGuardOk, R_OK) != 0) {
            LOGE("compatibility guard is absent; refusing Bluetooth mount");
            target_ = false;
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        companion_ = api_->connectCompanion();
        if (companion_ < 0) {
            LOGE("unable to connect to root companion before specialization");
            return;
        }

        const MountRequest request{static_cast<int32_t>(getpid()),
                                   static_cast<int32_t>(getppid())};
        uint8_t state = 0;
        if (WriteAll(companion_, &request, sizeof(request)) &&
            ReadAll(companion_, &state, sizeof(state)) && state == 1) {
            LOGI("Bluetooth payload mounted immediately in pid=%d", getpid());
        } else if (state == 2) {
            LOGI("Bluetooth final namespace watcher armed in pid=%d", getpid());
        } else {
            LOGE("Bluetooth payload mount setup failed in pid=%d", getpid());
        }
        close(companion_);
        companion_ = -1;
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    int companion_ = -1;
    bool target_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(LhdcV5Mount)
REGISTER_ZYGISK_COMPANION(CompanionHandler)
