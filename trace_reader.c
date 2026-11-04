// O_SYNC trace_pipe reader: keeps the kprobe events that matter on disk even
// if the device is hard-reset a few hundred ms later.
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *out = argc > 1 ? argv[1] : "/data/local/tmp/pi.log";
    int ofd = open(out, O_WRONLY | O_CREAT | O_APPEND | O_SYNC, 0666);
    if (ofd < 0) return 1;
    int ifd = open("/sys/kernel/tracing/trace_pipe", O_RDONLY);
    if (ifd < 0) ifd = open("/sys/kernel/debug/tracing/trace_pipe", O_RDONLY);
    if (ifd < 0) return 2;
    static char buf[65536];
    static char line[8192];
    int n = 0;
    ssize_t r;
    while ((r = read(ifd, buf, sizeof buf)) > 0) {
        for (ssize_t i = 0; i < r; i++) {
            char c = buf[i];
            if (c == '\n') {
                line[n] = 0;
                if (1) {
                    if (write(ofd, line, n) < 0) {}
                    if (write(ofd, "\n", 1) < 0) {}
                }
                n = 0;
            } else if (n < (int)sizeof(line) - 1) {
                line[n++] = c;
            }
        }
    }
    return 0;
}
