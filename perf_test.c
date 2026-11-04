#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <linux/perf_event.h>

#ifndef __NR_bpf
#define __NR_bpf 280
#endif
#ifndef __NR_perf_event_open
#define __NR_perf_event_open 241
#endif

struct bpf_insn_min {
    uint8_t code; uint8_t dst_src; int16_t off; int32_t imm;
};

int main(void) {
    int ktype = -1;
    FILE *f = fopen("/sys/bus/event_source/devices/kprobe/type", "r");
    if (f) { if (fscanf(f, "%d", &ktype) != 1) ktype = -1; fclose(f); }
    printf("kprobe_pmu_type_sysfs=%d errno=%d\n", ktype, errno);

    /* If sysfs unreadable, brute force plausible type ids with kprobe attrs. */
    int tried[64]; memset(tried, 0, sizeof tried);
    if (ktype <= 0) {
        for (int t = 1; t < 64; t++) {
            struct perf_event_attr a; memset(&a, 0, sizeof a);
            a.type = t; a.size = sizeof a; a.config = 0;
            a.config1 = (uint64_t)(uintptr_t)"rt_mutex_adjust_pi";
            a.config2 = 0; a.disabled = 1;
            int fd = syscall(__NR_perf_event_open, &a, -1, 0, -1, 0);
            if (fd >= 0) { printf("kprobe type=%d accepted fd=%d\n", t, fd); close(fd); ktype = t; break; }
        }
        if (ktype <= 0) printf("brute force found no kprobe type\n");
    }

    if (ktype > 0) {
        struct perf_event_attr a; memset(&a, 0, sizeof a);
        a.type = ktype; a.size = sizeof a; a.config = 0;
        a.config1 = (uint64_t)(uintptr_t)"rt_mutex_adjust_pi";
        a.config2 = 0;
        a.sample_period = 1;
        a.sample_type = PERF_SAMPLE_RAW | PERF_SAMPLE_TID | PERF_SAMPLE_TIME;
        a.disabled = 1; a.wakeup_events = 1;
        int fd = syscall(__NR_perf_event_open, &a, -1, 0, -1, 0);
        printf("perf_event_open_kprobe=%d errno=%d (%s)\n", fd, errno, strerror(errno));
        if (fd >= 0) close(fd);
    }

    /* BPF KPROBE prog load: mov r0,0; exit */
    struct bpf_insn_min insns[2];
    insns[0].code = 0xb7; insns[0].dst_src = 0; insns[0].off = 0; insns[0].imm = 0;
    insns[1].code = 0x95; insns[1].dst_src = 0; insns[1].off = 0; insns[1].imm = 0;
    static char logbuf[8192]; logbuf[0] = 0;
    struct {
        uint32_t prog_type, insn_cnt; uint64_t insns, license;
        uint32_t log_level, log_size; uint64_t log_buf;
        uint32_t kern_version, prog_flags; char prog_name[16];
        uint32_t prog_ifindex, expected_attach_type;
    } attr;
    memset(&attr, 0, sizeof attr);
    attr.prog_type = 2; /* BPF_PROG_TYPE_KPROBE */
    attr.insn_cnt = 2;
    attr.insns = (uint64_t)(uintptr_t)insns;
    attr.license = (uint64_t)(uintptr_t)"GPL";
    attr.log_level = 1; attr.log_size = sizeof logbuf; attr.log_buf = (uint64_t)(uintptr_t)logbuf;
    int pfd = syscall(__NR_bpf, 5 /*BPF_PROG_LOAD*/, &attr, sizeof attr);
    printf("bpf_prog_load_kprobe=%d errno=%d (%s)\n", pfd, errno, strerror(errno));
    if (pfd >= 0) close(pfd);
    printf("bpf_log=%.300s\n", logbuf);
    return 0;
}
