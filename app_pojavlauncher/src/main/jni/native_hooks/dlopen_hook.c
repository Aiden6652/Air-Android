#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"
#include "log.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

typedef void *(*dlopen_func_t)(const char *, int);
// Maybe make this a blacklist instead of a whitelist??
static const char *const redirected_libs[] = {
        "libSDL3.so",
        "libSDL2.so",
};

static const char *redirect_dlopen_path(const char *filename) {
    if (filename == NULL)
        return NULL;

    const char *basename = strrchr(filename, '/');
    basename = basename ? basename + 1 : filename;

    for (size_t i = 0; i < sizeof(redirected_libs) / sizeof(*redirected_libs); ++i) {
        if (strcmp(basename, redirected_libs[i]) == 0) {
            LOGI("Redirecting dlopen: %s -> %s", filename, redirected_libs[i]);
            return redirected_libs[i];
        }
    }

    return filename;
}

void *custom_dlopen(const char *filename, int flags) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlopen,
            dlopen_func_t,
            redirect_dlopen_path(filename),
            flags);

    BYTEHOOK_POP_STACK();
    return result;
}

void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    bytehook_stub_t stub =
            bytehook_hook_all_p(NULL, "dlopen", &custom_dlopen, NULL, NULL);

    LOGI("Successfully initialized dlopen hook, stub: %p", stub);
}